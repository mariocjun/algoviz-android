// SchedActivity — v1 UI for the CPU/task-scheduler mini-app.
//
// Runs the Maziero reference workload through one of the 7 algorithms (hardcoded
// configs live in sched/sched_bridge.cpp) and renders the result: per-tick Gantt
// chart on a Compose Canvas, final-metrics summary, and a per-task timing table.
// No workload editor and no "predict the next task" mini-game yet — those are
// intentionally deferred until the brainstorm pass.
package com.mariocjun.algoviz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class SchedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) { SchedScreen() }
                }
            }
        }
    }
}

private data class TaskRow(
    val id: Int, val name: String,
    val arrival: Int, val duration: Int, val priority: Int,
    val start: Int, val finish: Int,
    val turnaround: Int, val waiting: Int, val response: Int,
)

private data class GanttCell(val time: Int, val taskId: Int)

private data class SchedResult(
    val algo: String, val quantum: Int, val totalTime: Int,
    val preemptions: Int, val contextSwitches: Int,
    val avgTurnaround: Float, val avgWaiting: Float, val avgResponse: Float,
    val tasks: List<TaskRow>, val gantt: List<GanttCell>,
)

private fun parseResult(json: String): SchedResult {
    val o = JSONObject(json)
    val tasksArr = o.getJSONArray("tasks")
    val tasks = ArrayList<TaskRow>(tasksArr.length())
    for (i in 0 until tasksArr.length()) {
        val t = tasksArr.getJSONObject(i)
        tasks.add(TaskRow(
            id = t.getInt("id"),
            name = t.getString("name"),
            arrival = t.getInt("arrival"),
            duration = t.getInt("duration"),
            priority = t.getInt("priority"),
            start = t.getInt("start"),
            finish = t.getInt("finish"),
            turnaround = t.getInt("turnaround"),
            waiting = t.getInt("waiting"),
            response = t.getInt("response"),
        ))
    }
    val ganttArr = o.getJSONArray("gantt")
    val gantt = ArrayList<GanttCell>(ganttArr.length())
    for (i in 0 until ganttArr.length()) {
        val g = ganttArr.getJSONObject(i)
        gantt.add(GanttCell(time = g.getInt("time"), taskId = g.getInt("task_id")))
    }
    return SchedResult(
        algo = o.getString("algo"),
        quantum = o.getInt("quantum"),
        totalTime = o.getInt("total_time"),
        preemptions = o.getInt("preemptions"),
        contextSwitches = o.getInt("context_switches"),
        avgTurnaround = o.getDouble("avg_turnaround").toFloat(),
        avgWaiting = o.getDouble("avg_waiting").toFloat(),
        avgResponse = o.getDouble("avg_response").toFloat(),
        tasks = tasks,
        gantt = gantt,
    )
}

// Fixed per-task palette sampled from Maziero's Cap. 6 figures (6.2/6.4/6.5),
// so the Gantt matches the textbook and the table swatches match the Gantt.
// Tasks beyond 5 extend with further distinct hues. See
// docs/maziero-scheduling-diagram.md.
private val MAZIERO_COLORS = listOf(
    Color(0xFF3169CF), // t1 blue
    Color(0xFFD8D818), // t2 yellow
    Color(0xFF9048C0), // t3 purple
    Color(0xFF48D830), // t4 green
    Color(0xFFDF313B), // t5 red
    Color(0xFFE08A1E), // t6 orange
    Color(0xFF1FB6B6), // t7 teal
    Color(0xFFD83C9B), // t8 magenta
)

private fun taskColor(id: Int): Color =
    MAZIERO_COLORS[((id - 1).coerceAtLeast(0)) % MAZIERO_COLORS.size]

private fun fmt(f: Float): String = String.format(Locale.US, "%.2f", f)

