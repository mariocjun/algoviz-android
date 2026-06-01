// ExtremeActivity — the "Extremo" easter-egg: a full-screen, sort-visualizer-style
// animation of debt simplification. It steps through buildReduction()'s steps the
// way VizActivity steps through a sort — play/pause, step, rewind/scrub, speed
// (½…16 steps/sec) — and pays them off with lightning on the active edge, a live
// heuristic + min() formula, a running-rainbow easter-egg, and the headline
// collapse: 41 direct debts between 20 people reduce to a single payment,
// "Mário deve R$ 67,00 para Cássia".
//
// All the parafernália lives HERE; the normal Racha graph stays clean. Reached
// only from the Racha graph's ✨ chip once the extreme demo is loaded.
package com.mariocjun.algoviz

import android.content.res.Configuration
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.launch

// ---- Cinematic palette (darker / higher-contrast than the Racha tab) ----------
// internal so the tutorial sheet (ExtremeTutorial.kt) shares the exact look.
internal val EX_BG = Color(0xFF0A0A0D)
internal val EX_TXT = Color(0xFFF3F3F6)
internal val EX_DIM = Color(0xFF9A9AA4)
internal val EX_OWED = Color(0xFF3DCF7A)   // creditor — green
internal val EX_OWES = Color(0xFFFF5C50)   // debtor — coral
internal val EX_BLUE = Color(0xFF4296FA)
internal val EX_GOLD = Color(0xFFE8B62E)   // coins
internal val GLASS = Color(0xFF15151B)     // translucent panel base

// Steps per second. Spans the slow end (½, 1) the owner likes through 8/16.
private val SPS_LABELS = arrayOf("½", "1", "2", "4", "8", "16")
private val SPS_MS = longArrayOf(2000, 1000, 500, 250, 125, 62)
private const val DEFAULT_SPS = 4         // 8 steps/sec (190 absorbs is a lot at 4)

/** A short-lived floating label that rises off an edge: who owes whom (red),
 *  a cancellation (gold), a receipt (green). Purely visual feedback. */
private class Floater(val a: String, val b: String, val text: String, val color: Color, var age: Float = 0f)

class ExtremeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VizBridge.nativeInit()   // ensure the shared AAudio synth exists (idempotent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = EX_BG, surface = GLASS, primary = EX_BLUE,
                    onBackground = EX_TXT, onSurface = EX_TXT, onPrimary = Color.White,
                ),
            ) {
                Surface(color = EX_BG) {
                    AutoCloseGuard { Box(Modifier.safeDrawingPadding()) { ExtremeScreen { finish() } } }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); VizBridge.nativeAudioResume() }
    override fun onPause() { VizBridge.nativeAudioPause(); super.onPause() }
}

