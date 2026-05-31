// HomeActivity — the MAIN/LAUNCHER. Lists the mini-apps that ship in this
// build as tappable tiles; each one routes to an existing dedicated Activity
// via explicit Intent. Compose + Material 3 dark scheme, edge-to-edge to match
// the rest of the modern surfaces (VizActivity, SchedActivity).
package com.mariocjun.algoviz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Tile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val target: Class<*>,
    val testId: String,        // content-desc locator for UI automation
)

// Single source of truth for the launcher list. Adding a new mini-app =
// register its Activity in the manifest + append one entry here.
private val TILES = listOf(
    Tile(
        title = "Sort visualizer",
        subtitle = "8 sort algorithms, race mode, ASMR audio",
        icon = Icons.AutoMirrored.Filled.Sort,
        target = VizActivity::class.java,
        testId = "tile_sort",
    ),
    Tile(
        title = "Scheduler trainer",
        subtitle = "CPU/task scheduler — Maziero reference",
        icon = Icons.Filled.Schedule,
        target = SchedActivity::class.java,
        testId = "tile_sched",
    ),
    Tile(
        title = "Racha",
        subtitle = "divide a conta, simplifica as dívidas",
        icon = Icons.Filled.Groups,
        target = SplitwiseActivity::class.java,
        testId = "tile_racha",
    ),
    Tile(
        title = "Profiler",
        subtitle = "CPU benchmarks, sensors, HW caps",
        icon = Icons.Filled.Speed,
        target = MainActivity::class.java,
        testId = "tile_profiler",
    ),
)

class HomeActivity : ComponentActivity() {
    companion object {
        // HomeActivity is the launcher now; loading the native lib here makes
        // JNI_OnLoad fire on app start (the smoke test asserts that log line,
        // and the old launcher — MainActivity — is no longer exported).
        init { System.loadLibrary("algoviz") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AutoCloseGuard { Box(Modifier.safeDrawingPadding()) { HomeScreen() } }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "algoviz",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Mini-apps",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        for (t in TILES) {
            TileCard(t) {
                ctx.startActivity(Intent(ctx, t.target))
            }
        }
    }
}

@Composable
private fun TileCard(t: Tile, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = t.testId },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                t.icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    t.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
