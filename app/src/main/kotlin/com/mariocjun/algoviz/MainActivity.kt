// MainActivity — Profiler mini-app (rewritten to Compose, v0.6.2).
// All business logic (native calls, paste.rs upload, URL history, crash dump)
// is unchanged — only the UI layer migrated from programmatic LinearLayout to
// Compose Material 3, matching the visual language of the rest of the app.
//
// contentDescription values (btn_run, btn_hwcaps, btn_sensors, btn_cameras,
// btn_upload, btn_viz, field_filter) are preserved for UI automation.
package com.mariocjun.algoviz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Compose state (Activity-owned so business logic methods can update them
// without needing a Composable scope).
private val BG        = Color(0xFF161618)
private val PANEL     = Color(0xFF202023)
private val PANEL_HI  = Color(0xFF2A2A2E)
private val LINE      = Color(0xFF3A3A40)
private val TXT       = Color(0xFFF2F2F4)
private val TXT_DIM   = Color(0xFF9A9AA2)
private val ACCENT    = Color(0xFF4296FA)
private val CODE_FG   = Color(0xFFCFD0D4)

// Named benchmark chips: display name → filter string passed to nativeRunBenchmarks.
private val BENCHMARKS = listOf(
    "Padrão"     to "",
    "Stream"     to "stream",
    "Latência"   to "latency",
    "NEON FMA"   to "neon_fma",
    "SDOT int8"  to "dot_int8",
    "i8mm"       to "i8mm",
    "SVE2"       to "sve2",
    "PMU"        to "perf_counters",
    "Sustentado" to "sustained",
    "Sort"       to "sort",
)

class MainActivity : ComponentActivity() {

    companion object {
        init { System.loadLibrary("algoviz") }
        private const val PASTE_ENDPOINT  = "https://paste.rs/"
        private const val MAX_URL_HISTORY = 8
    }

    private external fun nativeSetCrashDir(internalDir: String)
    private external fun nativeRunBenchmarks(externalDir: String?, filter: String): String
    private external fun nativeEnumerateSensors(): String
    private external fun nativeEnumerateCameras(): String
    private external fun nativeHwcaps(): String

    // Compose state — mutated from business-logic methods (always on Main thread).
    private val outputText    = mutableStateOf("")
    private val isBusy        = mutableStateOf(false)
    private val uploadEnabled = mutableStateOf(false)
    private val urlHistory    = mutableStateListOf<String>()
    private var lastJson      = ""
    private var lastKind      = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nativeSetCrashDir(filesDir.absolutePath)
        loadUrlHistory()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BG, surface = PANEL,
                    primary = ACCENT, onPrimary = Color.White,
                    onBackground = TXT, onSurface = TXT,
                ),
            ) {
                Surface(color = BG) {
                    AutoCloseGuard {
                        Box(Modifier.safeDrawingPadding()) {
                            ProfilerScreen(
                                output        = outputText.value,
                                isBusy        = isBusy.value,
                                uploadEnabled = uploadEnabled.value,
                                urlHistory    = urlHistory,
                                onRun         = { filter -> triggerBenchmarks(filter) },
                                onHwCaps      = { triggerJob("hwcaps") },
                                onSensors     = { triggerJob("sensors") },
                                onCameras     = { triggerJob("cameras") },
                                onUpload      = { uploadLast() },
                                onViz         = { startActivity(Intent(this, VizActivity::class.java)) },
                            )
                        }
                    }
                }
            }
        }
        showInitialBanner()
        checkForPreviousCrash()
    }

    // -- Job dispatch --------------------------------------------------------

    private fun triggerBenchmarks(filter: String) {
        val label = if (filter.isEmpty()) "benchmarks" else "benchmarks[$filter]"
        setBusy(true, "Rodando $label …")
        lifecycleScope.launch(Dispatchers.IO) {
            val externalDir = getExternalFilesDir(null)?.absolutePath
            val raw = runCatching {
                nativeRunBenchmarks(externalDir, filter)
            }.getOrElse { t -> errorJson("triggerBenchmarks", "${t.javaClass.simpleName}: ${t.message}") }
            finishJob(if (filter.isEmpty()) "benchmarks" else "benchmarks-$filter", raw, externalDir)
        }
    }

    private fun triggerJob(kind: String) {
        setBusy(true, "Rodando $kind …")
        lifecycleScope.launch(Dispatchers.IO) {
            val externalDir = getExternalFilesDir(null)?.absolutePath
            val raw = runCatching {
                when (kind) {
                    "hwcaps"  -> nativeHwcaps()
                    "sensors" -> nativeEnumerateSensors()
                    "cameras" -> nativeEnumerateCameras()
                    else      -> errorJson("triggerJob", "unknown kind: $kind")
                }
            }.getOrElse { t -> errorJson("triggerJob", "${t.javaClass.simpleName}: ${t.message}") }
            finishJob(kind, raw, externalDir)
        }
    }

    private suspend fun finishJob(kind: String, raw: String, externalDir: String?) {
        val pretty = prettify(raw)
        if (externalDir != null) {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            runCatching { File(externalDir, "$kind-$ts.json").writeText(raw) }
        }
        withContext(Dispatchers.Main) {
            lastJson = raw
            lastKind = kind
            outputText.value = pretty
            uploadEnabled.value = true
            setBusy(false, null)
        }
    }

    private fun setBusy(busy: Boolean, msg: String?) {
        isBusy.value = busy
        if (msg != null) outputText.value = msg
    }

    // -- Pretty printer ------------------------------------------------------

    private fun prettify(raw: String): String {
        val trimmed = raw.trim()
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else                   -> raw
            }
        }.getOrElse {
            "$raw\n\n[prettify failed: ${(it as? JSONException)?.message ?: it.message}]"
        }
    }

    private fun errorJson(where: String, what: String): String =
        "{\"error\":true,\"where\":\"$where\",\"what\":\"${escapeJsonValue(what)}\"}"

    private fun escapeJsonValue(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
         .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    // -- Upload to paste.rs --------------------------------------------------

    private fun uploadLast() {
        if (lastJson.isEmpty()) { toast("Nada para enviar — rode um job primeiro."); return }
        val payload   = lastJson
        val labelKind = lastKind
        uploadEnabled.value = false
        outputText.value += "\n\n--- enviando para paste.rs … ---\n"
        lifecycleScope.launch(Dispatchers.IO) {
            val url = runCatching { httpPost(PASTE_ENDPOINT, payload) }
                .getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
            withContext(Dispatchers.Main) {
                if (url.startsWith("http")) {
                    recordUpload(labelKind, url)
                    outputText.value += "URL: $url\nKind: $labelKind\n(copiado para área de transferência; também no histórico acima)\n"
                    toast("Enviado — URL copiada")
                } else {
                    outputText.value += "$url\n"
                    toast("Envio falhou.")
                }
                uploadEnabled.value = true
            }
        }
    }

    private fun httpPost(endpoint: String, body: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true; doInput = true
            connectTimeout = 15_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("User-Agent", "algoviz/0.6.2")
        }
        try {
            conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray(Charsets.UTF_8)) }
            val code   = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text   = stream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            return if (code in 200..299) text else "ERROR $code: $text"
        } finally {
            conn.disconnect()
        }
    }

    // -- URL history persistence --------------------------------------------

    private fun recordUpload(kind: String, url: String) {
        val ts    = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$ts] $kind  $url"
        urlHistory.add(0, entry)
        while (urlHistory.size > MAX_URL_HISTORY) urlHistory.removeLast()
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("paste.rs upload", url))
        }
        runCatching {
            File(filesDir, "upload-log.txt")
                .appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}  $kind  $url\n")
        }
    }

    private fun loadUrlHistory() {
        val f = File(filesDir, "upload-log.txt")
        if (!f.exists()) return
        runCatching {
            val lines = f.readLines().asReversed().take(MAX_URL_HISTORY)
            for (line in lines) {
                val parts = line.split("  ", limit = 3)
                val short = if (parts.size == 3) {
                    val timeOnly = parts[0].substringAfter(' ')
                    "[$timeOnly] ${parts[1]}  ${parts[2]}"
                } else line
                urlHistory.add(short)
            }
        }
    }

    // -- Initial display & crash recovery -----------------------------------

    private fun showInitialBanner() {
        val externalDir = getExternalFilesDir(null)?.absolutePath ?: "(none)"
        outputText.value = buildString {
            appendLine("Início rápido:")
            appendLine("  1. Toque em 'HW caps' — mostra extensões ARM do kernel.")
            appendLine("  2. Toque em 'Run' (filtro vazio) para a suíte padrão (~15s).")
            appendLine("  3. Ou escolha um chip de benchmark acima para isolar um teste.")
            appendLine()
            appendLine("Após o job, toque em 'Enviar resultado' para compartilhar via paste.rs.")
            appendLine("A URL é copiada para a área de transferência e salva no histórico.")
            appendLine()
            append("Arquivos locais salvos em:\n  $externalDir")
        }
    }

    private fun checkForPreviousCrash() {
        val dump    = File(filesDir, "last-native-crash.json")
        if (!dump.exists()) return
        val content = runCatching { dump.readText() }.getOrElse { return }
        outputText.value += "\n\n*** CRASH NATIVO ANTERIOR DETECTADO ***\n" +
            prettify(content) +
            "\n\nToque em 'Enviar resultado' para compartilhar para análise.\n"
        lastJson = content
        lastKind = "native-crash"
        uploadEnabled.value = true
        dump.delete()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

// ---- Compose UI ------------------------------------------------------------

@Composable
private fun ProfilerScreen(
    output: String,
    isBusy: Boolean,
    uploadEnabled: Boolean,
    urlHistory: List<String>,
    onRun: (String) -> Unit,
    onHwCaps: () -> Unit,
    onSensors: () -> Unit,
    onCameras: () -> Unit,
    onUpload: () -> Unit,
    onViz: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var selectedBench by remember { mutableIntStateOf(0) }   // 0 = "Padrão" (empty filter)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(6.dp))

        // Header
        Text("Profiler", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = TXT)
        Text("benchmarks nativos · hardware do dispositivo",
            style = MaterialTheme.typography.bodySmall, color = TXT_DIM)

        // URL upload history
        if (urlHistory.isNotEmpty()) {
            UploadHistory(urlHistory)
        }

        // Benchmark chips — named selection replaces raw filter hint
        Text("Benchmark", fontSize = 12.sp, color = TXT_DIM)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BENCHMARKS.forEachIndexed { i, (label, value) ->
                FilterChip(
                    selected = i == selectedBench,
                    onClick = { selectedBench = i; filter = value },
                    label = { Text(label) },
                    modifier = Modifier.semantics { contentDescription = "bench_$value" },
                )
            }
        }

        // Manual filter field — populated by chip tap, also editable directly
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it; selectedBench = -1 },
            label = { Text("Filtro manual") },
            placeholder = { Text("vazio = suíte padrão; ex: stream, i8mm,sve2") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "field_filter" },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ACCENT, unfocusedBorderColor = LINE,
                focusedLabelColor = ACCENT, unfocusedLabelColor = TXT_DIM,
                focusedTextColor = TXT, unfocusedTextColor = TXT,
                cursorColor = ACCENT,
            ),
        )

        // Primary action — Run
        Button(
            onClick = { onRun(filter) },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "btn_run" },
        ) {
            if (isBusy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                    color = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Rodando…")
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Run", fontWeight = FontWeight.SemiBold)
            }
        }

        // Secondary info queries
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onHwCaps, enabled = !isBusy,
                modifier = Modifier.weight(1f).semantics { contentDescription = "btn_hwcaps" }) {
                Text("HW caps", fontSize = 13.sp)
            }
            OutlinedButton(onClick = onSensors, enabled = !isBusy,
                modifier = Modifier.weight(1f).semantics { contentDescription = "btn_sensors" }) {
                Text("Sensores", fontSize = 13.sp)
            }
            OutlinedButton(onClick = onCameras, enabled = !isBusy,
                modifier = Modifier.weight(1f).semantics { contentDescription = "btn_cameras" }) {
                Text("Câmeras", fontSize = 13.sp)
            }
        }

        // Navigate to sort visualizer
        OutlinedButton(
            onClick = onViz,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "btn_viz" },
        ) { Text("Visualizar sorts ▶") }

        // Upload
        OutlinedButton(
            onClick = onUpload,
            enabled = uploadEnabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "btn_upload" },
        ) { Text("Enviar resultado (paste.rs)") }

        // Output area — monospace, selectable, scrollable
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = Color(0xFF0D0D10),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp,
        ) {
            SelectionContainer(Modifier.fillMaxSize()) {
                Text(
                    text = output,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    color = CODE_FG,
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun UploadHistory(urlHistory: List<String>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(PANEL)
            .border(1.dp, LINE, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Uploads recentes", fontSize = 11.sp, color = TXT_DIM,
            fontFamily = FontFamily.Monospace)
        urlHistory.forEach { entry ->
            Text(entry, fontSize = 10.sp, color = CODE_FG.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
        }
    }
}