@Composable
private fun ExtremeScreen(onExit: () -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()

    val people = remember { extremeDemo().first }
    val expenses = remember { extremeDemo().second }
    val r = remember { buildReduction(people, expenses) }
    val total = r.steps.size
    val buildEnd = r.buildEnd
    val pruneEnd = r.pruneEnd
    // Lay nodes by their net balance just before settling, so the two non-zero
    // people (Cássia +, Mário −) land adjacent at the top and their final arc reads.
    val netBal = remember { r.balancesAt(pruneEnd) }
    val order = remember { people.sortedByDescending { netBal[it] ?: 0L } }
    val ringIndex = remember(order) { order.withIndex().associate { (i, name) -> name to i } }
    val colorOf: (String) -> Color =
        remember { { name -> PERSON_HUES[(people.indexOf(name).coerceAtLeast(0)) % PERSON_HUES.size] } }

    var cursor by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var spsIdx by remember { mutableIntStateOf(DEFAULT_SPS) }
    var rainbow by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(true) }
    var heldAtFull by remember { mutableStateOf(false) }   // one auto-pause on the full graph
    var showTutorial by remember { mutableStateOf(!ExtremeTutorial.dismissed) }  // intro auto-opens once/session
    val floaters = remember { mutableStateListOf<Floater>() }   // rising who-owes-whom / cancela / recebe pops
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val edgeFlash = remember { Animatable(0f) }     // active-edge bolt intensity
    val screenFlash = remember { Animatable(0f) }    // full-screen lightning (settle/payoff)

    val tr = rememberInfiniteTransition(label = "ex")
    val hue by tr.animateFloat(0f, 360f,
        infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "hue")
    val bob by tr.animateFloat(0f, (2.0 * PI).toFloat(),
        infiniteRepeatable(tween(4200), RepeatMode.Restart), label = "bob")
    val pulse by tr.animateFloat(0.55f, 1f,
        infiniteRepeatable(tween(820), RepeatMode.Reverse), label = "pulse")

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val done = cursor >= total
    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun strike(big: Boolean) {
        scope.launch {
            edgeFlash.snapTo(1f)
            edgeFlash.animateTo(0f, keyframes { durationMillis = 460; 0.35f at 110; 0.95f at 180; 0f at 460 })
        }
        if (big) scope.launch {
            screenFlash.snapTo(0.8f)
            screenFlash.animateTo(0f, keyframes { durationMillis = 560; 0.25f at 120; 0.7f at 190; 0f at 560 })
        }
    }
    fun goTo(c: Int, manual: Boolean) {
        val nc = c.coerceIn(0, total)
        if (nc == cursor) return
        val advancing = nc > cursor
        cursor = nc
        val isSettle = nc > pruneEnd
        strike(isSettle || nc == total)
        if (manual || isSettle || nc == total) haptic()
        // Floating pops (visual, regardless of mute) + ASMR note. Forward only.
        // Build → "A→B" in red (a debt appears). Absorb that shrinks the total
        // imbalance → "cancela" in gold (the satisfying part). Settle → "recebe"
        // in green. A pentatonic note tracks each touched edge; chord on settle.
        if (advancing) {
            when (val st = r.steps[nc - 1]) {
                is ReduceStep.Build -> {
                    floaters.add(Floater(st.edge.from, st.edge.to, "${initials(st.edge.from)}→${initials(st.edge.to)}", EX_OWES))
                    if (sound) VizBridge.nativePlayNote((ringIndex[st.edge.to] ?: 0).toFloat() / order.size)
                }
                is ReduceStep.Absorb -> {
                    val before = r.balancesAt(nc - 1); val after = r.balancesAt(nc); val d = st.edge
                    val delta = (kotlin.math.abs(after[d.from] ?: 0L) + kotlin.math.abs(after[d.to] ?: 0L)) -
                                (kotlin.math.abs(before[d.from] ?: 0L) + kotlin.math.abs(before[d.to] ?: 0L))
                    // gold "cancela" when the debt shrinks the imbalance, else the
                    // plain red who-owes-whom — so the prune always has life.
                    if (delta < 0L) floaters.add(Floater(d.from, d.to, "cancela", EX_GOLD))
                    else floaters.add(Floater(d.from, d.to, "${initials(d.from)}→${initials(d.to)}", EX_OWES))
                    if (sound) VizBridge.nativePlayNote((ringIndex[d.to] ?: 0).toFloat() / order.size)
                }
                is ReduceStep.Settle -> {
                    floaters.add(Floater(st.from, st.to, "recebe ${money(st.amountCents)}", EX_OWED))
                    if (sound) VizBridge.nativeCelebrate()
                }
            }
            while (floaters.size > 10) floaters.removeAt(0)   // "uns popzinhos", not a swarm
        }
    }

    // Audio: consonant pentatonic synth, soft volume; mute follows the toggle.
    LaunchedEffect(Unit) { VizBridge.nativeSetScale(0); VizBridge.nativeSetVolume(0.55f) }
    LaunchedEffect(sound) { VizBridge.nativeSetSound(sound) }

    // Auto-advance on a fixed step interval while playing (paused while the
    // tutorial is up, so the animation waits behind it).
    LaunchedEffect(playing, spsIdx, showTutorial) {
        if (!playing || showTutorial) return@LaunchedEffect
        while (cursor < total) {
            kotlinx.coroutines.delay(SPS_MS[spsIdx])
            if (!playing) break
            goTo(cursor + 1, manual = false)
            // Hold once on the complete graph so "cheio" is a real beat.
            if (cursor == buildEnd && !heldAtFull) { heldAtFull = true; playing = false; break }
        }
        playing = false
    }

    // Age + cull the floating pops (rise ~1s then fade out).
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                if (floaters.isNotEmpty()) {
                    for (f in floaters) f.age += dt
                    floaters.removeAll { it.age > 1.05f }
                }
            }
        }
    }

    var topGuardPx by remember { mutableFloatStateOf(0f) }
    var botGuardPx by remember { mutableFloatStateOf(0f) }

    val onReset = { playing = false; cursor = 0; heldAtFull = false }
    val onBack = { playing = false; goTo(cursor - 1, manual = true) }
    val onPlay = { if (done) cursor = 0; playing = !playing }
    val onForward = { playing = false; goTo(cursor + 1, manual = true) }
    val onSeek: (Int) -> Unit = { c -> playing = false; goTo(c, manual = true) }

    // Full-bleed graph; chrome floats on glass. Insets are measured from the
    // real chrome (status card top, control strip bottom) so nothing overlaps;
    // in landscape the controls move to a right rail and the ring gets the
    // full height (botGuard = 0) instead of being squeezed by a bottom bar.
    val graph: @Composable (Modifier) -> Unit = { mod ->
        Canvas(
            mod.graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.7f, 5f)     // 0.7 = 30% beyond fit
                        pan = if (scale <= 1.01f) Offset.Zero else pan + panChange
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; pan = Offset.Zero }) },
        ) {
            drawReductionFrame(
                r, order, colorOf, cursor, measurer,
                bob, pulse, hue, rainbow, edgeFlash.value, screenFlash.value,
                topGuardPx, if (landscape) 0f else botGuardPx, floaters,
            )
        }
    }
    val status: @Composable (Modifier) -> Unit = { mod ->
        ExtremeStatus(
            r = r, cursor = cursor, total = total,
            rainbow = rainbow, hue = hue, onToggleRainbow = { rainbow = !rainbow },
            modifier = mod.onGloballyPositioned { topGuardPx = it.boundsInParent().bottom },
        )
    }

    Box(Modifier.fillMaxSize()) {
    if (landscape) {
        Row(Modifier.fillMaxSize().background(EX_BG)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                graph(Modifier.fillMaxSize())
                status(Modifier.align(Alignment.TopStart).padding(12.dp))
                if (done) PayoffBanner(r.direct.size, r.settlements.size, hue, pulse, Modifier.align(Alignment.Center))
            }
            Column(
                Modifier.width(214.dp).fillMaxHeight().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    GlassIcon(Icons.Filled.Info, "Como funciona", { showTutorial = true }, Modifier)
                    GlassIcon(if (sound) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        if (sound) "Som ligado" else "Som desligado", { sound = !sound }, Modifier)
                    GlassIcon(Icons.Filled.Close, "Voltar", onExit, Modifier)
                }
                Spacer(Modifier.weight(1f))
                ControlCard {
                    Scrubber(cursor, buildEnd, pruneEnd, total, Modifier.fillMaxWidth(), onSeek)
                    TransportButtons(playing, onReset, onBack, onPlay, onForward, wrap = true)
                    SpeedSelector(spsIdx, { spsIdx = it }, wrap = true)
                }
                Spacer(Modifier.weight(1f))
            }
        }
    } else {
        Box(Modifier.fillMaxSize().background(EX_BG)) {
            graph(Modifier.fillMaxSize())
            status(Modifier.align(Alignment.TopStart).padding(12.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassIcon(Icons.Filled.Info, "Como funciona", { showTutorial = true }, Modifier)
                GlassIcon(if (sound) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    if (sound) "Som ligado" else "Som desligado", { sound = !sound }, Modifier)
                GlassIcon(Icons.Filled.Close, "Voltar", onExit, Modifier)
            }
            if (done) PayoffBanner(r.direct.size, r.settlements.size, hue, pulse, Modifier.align(Alignment.Center))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                    .onGloballyPositioned { botGuardPx = it.boundsInParent().top },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Scrubber(cursor, buildEnd, pruneEnd, total, Modifier.fillMaxWidth().widthIn(max = 560.dp), onSeek)
                ControlCard {
                    TransportButtons(playing, onReset, onBack, onPlay, onForward)
                    SpeedSelector(spsIdx, { spsIdx = it }, wrap = false)
                }
            }
        }
    }
        if (showTutorial) TutorialSheet { dont -> if (dont) ExtremeTutorial.dismissed = true; showTutorial = false }
    }
}

