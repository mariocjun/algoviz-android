// SchedBridge — JNI surface for the scheduler mini-app.
//
// v1 calls a single hardcoded-config runner; the bridge will grow to expose
// the live engine (step, peek_cpu0, set_workload, …) when the "predict the
// next task" mini-game lands. JNI symbols: Java_com_mariocjun_algoviz_SchedBridge_*
// (sched/sched_bridge.cpp).
package com.mariocjun.algoviz

object SchedBridge {
    init { System.loadLibrary("algoviz") }

    /** Display labels of the 7 algorithms, in the order the engine knows them. */
    external fun nativeSchedListAlgos(): Array<String>

    /**
     * Runs the Maziero reference workload through the chosen algorithm to
     * completion and returns the full result as JSON:
     *   { algo, quantum, total_time, preemptions, context_switches,
     *     avg_turnaround, avg_waiting, avg_response,
     *     tasks:[{id,name,arrival,duration,priority,start,finish,turnaround,waiting,response}],
     *     gantt:[{time,task_id}] }
     */
    external fun nativeSchedRunMaziero(algoIdx: Int): String

    /**
     * Runs a SEEDED RANDOM workload (the "Jogo" mode) through [algoIdx] and
     * returns the same JSON shape as [nativeSchedRunMaziero]. Reproducible: the
     * same (algoIdx, seed, difficulty) always yields the same schedule — so the
     * "Desafio do Dia" (seed = epoch-day) and bug-repro are deterministic, and the
     * generator rejects any workload that would hit the scheduler's coin-flip
     * tiebreak. difficulty 1=beginner / 2=intermediate / 3+=chaos (task count,
     * arrival spread, and the minimum number of dispatch decisions to predict).
     */
    external fun nativeSchedRunRandom(algoIdx: Int, seed: Long, difficulty: Int): String
}
