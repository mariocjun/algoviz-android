// VizActivity — the native Jetpack Compose sort visualizer (replaces the ImGui
// GLSurfaceView). Compose owns rendering (a Canvas drawing a per-frame snapshot
// pulled from C++ via VizBridge) and all input (Material 3 controls + native
// gestures). The C++ side keeps the coroutine sort engine + AAudio synth.
package com.mariocjun.algoviz

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun barColor(v01: Float, sat: Float, value: Float): Color =
    Color.hsv((v01 * 0.82f * 360f).coerceIn(0f, 360f), sat, value)

// Compact counter formatting so the stats line stays short (and stable-width)
// no matter how large the comparison/swap/step counts grow.
private fun fmt(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
    n >= 1_000     -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else           -> n.toString()
}

class VizActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VizBridge.nativeInit()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) { VizScreen() }
            }
        }
    }

    override fun onResume() { super.onResume(); VizBridge.nativeAudioResume() }
    override fun onPause() { VizBridge.nativeAudioPause(); super.onPause() }
}

@Composable
private fun VizScreen() {
    val buffer = remember { ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder()) }
    val ints = remember { buffer.asIntBuffer() }
    val algoNames = remember { runCatching { VizBridge.nativeAlgoNames() }.getOrDefault(emptyArray()) }
    val measurer = rememberTextMeasurer()

    var frame by remember { mutableLongStateOf(0L) }
    var stats by remember { mutableStateOf("") }

    var mode by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var algoIdx by remember { mutableIntStateOf(3) }
    var speed by remember { mutableIntStateOf(1) }    // always start at minimum speed
    var size by remember { mutableIntStateOf(16) }    // always start at minimum bar count
    var sound by remember { mutableStateOf(true) }
    var volume by remember { mutableFloatStateOf(0.6f) }
    var loop by remember { mutableStateOf(true) }
    var drawMode by remember { mutableStateOf(false) }
    var controlsOpen by remember { mutableStateOf(true) }
    var scaleIdx by remember { mutableIntStateOf(0) }
    val scaleNames = remember { runCatching { VizBridge.nativeScaleNames() }.getOrDefault(emptyArray()) }

    // Push initial UI state into the engine so the two never disagree (the C++
    // engine has its own defaults; the UI is the source of truth on launch).
    LaunchedEffect(Unit) {
        VizBridge.nativeSetMode(mode)
        VizBridge.nativeSetAlgorithm(algoIdx)
        VizBridge.nativeSetSpeed(speed)
        VizBridge.nativeSetSize(size)
        VizBridge.nativeSetScale(scaleIdx)
        VizBridge.nativeSetSound(sound)
        VizBridge.nativeSetVolume(volume)
        VizBridge.nativeSetAutoLoop(loop)
        VizBridge.nativeSetPlaying(playing)
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000.0).toFloat()
                last = now
                VizBridge.nativeUpdate(dt)
                val count = VizBridge.nativeFill(buffer)
                stats = if (count > 0 && ints.get(0) == 0) {
                    "cmp ${fmt(ints.get(5))}  swap ${fmt(ints.get(6))}  " +
                        "wr ${fmt(ints.get(7))}  steps ${fmt(ints.get(8))}"
                } else ""
                frame++
            }
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val canvas: @Composable (Modifier) -> Unit = { mod ->
        VizCanvas(
            modifier = mod,
            buffer = buffer,
            ints = ints,
            algoNames = algoNames,
            measurer = measurer,
            drawMode = drawMode,
            frame = frame,
            onDoubleTap = { playing = !playing; VizBridge.nativeTogglePlay() },
            onSpeed = { d -> speed = (speed + d).coerceIn(1, 512); VizBridge.nativeSetSpeed(speed) },
            onPaint = { idx, v01 -> VizBridge.nativePaint(idx, v01) },
        )
    }

    val panel: @Composable (Modifier) -> Unit = { mod ->
        ControlPanel(
            modifier = mod,
            mode = mode, playing = playing, algoIdx = algoIdx, algoNames = algoNames,
            speed = speed, size = size, sound = sound, volume = volume, loop = loop,
            drawMode = drawMode, stats = stats,
            scaleIdx = scaleIdx, scaleNames = scaleNames,
            onMode = { m -> mode = m; drawMode = false; VizBridge.nativeSetDrawMode(false); VizBridge.nativeSetMode(m) },
            onAlgo = { i -> algoIdx = i; VizBridge.nativeSetAlgorithm(i) },
            onPlay = { playing = !playing; VizBridge.nativeSetPlaying(playing) },
            onStep = { dir -> playing = false; VizBridge.nativeStep(dir) },
            onReset = { VizBridge.nativeReset() },
            onShuffle = { VizBridge.nativeShuffle() },
            onDraw = { drawMode = !drawMode; if (drawMode) playing = false; VizBridge.nativeSetDrawMode(drawMode) },
            onSpeed = { s -> speed = s; VizBridge.nativeSetSpeed(s) },
            onSize = { s -> size = s; VizBridge.nativeSetSize(s) },
            onSound = { e -> sound = e; VizBridge.nativeSetSound(e) },
            onVolume = { v -> volume = v; VizBridge.nativeSetVolume(v) },
            onScale = { i -> scaleIdx = i; VizBridge.nativeSetScale(i) },
            onLoop = { b -> loop = b; VizBridge.nativeSetAutoLoop(b) },
            onCollapse = { controlsOpen = false },
        )
    }

    if (landscape) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                canvas(Modifier.fillMaxSize())
                if (!controlsOpen) MenuPill { controlsOpen = true }
            }
            if (controlsOpen) panel(Modifier.width(360.dp).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                canvas(Modifier.fillMaxSize())
                if (!controlsOpen) MenuPill { controlsOpen = true }
            }
            if (controlsOpen) panel(Modifier.fillMaxWidth().heightIn(max = 300.dp))
        }
    }
}

