// SchedActivity — the CPU/task-scheduler trainer (v1, pre-game).
//
// This is a native-Compose replica of the owner's ImGui scheduler app
// (mariocjun/simulador_sistema_operacional_maziero): a per-task-lane Gantt where
// coloured blocks are CPU execution, with arrival (▶) and termination (■) event
// markers and a live red playhead; transport controls step the simulation
// forward/back, run-to-complete, and reset; ready tasks "light up". The whole
// simulation is computed once by SchedBridge (the validated sched/sim.h engine),
// and the playhead just scrubs that precomputed history — so stepping never
// touches the engine. Dark, app-native; NOT a textbook panel.
//
// Conventions (semantics from Maziero Cap. 6, look from the owner's app):
//   - lanes stacked t1 at the BOTTOM → tN at the top;
//   - a coloured cell at time t = that task held the CPU during tick [t, t+1);
//   - ▶ green = arrival instant, ■ red = termination instant;
//   - a task READY (arrived, not running, not finished) at the playhead glows.
package com.mariocjun.algoviz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

// ---- Palette (sampled from the owner's ImGui "modern style" + the book) -------

private val INK_BG = Color(0xFF161618)        // app background
private val INK_PANEL = Color(0xFF202023)     // raised panel
private val INK_PANEL_HI = Color(0xFF2A2A2E)  // hovered/active panel
private val INK_LINE = Color(0xFF3A3A40)       // hairlines
private val INK_TEXT = Color(0xFFF2F2F4)
private val INK_TEXT_DIM = Color(0xFF9A9AA2)
private val ACCENT = Color(0xFF4296FA)         // his accent_blue
private val ARRIVAL_GREEN = Color(0xFF3ECF6E)
private val FINISH_RED = Color(0xFFF2554B)

// Per-task colours (book/owner palette): t1 blue … t5 red, then extensions.
private val TASK_COLORS = listOf(
    Color(0xFF3E7BFA), Color(0xFFE4C23B), Color(0xFF9B5DE5),
    Color(0xFF3DCf7A), Color(0xFFF2554B), Color(0xFFE08A2E),
    Color(0xFF2BB6C4), Color(0xFFE05299),
)
private fun taskColor(id: Int): Color = TASK_COLORS[((id - 1).coerceAtLeast(0)) % TASK_COLORS.size]

// Per-algorithm accent (his renderFrame system_color, brightened for dark UI).
private val ALGO_ACCENT = listOf(
    Color(0xFF3E7BFA), // FCFS
    Color(0xFF36B9B9), // SJF
    Color(0xFF9B5DE5), // RR
    Color(0xFF35C46B), // SRTF
    Color(0xFFE0902E), // PRIOc
    Color(0xFFE8B62E), // PRIOp
    Color(0xFFE05A50), // PRIOd
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
                    Box(Modifier.safeDrawingPadding()) { SchedScreen() }
                }
            }
        }
    }
}

// ---- Model (parsed from the SchedBridge JSON) ---------------------------------

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

/** State of a task at playhead tick [t] (t in 0..total). */
private fun phaseAt(task: TaskRow, runningId: Int, t: Int): TaskPhase = when {
    task.finish <= t -> TaskPhase.DONE
    task.arrival > t -> TaskPhase.NEW
    task.id == runningId -> TaskPhase.RUNNING
    else -> TaskPhase.READY
}

private fun fmt(f: Float): String = String.format(Locale.US, "%.2f", f)

// ---- Screen -------------------------------------------------------------------