@Composable
private fun SchedScreen() {
    val algoNames = remember { runCatching { SchedBridge.nativeSchedListAlgos() }.getOrDefault(emptyArray()) }
    var algoIdx by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<SchedResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Auto-run the first algorithm on launch so the screen is never empty.
    LaunchedEffect(Unit) {
        running = true
        val r = withContext(Dispatchers.Default) {
            runCatching { parseResult(SchedBridge.nativeSchedRunMaziero(0)) }
        }
        running = false
        r.onSuccess { result = it; error = null }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Scheduler trainer",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "5-task Maziero workload (arr,dur,prio): " +
                "t1(0,5,2)  t2(0,2,3)  t3(1,4,1)  t4(3,1,4)  t5(5,2,5)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            algoNames.forEachIndexed { i, name ->
                FilterChip(
                    selected = i == algoIdx,
                    onClick = { algoIdx = i },
                    label = { Text(name) },
                )
            }
        }

        Button(
            onClick = {
                running = true
                error = null
                scope.launch {
                    val r = withContext(Dispatchers.Default) {
                        runCatching { parseResult(SchedBridge.nativeSchedRunMaziero(algoIdx)) }
                    }
                    running = false
                    r.onSuccess { result = it }
                        .onFailure { error = it.message ?: it.javaClass.simpleName }
                }
            },
            enabled = !running && algoNames.isNotEmpty(),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (running) "Running…" else "Run")
        }

        error?.let {
            Text("Error: $it", color = MaterialTheme.colorScheme.error)
        }

        result?.let { ResultBlock(it) }
    }
}

@Composable
private fun ResultBlock(r: SchedResult) {
    MetricsCard(r)
    Text("Diagrama de execução", style = MaterialTheme.typography.titleSmall)
    GanttChart(r)
    Text(
        "Preenchido = executando · vazio = esperando (fila de prontas) · " +
            "barra = chegada → término (largura = turnaround)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Tasks", style = MaterialTheme.typography.titleSmall)
    TaskTable(r.tasks)
}

@Composable
private fun MetricsCard(r: SchedResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${r.algo}  ·  quantum ${r.quantum}  ·  total ${r.totalTime}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Tt ${fmt(r.avgTurnaround)}   Tw ${fmt(r.avgWaiting)}   Tr ${fmt(r.avgResponse)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "context switches ${r.contextSwitches}   preemptions ${r.preemptions}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Maziero space-time diagram (Cap. 6, figs 6.2/6.4/6.5): one lane per task with
// t1 at the BOTTOM; each task is a single bar spanning [arrival, finish]; inside
// it, coloured = running on the CPU, hollow/white = ready-but-waiting. Drawn on a
// near-white panel (like the book page) so the hollow encoding reads clearly.
// See docs/maziero-scheduling-diagram.md.
@Composable
private fun GanttChart(r: SchedResult) {
    val measurer = rememberTextMeasurer()
    val total = r.totalTime.coerceAtLeast(1)
    val tasks = r.tasks.sortedBy { it.id }          // t1..tN, ascending
    val n = tasks.size.coerceAtLeast(1)

    // Running ticks per task id, reconstructed from the gantt log. Every tick in
    // [arrival, finish] that is NOT here is a waiting (hollow) tick.
    val runningByTask = remember(r) {
        val m = HashMap<Int, MutableSet<Int>>()
        for (g in r.gantt) m.getOrPut(g.taskId) { HashSet() }.add(g.time)
        m
    }

    val laneDp: Dp = 46.dp        // height of each task lane
    val leftDp: Dp = 36.dp        // room for the y axis + tN labels
    val axisDp: Dp = 28.dp        // room for x ticks + labels
    val topDp: Dp = 12.dp
    val trailDp: Dp = 22.dp       // room past the last tick for the x-axis arrow + 't'
    val minCellDp: Dp = 22.dp     // floor on tick width before we start scrolling

    val ink = Color(0xFF15161A)   // near-black axes / outlines
    val grid = Color(0xFFB9BDC6)  // light dotted gridlines

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FB)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            // Fill the card width when the timeline is short; fall back to a
            // legible minimum tick width + horizontal scroll when it is long.
            val cellDp = maxOf(minCellDp, (maxWidth - leftDp - trailDp) / total)
            val plotWidthDp = cellDp * total
            val widthDp = leftDp + plotWidthDp + trailDp
            val heightDp = topDp + laneDp * n + axisDp

            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Canvas(Modifier.width(widthDp).height(heightDp)) {
                val cw = plotWidthDp.toPx() / total
                val left = leftDp.toPx()
                val top = topDp.toPx()
                val laneH = laneDp.toPx()
                val barH = laneH * 0.58f
                val plotBottom = top + laneH * n
                val plotRight = left + cw * total

                // Dotted vertical gridlines + bottom-axis ticks/labels at 0..total.
                val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 5f))
                val tickStyle = TextStyle(color = ink, fontSize = 11.sp)
                for (t in 0..total) {
                    val x = left + t * cw
                    drawLine(grid, Offset(x, top), Offset(x, plotBottom),
                        strokeWidth = 1f, pathEffect = dash)
                    drawLine(ink, Offset(x, plotBottom), Offset(x, plotBottom + 5f), strokeWidth = 1.5f)
                    val lbl = t.toString()
                    val mm = measurer.measure(lbl, tickStyle)
                    drawText(measurer, lbl,
                        topLeft = Offset(x - mm.size.width / 2f, plotBottom + 7f), style = tickStyle)
                }

                // Axes as arrows: Y up, X right with a trailing 't'.
                drawLine(ink, Offset(left, plotBottom), Offset(left, top - 6f), strokeWidth = 2f)
                drawLine(ink, Offset(left, top - 6f), Offset(left - 4f, top + 2f), strokeWidth = 2f)
                drawLine(ink, Offset(left, top - 6f), Offset(left + 4f, top + 2f), strokeWidth = 2f)
                drawLine(ink, Offset(left, plotBottom), Offset(plotRight + 16f, plotBottom), strokeWidth = 2f)
                drawLine(ink, Offset(plotRight + 16f, plotBottom), Offset(plotRight + 8f, plotBottom - 4f), strokeWidth = 2f)
                drawLine(ink, Offset(plotRight + 16f, plotBottom), Offset(plotRight + 8f, plotBottom + 4f), strokeWidth = 2f)
                drawText(measurer, "t",
                    topLeft = Offset(plotRight + 18f, plotBottom - 9f),
                    style = TextStyle(color = ink, fontSize = 13.sp))

                // Lanes — t1 at the bottom (index 0 -> bottom-most lane).
                tasks.forEachIndexed { i, task ->
                    val laneTop = top + (n - 1 - i) * laneH
                    val barTop = laneTop + (laneH - barH) / 2f
                    val nameStyle = TextStyle(color = ink, fontSize = 12.sp)
                    val nm = measurer.measure(task.name, nameStyle)
                    drawText(measurer, task.name,
                        topLeft = Offset(left - nm.size.width - 8f, barTop + barH / 2f - nm.size.height / 2f),
                        style = nameStyle)

                    if (task.finish <= task.arrival) return@forEachIndexed
                    val running = runningByTask[task.id] ?: emptySet()
                    val col = taskColor(task.id)

                    // Each contiguous run of executing ticks = one coloured, bordered
                    // segment; the gaps between them stay hollow (= card background).
                    var t = task.arrival
                    while (t < task.finish) {
                        if (t in running) {
                            var e = t
                            while (e < task.finish && e in running) e++
                            val x = left + t * cw
                            val w = (e - t) * cw
                            drawRect(col, Offset(x, barTop), Size(w, barH))
                            drawRect(ink, Offset(x, barTop), Size(w, barH), style = Stroke(width = 1.4f))
                            t = e
                        } else {
                            t++
                        }
                    }
                    // Outline the whole [arrival, finish] bar so the hollow waiting
                    // region is clearly bounded.
                    drawRect(ink,
                        Offset(left + task.arrival * cw, barTop),
                        Size((task.finish - task.arrival) * cw, barH),
                        style = Stroke(width = 1.6f))
                }
            }     // Canvas
        }         // Row
        }         // BoxWithConstraints
    }             // Card
}