@Composable
private fun MenuPill(onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(10.dp)) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu")
            Spacer(Modifier.width(6.dp))
            Text("Menu")
        }
    }
}

@Composable
private fun VizCanvas(
    modifier: Modifier,
    buffer: ByteBuffer,
    ints: java.nio.IntBuffer,
    algoNames: Array<String>,
    measurer: androidx.compose.ui.text.TextMeasurer,
    drawMode: Boolean,
    frame: Long,
    onDoubleTap: () -> Unit,
    onSpeed: (Int) -> Unit,
    onPaint: (Int, Float) -> Unit,
) {
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
            .pointerInput(drawMode) {
                detectDragGestures { change, drag ->
                    change.consume()
                    if (drawMode && ints.get(0) == 0) {
                        val n = ints.get(1)
                        if (n > 0) {
                            val idx = (change.position.x / size.width.toFloat() * n).toInt().coerceIn(0, n - 1)
                            val v01 = 1f - (change.position.y / size.height.toFloat())
                            onPaint(idx, v01.coerceIn(0f, 1f))
                        }
                    } else {
                        onSpeed((drag.x * 0.3f).roundToInt())
                    }
                }
            }
    ) {
        if (frame < 0L) return@Canvas   // reference `frame` so the draw re-runs each tick
        if (ints.get(0) == 0) drawSingle(ints) else drawRace(ints, algoNames, measurer)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSingle(ints: java.nio.IntBuffer) {
    val n = ints.get(1)
    if (n <= 0) return
    val hiA = ints.get(2); val hiB = ints.get(3); val finished = ints.get(4) == 1
    val w = size.width / n
    for (i in 0 until n) {
        val v = ints.get(10 + i)
        val v01 = v.toFloat() / n
        val h = v01 * size.height
        val col = when {
            !finished && (i == hiA || i == hiB) -> Color.White
            finished -> barColor(v01, 0.78f, 1f)
            else -> barColor(v01, 0.60f, 0.92f)
        }
        drawRect(col, Offset(i * w, size.height - h), Size(maxOf(w - 1f, 1f), h))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRace(
    ints: java.nio.IntBuffer,
    algoNames: Array<String>,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val L = ints.get(1)
    val n = ints.get(2)
    if (L <= 0 || n <= 0) return
    val aspect = size.width / size.height
    var cols = sqrt(L.toDouble() * aspect.toDouble()).roundToInt().coerceIn(1, L)
    val rows = ceil(L.toDouble() / cols).toInt()
    val cw = size.width / cols
    val ch = size.height / rows
    val valuesOff = 3 + 4 * L
    for (k in 0 until L) {
        val finished = ints.get(3 + 4 * k) == 1
        val rank = ints.get(3 + 4 * k + 1)
        val hiA = ints.get(3 + 4 * k + 2)
        val hiB = ints.get(3 + 4 * k + 3)
        val gc = k % cols
        val gr = k / cols
        val ox = gc * cw
        val oy = gr * ch
        val pad = 6f
        val labelH = 34f
        val bx = ox + pad
        val by0 = oy + pad + labelH
        val by1 = oy + ch - pad
        val areaW = cw - 2 * pad
        val areaH = by1 - by0
        if (areaW > 2f && areaH > 4f) {
            val bw = areaW / n
            for (i in 0 until n) {
                val v = ints.get(valuesOff + k * n + i)
                val v01 = v.toFloat() / n
                val h = v01 * areaH
                val col = when {
                    !finished && (i == hiA || i == hiB) -> Color.White
                    finished -> barColor(v01, 0.78f, 1f)
                    else -> barColor(v01, 0.58f, 0.90f)
                }
                drawRect(col, Offset(bx + i * bw, by1 - h), Size(maxOf(bw - 0.5f, 0.7f), h))
            }
        }
        val name = if (k < algoNames.size) algoNames[k] else ""
        val label = if (finished) "$name #$rank" else name
        // Shrink the label to fit its lane so long names never spill into the
        // neighbouring cell.
        val avail = (cw - 2 * pad).coerceAtLeast(1f)
        val base = TextStyle(color = Color(0xFFCFE0FF), fontSize = 13.sp)
        val measured = measurer.measure(label, base)
        val fit = (avail / measured.size.width.toFloat()).coerceIn(0.5f, 1f)
        val style = if (fit < 1f) base.copy(fontSize = (13f * fit).coerceAtLeast(7f).sp) else base
        drawText(measurer, label, topLeft = Offset(ox + pad, oy + pad), style = style)
    }
}

@Composable
private fun ControlPanel(
    modifier: Modifier,
    mode: Int, playing: Boolean, algoIdx: Int, algoNames: Array<String>,
    speed: Int, size: Int, sound: Boolean, volume: Float, loop: Boolean,
    drawMode: Boolean, stats: String, scaleIdx: Int, scaleNames: Array<String>,
    onMode: (Int) -> Unit, onAlgo: (Int) -> Unit, onPlay: () -> Unit, onStep: (Int) -> Unit,
    onReset: () -> Unit, onShuffle: () -> Unit, onDraw: () -> Unit,
    onSpeed: (Int) -> Unit, onSize: (Int) -> Unit, onSound: (Boolean) -> Unit,
    onVolume: (Float) -> Unit, onScale: (Int) -> Unit, onLoop: (Boolean) -> Unit, onCollapse: () -> Unit,
) {
    Surface(modifier, tonalElevation = 3.dp) {
        Column(
            Modifier
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { onMode(0) }, label = { Text("Single") },
                    modifier = Modifier.semantics { contentDescription = "Single" })
                FilterChip(selected = mode == 1, onClick = { onMode(1) }, label = { Text("Race") },
                    modifier = Modifier.semantics { contentDescription = "Race" })
                Spacer(Modifier.weight(1f))
                Button(onClick = onCollapse) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Hide") }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sound"); Switch(checked = sound, onCheckedChange = onSound)
                Text("Loop"); Switch(checked = loop, onCheckedChange = onLoop)
                Text("Vol")
                Slider(value = volume, onValueChange = onVolume, modifier = Modifier.weight(1f))
            }
            // Scale / mode picker — sets how element values quantize to pitches
            // (pentatonic, the Greek modes, whole-tone, blues, chromatic).
            if (scaleNames.isNotEmpty()) {
                Text("Scale", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scaleNames.forEachIndexed { i, name ->
                        FilterChip(selected = i == scaleIdx, onClick = { onScale(i) },
                            label = { Text(name) },
                            modifier = Modifier.semantics { contentDescription = name })
                    }
                }
            }
            if (mode == 0) {
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    algoNames.forEachIndexed { i, name ->
                        FilterChip(selected = i == algoIdx, onClick = { onAlgo(i) }, label = { Text(name) },
                            modifier = Modifier.semantics { contentDescription = name })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play")
                    }
                    Button(onClick = { onStep(-1) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Step back")
                    }
                    Button(onClick = { onStep(1) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Step forward")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onReset, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset")
                    }
                    Button(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle")
                    }
                    FilledTonalButton(onClick = onDraw, modifier = Modifier.weight(1f)) {
                        Icon(if (drawMode) Icons.Filled.Sort else Icons.Filled.Edit,
                            contentDescription = if (drawMode) "Sort" else "Draw")
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play")
                    }
                    Button(onClick = onReset, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset")
                    }
                    Button(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle")
                    }
                }
            }
            StepperRow("Speed", speed, 1, 512, 1, onSpeed)
            StepperRow("Size", size, 16, 400, 8, onSize)
            Text(stats, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StepperRow(label: String, value: Int, lo: Int, hi: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label $value", fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(86.dp))
        Button(onClick = { onChange((value - step).coerceAtLeast(lo)) }) {
            Icon(Icons.Filled.Remove, contentDescription = "decrease $label")
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(lo, hi)) },
            valueRange = lo.toFloat()..hi.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onChange((value + step).coerceAtMost(hi)) }) {
            Icon(Icons.Filled.Add, contentDescription = "increase $label")
        }
    }
}