// ---- Canvas frame -------------------------------------------------------------

private fun DrawScope.drawReductionFrame(
    r: DebtReduction, order: List<String>, colorOf: (String) -> Color,
    cursor: Int, measurer: TextMeasurer,
    bob: Float, pulse: Float, hue: Float, rainbow: Boolean,
    edgeFlash: Float, screenFlash: Float, topGuardPx: Float, botGuardPx: Float,
    floaters: List<Floater>,
) {
    val n = order.size
    if (n == 0) return
    // Insets are MEASURED from the real chrome: topGuardPx = the status card's
    // bottom edge, botGuardPx = the bottom control strip's top edge (0 when the
    // controls live in a side rail, e.g. landscape). So nodes/edges never hide
    // behind chrome regardless of orientation, font scale, or formula line.
    val topInset = (if (topGuardPx > 1f) topGuardPx else 92f) + 14f
    val bottomInset = (if (botGuardPx > 1f) size.height - botGuardPx else 34f) + 14f
    val cx = size.width / 2f
    val cy = (topInset + (size.height - bottomInset)) / 2f
    val rx = (size.width / 2f - 46f).coerceAtLeast(10f)
    val ry = ((size.height - topInset - bottomInset) / 2f - 6f).coerceAtLeast(10f)
    // Node radius scales with the canvas so the ring fills a tablet as nicely as
    // a phone (was a fixed px → tiny dots on a large screen). Tuned to match the
    // old phone sizes at ~720px and grow proportionally on bigger surfaces.
    val unit = minOf(size.width, size.height)
    val nodeR = (unit * when { n <= 8 -> 0.028f; n <= 14 -> 0.020f; else -> 0.0153f }).coerceIn(7f, 40f)
    val center = Offset(cx, cy)
    val curve = 0.34f

    val pos = HashMap<String, Offset>(n)
    order.forEachIndexed { i, name ->
        val a = -PI / 2.0 + i * 2.0 * PI / n
        val bobY = sin(bob + i.toFloat()) * 3.5f
        pos[name] = Offset(cx + (rx * cos(a)).toFloat(), cy + (ry * sin(a)).toFloat() + bobY)
    }
    val maxAmt = (r.direct.maxOfOrNull { it.amountCents } ?: 1L)
        .coerceAtLeast(r.settlements.maxOfOrNull { it.amountCents } ?: 1L).coerceAtLeast(1L)
    fun nodeColor(name: String, i: Int): Color =
        if (rainbow) Color.hsv(((hue + i * (360f / n)) % 360f), 0.72f, 1f) else colorOf(name)

    // 1) Direct debts on screen. Building (cursor ≤ buildEnd): the first `cursor`
    // debts have appeared. Pruning (cursor ≤ pruneEnd): the first (cursor-buildEnd)
    // have netted away, so the remaining tail is drawn. The denser the web, the
    // more translucent (and arrow-free) each edge — 190 debts read as a haze.
    val buildEnd = r.buildEnd
    val pruneEnd = r.pruneEnd
    val visLo: Int; val visHi: Int
    if (cursor <= buildEnd) { visLo = 0; visHi = cursor.coerceAtMost(r.direct.size) }
    else { visLo = (cursor - buildEnd).coerceIn(0, r.direct.size); visHi = r.direct.size }
    val dense = r.direct.size > 80
    val tangleAlpha = if (dense) 0.12f else 0.22f
    for (i in visLo until visHi) {
        val d = r.direct[i]
        val pu = pos[d.from] ?: continue; val pv = pos[d.to] ?: continue
        drawEdge(pu, pv, center, curve, nodeR, thickness(d.amountCents, maxAmt, 4f),
            (if (rainbow) Color.hsv((hue + i * 11f) % 360f, 0.6f, 1f) else EX_OWES).copy(alpha = tangleAlpha),
            arrow = !dense)
    }

    // 2) Settlement edges (after the prune phase) — bright, with amount chips.
    val settledShown = (cursor - pruneEnd).coerceIn(0, r.settlements.size)
    for (i in 0 until settledShown) {
        val s = r.settlements[i]
        val pu = pos[s.from] ?: continue; val pv = pos[s.to] ?: continue
        val col = if (rainbow) Color.hsv((hue + 120f) % 360f, 0.7f, 1f) else EX_OWED
        drawEdge(pu, pv, center, curve, nodeR, thickness(s.amountCents, maxAmt, 7f), col.copy(alpha = 0.95f), arrow = true)
        drawAmountChip(measurer, pu, pv, center, curve, money(s.amountCents), col)
    }

    // 3) Lightning on the just-touched edge.
    if (edgeFlash > 0.02f && cursor in 1..r.steps.size) {
        val st = r.steps[cursor - 1]
        val pair = when (st) {
            is ReduceStep.Build -> st.edge.from to st.edge.to
            is ReduceStep.Absorb -> st.edge.from to st.edge.to
            is ReduceStep.Settle -> st.from to st.to
        }
        val pu = pos[pair.first]; val pv = pos[pair.second]
        if (pu != null && pv != null) drawBolt(pu, pv, center, curve, nodeR, edgeFlash, cursor)
    }

    // 4) Nodes + initials + running balance chips. Chips only when few nodes are
    // still live, so the messy 190-edge middle stays clean and the numbers appear
    // exactly at the convergence (… → just Mário & Cássia → the payment).
    val bal = r.balancesAt(cursor)
    val liveCount = bal.values.count { it != 0L }
    order.forEachIndexed { i, name ->
        val pp = pos[name] ?: return@forEachIndexed
        val b = bal[name] ?: 0L
        val live = b != 0L
        val col = nodeColor(name, i)
        // glow ring on still-active nodes
        if (live) drawCircle(col.copy(alpha = 0.18f * pulse), radius = nodeR * 1.7f, center = pp)
        drawCircle(if (live) col else col.copy(alpha = 0.4f), radius = nodeR, center = pp)
        val ist = TextStyle(color = Color.White.copy(alpha = if (live) 1f else 0.7f),
            fontSize = (nodeR * 0.62f).coerceAtLeast(7f).sp, fontWeight = FontWeight.Bold)
        val im = measurer.measure(initials(name), ist)
        drawText(textLayoutResult = im,
            topLeft = Offset(pp.x - im.size.width / 2f, pp.y - im.size.height / 2f))
        if (live && liveCount <= 6) {
            val bc = if (b > 0) EX_OWED else EX_OWES
            val txt = (if (b > 0) "+" else "−") + money(kotlin.math.abs(b)).removePrefix("R$ ")
            val bs = TextStyle(color = bc, fontSize = (nodeR * 0.5f).coerceIn(8f, 12f).sp, fontWeight = FontWeight.Bold)
            val bm = measurer.measure(txt, bs)
            drawText(textLayoutResult = bm, topLeft = Offset(pp.x - bm.size.width / 2f, pp.y + nodeR + 2f))
        }
    }

    // 4.5) Floating pops rising off their edge: who owes whom (red), cancellations
    // (gold), the receipt (green) — the life the prune phase was missing.
    for (f in floaters) {
        val pu = pos[f.a] ?: continue; val pv = pos[f.b] ?: continue
        val a = (1f - f.age / 1.05f).coerceIn(0f, 1f)
        val mx = (pu.x + pv.x) / 2f; val my = (pu.y + pv.y) / 2f - f.age * 50f
        val style = TextStyle(color = Color.White.copy(alpha = a), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val m = measurer.measure(f.text, style)
        val cw = m.size.width + 12f; val ch = m.size.height + 6f
        val tl = Offset(mx - cw / 2f, my - ch / 2f)
        drawRoundRect(f.color.copy(alpha = 0.92f * a), topLeft = tl, size = Size(cw, ch), cornerRadius = CornerRadius(7f, 7f))
        drawText(textLayoutResult = m, topLeft = Offset(mx - m.size.width / 2f, my - m.size.height / 2f))
    }

    // 5) Full-screen lightning flash (settle / payoff).
    if (screenFlash > 0.02f) drawRect(Color.White, size = size, alpha = (screenFlash * 0.6f).coerceIn(0f, 1f))
}

private fun thickness(amt: Long, maxAmt: Long, max: Float): Float =
    (1.5f + max * (amt.toFloat() / maxAmt)).coerceIn(1.5f, max + 2f)

/** Curved (quadratic) directed edge from node u to node v, arrowhead at v. */
private fun DrawScope.drawEdge(
    pu: Offset, pv: Offset, center: Offset, curve: Float, nodeR: Float,
    thickness: Float, color: Color, arrow: Boolean,
) {
    val dx = pv.x - pu.x; val dy = pv.y - pu.y
    val len = hypot(dx, dy); if (len < 1f) return
    val ux = dx / len; val uy = dy / len
    val s = Offset(pu.x + ux * nodeR, pu.y + uy * nodeR)
    val gap = nodeR * 0.5f + 4f
    val e = Offset(pv.x - ux * (nodeR + gap), pv.y - uy * (nodeR + gap))
    val mx = (s.x + e.x) / 2f; val my = (s.y + e.y) / 2f
    val cpx = mx + (center.x - mx) * curve; val cpy = my + (center.y - my) * curve
    drawPath(Path().apply { moveTo(s.x, s.y); quadraticBezierTo(cpx, cpy, e.x, e.y) }, color, style = Stroke(thickness))
    if (!arrow) return
    var adx = e.x - cpx; var ady = e.y - cpy
    val al = hypot(adx, ady); if (al > 0.01f) { adx /= al; ady /= al } else { adx = ux; ady = uy }
    val ah = (nodeR * 0.55f).coerceIn(6f, 12f); val px = -ady; val py = adx
    val b1 = Offset(e.x - adx * ah + px * ah * 0.55f, e.y - ady * ah + py * ah * 0.55f)
    val b2 = Offset(e.x - adx * ah - px * ah * 0.55f, e.y - ady * ah - py * ah * 0.55f)
    drawPath(Path().apply { moveTo(e.x, e.y); lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close() }, color)
}

/** Jagged lightning bolt along an edge — the "raio" reused from the sort viz. */
private fun DrawScope.drawBolt(
    pu: Offset, pv: Offset, center: Offset, curve: Float, nodeR: Float, intensity: Float, seed: Int,
) {
    val dx = pv.x - pu.x; val dy = pv.y - pu.y
    val len = hypot(dx, dy); if (len < 1f) return
    val ux = dx / len; val uy = dy / len
    val s = Offset(pu.x + ux * nodeR, pu.y + uy * nodeR)
    val e = Offset(pv.x - ux * nodeR, pv.y - uy * nodeR)
    val px = -uy; val py = ux
    val segs = 8
    val path = Path().apply {
        moveTo(s.x, s.y)
        for (k in 1 until segs) {
            val t = k.toFloat() / segs
            val bx = s.x + (e.x - s.x) * t; val by = s.y + (e.y - s.y) * t
            // alternating jagged offset, biggest in the middle of the bolt
            val sign = if (k % 2 == 0) 1f else -1f
            val j = sign * (0.10f + 0.06f * sin((seed + k * 53) * 1.7f)) * len * (0.5f - kotlin.math.abs(0.5f - t))
            lineTo(bx + px * j, by + py * j)
        }
        lineTo(e.x, e.y)
    }
    // glow behind, bright white core on top → reads as lightning, not a line
    drawPath(path, EX_BLUE.copy(alpha = (0.45f * intensity).coerceIn(0f, 1f)), style = Stroke(7f))
    drawPath(path, Color.White.copy(alpha = (0.95f * intensity).coerceIn(0f, 1f)), style = Stroke(2.6f))
}

/** Opaque amount chip off the line (~62% toward the creditor), never on it. */
private fun DrawScope.drawAmountChip(
    measurer: TextMeasurer, pu: Offset, pv: Offset, center: Offset, curve: Float, text: String, col: Color,
) {
    val dx = pv.x - pu.x; val dy = pv.y - pu.y
    val len = hypot(dx, dy); if (len < 1f) return
    val ux = dx / len; val uy = dy / len; val px = -uy; val py = ux
    val t = 0.6f
    val lx = pu.x + (pv.x - pu.x) * t + px * 13f
    val ly = pu.y + (pv.y - pu.y) * t + py * 13f
    val style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val m = measurer.measure(text, style)
    val cw = m.size.width + 14f; val ch = m.size.height + 8f
    val tl = Offset(lx - cw / 2f, ly - ch / 2f)
    drawRoundRect(EX_BG.copy(alpha = 0.95f), topLeft = tl, size = Size(cw, ch), cornerRadius = CornerRadius(8f, 8f))
    drawRoundRect(col, topLeft = tl, size = Size(cw, ch), cornerRadius = CornerRadius(8f, 8f), style = Stroke(1.5f))
    drawText(textLayoutResult = m, topLeft = Offset(lx - m.size.width / 2f, ly - m.size.height / 2f))
}

// ---- Glass overlays -----------------------------------------------------------

@Composable
private fun ExtremeStatus(
    r: DebtReduction, cursor: Int, total: Int,
    rainbow: Boolean, hue: Float, onToggleRainbow: () -> Unit, modifier: Modifier,
) {
    val titleColor = if (rainbow) Color.hsv(hue % 360f, 0.8f, 1f) else EX_TXT
    val buildEnd = r.buildEnd; val pruneEnd = r.pruneEnd; val nd = r.direct.size
    val phase: String; val caption: String; val formula: String?
    when {
        cursor == 0 -> {
            phase = "${r.people.size} pessoas · C(${r.people.size},2) = $nd"
            caption = "Toque ▶ — vamos montar todas as dívidas possíveis."
            formula = null
        }
        cursor < buildEnd -> {                                   // Act 1 — Enchendo
            phase = "Enchendo · $cursor / $nd dívidas"
            caption = "Todo par pode dever: o grafo completo se forma."
            formula = null
        }
        cursor == buildEnd -> {                                  // Act 2 — Cheio
            phase = "Grafo cheio · $nd dívidas"
            caption = "Todas as dívidas diretas. ▶ para simplificar."
            formula = null
        }
        cursor <= pruneEnd -> {                                  // Act 3 — Podando
            phase = "Podando · ${cursor - buildEnd} / $nd"
            caption = "Cada dívida vira saldo; os ciclos se cancelam."
            formula = "restam ${pruneEnd - cursor} arestas"
        }
        else -> {                                                // Act 4 — Acerto / Fim
            // Keep the settlement's min() formula on screen, incl. at the payoff.
            val s = r.steps[cursor - 1] as? ReduceStep.Settle
            phase = if (cursor >= total) "Reduzido · $nd → ${r.settlements.size}"
                    else "Acerto · pagamento ${cursor - pruneEnd} / ${r.settlements.size}"
            caption = if (cursor >= total) "1 pagamento zera todo mundo (economia de ${nd - r.settlements.size})."
                      else "Maior credor recebe do maior devedor."
            formula = s?.let { "${money(it.amountCents)} = min(${money(it.creditorBefore)}, ${money(-it.debtorBefore)})" }
        }
    }
    GlassCard(modifier.widthIn(max = 360.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Extremo", color = titleColor, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { onToggleRainbow() }) })
            Spacer(Modifier.width(8.dp))
            Text(phase, color = EX_DIM, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(2.dp))
        Text(caption, color = EX_TXT.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        if (formula != null) {
            Spacer(Modifier.height(3.dp))
            Text(formula, color = EX_BLUE, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PayoffBanner(directCount: Int, payCount: Int, hue: Float, pulse: Float, modifier: Modifier) {
    GlassCard(modifier.widthIn(max = 360.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for ((i, c) in "Mário".withIndex())
                Text(c.toString(), color = Color.hsv((hue + i * 24f) % 360f, 0.75f, 1f),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(8.dp))
            Text("deve", color = EX_DIM, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(2.dp))
        Text("R$ 67,00", color = EX_OWED.copy(alpha = pulse), fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineMedium)
        Text("para Cássia", color = EX_TXT, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("$directCount diretas → $payCount pagamento", color = EX_DIM, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Scrubber(cursor: Int, buildEnd: Int, pruneEnd: Int, total: Int, modifier: Modifier, onSeek: (Int) -> Unit) {
    Box(
        modifier
            .height(26.dp)
            .pointerInput(total) {
                detectTapGestures { p -> onSeek((p.x / size.width.toFloat() * total).toInt()) }
            }
            .pointerInput(total) {
                detectDragGestures { change, _ ->
                    onSeek((change.position.x / size.width.toFloat() * total).toInt())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val w = size.width; val h = size.height; val y = h / 2f
            val buildX = w * (buildEnd.toFloat() / total)
            val pruneX = w * (pruneEnd.toFloat() / total)
            // three phase tracks: Enchendo · Podando · Acerto, then progress + thumb.
            drawLine(EX_OWES.copy(alpha = 0.22f), Offset(0f, y), Offset(buildX, y), strokeWidth = h)
            drawLine(Color(0xFFE8B62E).copy(alpha = 0.22f), Offset(buildX, y), Offset(pruneX, y), strokeWidth = h)
            drawLine(EX_OWED.copy(alpha = 0.30f), Offset(pruneX, y), Offset(w, y), strokeWidth = h)
            val px = w * (cursor.toFloat() / total)
            drawLine(EX_BLUE, Offset(0f, y), Offset(px, y), strokeWidth = h)
            drawCircle(Color.White, radius = h * 1.6f, center = Offset(px.coerceIn(0f, w), y))
        }
    }
}

/** Glass surface that groups the transport + speed controls. */
@Composable
private fun ControlCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).background(GLASS.copy(alpha = 0.82f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun TransportButtons(
    playing: Boolean, onReset: () -> Unit, onBack: () -> Unit, onPlay: () -> Unit, onForward: () -> Unit,
    wrap: Boolean = false,
) {
    val reset = @Composable { TransportIcon(Icons.Filled.Refresh, "Reiniciar", onReset) }
    val back = @Composable { TransportIcon(Icons.Filled.SkipPrevious, "Voltar passo", onBack) }
    val play = @Composable {
        TransportIcon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (playing) "Pausar" else "Reproduzir", onPlay, primary = true)
    }
    val fwd = @Composable { TransportIcon(Icons.Filled.SkipNext, "Avançar passo", onForward) }
    if (wrap) {
        // 2×2 so the 44–50dp targets fit the narrow landscape rail without clipping.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { reset(); back() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { play(); fwd() }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            reset(); back(); play(); fwd()
        }
    }
}

/** Steps-per-second selector. `wrap` = two rows of three (for the narrow rail). */
@Composable
private fun SpeedSelector(spsIdx: Int, onSpeed: (Int) -> Unit, wrap: Boolean) {
    val chip: @Composable (Int) -> Unit = { i ->
        val on = i == spsIdx
        Box(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(if (on) EX_BLUE else Color.White.copy(alpha = 0.06f))
                .clickable { onSpeed(i) }
                .semantics { contentDescription = "vel ${SPS_LABELS[i]}" }
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) { Text(SPS_LABELS[i], color = if (on) Color.White else EX_DIM, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
    }
    if (wrap) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (i in 0..2) chip(i) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (i in 3..5) chip(i) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (i in SPS_LABELS.indices) chip(i) }
    }
}

@Composable
private fun TransportIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit, primary: Boolean = false) {
    Box(
        Modifier.size(if (primary) 50.dp else 44.dp).clip(RoundedCornerShape(50))
            .background(if (primary) EX_BLUE else Color.White.copy(alpha = 0.07f))
            .clickable { onClick() }.semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = if (primary) Color.White else EX_TXT, modifier = Modifier.size(if (primary) 27.dp else 23.dp)) }
}

@Composable
private fun GlassIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.size(46.dp).clip(RoundedCornerShape(50)).background(GLASS.copy(alpha = 0.8f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
            .clickable { onClick() }.semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = EX_TXT, modifier = Modifier.size(23.dp)) }
}

@Composable
private fun GlassCard(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(GLASS.copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}
