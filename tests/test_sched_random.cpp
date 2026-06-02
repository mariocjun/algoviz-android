// Host unit test for the Scheduler "Jogo" random workload generator
// (sched/sched_random.h). Exercises the REAL generator (NDK-free), proving the
// three contracts the game relies on:
//   1. Reproducible — same (algo, seed, difficulty) → identical workload + schedule.
//   2. Interesting  — at least `min_dispatches` dispatch decisions to predict.
//   3. Deterministic — accepted workloads never reach the scheduler's coin-flip
//      tiebreak, so a replay reproduces the schedule bit-for-bit.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest/doctest.h"

#include "sched/sched_random.h"
#include "sched/sim.h"

#include <cstdint>
#include <cstddef>

using namespace sched;

namespace {

struct AlgoQ { Algo algo; int quantum; const char* name; };

// Mirrors the 7 algorithms + per-algorithm quantum the app's bridge uses.
const AlgoQ kAlgos[] = {
    {Algo::Fifo, 5, "FCFS"}, {Algo::Sjf, 2, "SJF"},  {Algo::Rr, 2, "RR"},
    {Algo::Srtf, 5, "SRTF"}, {Algo::PrioC, 2, "PRIOc"},
    {Algo::PrioP, 5, "PRIOp"}, {Algo::PrioD, 2, "PRIOd"},
};

bool same_tasks(const System& a, const System& b) {
    if (a.tasks.size() != b.tasks.size()) return false;
    for (std::size_t i = 0; i < a.tasks.size(); ++i) {
        const Task& x = a.tasks[i];
        const Task& y = b.tasks[i];
        if (x.id != y.id || x.arrival != y.arrival ||
            x.duration != y.duration || x.priority != y.priority) return false;
    }
    return true;
}

bool same_gantt(const System& a, const System& b) {
    if (a.gantt.size() != b.gantt.size()) return false;
    for (std::size_t i = 0; i < a.gantt.size(); ++i) {
        if (a.gantt[i].time != b.gantt[i].time ||
            a.gantt[i].task_id != b.gantt[i].task_id) return false;
    }
    return true;
}

// Rebuild a fresh (prepared, un-run) System from a finished one's task table.
System fresh_copy(const System& g) {
    System s;
    s.algo = g.algo;
    s.quantum = g.quantum;
    s.num_cpus = 1;
    s.alpha = 1;
    for (const Task& t : g.tasks) {
        Task n;
        n.id = t.id; n.name = t.name; n.arrival = t.arrival;
        n.duration = t.duration; n.priority = t.priority;
        s.tasks.push_back(n);
    }
    prepare(s);
    return s;
}

}  // namespace

TEST_CASE("same (algo, seed, difficulty) reproduces the exact workload + schedule") {
    for (const AlgoQ& aq : kAlgos) {
        for (int diff = 1; diff <= 3; ++diff) {
            for (std::uint64_t seed : {1ull, 42ull, 1000ull, 987654321ull}) {
                System a = generate_game(aq.algo, aq.quantum, seed, diff);
                System b = generate_game(aq.algo, aq.quantum, seed, diff);
                REQUIRE_MESSAGE(!a.tasks.empty(), "generator found a workload for " << aq.name);
                CHECK(same_tasks(a, b));
                CHECK(same_gantt(a, b));
            }
        }
    }
}

TEST_CASE("accepted workloads are lottery-free, interesting, bounded, and replayable") {
    for (const AlgoQ& aq : kAlgos) {
        for (int diff = 1; diff <= 3; ++diff) {
            const DiffCfg dc = diff_cfg(diff);
            for (std::uint64_t seed = 1; seed <= 40; ++seed) {
                System g = generate_game(aq.algo, aq.quantum, seed, diff);
                REQUIRE(!g.tasks.empty());

                CHECK_FALSE(had_lottery(g));                       // → deterministic
                CHECK(count_dispatches(g) >= dc.min_dispatches);   // → interesting
                CHECK(g.time > 0);
                CHECK(g.time <= 40);                               // → bounded
                CHECK(static_cast<int>(g.tasks.size()) >= dc.min_tasks);
                CHECK(static_cast<int>(g.tasks.size()) <= dc.max_tasks);

                System rerun = fresh_copy(g);                      // replay it
                run(rerun);
                CHECK(same_gantt(g, rerun));
            }
        }
    }
}

TEST_CASE("difficulty 1 stays small (<= 4 tasks); difficulty 3 is the chaos band") {
    for (std::uint64_t seed = 1; seed <= 30; ++seed) {
        System easy = generate_game(Algo::Srtf, 5, seed, 1);
        REQUIRE(!easy.tasks.empty());
        CHECK(easy.tasks.size() <= 4u);

        System chaos = generate_game(Algo::Srtf, 5, seed, 3);
        REQUIRE(!chaos.tasks.empty());
        CHECK(chaos.tasks.size() == 6u);
        CHECK(count_dispatches(chaos) >= 4);
    }
}

TEST_CASE("different seeds produce a variety of workloads") {
    System base = generate_game(Algo::Sjf, 2, 1, 2);
    int distinct = 0;
    for (std::uint64_t seed = 2; seed <= 30; ++seed) {
        if (!same_tasks(base, generate_game(Algo::Sjf, 2, seed, 2))) ++distinct;
    }
    CHECK(distinct >= 20);   // the overwhelming majority differ
}
