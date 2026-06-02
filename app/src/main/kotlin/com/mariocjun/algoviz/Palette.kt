// Palette.kt — shared "Dusk" design tokens (dark pastel + low-reflectance grays).
//
// Introduced in v0.6.7 for the Scheduler "Jogo" redesign and adopted by the Home
// launcher. The family is Material ~200 tones over a warm near-black — deliberately
// NOT the saturated AI purple (0xFF8E75FF) used by the Gemini chrome: the owner
// wants the premium Pokémon-card surfaces to read as calm/tactile, not "AI app".
//
// The other four mini-apps keep their own *_BG constants for now (no app-wide
// re-validation in this release); Dusk is the direction they'll migrate to.
package com.mariocjun.algoviz

import androidx.compose.ui.graphics.Color

object Dusk {
    // ---- Base: warm near-black + neutral grays -------------------------------
    val Background  = Color(0xFF121013)   // app background (slightly warm)
    val Surface     = Color(0xFF1E1B20)   // panels / glass-card base
    val SurfaceHi   = Color(0xFF272430)   // elevated / selected surface
    val Line        = Color(0xFF38343F)   // hairline borders
    val TextPrimary = Color(0xFFE6E1E5)   // titles / critical text (>=4.5:1 on bg)
    val TextDim     = Color(0xFFA39EAC)   // secondary text (>=4.5:1 on bg)

    // ---- Pastel accents (the owner's three) ----------------------------------
    val AccentTeal  = Color(0xFF80CBC4)   // progress / calm focus — the primary
    val AccentRose  = Color(0xFFF48FB1)   // error / preemption (attention, no panic)
    val AccentAmber = Color(0xFFFFCC80)   // mastery / reward

    // ---- Per-algorithm foil colors, indexed by algoIdx (FCFS=0 .. PRIOd=6) ----
    // Each card's holographic foil; all in the same pastel-200 family.
    val algoAccents = listOf(
        Color(0xFF80CBC4), // 0 FCFS  — teal
        Color(0xFFFFCC80), // 1 SJF   — amber
        Color(0xFF9FA8DA), // 2 RR    — periwinkle
        Color(0xFFA5D6A7), // 3 SRTF  — sage
        Color(0xFFF48FB1), // 4 PRIOc — rose
        Color(0xFFFFAB91), // 5 PRIOp — coral
        Color(0xFFCE93D8), // 6 PRIOd — lilac (pastel, not the AI purple)
    )
    fun algoAccent(idx: Int): Color = algoAccents.getOrElse(idx) { AccentTeal }
}