@Composable
private fun TaskTable(tasks: List<TaskRow>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HeaderRow()
            for (t in tasks) TaskBodyRow(t)
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Cell("id", weight = 0.7f, header = true)
        Cell("arr", weight = 0.7f, header = true)
        Cell("dur", weight = 0.7f, header = true)
        Cell("prio", weight = 0.7f, header = true)
        Cell("start", weight = 0.9f, header = true)
        Cell("end", weight = 0.9f, header = true)
        Cell("Tt", weight = 0.7f, header = true)
        Cell("Tw", weight = 0.7f, header = true)
        Cell("Tr", weight = 0.7f, header = true)
    }
}

@Composable
private fun TaskBodyRow(t: TaskRow) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.weight(0.7f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(10.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(color = taskColor(t.id), size = size)
                }
            }
            Text(t.name, style = MaterialTheme.typography.bodySmall)
        }
        Cell("${t.arrival}", 0.7f)
        Cell("${t.duration}", 0.7f)
        Cell("${t.priority}", 0.7f)
        Cell("${t.start}", 0.9f)
        Cell("${t.finish}", 0.9f)
        Cell("${t.turnaround}", 0.7f)
        Cell("${t.waiting}", 0.7f)
        Cell("${t.response}", 0.7f)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String, weight: Float, header: Boolean = false,
) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(weight),
    )
}
