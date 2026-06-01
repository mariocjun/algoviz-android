// SchedActivity — the CPU/task-scheduler trainer (v1, pre-game).
//
// Native-Compose replica of the owner's ImGui scheduler app
// (mariocjun/simulador_sistema_operacional_maziero): a per-task-lane Gantt where
// coloured blocks are CPU execution, with arrival (▶) / termination (■) markers
// and a live playhead; transport steps the simulation forward/back, runs to
// complete, and resets; ready tasks "light up". The whole simulation is computed
// once by SchedBridge (the validated sched/sim.h engine) and the playhead scrubs
// that precomputed history — stepping never touches the engine. Dark, app-native.
//
// Layout adapts per orientation: portrait stacks (chart is the tall hero);
// landscape gives the chart the full width and parks the controls in a side rail.
package com.mariocjun.algoviz

import android.content.res.Configuration
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

// Pedagogical chip order: FCFS first (simplest), then RR, SJF, SRTF, PRIOc, PRIOp, PRIOd.
// The native engine returns them as [FCFS=0, SJF=1, RR=2, SRTF=3, PRIOc=4, PRIOp=5, PRIOd=6].
private val PEDAGOGICAL_ORDER = intArrayOf(0, 2, 1, 3, 4, 5, 6)

// ---- Palette (sampled from the owner's ImGui "modern style" + the book) -------

private val INK_BG = Color(0xFF161618)
private val INK_PANEL = Color(0xFF202023)
private val INK_PANEL_HI = Color(0xFF2A2A2E)
private val INK_LINE = Color(0xFF3A3A40)
private val INK_TEXT = Color(0xFFF2F2F4)
private val INK_TEXT_DIM = Color(0xFF9A9AA2)
private val ACCENT = Color(0xFF4296FA)
private val ARRIVAL_GREEN = Color(0xFF3ECF6E)
private val FINISH_RED = Color(0xFFF2554B)

private val TASK_COLORS = listOf(
    Color(0xFF3E7BFA), Color(0xFFE4C23B), Color(0xFF9B5DE5),
    Color(0xFF3DCF7A), Color(0xFFF2554B), Color(0xFFE08A2E),
    Color(0xFF2BB6C4), Color(0xFFE05299),
)
private fun taskColor(id: Int): Color = TASK_COLORS[((id - 1).coerceAtLeast(0)) % TASK_COLORS.size]

private val ALGO_ACCENT = listOf(
    Color(0xFF3E7BFA), Color(0xFF36B9B9), Color(0xFF9B5DE5), Color(0xFF35C46B),
    Color(0xFFE0902E), Color(0xFFE8B62E), Color(0xFFE05A50),
)
private fun algoAccent(idx: Int): Color = ALGO_ACCENT.getOrElse(idx) { ACCENT }

class SchedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = INK_BG, surface = INK_PANEL,
                    primary = ACCENT, onPrimary = Color.White,
                    onBackground = INK_TEXT, onSurface = INK_TEXT,
                ),
            ) {
                Surface(color = INK_BG) {
                    AutoCloseGuard { Box(Modifier.safeDrawingPadding()) { SchedScreen() } }
                }
            }
        }
    }
}

// ---- Model --------------------------------------------------------------------

private data class TaskRow(
    val id: Int, val name: String,
    val arrival: Int, val duration: Int, val priority: Int,
    val start: Int, val finish: Int,
    val turnaround: Int, val waiting: Int, val response: Int,
)

private data class SchedResult(
    val algo: String, val quantum: Int, val totalTime: Int,
    val preemptions: Int, val contextSwitches: Int,
    val avgTurnaround: Float, val avgWaiting: Float, val avgResponse: Float,
    val tasks: List<TaskRow>,
    /** tick -> task id running on CPU0 (−1 = idle). Indexed [0, totalTime). */
    val runAt: IntArray,
)

private fun parseResult(json: String): SchedResult {
    val o = JSONObject(json)
    val tasksArr = o.getJSONArray("tasks")
    val tasks = ArrayList<TaskRow>(tasksArr.length())
    for (i in 0 until tasksArr.length()) {
        val t = tasksArr.getJSONObject(i)
        tasks.add(TaskRow(
            id = t.getInt("id"), name = t.getString("name"),
            arrival = t.getInt("arrival"), duration = t.getInt("duration"),
            priority = t.getInt("priority"), start = t.getInt("start"),
            finish = t.getInt("finish"), turnaround = t.getInt("turnaround"),
            waiting = t.getInt("waiting"), response = t.getInt("response"),
        ))
    }
    val total = o.getInt("total_time")
    val runAt = IntArray(total) { -1 }
    val ganttArr = o.getJSONArray("gantt")
    for (i in 0 until ganttArr.length()) {
        val g = ganttArr.getJSONObject(i)
        val time = g.getInt("time")
        if (time in 0 until total) runAt[time] = g.getInt("task_id")
    }
    return SchedResult(
        algo = o.getString("algo"), quantum = o.getInt("quantum"), totalTime = total,
        preemptions = o.getInt("preemptions"), contextSwitches = o.getInt("context_switches"),
        avgTurnaround = o.getDouble("avg_turnaround").toFloat(),
        avgWaiting = o.getDouble("avg_waiting").toFloat(),
        avgResponse = o.getDouble("avg_response").toFloat(),
        tasks = tasks, runAt = runAt,
    )
}

private enum class TaskPhase { NEW, READY, RUNNING, DONE }

