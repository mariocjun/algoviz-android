// sched_random.h — seeded RANDOM workload generator for the Scheduler "Jogo"
// mode. NDK-free (only sim.h + the standard library), so the host unit test
// exercises the real generator, not a reimplementation — same discipline as
// sim.h / sorts.h.
//
// Reproducibility is the whole point: same (algo, quantum, seed, difficulty) ->
// the same schedule, every time, on host and device. That powers the "Desafio do
// Dia" (seed = epoch-day) and one-line bug-repro. The catch is sched::select_next
// breaks a full metric+arrival+duration tie with std::rand() (an unseeded coin
// flip). We make that path unreachable for accepted workloads: candidates use
// DISTINCT durations + a permuted priority set, and any candidate that still
// reaches a lottery (Task::won_by_lottery) is rejected — so accepted schedules
// never touch std::rand() and are fully deterministic.
#pragma once

#include "sim.h"

#include <algorithm>
#include <cstdint>
#include <random>
#include <vector>

namespace sched {

// Difficulty knob: task count, arrival spread, duration range, and the minimum
// number of dispatch decisions a player must predict (the "interesting" bar).
struct DiffCfg { int min_tasks, max_tasks, max_arrival, min_dur, max_dur, min_dispatches; };

inline DiffCfg diff_cfg(int difficulty) {
    switch (difficulty) {
        case 1:  return {3, 4, 5, 1, 5, 2};   // beginner: spaced arrivals, few switches
        case 2:  return {5, 5, 6, 1, 6, 3};   // intermediate: a simultaneous burst
        default: return {6, 6, 7, 1, 7, 4};   // 3+ chaos: long+short mix, more preemption
    }
}

// Distinct consecutive task segments in the Gantt = the number of dispatch
// decisions to predict. >=2 means at least one context switch (interesting).
inline int count_dispatches(const System& sys) {
    int c = 0, prev = -2;
    for (const Gantt& g : sys.gantt) {
        if (g.task_id != prev) { ++c; prev = g.task_id; }
    }
    return c;
}

inline bool had_lottery(const System& sys) {
    for (const Task& t : sys.tasks) if (t.won_by_lottery) return true;
    return false;
}

// One candidate workload (prepared, not yet run). DISTINCT durations + a permuted
// 1..n priority set keep almost every tiebreak resolvable without the lottery.
inline System build_random_system(Algo algo, int quantum, std::mt19937& rng, const DiffCfg& dc) {
    const int n = std::uniform_int_distribution<int>(dc.min_tasks, dc.max_tasks)(rng);

    std::vector<int> durs;                                   // distinct durations
    for (int d = dc.min_dur; d <= dc.max_dur; ++d) durs.push_back(d);
    std::shuffle(durs.begin(), durs.end(), rng);
    durs.resize(static_cast<std::size_t>(n));

    std::vector<int> prios;                                  // permutation of 1..n
    for (int p = 1; p <= n; ++p) prios.push_back(p);
    std::shuffle(prios.begin(), prios.end(), rng);

    std::uniform_int_distribution<int> ad(0, dc.max_arrival);  // arrivals; t1 at 0
    std::vector<int> arr(static_cast<std::size_t>(n), 0);
    for (int i = 1; i < n; ++i) arr[static_cast<std::size_t>(i)] = ad(rng);
    if (dc.min_tasks == 5 && n >= 3) arr[2] = arr[1];        // intermediate: a burst

    System sys;
    sys.algo = algo;
    sys.quantum = quantum;
    sys.num_cpus = 1;
    sys.alpha = 1;
    for (int i = 0; i < n; ++i) {
        Task t;
        t.id = i + 1;
        t.name = "t" + std::to_string(i + 1);
        t.arrival = arr[static_cast<std::size_t>(i)];
        t.duration = durs[static_cast<std::size_t>(i)];
        t.priority = prios[static_cast<std::size_t>(i)];
        sys.tasks.push_back(t);
    }
    prepare(sys);
    return sys;
}

// Generates a reproducible, deterministic, "interesting" workload and runs it to
// completion. Returns the finished System, or an empty System (tasks.empty()) if
// no good candidate turned up within maxAttempts — the caller falls back then.
inline System generate_game(Algo algo, int quantum, std::uint64_t seed,
                            int difficulty, int maxAttempts = 400) {
    const DiffCfg dc = diff_cfg(difficulty);
    std::mt19937 rng(static_cast<std::uint32_t>(seed ^ (seed >> 32)));
    for (int attempt = 0; attempt < maxAttempts; ++attempt) {
        System cand = build_random_system(algo, quantum, rng, dc);
        System probe = cand;                 // validate on a throwaway copy
        run(probe);
        const bool ok = !had_lottery(probe)                          // deterministic
                     && count_dispatches(probe) >= dc.min_dispatches // interesting
                     && probe.time > 0 && probe.time <= 40;          // bounded
        if (ok) {
            run(cand);                       // cand is still prepared → run for real
            return cand;
        }
    }
    return System{};
}

} // namespace sched
