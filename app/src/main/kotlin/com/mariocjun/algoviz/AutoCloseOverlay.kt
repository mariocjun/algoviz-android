// Hidden control for the 39-min auto-close. AutoCloseGuard WRAPS a screen's
// content: the tap-spy lives on the PARENT Box (observes downs on the Initial
// pass and never consumes), so children (all the real controls) still receive
// every tap on the Main pass — a sibling overlay on top would swallow them.
// 10 quick taps in the bottom 20% reveal a button that disables the auto-close
// for the session.
package com.mariocjun.algoviz

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AutoCloseGuard(content: @Composable BoxScope.() -> Unit) {
    var disabled by remember { mutableStateOf(AutoClose.disabled) }
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var show by remember { mutableStateOf(false) }

    // Parent-level tap spy: observe the down on the Initial pass WITHOUT consuming,
    // so the children below keep receiving every gesture on the Main pass.
    val spy = if (disabled) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.y > size.height * 0.8f) {
                val now = SystemClock.elapsedRealtime()
                taps = if (now - lastTap < 1500L) taps + 1 else 1   // a fast streak only
                lastTap = now
                if (taps >= 10) show = true
            }
        }
    }

    Box(Modifier.fillMaxSize().then(spy)) {
        content()
        if (show && !disabled) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF202023))
                    .clickable { AutoClose.disable(); disabled = true; show = false }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    "Desativar fechamento automático (39 min)",
                    color = Color(0xFF4296FA), fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