@Composable
private fun SchedScreen() {
    val algoNames = remember { runCatching { SchedBridge.nativeSchedListAlgos() }.getOrDefault(emptyArray()) }
    var algoIdx by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<SchedResult?>(null) }
    var currentT by remember { mutableIntStateOf(0) }   // playhead tick, 0..total
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(idx: Int) {
        val r = withContext(Dispatchers.Default) {
            runCatching { parseResult(SchedBridge.nativeSchedRunMaziero(idx)) }
        }
        r.onSuccess { result = it; currentT = 0; playing = false; error = null }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    LaunchedEffect(Unit) { load(0) }

    // Run-to-complete: advance the playhead tick-by-tick for a "playing" feel.
    LaunchedEffect(playing, result) {
        if (!playing) return@LaunchedEffect
        val total = result?.totalTime ?: 0
        while (playing && currentT < total) { delay(160); currentT++ }
        playing = false
    }

    val accent by animateColorAsState(algoAccent(algoIdx), tween(350), label = "accent")

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Escalonador", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, color = INK_TEXT)
        Spacer(Modifier.height(10.dp))

        AlgoChips(algoNames, algoIdx, accent) { i ->
            algoIdx = i
            scope.launch { load(i) }
        }
        Spacer(Modifier.height(10.dp))

        val r = result
        if (error != null) {
            Text("Erro: $error", color = FINISH_RED, style = MaterialTheme.typography.bodyMedium)
        } else if (r != null) {
            StatusStrip(r, currentT, accent)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                GanttBoard(r, currentT)
            }
            Spacer(Modifier.height(10.dp))
            TaskPills(r, currentT)
            Spacer(Modifier.height(12.dp))
            Transport(
                accent = accent,
                canBack = currentT > 0,
                canFwd = currentT < r.totalTime,
                playing = playing,
                onBack = { playing = false; if (currentT > 0) currentT-- },
                onFwd = { playing = false; if (currentT < r.totalTime) currentT++ },
                onRun = { if (currentT >= r.totalTime) currentT = 0; playing = !playing },
                onReset = { playing = false; currentT = 0 },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun AlgoChips(names: Array<String>, selected: Int, accent: Color, onPick: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEachIndexed { i, name ->
            val on = i == selected
            val bg by animateColorAsState(if (on) accent else INK_PANEL, tween(250), label = "chipbg")
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onPick(i) }
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
private fun StatusStrip(r: SchedResult, t: Int, accent: Color) {
    val finished = t >= r.totalTime
    val runningId = if (t < r.totalTime) r.runAt.getOrElse(t) { -1 } else -1
    val runningName = r.tasks.firstOrNull { it.id == runningId }?.name
    Surface(color = INK_PANEL, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${r.algo} · quantum ${r.quantum}", color = INK_TEXT_DIM,
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(2.dp))
                if (finished) {
                    Text("Concluído · Tt ${fmt(r.avgTurnaround)}  Tw ${fmt(r.avgWaiting)}  Tr ${fmt(r.avgResponse)}",
                        color = INK_TEXT, fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(
                        if (runningName != null) "Executando $runningName" else "CPU ociosa",
                        color = if (runningName != null) accent else INK_TEXT_DIM,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            // Big clock: t / total
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$t", color = INK_TEXT, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall)
                Text(" / ${r.totalTime}", color = INK_TEXT_DIM,
                    style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ---- The Gantt board (the hero) ----------------------------------------------

@Composable
private fun GanttBoard(r: SchedResult, currentT: Int) {
    val measurer = rememberTextMeasurer()
    val tasks = remember(r) { r.tasks.sortedBy { it.id } }
    val n = tasks.size.coerceAtLeast(1)

    // Smoothly-animated playhead position + a gentle pulse for "ready" glow.
    val animT by animateFloatAsState(
        currentT.toFloat(),
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "playhead",
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow",
    )

    Surface(color = INK_PANEL, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            val total = r.totalTime.coerceAtLeast(1)
            val labelW = 30.dp.toPx()
            val axisH = 20.dp.toPx()
            val topPad = 6.dp.toPx()
            val trail = 10.dp.toPx()
            val plotW = size.width - labelW - trail
            val plotH = size.height - axisH - topPad
            val cw = plotW / total
            val laneH = plotH / n
            val barH = laneH * 0.52f
            val left = labelW
            val plotBottom = topPad + plotH

            fun x(t: Float) = left + t * cw
            fun laneTop(i: Int) = topPad + (n - 1 - i) * laneH      // t1 (i=0) at bottom
            fun runningAt(t: Int) = if (t in 0 until total) r.runAt[t] else -1

            // 1) time grid — stronger line every 5 ticks, labels at 0/5/10/last
            val tickStyle = TextStyle(color = INK_TEXT_DIM, fontSize = 10.sp)
            for (tk in 0..total) {
                val gx = x(tk.toFloat())
                val strong = tk % 5 == 0
                drawLine(
                    if (strong) INK_LINE else INK_LINE.copy(alpha = 0.45f),
                    Offset(gx, topPad), Offset(gx, plotBottom), strokeWidth = 1f,
                )
                if (strong || tk == total) {
                    val lbl = tk.toString()
                    val m = measurer.measure(lbl, tickStyle)
                    drawText(measurer, lbl,
                        topLeft = Offset(gx - m.size.width / 2f, plotBottom + 4f), style = tickStyle)
                }
            }

            // 2) lanes
            tasks.forEachIndexed { i, task ->
                val top = laneTop(i)
                val barTop = top + (laneH - barH) / 2f
                val col = taskColor(task.id)
                val phase = phaseAt(task, runningAt(currentT), currentT)

                // lane label (glows when the task is READY at the playhead)
                val labelCol = when (phase) {
                    TaskPhase.READY -> lerp(INK_TEXT_DIM, col, pulse)
                    TaskPhase.RUNNING -> col
                    TaskPhase.DONE -> INK_TEXT_DIM.copy(alpha = 0.6f)
                    TaskPhase.NEW -> INK_TEXT_DIM.copy(alpha = 0.5f)
                }
                val nameStyle = TextStyle(color = labelCol, fontSize = 12.sp,
                    fontWeight = if (phase == TaskPhase.RUNNING || phase == TaskPhase.READY) FontWeight.Bold else FontWeight.Normal)
                val nm = measurer.measure(task.name, nameStyle)
                drawText(measurer, task.name,
                    topLeft = Offset(left - nm.size.width - 8f, barTop + barH / 2f - nm.size.height / 2f),
                    style = nameStyle)

                // faint "lifetime" track from arrival→finish; READY lanes light up
                if (task.finish > task.arrival) {
                    val trackAlpha = if (phase == TaskPhase.READY) 0.10f + 0.22f * pulse else 0.07f
                    drawRoundRect(
                        color = col.copy(alpha = trackAlpha),
                        topLeft = Offset(x(task.arrival.toFloat()), barTop),
                        size = Size((task.finish - task.arrival) * cw, barH),
                        cornerRadius = radius(6f),
                    )
                }

                // executed cells [0, currentT): solid colour, soft top highlight
                var tk = task.arrival
                while (tk < minOf(currentT, task.finish)) {
                    if (runningAt(tk) == task.id) {
                        drawCell(x(tk.toFloat()), barTop, cw, barH, col, glow = 0f)
                    }
                    tk++
                }
                // the cell at the playhead (currently executing) glows
                if (currentT < total && runningAt(currentT) == task.id) {
                    drawCell(x(currentT.toFloat()), barTop, cw, barH, col, glow = pulse)
                }

                // ▶ arrival marker (green) at the left edge of the lane
                run {
                    val ax = x(task.arrival.toFloat())
                    val cy = barTop + barH / 2f
                    val s = 5.dp.toPx()
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(ax - 2f, cy - s); lineTo(ax - 2f, cy + s); lineTo(ax - 2f + s, cy); close()
                    }
                    drawPath(p, ARRIVAL_GREEN.copy(alpha = if (currentT >= task.arrival) 1f else 0.45f))
                }
                // ■ termination marker (red) once the playhead passes the finish
                if (task.finish in 1..currentT) {
                    val fx = x(task.finish.toFloat())
                    val s = 4.dp.toPx()
                    drawRect(FINISH_RED, topLeft = Offset(fx - s, barTop + barH - s), size = Size(2 * s, 2 * s))
                }
            }

            // 3) playhead — glowing vertical line + head, springs between ticks
            val px = x(animT)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent, 1f to ACCENT.copy(alpha = 0.16f),
                    startX = px - 14f, endX = px,
                ),
                topLeft = Offset(px - 14f, topPad), size = Size(14f, plotH),
            )
            drawLine(ACCENT, Offset(px, topPad - 2f), Offset(px, plotBottom), strokeWidth = 2f)
            val hp = androidx.compose.ui.graphics.Path().apply {
                moveTo(px - 5f, topPad - 2f); lineTo(px + 5f, topPad - 2f); lineTo(px, topPad + 6f); close()
            }
            drawPath(hp, ACCENT)
        }
    }
}

private fun DrawScope.radius(dp: Float) =
    androidx.compose.ui.geometry.CornerRadius(dp, dp)

/** One execution cell: filled rounded rect + top highlight + optional glow ring. */
private fun DrawScope.drawCell(x: Float, top: Float, cw: Float, h: Float, col: Color, glow: Float) {
    val w = cw - 1.5f
    val r = radius(4f)
    if (glow > 0f) {
        drawRoundRect(col.copy(alpha = 0.35f * glow),
            topLeft = Offset(x - 3f, top - 3f), size = Size(w + 6f, h + 6f), cornerRadius = radius(6f))
    }
    drawRoundRect(col, topLeft = Offset(x, top), size = Size(w, h), cornerRadius = r, style = Fill)
    // glossy top highlight
    drawRoundRect(Color.White.copy(alpha = 0.18f),
        topLeft = Offset(x, top), size = Size(w, h * 0.42f), cornerRadius = r)
    drawRoundRect(Color.White.copy(alpha = 0.22f),
        topLeft = Offset(x, top), size = Size(w, h), cornerRadius = r, style = Stroke(width = 1f))
}

// ---- Task pills (the "ready tasks light up" surface) --------------------------

@Composable
private fun TaskPills(r: SchedResult, currentT: Int) {
    val pulse by rememberInfiniteTransition(label = "pillpulse").animateFloat(
        0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pill",
    )
    val runningId = if (currentT < r.totalTime) r.runAt.getOrElse(currentT) { -1 } else -1
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (task in r.tasks.sortedBy { it.id }) {
            val phase = phaseAt(task, runningId, currentT)
            val col = taskColor(task.id)
            val (bg, fg, ring) = when (phase) {
                TaskPhase.RUNNING -> Triple(col, Color.White, col)
                TaskPhase.READY -> Triple(col.copy(alpha = 0.12f + 0.20f * pulse), col, col.copy(alpha = pulse))
                TaskPhase.DONE -> Triple(INK_PANEL, INK_TEXT_DIM.copy(alpha = 0.7f), Color.Transparent)
                TaskPhase.NEW -> Triple(INK_PANEL, INK_TEXT_DIM.copy(alpha = 0.45f), Color.Transparent)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .then(
                        if (ring != Color.Transparent)
                            Modifier.border(1.5.dp, ring, RoundedCornerShape(12.dp)) else Modifier,
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    task.name + if (phase == TaskPhase.DONE) " ✓" else "",
                    color = fg,
                    fontWeight = if (phase == TaskPhase.RUNNING) FontWeight.Bold else FontWeight.Medium,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ---- Transport ----------------------------------------------------------------

@Composable
private fun Transport(
    accent: Color, canBack: Boolean, canFwd: Boolean, playing: Boolean,
    onBack: () -> Unit, onFwd: () -> Unit, onRun: () -> Unit, onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostButton(Icons.Filled.SkipPrevious, "Voltar passo", enabled = canBack, onClick = onBack,
            modifier = Modifier.weight(1f))
        // primary action: run / pause (run-to-complete animation)
        PrimaryButton(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.FastForward,
            label = if (playing) "Pausar" else "Executar",
            accent = accent, onClick = onRun, modifier = Modifier.weight(1.6f),
        )
        GhostButton(Icons.Filled.SkipNext, "Avançar passo", enabled = canFwd, onClick = onFwd,
            modifier = Modifier.weight(1f))
        GhostButton(Icons.Filled.Refresh, "Reiniciar", enabled = true, onClick = onReset,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PrimaryButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent)
            .clickable(onClick = onClick),
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
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(INK_PANEL_HI.copy(alpha = alpha))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = INK_TEXT.copy(alpha = alpha))
    }
}
