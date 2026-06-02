// SchedCards.kt — the Scheduler's algorithm picker as a vertical carousel of
// "rare holographic card" surfaces (the owner's brief: Pokémon-card feel). Swipe
// like Reels to flip algorithms; the centered card tilts toward your finger with a
// foil sheen (graphicsLayer 3D rotation + a moving highlight); neighbors recede in
// depth. A transparent name rail on the left jumps straight to any algorithm.
//
// Pure presentation: it reads the Dusk palette + the shared heuristicFor() captions
// and reports the chosen algoIdx via onPick. The phase machine (SchedActivity)
// owns what happens next (the lightning + explanation + game — B2/B3).
package com.mariocjun.algoviz

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MAX_TILT = 12f          // degrees the centered card leans toward the finger
private fun <T> tiltSpring() = spring<T>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

/**
 * The full picker. [order] is the pedagogical card order (each entry an algoIdx);
 * [names] are the engine labels indexed by algoIdx. [onPick] fires with the chosen
 * algoIdx when the centered card is tapped.
 */
@Composable
fun AlgoCarousel(
    names: Array<String>,
    order: IntArray = PEDAGOGICAL_ORDER,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = order.filter { it < names.size }
    if (pages.isEmpty()) return
    val pager = rememberPagerState(pageCount = { pages.size })
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // A soft tick each time a new card settles under the eye (Reels-like).
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Row(modifier.fillMaxSize()) {
        QuickNavRail(
            names = names, pages = pages, current = pager.currentPage,
            modifier = Modifier.fillMaxHeight().width(58.dp),
        ) { page -> scope.launch { pager.animateScrollToPage(page) } }

        VerticalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            // Peek the neighbours so the deck reads as a 3D stack, not full-screen pages.
            contentPadding = PaddingValues(vertical = 72.dp),
            pageSpacing = 8.dp,
        ) { page ->
            val algoIdx = pages[page]
            // Distance of this page from the settled centre, in pages (0 = centred).
            val dist = (pager.currentPage - page) + pager.currentPageOffsetFraction
            AlgoCard(
                algoIdx = algoIdx,
                label = names[algoIdx],
                pageOffset = dist,
                interactive = abs(dist) < 0.5f,
                onPlay = { onPick(algoIdx) },
            )
        }
    }
}

@Composable
private fun QuickNavRail(
    names: Array<String>,
    pages: List<Int>,
    current: Int,
    modifier: Modifier = Modifier,
    onJump: (Int) -> Unit,
) {
    Column(
        modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        pages.forEachIndexed { page, algoIdx ->
            val on = page == current
            Text(
                names[algoIdx],
                color = if (on) Dusk.algoAccent(algoIdx) else Dusk.TextDim.copy(alpha = 0.45f),
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (on) 14.sp else 12.sp,
                modifier = Modifier
                    .padding(vertical = 7.dp, horizontal = 4.dp)
                    .semantics { contentDescription = "nav_${names[algoIdx]}" }
                    .pointerInput(page) { detectTapGestures { onJump(page) } },
            )
        }
    }
}

@Composable
private fun AlgoCard(
    algoIdx: Int,
    label: String,
    pageOffset: Float,
    interactive: Boolean,
    onPlay: () -> Unit,
) {
    val accent = Dusk.algoAccent(algoIdx)
    val h = heuristicFor(algoIdx)
    val scope = rememberCoroutineScope()

    // Live tilt (set directly on touch for responsiveness) + a moving sheen centre.
    var rotX by remember { mutableFloatStateOf(0f) }
    var rotY by remember { mutableFloatStateOf(0f) }
    var sheen by remember { mutableStateOf(Offset(0.5f, 0.35f)) }
    var pressed by remember { mutableStateOf(false) }

    // Depth: cards away from the centre shrink + fade so the deck floats.
    val depth = (1f - abs(pageOffset) * 0.18f).coerceIn(0.8f, 1f)
    val fade = (1f - abs(pageOffset) * 0.55f).coerceIn(0.35f, 1f)

    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .graphicsLayer {
                scaleX = depth; scaleY = depth; alpha = fade
                rotationX = rotX; rotationY = rotY
                cameraDistance = 14f * density
            }
            .clip(RoundedCornerShape(24.dp))
            .background(Dusk.Surface.copy(alpha = 0.92f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            // Holographic foil + finger-tracking sheen, drawn over the glass base.
            .drawWithContent {
                // Diagonal foil in the algorithm's colour, nudged by the tilt.
                drawRect(
                    Brush.linearGradient(
                        0f to accent.copy(alpha = 0.16f),
                        0.5f to Color.Transparent,
                        1f to accent.copy(alpha = 0.10f),
                        start = Offset(size.width * (0.5f + rotY / 60f), 0f),
                        end = Offset(size.width * (0.5f - rotY / 60f), size.height),
                    ),
                )
                drawContent()
                // Specular highlight following the finger (fades when released).
                if (pressed) {
                    drawRect(
                        Brush.radialGradient(
                            0f to Color.White.copy(alpha = 0.20f),
                            1f to Color.Transparent,
                            center = Offset(sheen.x * size.width, sheen.y * size.height),
                            radius = size.minDimension * 0.55f,
                        ),
                    )
                }
            }
            // Tap (no drag) selects this algorithm.
            .pointerInput(algoIdx) { detectTapGestures { if (interactive) onPlay() } }
            // Read the finger on the Initial pass WITHOUT consuming, so the pager
            // still pages — the tilt is a flourish layered over the swipe.
            .then(
                if (!interactive) Modifier
                else Modifier.pointerInput(algoIdx) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull()
                            if (ch != null && ch.pressed) {
                                val nx = (ch.position.x / size.width - 0.5f).coerceIn(-0.5f, 0.5f)
                                val ny = (ch.position.y / size.height - 0.5f).coerceIn(-0.5f, 0.5f)
                                rotY = nx * 2f * MAX_TILT
                                rotX = -ny * 2f * MAX_TILT
                                sheen = Offset(ch.position.x / size.width, ch.position.y / size.height)
                                pressed = true
                            } else if (pressed) {
                                pressed = false
                                val fromX = rotX; val fromY = rotY
                                scope.launch { animate(fromX, 0f, animationSpec = tiltSpring()) { v, _ -> rotX = v } }
                                scope.launch { animate(fromY, 0f, animationSpec = tiltSpring()) { v, _ -> rotY = v } }
                            }
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Mastery chip (collection feel) — full goal-gradient ring lands in B4.
            val acc = remember(algoIdx) { Progress.accuracy(algoIdx) }
            Text(
                if (acc > 0f) "maestria ${(acc * 100).toInt()}%" else "novo",
                color = if (acc > 0f) Dusk.AccentAmber else Dusk.TextDim,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f, fill = false))
            Text(label, color = accent, fontWeight = FontWeight.Bold, fontSize = 46.sp)
            Text(h.caption, color = Dusk.TextPrimary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Text(h.pick, color = Dusk.TextDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            if (interactive) {
                Text("toque para jogar  ▶", color = accent.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
