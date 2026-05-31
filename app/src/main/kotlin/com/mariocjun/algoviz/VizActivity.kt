// VizActivity — the native Jetpack Compose sort visualizer (replaces the ImGui
// GLSurfaceView). Compose owns rendering (a Canvas drawing a per-frame snapshot
// pulled from C++ via VizBridge) and all input (Material 3 controls + native
// gestures). The C++ side keeps the coroutine sort engine + AAudio synth.
package com.mariocjun.algoviz

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOn
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

private fun barColor(
    v01: Float, bright: Boolean, noteCount: Int,
    degreeOn: Boolean, moodOn: Boolean, mood: Float,
): Color {
    val hue = (v01 * 0.82f * 360f).coerceIn(0f, 360f)
    var sat = if (bright) 0.80f else 0.62f
    var value = if (bright) 1.00f else 0.90f
    if (degreeOn && noteCount > 0) {                       // emphasise chord tones
        val steps = noteCount * 3                          // kOctaves = 3
        val idx = (v01 * (steps - 1) + 0.5f).toInt().coerceIn(0, steps - 1)
        when (idx % noteCount) {
            0, 2, 4 -> { sat = (sat + 0.18f).coerceAtMost(1f); value = 1f }   // root/3rd/5th
            else -> { sat *= 0.55f; value *= 0.66f }                          // tensions: dim
        }
    }
    if (moodOn) {                                          // sad → darker, grand → brighter
        value *= (0.50f + 0.50f * mood)
        sat *= (0.65f + 0.35f * mood)
    }
    return Color.hsv(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
}

// Compact counter formatting so the stats line stays short (and stable-width)
// no matter how large the comparison/swap/step counts grow.
private fun fmt(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
    n >= 1_000     -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else           -> n.toString()
}

// Slow-motion rates, offered only for <=32 bars. ms = milliseconds per single
// step (0 = off, use the normal speed). Slowest = one operation every 2 s.
private val SLOW_LABELS = arrayOf("Off", "1/2s", "1/s", "2/s", "4/s")
private val SLOW_MS = intArrayOf(0, 2000, 1000, 500, 250)

// Each scale/mode has a "mood" in [0,1] — 0 = dark/sad, 1 = bright/grand — that
// shifts the bar brightness + saturation when Mood colouring is on. Order matches
// the scale menu.
private val SCALE_MOOD = floatArrayOf(
    0.88f, // Maj Pentatonic — cheerful
    0.42f, // Min Pentatonic — bluesy
    0.95f, // Ionian (major) — bright/happy
    0.62f, // Dorian — hopeful-melancholy
    0.28f, // Phrygian — dark/exotic
    1.00f, // Lydian — dreamy/grand
    0.72f, // Mixolydian — warm
    0.34f, // Aeolian (minor) — sad
    0.18f, // Locrian — unstable/dark
    0.78f, // Whole tone — floating
    0.40f, // Blues — gritty
    0.50f, // Chromatic — tense
)

class VizActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VizBridge.nativeInit()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Background bleeds edge-to-edge; the content is inset by the
                // system bars (status/navigation) so no control sits under them.
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) { VizScreen(); AutoCloseDisableOverlay() }
                }
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
    var loopRandom by remember { mutableStateOf(false) }   // reshuffle each loop (off = replay same)
    var drawMode by remember { mutableStateOf(false) }
    var controlsOpen by remember { mutableStateOf(true) }
    var scaleIdx by remember { mutableIntStateOf(0) }
    val scaleNames = remember { runCatching { VizBridge.nativeScaleNames() }.getOrDefault(emptyArray()) }
    var finishFx by remember { mutableStateOf(true) }   // completion flash + flourish (toggleable)
    var flash by remember { mutableFloatStateOf(0f) }   // white overlay intensity, decays per frame
    var prevDone by remember { mutableStateOf(false) }
    var reStrike by remember { mutableStateOf(false) }
    var rewinding by remember { mutableStateOf(false) }   // VHS rewind (long-press + drag left)
    var slowIdx by remember { mutableIntStateOf(0) }      // slow-motion rate (0 = off; <=32 bars only)
    var raceMode by remember { mutableIntStateOf(0) }     // 0 = fair race, 1 = worst case
    var scaleNotes by remember { mutableIntStateOf(5) }   // notes/octave of the active scale
    var degreeColor by remember { mutableStateOf(true) }  // highlight chord tones (root/3rd/5th)
    var moodColor by remember { mutableStateOf(true) }    // mode-mood brightness/saturation

    // Push initial UI state into the engine so the two never disagree (the C++
    // engine has its own defaults; the UI is the source of truth on launch).
    LaunchedEffect(Unit) {
        VizBridge.nativeSetMode(mode)
        VizBridge.nativeSetAlgorithm(algoIdx)
        VizBridge.nativeSetSpeed(speed)
        VizBridge.nativeSetSize(size)
        VizBridge.nativeSetScale(scaleIdx)
        scaleNotes = VizBridge.nativeScaleNotes(scaleIdx)
        VizBridge.nativeSetSlow(SLOW_MS[slowIdx])
        VizBridge.nativeSetRaceMode(raceMode)
        VizBridge.nativeSetSound(sound)
        VizBridge.nativeSetVolume(volume)
        VizBridge.nativeSetAutoLoop(loop)
        VizBridge.nativeSetLoopRandom(loopRandom)
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
                // Sort-completion detector (single: finished flag; race: every
                // lane finished) → lightning flash + a distinct audio flourish.
                val done = count > 0 && (
                    (ints.get(0) == 0 && ints.get(4) == 1) ||
                    (ints.get(0) == 1 && run {
                        val L = ints.get(1)
                        var all = L > 0
                        var k = 0
                        while (k < L) { if (ints.get(3 + 4 * k) != 1) { all = false; break }; k++ }
                        all
                    })
                )
                if (done && !prevDone && finishFx) {
                    flash = 1f; reStrike = true
                    if (sound) VizBridge.nativeCelebrate()
                }
                prevDone = done
                if (flash > 0f) {
                    flash *= 0.85f
                    if (reStrike && flash < 0.25f) { flash = 0.7f; reStrike = false }  // lightning double-strike
                    if (flash < 0.02f) flash = 0f
                }
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
            flash = flash,
            onDoubleTap = { playing = !playing; VizBridge.nativeTogglePlay() },
            onSpeed = { d -> speed = (speed + d).coerceIn(1, 512); VizBridge.nativeSetSpeed(speed) },
            onPaint = { idx, v01 -> VizBridge.nativePaint(idx, v01) },
            onPlayNote = { v01 -> VizBridge.nativePlayNote(v01) },
            rewinding = rewinding,
            onRewindActive = { active -> rewinding = active; if (active) playing = false },
            onRewindStep = { VizBridge.nativeStep(-1) },
            noteCount = scaleNotes,
            degreeOn = degreeColor,
            moodOn = moodColor,
            mood = SCALE_MOOD.getOrElse(scaleIdx) { 0.7f },
        )
    }

    val panel: @Composable (Modifier) -> Unit = { mod ->
        ControlPanel(
            modifier = mod,
            mode = mode, playing = playing, algoIdx = algoIdx, algoNames = algoNames,
            speed = speed, size = size, sound = sound, volume = volume, loop = loop, loopRandom = loopRandom,
            drawMode = drawMode, stats = stats,
            scaleIdx = scaleIdx, scaleNames = scaleNames, finishFx = finishFx, slowIdx = slowIdx, raceMode = raceMode,
            degreeColor = degreeColor, moodColor = moodColor,
            onMode = { m -> mode = m; drawMode = false; VizBridge.nativeSetDrawMode(false); VizBridge.nativeSetMode(m) },
            onAlgo = { i -> algoIdx = i; VizBridge.nativeSetAlgorithm(i) },
            onPlay = { playing = !playing; VizBridge.nativeSetPlaying(playing) },
            onStep = { dir -> playing = false; VizBridge.nativeStep(dir) },
            onReset = { VizBridge.nativeReset() },
            onShuffle = { VizBridge.nativeShuffle() },
            onDraw = { drawMode = !drawMode; if (drawMode) playing = false; VizBridge.nativeSetDrawMode(drawMode) },
            onSpeed = { s -> speed = s; VizBridge.nativeSetSpeed(s) },
            onSize = { s -> size = s; VizBridge.nativeSetSize(s); if (s > 32 && slowIdx != 0) { slowIdx = 0; VizBridge.nativeSetSlow(0) } },
            onSound = { e -> sound = e; VizBridge.nativeSetSound(e) },
            onVolume = { v -> volume = v; VizBridge.nativeSetVolume(v) },
            onScale = { i -> scaleIdx = i; VizBridge.nativeSetScale(i); scaleNotes = VizBridge.nativeScaleNotes(i) },
            onSlow = { i -> slowIdx = i; VizBridge.nativeSetSlow(SLOW_MS[i]) },
            onRaceMode = { m -> raceMode = m; VizBridge.nativeSetRaceMode(m) },
            onDegreeColor = { b -> degreeColor = b },
            onMoodColor = { b -> moodColor = b },
            onLoop = { b -> loop = b; VizBridge.nativeSetAutoLoop(b) },
            onLoopRandom = { b -> loopRandom = b; VizBridge.nativeSetLoopRandom(b) },
            onFinishFx = { b -> finishFx = b },
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
    flash: Float,
    onDoubleTap: () -> Unit,
    onSpeed: (Int) -> Unit,
    onPaint: (Int, Float) -> Unit,
    onPlayNote: (Float) -> Unit,
    rewinding: Boolean,
    onRewindActive: (Boolean) -> Unit,
    onRewindStep: () -> Unit,
    noteCount: Int,
    degreeOn: Boolean,
    moodOn: Boolean,
    mood: Float,
) {
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { pos ->            // tap a bar to play its note (single mode)
                        if (ints.get(0) == 0) {
                            val n = ints.get(1)
                            if (n > 0) {
                                val idx = (pos.x / size.width.toFloat() * n).toInt().coerceIn(0, n - 1)
                                onPlayNote(ints.get(10 + idx).toFloat() / n)
                            }
                        }
                    },
                )
            }
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
            .pointerInput(Unit) {
                // Press-and-hold then drag LEFT = rewind through the sort history.
                detectDragGesturesAfterLongPress(
                    onDragStart = { onRewindActive(true) },
                    onDragEnd = { onRewindActive(false) },
                    onDragCancel = { onRewindActive(false) },
                    onDrag = { change, drag ->
                        change.consume()
                        if (drag.x < 0f) repeat((-drag.x / 3f).toInt().coerceIn(1, 30)) { onRewindStep() }
                    },
                )
            }
    ) {
        if (frame < 0L) return@Canvas   // reference `frame` so the draw re-runs each tick
        if (ints.get(0) == 0) drawSingle(ints, noteCount, degreeOn, moodOn, mood)
        else drawRace(ints, algoNames, measurer, noteCount, degreeOn, moodOn, mood)
        if (flash > 0f) drawRect(color = Color.White, size = size, alpha = (flash * 0.85f).coerceIn(0f, 1f))
        if (rewinding) drawVhs(frame, measurer)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSingle(
    ints: java.nio.IntBuffer, noteCount: Int, degreeOn: Boolean, moodOn: Boolean, mood: Float,
) {
    val n = ints.get(1)
    if (n <= 0) return
    val hiA = ints.get(2); val hiB = ints.get(3); val finished = ints.get(4) == 1
    val w = size.width / n
    for (i in 0 until n) {
        val v = ints.get(10 + i)
        val v01 = v.toFloat() / n
        val h = v01 * size.height
        val col = if (!finished && (i == hiA || i == hiB)) Color.White
                  else barColor(v01, finished, noteCount, degreeOn, moodOn, mood)
        drawRect(col, Offset(i * w, size.height - h), Size(maxOf(w - 1f, 1f), h))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVhs(
    frame: Long, measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val w = size.width
    val h = size.height
    // VHS "tracking" — translucent horizontal bands sweeping down the screen.
    for (i in 0 until 5) {
        val y = (((frame * (7 + i * 11)) % 1000L).toFloat() / 1000f) * h
        drawRect(Color.White, topLeft = Offset(0f, y), size = Size(w, 3f), alpha = 0.15f)
    }
    // "◀◀ REW" marker in the empty upper area (VHS reference).
    val style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 44.sp)
    val m = measurer.measure("◀◀ REW", style)
    drawText(measurer, "◀◀ REW",
        topLeft = Offset(w / 2f - m.size.width / 2f, h * 0.15f), style = style)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRace(
    ints: java.nio.IntBuffer,
    algoNames: Array<String>,
    measurer: androidx.compose.ui.text.TextMeasurer,
    noteCount: Int, degreeOn: Boolean, moodOn: Boolean, mood: Float,
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
                val col = if (!finished && (i == hiA || i == hiB)) Color.White
                          else barColor(v01, finished, noteCount, degreeOn, moodOn, mood)
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
    speed: Int, size: Int, sound: Boolean, volume: Float, loop: Boolean, loopRandom: Boolean,
    drawMode: Boolean, stats: String, scaleIdx: Int, scaleNames: Array<String>, finishFx: Boolean,
    slowIdx: Int, raceMode: Int, degreeColor: Boolean, moodColor: Boolean,
    onMode: (Int) -> Unit, onAlgo: (Int) -> Unit, onPlay: () -> Unit, onStep: (Int) -> Unit,
    onReset: () -> Unit, onShuffle: () -> Unit, onDraw: () -> Unit,
    onSpeed: (Int) -> Unit, onSize: (Int) -> Unit, onSound: (Boolean) -> Unit,
    onVolume: (Float) -> Unit, onScale: (Int) -> Unit, onLoop: (Boolean) -> Unit, onLoopRandom: (Boolean) -> Unit,
    onFinishFx: (Boolean) -> Unit, onSlow: (Int) -> Unit, onRaceMode: (Int) -> Unit,
    onDegreeColor: (Boolean) -> Unit, onMoodColor: (Boolean) -> Unit, onCollapse: () -> Unit,
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
                Icon(Icons.Filled.FlashOn, contentDescription = "Finish FX")
                Switch(checked = finishFx, onCheckedChange = onFinishFx,
                    modifier = Modifier.semantics { contentDescription = "Finish FX" })
                Button(onClick = onCollapse, modifier = Modifier.semantics { contentDescription = "Hide" }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sound"); Switch(checked = sound, onCheckedChange = onSound)
                Text("Loop"); Switch(checked = loop, onCheckedChange = onLoop)
                Text("Rnd"); Switch(checked = loopRandom, onCheckedChange = onLoopRandom,
                    modifier = Modifier.semantics { contentDescription = "Random loop" })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Degrees", fontSize = 12.sp)
                Switch(checked = degreeColor, onCheckedChange = onDegreeColor,
                    modifier = Modifier.semantics { contentDescription = "Degrees" })
                Text("Mood", fontSize = 12.sp)
                Switch(checked = moodColor, onCheckedChange = onMoodColor,
                    modifier = Modifier.semantics { contentDescription = "Mood" })
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
                    Button(onClick = onPlay, modifier = Modifier.weight(1f).semantics { contentDescription = if (playing) "Pause" else "Play" }) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                    }
                    Button(onClick = { onStep(-1) }, modifier = Modifier.weight(1f).semantics { contentDescription = "Step back" }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = null)
                    }
                    Button(onClick = { onStep(1) }, modifier = Modifier.weight(1f).semantics { contentDescription = "Step forward" }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onReset, modifier = Modifier.weight(1f).semantics { contentDescription = "Reset" }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Button(onClick = onShuffle, modifier = Modifier.weight(1f).semantics { contentDescription = "Shuffle" }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null)
                    }
                    FilledTonalButton(onClick = onDraw, modifier = Modifier.weight(1f).semantics { contentDescription = if (drawMode) "Sort" else "Draw" }) {
                        Icon(if (drawMode) Icons.Filled.Sort else Icons.Filled.Edit, contentDescription = null)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Race", fontSize = 12.sp)
                    FilterChip(selected = raceMode == 0, onClick = { onRaceMode(0) },
                        label = { Text("Fair") },
                        modifier = Modifier.semantics { contentDescription = "Fair" })
                    FilterChip(selected = raceMode == 1, onClick = { onRaceMode(1) },
                        label = { Text("Worst case") },
                        modifier = Modifier.semantics { contentDescription = "Worst case" })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onPlay, modifier = Modifier.weight(1f).semantics { contentDescription = if (playing) "Pause" else "Play" }) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                    }
                    Button(onClick = onReset, modifier = Modifier.weight(1f).semantics { contentDescription = "Reset" }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Button(onClick = onShuffle, modifier = Modifier.weight(1f).semantics { contentDescription = "Shuffle" }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null)
                    }
                }
            }
            StepperRow("Speed", speed, 1, 512, 1, onSpeed)
            StepperRow("Size", size, 16, 400, 8, onSize)
            if (size <= 32) {     // slow-motion only makes sense for a few bars
                Text("Slow (≤32 bars)", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SLOW_LABELS.forEachIndexed { i, lab ->
                        FilterChip(selected = i == slowIdx, onClick = { onSlow(i) },
                            label = { Text(lab) },
                            modifier = Modifier.semantics { contentDescription = "slow $lab" })
                    }
                }
            }
            Text(stats, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StepperRow(label: String, value: Int, lo: Int, hi: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label $value", fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(86.dp))
        Button(onClick = { onChange((value - step).coerceAtLeast(lo)) },
            modifier = Modifier.semantics { contentDescription = "decrease $label" }) {
            Icon(Icons.Filled.Remove, contentDescription = null)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(lo, hi)) },
            valueRange = lo.toFloat()..hi.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onChange((value + step).coerceAtMost(hi)) },
            modifier = Modifier.semantics { contentDescription = "increase $label" }) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
    }
}