private fun phaseAt(task: TaskRow, runningId: Int, t: Int): TaskPhase = when {
    task.finish <= t -> TaskPhase.DONE
    task.arrival > t -> TaskPhase.NEW
    task.id == runningId -> TaskPhase.RUNNING
    else -> TaskPhase.READY
}

private fun runningAt(r: SchedResult, t: Int): Int = if (t in 0 until r.totalTime) r.runAt[t] else -1

/** Next task that will take the CPU after the current one (a context switch), or null. */
private fun nextDispatch(r: SchedResult, t: Int): Int? {
    val cur = runningAt(r, t)
    var k = t + 1
    while (k < r.totalTime) {
        val id = r.runAt[k]
        if (id != -1 && id != cur) return id
        k++
    }
    return null
}

private fun fmt(f: Float): String = String.format(Locale.US, "%.2f", f)

/** Session-only "challenge onboarding shown" flag — resets when the process
 *  dies, so the intro sheet shows once per execution (mirrors ExtremeTutorial). */
object ChallengeIntro { var dismissed = false }

// ---- Screen -------------------------------------------------------------------

@Composable
private fun SchedScreen() {
    val algoNames = remember { runCatching { SchedBridge.nativeSchedListAlgos() }.getOrDefault(emptyArray()) }
    var algoIdx by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<SchedResult?>(null) }
    var currentT by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var hintShown by remember { mutableStateOf(false) }   // auto-show heuristic on first manual algo switch
    // ---- Challenge Mode state --------------------------------------------------
    var challengeMode by remember { mutableStateOf(false) }
    var challengePendingId by remember { mutableStateOf<Int?>(null) }
    var challengePickedId by remember { mutableStateOf<Int?>(null) }
    var challengeScore by remember { mutableIntStateOf(0) }
    var challengeTotal by remember { mutableIntStateOf(0) }
    var showChallengeIntro by remember { mutableStateOf(false) }   // first-run onboarding sheet
    // ---------------------------------------------------------------------------
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    suspend fun load(idx: Int) {
        val r = withContext(Dispatchers.Default) {
            runCatching { parseResult(SchedBridge.nativeSchedRunMaziero(idx)) }
        }
        r.onSuccess {
            result = it; currentT = 0; playing = false; error = null
            // reset pending challenge state on algo switch (score persists)
            challengePendingId = null; challengePickedId = null
        }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    LaunchedEffect(Unit) { load(0) }

    // Run-to-complete plays the playhead forward tick-by-tick.
    // In challenge mode, pauses before any context switch and waits for the user's pick.
    LaunchedEffect(playing, result) {
        if (!playing) return@LaunchedEffect
        val r2 = result ?: return@LaunchedEffect
        val total = r2.totalTime
        while (playing && currentT < total) {
            val nextT = currentT + 1
            if (challengeMode && nextT < total) {
                val curRunning = runningAt(r2, currentT)
                val nextRunning = runningAt(r2, nextT)
                if (nextRunning != -1 && nextRunning != curRunning) {
                    // Pause and wait for the user to pick
                    playing = false
                    challengePendingId = nextRunning
                    return@LaunchedEffect
                }
            }
            delay(150)
            currentT++
        }
        playing = false
    }

    // "Pop" the newest execution cell + completion flourish, both driven by currentT.
    val cellPop = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(currentT, result) {
        val total = result?.totalTime ?: 0
        cellPop.snapTo(0f); cellPop.animateTo(1f, tween(150))
        if (total > 0 && currentT >= total) { flash.snapTo(1f); flash.animateTo(0f, tween(650)) }
    }

    val accent by animateColorAsState(algoAccent(algoIdx), tween(350), label = "accent")
    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    val r = result
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error?.let { "Erro: $it" } ?: "…", color = if (error != null) FINISH_RED else INK_TEXT_DIM)
        }
        return
    }

    val onBack: () -> Unit = { playing = false; if (currentT > 0) { currentT--; haptic() } }
    val onFwd: () -> Unit = {
        if (challengePendingId == null) {
            playing = false; if (currentT < r.totalTime) { currentT++; haptic() }
        }
    }
    val onRun: () -> Unit = {
        if (challengePendingId == null) {
            if (currentT >= r.totalTime) currentT = 0; playing = !playing
        }
    }
    val onReset: () -> Unit = { playing = false; currentT = 0 }

    val onChallengePick: (Int) -> Unit = { taskId ->
        if (challengePendingId != null && challengePickedId == null) {
            // Capture before async work — guards against algo switch or mode toggle during delay.
            val expectedPendingId = challengePendingId
            challengeTotal++
            val correct = taskId == expectedPendingId
            if (correct) challengeScore++
            challengePickedId = taskId
            haptic()
            scope.launch {
                // 800ms on a hit gives the eye time to read the ✓ + green before
                // advancing; 1100ms on a miss to read both the ✗ and the revealed answer.
                delay(if (correct) 800L else 1100L)
                // Bail if challenge was externally reset (algo switch, mode disabled) during delay.
                if (challengePendingId != expectedPendingId) return@launch
                challengePendingId = null
                challengePickedId = null
                currentT++
                haptic()
                if (currentT < r.totalTime) playing = true
            }
        }
    }

    // Toggle challenge mode; on FIRST enable per session, open the onboarding
    // sheet (resolves the no-onboarding / unclear-affordance findings MD-1..3).
    val onToggleChallenge: () -> Unit = {
        challengeMode = !challengeMode
        if (challengeMode) {
            if (!ChallengeIntro.dismissed) showChallengeIntro = true
        } else {
            challengePendingId = null
            challengePickedId = null
        }
    }

    val chart: @Composable (Modifier) -> Unit = { m ->
        GanttBoard(r, currentT, cellPop.value, flash.value, { showInfo = true }, m)
    }
    val controls: @Composable (Boolean) -> Unit = { rail ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlgoChips(
                algoNames, algoIdx, accent,
                Modifier.weight(1f),
            ) { i ->
                if (!hintShown) { showInfo = true; hintShown = true }
                algoIdx = i; scope.launch { load(i) }
            }
            ChallengeChip(enabled = challengeMode, onClick = onToggleChallenge)
        }
        Spacer(Modifier.height(10.dp))
        StatusStrip(
            r, currentT, accent,
            hideNext = challengePendingId != null,
            challengeScore = if (challengeMode) challengeScore else -1,
            challengeTotal = challengeTotal,
        )
        Spacer(Modifier.height(10.dp))
        if (challengePendingId != null) {
            ChallengePrompt(algoIdx = algoIdx, algoName = r.algo, nextTick = currentT + 1)
            Spacer(Modifier.height(8.dp))
        }
        TaskPills(
            r = r,
            currentT = currentT,
            challengePendingId = challengePendingId,
            challengePickedId = challengePickedId,
            onChallengePick = if (challengePendingId != null) onChallengePick else null,
        )
        Spacer(Modifier.height(if (rail) 14.dp else 12.dp))
        Transport(accent, currentT > 0, currentT < r.totalTime, playing, onBack, onFwd, onRun, onReset, stacked = rail)
    }

    if (landscape) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp, top = 10.dp, bottom = 12.dp, end = 12.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { chart(Modifier.fillMaxSize()) }
            Spacer(Modifier.width(14.dp))
            Column(
                Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            ) {
                Text("Escalonador", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, color = INK_TEXT)
                Spacer(Modifier.height(10.dp))
                GanttLegend()
                Spacer(Modifier.height(6.dp))
                controls(true)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("Escalonador", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold, color = INK_TEXT)
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AlgoChips(
                    algoNames, algoIdx, accent,
                    Modifier.weight(1f),
                ) { i ->
                    if (!hintShown) { showInfo = true; hintShown = true }
                    algoIdx = i; scope.launch { load(i) }
                }
                ChallengeChip(enabled = challengeMode, onClick = onToggleChallenge)
            }
            Spacer(Modifier.height(10.dp))
            StatusStrip(
                r, currentT, accent,
                hideNext = challengePendingId != null,
                challengeScore = if (challengeMode) challengeScore else -1,
                challengeTotal = challengeTotal,
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) { chart(Modifier.fillMaxSize()) }
            GanttLegend()
            Spacer(Modifier.height(6.dp))
            if (challengePendingId != null) {
                ChallengePrompt(algoIdx = algoIdx, algoName = r.algo, nextTick = currentT + 1)
                Spacer(Modifier.height(8.dp))
            }
            TaskPills(
                r = r,
                currentT = currentT,
                challengePendingId = challengePendingId,
                challengePickedId = challengePickedId,
                onChallengePick = if (challengePendingId != null) onChallengePick else null,
            )
            Spacer(Modifier.height(12.dp))
            Transport(accent, currentT > 0, currentT < r.totalTime, playing, onBack, onFwd, onRun, onReset)
            Spacer(Modifier.height(10.dp))
        }
    }

    if (showInfo) HeuristicDialog(algoIdx) { showInfo = false }
    if (showChallengeIntro) {
        ChallengeIntroSheet { ChallengeIntro.dismissed = true; showChallengeIntro = false }
    }
}

