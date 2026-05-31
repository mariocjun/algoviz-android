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

private fun taskColor(id: Int): Color {
    // Distinct hue per task id — small workloads (≤8 tasks) stay legible.
    val hue = ((id - 1) * 360f / 6f) % 360f
    return Color.hsv(hue, 0.65f, 0.95f)
}

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
    Text("Gantt", style = MaterialTheme.typography.titleSmall)
    GanttChart(r)
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

@Composable
private fun GanttChart(r: SchedResult) {
    val cellDp: Dp = 36.dp
    val rowDp: Dp = 56.dp
    val axisDp: Dp = 22.dp
    val measurer = rememberTextMeasurer()
    val total = r.gantt.size.coerceAtLeast(1)
    val widthDp = cellDp * total
    val heightDp = rowDp + axisDp
    val onSurface = MaterialTheme.colorScheme.onSurface

    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Canvas(Modifier.width(widthDp).height(heightDp)) {
            val cw = size.width / total
            val rowH = size.height * rowDp.value / (rowDp.value + axisDp.value)
            for ((i, cell) in r.gantt.withIndex()) {
                val x = i * cw
                drawRect(
                    color = taskColor(cell.taskId),
                    topLeft = Offset(x, 0f),
                    size = Size(cw - 1f, rowH),
                )
                val label = "t${cell.taskId}"
                val style = TextStyle(color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                val m = measurer.measure(label, style)
                drawText(
                    measurer, label,
                    topLeft = Offset(x + cw / 2f - m.size.width / 2f, rowH / 2f - m.size.height / 2f),
                    style = style,
                )
            }
            // Time axis: a tick at every integer boundary (0..N), labelled below.
            val tickStyle = TextStyle(color = onSurface, fontSize = 10.sp)
            for (i in 0..total) {
                val x = i * cw
                drawLine(
                    color = onSurface,
                    start = Offset(x, rowH + 2f),
                    end = Offset(x, rowH + 8f),
                    strokeWidth = 1f,
                )
                val lbl = i.toString()
                val m = measurer.measure(lbl, tickStyle)
                drawText(
                    measurer, lbl,
                    topLeft = Offset(x - m.size.width / 2f, rowH + 9f),
                    style = tickStyle,
                )
            }
            drawRect(
                color = onSurface,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, rowH),
                style = Stroke(width = 1f),
            )
        }
    }
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
