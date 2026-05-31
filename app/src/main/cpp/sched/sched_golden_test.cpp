// Golden validation for the scheduler port: reproduces Maziero's textbook
// reference table. The same 5-task workload runs under all 7 algorithms; the
// expected average turnaround (Tt), average waiting (Tw), context-switch count,
// and total time must match. Pass criteria mirror the reference test harness:
// Tt/Tw within 0.05, ctx and total exact.
//
// Host build:  g++ -std=c++17 sched_golden_test.cpp -o golden && ./golden
#include "sim.h"

#include <cmath>
#include <cstdio>

using namespace sched;

namespace {

// The shared workload: t1 arr0 dur5 prio2, t2 arr0 dur2 prio3, t3 arr1 dur4
// prio1, t4 arr3 dur1 prio4, t5 arr5 dur2 prio5.
struct Spec { int arrival, duration, priority; };
const Spec kWorkload[] = {
    {0, 5, 2}, {0, 2, 3}, {1, 4, 1}, {3, 1, 4}, {5, 2, 5},
};

System make_system(Algo algo, int quantum, int num_cpus, int alpha) {
    System sys;
    sys.algo = algo;
    sys.quantum = quantum;
    sys.num_cpus = num_cpus;
    sys.alpha = alpha;
    int id = 1;
    for (const Spec& s : kWorkload) {
        Task t;
        t.id = id;
        t.name = "t" + std::to_string(id);
        t.arrival = s.arrival;
        t.duration = s.duration;
        t.priority = s.priority;
        sys.tasks.push_back(t);
        ++id;
    }
    prepare(sys);
    return sys;
}

struct Expect {
    const char* label;
    Algo algo;
    int quantum;
    float tt;   // avg turnaround
    float tw;   // avg waiting
    int ctx;    // context switches
    int total;  // final time
};

// Maziero, "Sistemas Operacionais", reference table (single CPU, alpha 1).
const Expect kExpected[] = {
    {"FCFS",  Algo::Fifo,  5, 8.0f, 5.2f, 4, 14},
    {"SJF",   Algo::Sjf,   2, 5.8f, 3.0f, 4, 14},
    {"RR",    Algo::Rr,    2, 8.4f, 5.6f, 7, 14},
    {"SRTF",  Algo::Srtf,  5, 5.4f, 2.6f, 5, 14},
    {"PRIOc", Algo::PrioC, 2, 6.6f, 3.8f, 4, 14},
    {"PRIOp", Algo::PrioP, 5, 5.6f, 2.8f, 6, 14},
    {"PRIOd", Algo::PrioD, 2, 5.8f, 3.0f, 6, 14},
};

} // namespace

int main() {
    const float eps = 0.05f;
    int failures = 0;

    std::printf("%-6s %8s %8s %5s %6s   result\n", "algo", "Tt", "Tw", "ctx", "total");
    std::printf("------------------------------------------------------\n");
    for (const Expect& e : kExpected) {
        System sys = make_system(e.algo, e.quantum, 1, 1);
        run(sys);

        const bool tt_ok = std::fabs(sys.avg_turnaround - e.tt) < eps;
        const bool tw_ok = std::fabs(sys.avg_waiting - e.tw) < eps;
        const bool ctx_ok = sys.context_switches == e.ctx;
        const bool total_ok = sys.time == e.total;
        const bool ok = tt_ok && tw_ok && ctx_ok && total_ok;
        if (!ok) ++failures;

        std::printf("%-6s %8.2f %8.2f %5d %6d   %s",
                    e.label, sys.avg_turnaround, sys.avg_waiting,
                    sys.context_switches, sys.time, ok ? "PASS" : "FAIL");
        if (!ok) {
            std::printf("  (exp Tt=%.1f Tw=%.1f ctx=%d total=%d%s%s%s%s)",
                        e.tt, e.tw, e.ctx, e.total,
                        tt_ok ? "" : " Tt!", tw_ok ? "" : " Tw!",
                        ctx_ok ? "" : " ctx!", total_ok ? "" : " total!");
        }
        std::printf("\n");
    }
    std::printf("------------------------------------------------------\n");
    std::printf("%d/%d algorithms match the reference table.\n",
                static_cast<int>(sizeof(kExpected) / sizeof(kExpected[0])) - failures,
                static_cast<int>(sizeof(kExpected) / sizeof(kExpected[0])));
    return failures == 0 ? 0 : 1;
}