@Composable
private fun AlgoChips(
    names: Array<String>,
    selected: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    val order = if (names.size >= PEDAGOGICAL_ORDER.size) PEDAGOGICAL_ORDER.toList()
                else names.indices.toList()
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        order.forEach { origIdx ->
            if (origIdx >= names.size) return@forEach
            val name = names[origIdx]
            val on = origIdx == selected
            val bg by animateColorAsState(if (on) accent else INK_PANEL, tween(250), label = "chipbg")
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onPick(origIdx) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(name, color = if (on) Color.White else INK_TEXT_DIM,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StatusStrip(
    r: SchedResult, t: Int, accent: Color,
    hideNext: Boolean = false,          // during a pending challenge, don't reveal the answer
    challengeScore: Int = -1,           // >=0 → show a (non-interactive) score badge
    challengeTotal: Int = 0,
) {
    val finished = t >= r.totalTime
    val runId = runningAt(r, t)
    val runName = r.tasks.firstOrNull { it.id == runId }?.name
    val nextName = nextDispatch(r, t)?.let { id -> r.tasks.firstOrNull { it.id == id }?.name }
    val metricScale by animateFloatAsState(if (finished) 1f else 0.96f, spring(stiffness = Spring.StiffnessLow), label = "metric")
    Surface(color = INK_PANEL, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${r.algo} · quantum ${r.quantum}", color = INK_TEXT_DIM,
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(2.dp))
                if (finished) {
                    Text("Concluído · Retorno ${fmt(r.avgTurnaround)}  Espera ${fmt(r.avgWaiting)}  Resp. ${fmt(r.avgResponse)}",
                        color = lerp(INK_TEXT, accent, (metricScale - 0.96f) / 0.04f * 0.5f),
                        fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
                    Text("(médias: Tt=retorno · Tw=espera · Tr=resposta)",
                        color = INK_TEXT_DIM.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall)
                } else {
                    Text(if (runName != null) "Executando $runName" else "CPU ociosa",
                        color = if (runName != null) accent else INK_TEXT_DIM,
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    if (nextName != null) {
                        // Never spell out the answer while the student is being asked it.
                        Text(if (hideNext) "Próxima: 🎯 adivinhe!" else "Próxima: $nextName",
                            color = INK_TEXT_DIM, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            // Non-interactive score badge (placar) — separated from the toggle chip so
            // checking the score can never accidentally turn the mode off.
            if (challengeScore >= 0 && challengeTotal > 0) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(ACCENT.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text("🎯 $challengeScore de $challengeTotal", color = accent,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(10.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$t", color = INK_TEXT, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall)
                Text(" / ${r.totalTime}", color = INK_TEXT_DIM, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ---- The Gantt board (the hero) ----------------------------------------------

@Composable
private fun GanttBoard(r: SchedResult, currentT: Int, pop: Float, flash: Float, onInfo: () -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val tasks = remember(r) { r.tasks.sortedBy { it.id } }
    val n = tasks.size.coerceAtLeast(1)

    val animT by animateFloatAsState(
        currentT.toFloat(), spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "playhead",
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow",
    )

    Surface(color = INK_PANEL, shape = RoundedCornerShape(20.dp), modifier = modifier) {
      Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val total = r.totalTime.coerceAtLeast(1)
            val labelW = 26.dp.toPx()
            val axisH = 18.dp.toPx()
            val topPad = 6.dp.toPx()
            val trail = 10.dp.toPx()
            val plotW = size.width - labelW - trail
            val plotH = size.height - axisH - topPad
            val cw = plotW / total
            val laneH = plotH / n
            val barH = laneH * 0.66f                      // data-dense: taller bars
            val left = labelW
            val plotBottom = topPad + plotH

            fun x(t: Float) = left + t * cw
            fun laneTop(i: Int) = topPad + (n - 1 - i) * laneH

            // 1) time grid (stronger every 5) + bottom labels
            val tickStyle = TextStyle(color = INK_TEXT_DIM, fontSize = 10.sp)
            for (tk in 0..total) {
                val gx = x(tk.toFloat())
                drawLine(if (tk % 5 == 0) INK_LINE else INK_LINE.copy(alpha = 0.4f),
                    Offset(gx, topPad), Offset(gx, plotBottom), strokeWidth = 1f)
                if (tk % 5 == 0 || tk == total) {
                    val m = measurer.measure(tk.toString(), tickStyle)
                    drawText(textLayoutResult = m,
                        topLeft = Offset(gx - m.size.width / 2f, plotBottom + 3f))
                }
            }

            // 2) lanes
            tasks.forEachIndexed { i, task ->
                val top = laneTop(i)
                val barTop = top + (laneH - barH) / 2f
                val col = taskColor(task.id)
                val phase = phaseAt(task, runningAt(r, currentT), currentT)

                val labelCol = when (phase) {
                    TaskPhase.READY -> lerp(INK_TEXT_DIM, col, pulse)
                    TaskPhase.RUNNING -> col
                    else -> INK_TEXT_DIM.copy(alpha = 0.55f)
                }
                val nameStyle = TextStyle(color = labelCol, fontSize = 11.sp,
                    fontWeight = if (phase == TaskPhase.RUNNING || phase == TaskPhase.READY) FontWeight.Bold else FontWeight.Normal)
                val nm = measurer.measure(task.name, nameStyle)
                drawText(textLayoutResult = nm,
                    topLeft = Offset(left - nm.size.width - 6f, barTop + barH / 2f - nm.size.height / 2f))

                // ghost bar: subtle arrival→finish track (the "waiting" hollow), READY pulses
                if (task.finish > task.arrival) {
                    val a = if (phase == TaskPhase.READY) 0.06f + 0.16f * pulse else 0.045f
                    drawRoundRect(col.copy(alpha = a),
                        topLeft = Offset(x(task.arrival.toFloat()), barTop),
                        size = Size((task.finish - task.arrival) * cw, barH), cornerRadius = cr(6f))
                }

                // executed cells [0, currentT)
                var tk = task.arrival
                while (tk < minOf(currentT, task.finish)) {
                    if (runningAt(r, tk) == task.id) {
                        val s = if (tk == currentT - 1) pop else 1f       // pop the newest cell
                        drawCell(x(tk.toFloat()), barTop, cw, barH, col, glow = 0f, scale = s)
                    }
                    tk++
                }
                // the cell at the playhead (executing now) glows
                if (currentT < total && runningAt(r, currentT) == task.id) {
                    drawCell(x(currentT.toFloat()), barTop, cw, barH, col, glow = pulse, scale = 1f)
                }

                // ▶ arrival
                run {
                    val ax = x(task.arrival.toFloat()); val cy = barTop + barH / 2f; val s = 5.dp.toPx()
                    val p = Path().apply { moveTo(ax - 2f, cy - s); lineTo(ax - 2f, cy + s); lineTo(ax - 2f + s, cy); close() }
                    drawPath(p, ARRIVAL_GREEN.copy(alpha = if (currentT >= task.arrival) 1f else 0.45f))
                }
                // ■ termination (once the playhead reaches it)
                if (task.finish in 1..currentT) {
                    val fx = x(task.finish.toFloat()); val s = 4.dp.toPx()
                    drawRect(FINISH_RED, topLeft = Offset(fx - s, barTop + barH - s), size = Size(2 * s, 2 * s))
                }
            }

            // 3) playhead — gradient glow + line + head
            val px = x(animT)
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to ACCENT.copy(alpha = 0.16f),
                    startX = px - 14f, endX = px),
                topLeft = Offset(px - 14f, topPad), size = Size(14f, plotH))
            drawLine(ACCENT, Offset(px, topPad - 2f), Offset(px, plotBottom), strokeWidth = 2f)
            val hp = Path().apply { moveTo(px - 5f, topPad - 2f); lineTo(px + 5f, topPad - 2f); lineTo(px, topPad + 6f); close() }
            drawPath(hp, ACCENT)

            // 4) completion flourish — a brief whole-board flash
            if (flash > 0f) {
                drawRoundRect(Color.White.copy(alpha = 0.10f * flash),
                    topLeft = Offset(0f, 0f), size = size, cornerRadius = cr(12f))
            }
        }
        // ℹ in the empty top-right corner → opens the didactic heuristic card
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp)
                .clip(RoundedCornerShape(50)).background(INK_PANEL_HI).clickable { onInfo() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Info, contentDescription = "Como funciona", tint = INK_TEXT_DIM, modifier = Modifier.size(18.dp)) }
      }
    }
}

@Composable
private fun GanttLegend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▶ chegada", color = ARRIVAL_GREEN, style = MaterialTheme.typography.labelSmall)
        Text("■ término", color = FINISH_RED, style = MaterialTheme.typography.labelSmall)
        Text("■ executando", color = INK_TEXT_DIM, style = MaterialTheme.typography.labelSmall)
        Text("□ esperando", color = INK_TEXT_DIM.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall)
    }
}

private fun cr(r: Float) = CornerRadius(r, r)

/** One execution cell: filled rounded rect + top highlight + optional glow ring + entrance scale. */
private fun DrawScope.drawCell(x: Float, top: Float, cw: Float, h: Float, col: Color, glow: Float, scale: Float) {
    val w = cw - 1.5f
    val sh = h * scale
    val sw = w * scale
    val ox = x + (w - sw) / 2f
    val oy = top + (h - sh) / 2f
    val r = cr(4f)
    if (glow > 0f) {
        drawRoundRect(col.copy(alpha = 0.35f * glow),
            topLeft = Offset(ox - 3f, oy - 3f), size = Size(sw + 6f, sh + 6f), cornerRadius = cr(6f))
    }
    drawRoundRect(col.copy(alpha = scale), topLeft = Offset(ox, oy), size = Size(sw, sh), cornerRadius = r, style = Fill)
    drawRoundRect(Color.White.copy(alpha = 0.18f * scale),
        topLeft = Offset(ox, oy), size = Size(sw, sh * 0.42f), cornerRadius = r)
    drawRoundRect(Color.White.copy(alpha = 0.22f * scale),
        topLeft = Offset(ox, oy), size = Size(sw, sh), cornerRadius = r, style = Stroke(width = 1f))
}

// ---- Task pills ("ready tasks light up") --------------------------------------

@Composable
private fun TaskPills(
    r: SchedResult,
    currentT: Int,
    challengePendingId: Int? = null,
    challengePickedId: Int? = null,
    onChallengePick: ((Int) -> Unit)? = null,
) {
    val pulse by rememberInfiniteTransition(label = "pillpulse").animateFloat(
        0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pill",
    )
    val runId = runningAt(r, currentT)
    val challengeActive = onChallengePick != null
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (task in r.tasks.sortedBy { it.id }) {
            val phase = phaseAt(task, runId, currentT)
            val col = taskColor(task.id)

            // When a challenge is pending, evaluate pickability at the NEXT tick so tasks
            // that arrive exactly at currentT+1 are valid candidates (they appear as NEW at
            // currentT but the algo can legally pick them — sim processes arrivals and dispatch
            // in the same tick). Use runId=-1 so no task is artificially marked "running".
            val phaseForChallenge = if (challengeActive) phaseAt(task, -1, currentT + 1) else phase
            val isPickable = challengeActive && challengePickedId == null &&
                phaseForChallenge == TaskPhase.READY

            // Determine border and background considering challenge state
            val (bg, fg, ring) = when {
                // Challenge answer feedback: picked correct
                challengeActive && challengePickedId == task.id && task.id == challengePendingId ->
                    Triple(col.copy(alpha = 0.18f), ARRIVAL_GREEN, ARRIVAL_GREEN)
                // Challenge answer feedback: picked wrong (the wrong one gets red)
                challengeActive && challengePickedId == task.id && task.id != challengePendingId ->
                    Triple(FINISH_RED.copy(alpha = 0.14f), FINISH_RED, FINISH_RED)
                // Challenge answer feedback: reveal correct answer when user was wrong
                challengeActive && challengePickedId != null && task.id == challengePendingId ->
                    Triple(col.copy(alpha = 0.18f), ARRIVAL_GREEN, ARRIVAL_GREEN)
                // Challenge mode active (waiting for pick): candidate tasks get ACCENT border
                isPickable ->
                    Triple(col.copy(alpha = 0.12f + 0.20f * pulse), col, ACCENT)
                // Normal phase rendering
                else -> when (phase) {
                    TaskPhase.RUNNING -> Triple(col, Color.White, col)
                    TaskPhase.READY -> Triple(col.copy(alpha = 0.12f + 0.20f * pulse), col, col.copy(alpha = pulse))
                    TaskPhase.DONE -> Triple(INK_PANEL, INK_TEXT_DIM.copy(alpha = 0.7f), Color.Transparent)
                    TaskPhase.NEW -> Triple(INK_PANEL, INK_TEXT_DIM.copy(alpha = 0.45f), Color.Transparent)
                }
            }

            val borderWidth = when {
                challengeActive && (challengePickedId != null) &&
                    (task.id == challengePickedId || task.id == challengePendingId) -> 2.dp
                isPickable -> 2.dp
                ring != Color.Transparent -> 1.5.dp
                else -> 0.dp
            }

            // MD-4: while waiting for an answer, dim the non-candidate pills so the
            // clickable ones stand out (which to click is then unambiguous).
            val dim = challengeActive && challengePickedId == null && !isPickable
            // MD-5: pair the green/red feedback with a ✓/✗ glyph so it survives
            // colour-blindness (not colour-only).
            val answered = challengeActive && challengePickedId != null
            val suffix = when {
                answered && task.id == challengePickedId && task.id == challengePendingId -> "  ✓"
                answered && task.id == challengePickedId && task.id != challengePendingId -> "  ✗"
                answered && task.id == challengePendingId -> "  ✓"   // reveal correct after a wrong pick
                phase == TaskPhase.DONE -> " ✓"
                else -> ""
            }

            Box(
                Modifier
                    .alpha(if (dim) 0.4f else 1f)
                    // ≥48dp tap target (HIG 44pt / Material 48dp / CLAUDE.md) — the pills
                    // are the primary challenge target, so they must clear the minimum.
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .then(
                        if (ring != Color.Transparent && borderWidth > 0.dp)
                            Modifier.border(borderWidth, ring, RoundedCornerShape(12.dp))
                        else Modifier
                    )
                    .then(if (isPickable) Modifier.clickable { onChallengePick!!(task.id) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    task.name + suffix,
                    color = fg,
                    fontWeight = if (phase == TaskPhase.RUNNING || isPickable) FontWeight.Bold else FontWeight.Medium,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ---- Challenge chip & prompt --------------------------------------------------

@Composable
private fun ChallengeChip(enabled: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (enabled) ACCENT else INK_PANEL_HI, tween(250), label = "challengechipbg",
    )
    // Pure toggle — the score lives in the StatusStrip badge, so tapping here can
    // only ever turn the mode on/off (never misread as "check my score").
    val label = if (enabled) "🎯 Desafio ✓" else "🎯 Desafio"
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(
                if (!enabled) Modifier.border(1.5.dp, ACCENT.copy(alpha = 0.55f), RoundedCornerShape(50))
                else Modifier.border(1.5.dp, ACCENT, RoundedCornerShape(50))
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (enabled) Color.White else INK_TEXT_DIM,
            fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ChallengePrompt(algoIdx: Int, algoName: String, nextTick: Int) {
    // Recap the algorithm's rule at the moment of decision (MD-9) so the student
    // doesn't have to have opened the ℹ card first.
    val rule = heuristicFor(algoIdx).caption
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(INK_PANEL)
            .border(1.dp, ACCENT.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Column {
            Text(
                "Tick $nextTick · $algoName — $rule",
                color = INK_TEXT_DIM,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Qual tarefa a CPU escolhe agora?",
                color = INK_TEXT,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            // Explicit gesture signifier (MD-2): kills the tap-vs-drag ambiguity.
            Text(
                "👆 Toque na tarefa destacada que você acha que entra",
                color = ACCENT,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** First-run onboarding for Challenge Mode — opens once per session the first
 *  time the 🎯 chip is enabled. Mirrors the Min Cash Flow TutorialSheet pattern
 *  so the app is internally consistent (MD-1, MD-3). Tells the student WHAT
 *  changes and WHICH gesture to use before the first prompt ever fires. */
@Composable
private fun ChallengeIntroSheet(onDone: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.74f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { /* scrim swallows taps; closing is explicit via the button */ },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 420.dp).padding(20.dp)
                .clip(RoundedCornerShape(24.dp)).background(INK_PANEL)
                .border(1.dp, ACCENT.copy(alpha = 0.30f), RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Text("🎯 Modo Desafio", color = INK_TEXT, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            // The "why" — sells the value (testing effect), not just the rules.
            Text("Prever antes de ver fixa o algoritmo melhor do que só assistir.",
                color = INK_TEXT_DIM, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            IntroStep("1", "A simulação pausa antes de cada troca de tarefa.")
            IntroStep("2", "Toque na tarefa destacada que você acha que a CPU vai escolher.")
            IntroStep("3", "Acertou fica verde ✓ · errou fica vermelha ✗ e eu revelo a certa.")
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                    .background(ACCENT).clickable { onDone() }
                    .semantics { contentDescription = "Começar desafio" },
                contentAlignment = Alignment.Center,
            ) { Text("Começar ▶", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun IntroStep(n: String, text: String) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(ACCENT.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Text(n, color = ACCENT, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.width(10.dp))
        Text(text, color = INK_TEXT.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
    }
}

// ---- Transport ----------------------------------------------------------------

@Composable
private fun Transport(
    accent: Color, canBack: Boolean, canFwd: Boolean, playing: Boolean,
    onBack: () -> Unit, onFwd: () -> Unit, onRun: () -> Unit, onReset: () -> Unit,
    stacked: Boolean = false,
) {
    val playIcon = if (playing) Icons.Filled.Pause else Icons.Filled.FastForward
    val playLabel = if (playing) "Pausar" else "Executar"
    if (stacked) {
        // Narrow side-rail (landscape): primary on its own row so the label never wraps.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(playIcon, playLabel, accent, onRun, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(Icons.Filled.SkipPrevious, "Voltar passo", canBack, onBack, Modifier.weight(1f))
                GhostButton(Icons.Filled.SkipNext, "Avançar passo", canFwd, onFwd, Modifier.weight(1f))
                GhostButton(Icons.Filled.Refresh, "Reiniciar", true, onReset, Modifier.weight(1f))
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GhostButton(Icons.Filled.SkipPrevious, "Voltar passo", canBack, onBack, Modifier.weight(1f))
            PrimaryButton(playIcon, playLabel, accent, onRun, Modifier.weight(1.6f))
            GhostButton(Icons.Filled.SkipNext, "Avançar passo", canFwd, onFwd, Modifier.weight(1f))
            GhostButton(Icons.Filled.Refresh, "Reiniciar", true, onReset, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PrimaryButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).background(accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = label, tint = Color.White)
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun GhostButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String,
    enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.32f
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).background(INK_PANEL_HI.copy(alpha = alpha))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = INK_TEXT.copy(alpha = alpha)) }
}

// ---- Didactic heuristic explainer (the ℹ card) --------------------------------

private enum class AnimMode { ARRIVAL, SIZE, PRIORITY, RR, AGING }

private data class Heuristic(
    val title: String, val pick: String, val body: String, val tradeoff: String,
    val mode: AnimMode, val caption: String,
)

// Didactic, in Maziero's teaching spirit (faithful to Cap. 6; paraphrased).
private fun heuristicFor(idx: Int): Heuristic = when (idx) {
    1 -> Heuristic("Como o SJF decide", "Entre as prontas, pega a de menor duração.",
        "Cooperativo: a escolhida roda até o fim. É o que dá o menor tempo médio de espera.",
        "Exige estimar a duração antes; tarefas longas podem ficar pra trás (inanição).",
        AnimMode.SIZE, "menor duração primeiro")
    2 -> Heuristic("Como o RR decide", "Pega a próxima da fila e roda por um quantum.",
        "Revezamento com preempção por tempo: se não termina no quantum, volta pro fim da fila e dá a vez a outra.",
        "Ótima resposta (interativo); quantum menor = mais resposta, porém mais trocas de contexto.",
        AnimMode.RR, "revezamento por quantum")
    3 -> Heuristic("Como o SRTF decide", "Pega a de menor tempo restante.",
        "SJF preemptivo: se chega uma tarefa mais curta que o que falta da atual, ela toma o processador na hora.",
        "Os melhores tempos médios de todos; em troca, mais preempções.",
        AnimMode.SIZE, "menor tempo restante")
    4 -> Heuristic("Como o PRIOc decide", "Entre as prontas, pega a de maior prioridade.",
        "Escala positiva: número maior = mais prioritária. Cooperativo — roda até o fim.",
        "Prioridade alta passa na frente; baixa prioridade espera.",
        AnimMode.PRIORITY, "maior prioridade")
    5 -> Heuristic("Como o PRIOp decide", "Maior prioridade — mas preemptivo.",
        "Como o PRIOc, porém se chega alguém mais prioritário que a atual, toma o processador na hora.",
        "Resposta rápida pros urgentes; baixa prioridade pode passar fome.",
        AnimMode.PRIORITY, "maior prioridade (preempta)")
    6 -> Heuristic("Como o PRIOd decide", "Maior prioridade dinâmica, com envelhecimento.",
        "Prioridade preemptiva, mas quem espera ganha prioridade aos poucos (envelhece, +α por turno) pra não morrer de fome; ao rodar, rejuvenesce pra prioridade base.",
        "Mais justo: divide proporcional à prioridade base, sem inanição.",
        AnimMode.AGING, "prioridade + envelhecimento (+α)")
    else -> Heuristic("Como o FCFS decide", "Pega a que chegou primeiro.",
        "É a fila do banco: o primeiro a chegar é o primeiro servido, e roda até terminar — cooperativo, sem interrupção.",
        "Justo na ordem; mas uma tarefa longa na frente faz todas esperarem (efeito comboio).",
        AnimMode.ARRIVAL, "ordem de chegada")
}

@Composable
private fun HeuristicDialog(algoIdx: Int, onDismiss: () -> Unit) {
    val h = heuristicFor(algoIdx)
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = INK_PANEL, shape = RoundedCornerShape(24.dp)) {
            Column(
                Modifier.padding(20.dp).widthIn(max = 460.dp)
                    .heightIn(max = 580.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(h.title, color = INK_TEXT, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                HeuristicAnim(h)
                Spacer(Modifier.height(8.dp))
                Text(h.caption, color = ACCENT, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(16.dp))
                InfoLabel("Escolhe")
                Text(h.pick, color = INK_TEXT, fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Text(h.body, color = INK_TEXT_DIM, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                InfoLabel("Trade-off")
                Text(h.tradeoff, color = INK_TEXT_DIM, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                        .background(ACCENT).clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { Text("Entendi", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun InfoLabel(t: String) {
    Text(t.uppercase(), color = ACCENT, fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(3.dp))
}

/** A small looping diagram: the ready queue, the chosen task highlighted by the
 *  algorithm's rule, and an arrow into the CPU. Purely illustrative. */
@Composable
private fun HeuristicAnim(h: Heuristic) {
    val measurer = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "heur").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p",
    )
    val sizes = floatArrayOf(0.62f, 0.38f, 0.95f)   // durations (SIZE mode)
    val prios = intArrayOf(2, 5, 3)                  // priorities (PRIORITY/AGING)
    val chosen = when (h.mode) {
        AnimMode.SIZE -> 1                            // shortest
        AnimMode.PRIORITY, AnimMode.AGING -> 1        // highest (5)
        else -> 0                                     // first in line
    }
    Surface(color = INK_BG, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(12.dp)) {
            val w = size.width; val hgt = size.height
            val slot = w / 3f
            val boxW = slot * 0.6f
            val maxBoxH = hgt * 0.42f
            val qBottom = hgt - 20f
            val cpuW = slot * 0.6f; val cpuH = maxBoxH * 0.6f
            val cpuX = w / 2f - cpuW / 2f; val cpuY = 0f
            fun boxH(i: Int) = if (h.mode == AnimMode.SIZE) maxBoxH * sizes[i] else maxBoxH * 0.7f

            // CPU slot
            drawRoundRect(INK_PANEL_HI, Offset(cpuX, cpuY), Size(cpuW, cpuH), cr(8f))
            drawRoundRect(ACCENT.copy(alpha = 0.25f + 0.55f * pulse), Offset(cpuX, cpuY),
                Size(cpuW, cpuH), cr(8f), style = Stroke(2f))
            val cpuStyle = TextStyle(color = INK_TEXT_DIM, fontSize = 11.sp)
            val cpuLbl = measurer.measure("CPU", cpuStyle)
            drawText(textLayoutResult = cpuLbl,
                topLeft = Offset(w / 2f - cpuLbl.size.width / 2f, cpuY + cpuH / 2f - cpuLbl.size.height / 2f))

            // candidate boxes (the ready queue)
            for (i in 0 until 3) {
                val bh = boxH(i)
                val bx = i * slot + (slot - boxW) / 2f
                val by = qBottom - bh
                val isCh = i == chosen
                drawRoundRect(if (isCh) ACCENT.copy(alpha = 0.85f) else INK_PANEL_HI,
                    Offset(bx, by), Size(boxW, bh), cr(6f))
                if (isCh) drawRoundRect(ACCENT.copy(alpha = 0.4f + 0.6f * pulse),
                    Offset(bx - 2f, by - 2f), Size(boxW + 4f, bh + 4f), cr(8f), style = Stroke(2.5f))
                when (h.mode) {
                    AnimMode.PRIORITY, AnimMode.AGING -> {
                        val s = TextStyle(color = if (isCh) Color.White else INK_TEXT,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        val m = measurer.measure(prios[i].toString(), s)
                        drawText(textLayoutResult = m,
                            topLeft = Offset(bx + boxW / 2f - m.size.width / 2f, by + bh / 2f - m.size.height / 2f))
                        if (h.mode == AnimMode.AGING && !isCh) {
                            val a = TextStyle(color = ARRIVAL_GREEN.copy(alpha = 0.35f + 0.6f * pulse), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            val am = measurer.measure("↑+α", a)
                            drawText(textLayoutResult = am, topLeft = Offset(bx + boxW / 2f - 13f, by - 17f))
                        }
                    }
                    AnimMode.ARRIVAL -> {
                        val s = TextStyle(color = INK_TEXT_DIM, fontSize = 10.sp)
                        val sm = measurer.measure("${i + 1}º", s)
                        drawText(textLayoutResult = sm, topLeft = Offset(bx + boxW / 2f - 7f, qBottom + 3f))
                    }
                    else -> {}
                }
            }

            // arrow: chosen box → CPU (pulsing)
            val chX = chosen * slot + slot / 2f
            val aCol = ACCENT.copy(alpha = 0.5f + 0.5f * pulse)
            drawLine(aCol, Offset(chX, qBottom - boxH(chosen)), Offset(w / 2f, cpuY + cpuH + 5f), strokeWidth = 2.5f)
            val ah = Path().apply {
                moveTo(w / 2f, cpuY + cpuH); lineTo(w / 2f - 5f, cpuY + cpuH + 10f); lineTo(w / 2f + 5f, cpuY + cpuH + 10f); close()
            }
            drawPath(ah, aCol)

            // RR only: a faint "↻ volta" return hint to the back of the queue
            if (h.mode == AnimMode.RR) {
                val rCol = INK_TEXT_DIM.copy(alpha = 0.35f + 0.4f * pulse)
                drawLine(rCol, Offset(cpuX + cpuW, cpuY + cpuH / 2f),
                    Offset(2 * slot + slot / 2f, qBottom - boxH(2)), strokeWidth = 1.6f)
                val rs = TextStyle(color = rCol, fontSize = 10.sp)
                val rl = measurer.measure("↻ volta", rs)
                drawText(textLayoutResult = rl, topLeft = Offset(w - rl.size.width, cpuY))
            }
        }
    }
}
