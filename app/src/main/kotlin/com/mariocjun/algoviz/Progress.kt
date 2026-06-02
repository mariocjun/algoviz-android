// Progress.kt — local-only persistence (SharedPreferences). NO PII, NO backend.
//
// Ethical guardrail (Manipulation Matrix): mastery rises ONLY by performance
// (answering correctly), never by screen time or number of opens. The user can
// wipe everything via resetAll(). Initialised once in AlgovizApp.onCreate().
//
// v0.6.6 uses only the onboarding flags (resolves MD-18: "don't show again" used
// to live in an in-memory object that died with the process). The mastery /
// challenge-accuracy API is the foundation for the v0.6.7+ collection & adaptive
// difficulty; it persists nothing until those features call it.
package com.mariocjun.algoviz

import android.content.Context
import android.content.SharedPreferences

object Progress {
    private const val PREFS = "algoviz_progress"
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ---- Onboarding (per-install, persistent) — MD-18 ----
    var challengeIntroDone: Boolean
        get() = sp?.getBoolean("challenge_intro_done", false) ?: false
        set(v) { sp?.edit()?.putBoolean("challenge_intro_done", v)?.apply() }
    var extremeTutorialDone: Boolean
        get() = sp?.getBoolean("extreme_tutorial_done", false) ?: false
        set(v) { sp?.edit()?.putBoolean("extreme_tutorial_done", v)?.apply() }

    // ---- Mastery / collection (foundation for v0.6.7+) ----
    val sortsMastered: Set<Int> get() = readIntSet("sorts_mastered")
    val schedulersMastered: Set<Int> get() = readIntSet("scheds_mastered")
    fun markSortMastered(i: Int) = addToIntSet("sorts_mastered", i)
    fun markSchedulerMastered(i: Int) = addToIntSet("scheds_mastered", i)

    // ---- Challenge accuracy — per algorithm, bounded sliding window of '1'/'0' ----
    fun recordChallenge(algoIdx: Int, correct: Boolean) {
        val k = "ch_$algoIdx"
        val hist = (sp?.getString(k, "") ?: "") + if (correct) "1" else "0"
        sp?.edit()?.putString(k, hist.takeLast(20))?.apply()
    }
    fun last5(algoIdx: Int): List<Boolean> =
        (sp?.getString("ch_$algoIdx", "") ?: "").takeLast(5).map { it == '1' }
    fun accuracy(algoIdx: Int): Float {
        val h = sp?.getString("ch_$algoIdx", "") ?: ""
        return if (h.isEmpty()) 0f else h.count { it == '1' }.toFloat() / h.length
    }

    /** Wipe all local progress (the "zerar progresso" control). */
    fun resetAll() { sp?.edit()?.clear()?.apply() }

    private fun readIntSet(key: String): Set<Int> =
        (sp?.getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()
    private fun addToIntSet(key: String, i: Int) {
        val cur = (sp?.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        cur.add(i.toString())
        sp?.edit()?.putStringSet(key, cur)?.apply()
    }
}
