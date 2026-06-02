// Motion.kt — centralized animation tokens (the app's motion "idioma").
// Material 3 motion: durations + emphasized/standard easings + spring presets.
// New animations should reference these instead of magic tween()/spring() values;
// existing magic numbers migrate opportunistically when a file is touched.
package com.mariocjun.algoviz

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

object Motion {
    // Durations (ms)
    const val Quick = 120        // small acknowledgements (HUD, taps)
    const val Standard = 250     // default content/affordance changes
    const val Emphasized = 450   // entrances/exits of emphasis (climax, verdict)

    // Easings (Material 3)
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f) // entrada
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f) // saída

    // Springs
    fun <T> springStd(): SpringSpec<T> =
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    fun <T> springBouncy(): SpringSpec<T> =   // climax / "stamp"
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)
}
