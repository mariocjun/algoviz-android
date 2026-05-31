// Task-scheduling simulator — a faithful, self-contained port of the discrete
// CPU-scheduler model used in OS courses (Maziero, "Sistemas Operacionais:
// Conceitos e Mecanismos"). Pure logic: no I/O, no framework deps, so it builds
// identically on the host (golden validation) and the NDK (the interactive app).
//
// One step() == one simulation tick. The clock advances once per tick, at the
// end, after each running task consumes its tick. The 8-phase pipeline order is
// load-bearing — it reproduces the reference engine's results bit-for-bit,
// including the textbook validation table (7 algorithms, the same 5-task
// workload, total time 14). See sched_golden_test.cpp.
#pragma once

#include <algorithm>
#include <climits>
#include <cstdlib>
#include <string>
#include <vector>

namespace sched {

enum class State { New, Ready, Running, Blocked, Terminated };

// Algorithm identifiers. PrioC/PrioP/PrioD = priority cooperative / preemptive /
// dynamic-with-aging. The priority scale is POSITIVE: higher number = higher
// priority (the opposite of Unix nice).
enum class Algo { Fifo, Sjf, Rr, Srtf, PrioC, PrioP, PrioD };

// One task control block (TCB). All times are integer ticks.
struct Task {
    int id = 0;
    std::string name;
    std::string color;
    int arrival = 0;
    int duration = 0;
    int remaining = 0;
    int priority = 0;      // static priority (positive scale)
    State state = State::New;
    int start = -1;        // first dispatch tick
    int finish = -1;
    int waiting = 0;
    int turnaround = 0;
    int response = 0;
    bool first_run = true;
    int io_start = -1;     // ticks-executed at which the single I/O event fires
    int io_duration = 0;
    int io_left = 0;
    bool won_by_lottery = false;
    int dyn_priority = 0;  // dynamic priority, for PrioD aging
    int queue_order = 0;   // FIFO order of the ready queue, for RR
};

struct Cpu {
    int task = -1;          // index into System::tasks, -1 = idle
    bool powered_on = true;
    int quantum_left = 0;   // per-CPU, for RR
};

struct Gantt {
    int time;
    int task_id;
    int cpu;
};

// The complete simulation state.
struct System {
    std::vector<Task> tasks;
    std::vector<Cpu> cpus;
    std::vector<Gantt> gantt;
    Algo algo = Algo::Fifo;
    int time = 0;
    int prev_running = -1;   // shared across CPUs; mutated mid-loop (see select)
    bool finished = false;
    float avg_waiting = 0.0f;
    float avg_turnaround = 0.0f;
    float avg_response = 0.0f;
    int preemptions = 0;
    int context_switches = 0;
    int quantum = 0;
    int num_cpus = 1;
    int next_queue_order = 0;
    int alpha = 1;
};

// Shared tiebreak used by every metric-based scheduler except FIFO and RR.
// Returns true iff candidate A should displace incumbent B, in order:
//   (1) prefer the task that was previously running (avoids a context switch),
//   (2) earlier arrival, (3) shorter total duration, (4) coin flip.
inline bool tiebreak_a_wins(const Task& a, int a_idx, const Task& b, int b_idx,
                            int prev_running, bool& lottery_used) {
    if (a_idx == prev_running && b_idx != prev_running) return true;
    if (b_idx == prev_running && a_idx != prev_running) return false;
    if (a.arrival < b.arrival) return true;
    if (a.arrival > b.arrival) return false;
    if (a.duration < b.duration) return true;
    if (a.duration > b.duration) return false;
    lottery_used = true;
    return (std::rand() % 2) == 0;
}

// Returns the READY task the active algorithm would dispatch next, or nullptr if
// none is ready (CPU stays idle). Pure read of state except the lottery RNG. The
// caller sets sys.prev_running first (the CPU's incumbent during preemption
// checks, or its previous occupant during dispatch) so the tiebreak can prefer
// staying put.
inline Task* select_next(System& sys) {
    Task* selected = nullptr;
    int selected_idx = -1;
    bool any_lottery = false;

    // Primary-metric seeds: min metrics start at INT_MAX, max at INT_MIN.
    int best_arrival = INT_MAX;     // FIFO
    int best_duration = INT_MAX;    // SJF
    int best_remaining = INT_MAX;   // SRTF
    int best_queue = INT_MAX;       // RR
    int best_priority = INT_MIN;    // PrioC / PrioP
    int best_dyn = INT_MIN;         // PrioD

    for (size_t i = 0; i < sys.tasks.size(); ++i) {
        Task& t = sys.tasks[i];
        if (t.state != State::Ready) continue;
        const int idx = static_cast<int>(i);
        bool should = false;

        switch (sys.algo) {
            case Algo::Fifo: {
                if (selected == nullptr) {
                    should = true;
                } else if (t.arrival < best_arrival) {
                    should = true;
                } else if (t.arrival == best_arrival) {
                    // FIFO's tiebreak is hand-inlined and DIFFERENT from the
                    // shared helper: criterion 2 is the task ID (entry order),
                    // not arrival, then duration, then a coin flip.
                    if (idx == sys.prev_running && selected_idx != sys.prev_running) {
                        should = true;
                    } else if (selected_idx == sys.prev_running && idx != sys.prev_running) {
                        // keep the incumbent (it is the previously-running task)
                    } else if (t.id < selected->id) {
                        should = true;
                    } else if (t.id == selected->id) {
                        if (t.duration < selected->duration) {
                            should = true;
                        } else if (t.duration == selected->duration) {
                            any_lottery = true;
                            should = (std::rand() % 2) == 0;
                        }
                    }
                }
                break;
            }
            case Algo::Sjf: {
                if (selected == nullptr) should = true;
                else if (t.duration < best_duration) should = true;
                else if (t.duration == best_duration) {
                    bool lot = false;
                    if (tiebreak_a_wins(t, idx, *selected, selected_idx, sys.prev_running, lot)) {
                        should = true;
                        if (lot) any_lottery = true;
                    }
                }
                break;
            }
            case Algo::Srtf: {
                if (selected == nullptr) should = true;
                else if (t.remaining < best_remaining) should = true;
                else if (t.remaining == best_remaining) {
                    bool lot = false;
                    if (tiebreak_a_wins(t, idx, *selected, selected_idx, sys.prev_running, lot)) {
                        should = true;
                        if (lot) any_lottery = true;
                    }
                }
                break;
            }
            case Algo::Rr: {
                // Pure FIFO on queue_order — no tiebreak, no lottery.
                if (t.queue_order < best_queue) should = true;
                break;
            }
            case Algo::PrioC:
            case Algo::PrioP: {
                if (selected == nullptr) should = true;
                else if (t.priority > best_priority) should = true;
                else if (t.priority == best_priority) {
                    bool lot = false;
                    if (tiebreak_a_wins(t, idx, *selected, selected_idx, sys.prev_running, lot)) {
                        should = true;
                        if (lot) any_lottery = true;
                    }
                }
                break;
            }
            case Algo::PrioD: {
                if (selected == nullptr) {
                    should = true;
                } else if (t.dyn_priority > best_dyn) {
                    should = true;
                } else if (t.dyn_priority == best_dyn) {
                    // Extra level before the shared chain: higher static priority.
                    if (t.priority > selected->priority) {
                        should = true;
                    } else if (t.priority == selected->priority) {
                        bool lot = false;
                        if (tiebreak_a_wins(t, idx, *selected, selected_idx, sys.prev_running, lot)) {
                            should = true;
                            if (lot) any_lottery = true;
                        }
                    }
                }
                break;
            }
        }

        if (should) {
            selected = &t;
            selected_idx = idx;
            best_arrival = t.arrival;
            best_duration = t.duration;
            best_remaining = t.remaining;
            best_queue = t.queue_order;
            best_priority = t.priority;
            best_dyn = t.dyn_priority;
        }
    }

    if (selected && any_lottery) selected->won_by_lottery = true;
    return selected;
}

// Per-step scratch shared between the scheduling and execution halves of a tick.
struct StepCtx {
    std::vector<int> prev_task;   // CPU occupants at the start of the tick
    bool new_arrival = false;
};

// Phases 1-5: I/O wakeups, arrivals, termination, preemption, dispatch. This is
// the half a player predicts — it decides who occupies each CPU this tick. Split
// out so peek_cpu0() can run it on a throwaway copy without advancing the clock.
inline void phase_schedule(System& sys, StepCtx& ctx) {
    ctx.prev_task.resize(sys.cpus.size());
    for (size_t c = 0; c < sys.cpus.size(); ++c) ctx.prev_task[c] = sys.cpus[c].task;

    // 1. I/O: blocked -> ready.
    for (Task& t : sys.tasks) {
        if (t.state == State::Blocked) {
            t.io_left--;
            if (t.io_left <= 0) {
                t.state = State::Ready;
                t.queue_order = sys.next_queue_order++;
            }
        }
    }

    // 2. Arrivals: new -> ready (arrival <= now admits past arrivals too).
    ctx.new_arrival = false;
    for (Task& t : sys.tasks) {
        if (t.state == State::New && t.arrival <= sys.time) {
            t.state = State::Ready;
            t.dyn_priority = t.priority;
            t.queue_order = sys.next_queue_order++;
            ctx.new_arrival = true;
        }
    }

    // 3. Termination (detected at the start of the tick after remaining hit 0).
    for (Cpu& cpu : sys.cpus) {
        if (cpu.task == -1) continue;
        Task& t = sys.tasks[static_cast<std::size_t>(cpu.task)];
        if (t.remaining <= 0) {
            t.state = State::Terminated;
            t.finish = sys.time;
            t.turnaround = t.finish - t.arrival;
            t.waiting = t.turnaround - t.duration;
            cpu.task = -1;
        }
    }

    // 4. Preemption (per CPU).
    for (Cpu& cpu : sys.cpus) {
        if (cpu.task == -1) continue;
        Task& cur = sys.tasks[static_cast<std::size_t>(cpu.task)];
        if (cur.state != State::Running) continue;

        bool preempt = false;
        const int executed = cur.duration - cur.remaining;

        // 4a. I/O block (not a preemption, not a context switch).
        if (cur.io_start != -1 && executed == cur.io_start) {
            cur.state = State::Blocked;
            cur.io_left = cur.io_duration;
            cpu.task = -1;
            continue;
        }
        // 4b. RR quantum expiry.
        if (sys.algo == Algo::Rr && cpu.quantum_left <= 0 && cur.remaining > 0) {
            preempt = true;
        }
        // 4c. SRTF.
        if (!preempt && sys.algo == Algo::Srtf) {
            sys.prev_running = cpu.task;
            const Task* best = select_next(sys);
            if (best && best != &cur && best->remaining < cur.remaining) preempt = true;
        }
        // 4d. PrioP.
        if (!preempt && sys.algo == Algo::PrioP) {
            sys.prev_running = cpu.task;
            const Task* best = select_next(sys);
            if (best && best != &cur && best->priority > cur.priority) preempt = true;
        }
        // 4e. PrioD (only re-checked when a new task arrived this tick).
        if (!preempt && sys.algo == Algo::PrioD && ctx.new_arrival) {
            sys.prev_running = cpu.task;
            const Task* best = select_next(sys);
            if (best && best != &cur && best->dyn_priority > cur.dyn_priority) preempt = true;
        }

        if (preempt) {
            cur.state = State::Ready;
            cur.queue_order = sys.next_queue_order++;
            cpu.task = -1;
            sys.preemptions++;
        }
    }

    // 5. Dispatch idle CPUs (+ PrioD aging, first-run metrics, RR quantum reset).
    for (size_t c = 0; c < sys.cpus.size(); ++c) {
        Cpu& cpu = sys.cpus[c];
        if (cpu.task != -1) continue;
        sys.prev_running = ctx.prev_task[c];   // tiebreak prefers this CPU's prior task

        Task* next = select_next(sys);
        if (next) {
            const int next_idx = static_cast<int>(next - sys.tasks.data());
            cpu.task = next_idx;
            next->state = State::Running;

            if (sys.algo == Algo::PrioD) {
                for (Task& t : sys.tasks) {
                    if (&t != next && t.state == State::Ready) t.dyn_priority += sys.alpha;
                }
                next->dyn_priority = next->priority;
            }
            if (next->first_run) {
                next->start = sys.time;
                next->response = next->start - next->arrival;
                next->first_run = false;
            }
            if (sys.algo == Algo::Rr) cpu.quantum_left = sys.quantum;
        }
    }
}

// Phases 6-8: context-switch accounting, execution of one tick per busy CPU, and
// the end-of-simulation check + final statistics. Consumes the StepCtx produced
// by phase_schedule().
inline void phase_execute(System& sys, StepCtx& ctx) {
    // 6. Context switches: only task -> different-task on the same CPU.
    for (size_t c = 0; c < sys.cpus.size(); ++c) {
        const int old_idx = ctx.prev_task[c];
        const int new_idx = sys.cpus[c].task;
        if (old_idx != -1 && new_idx != -1 && old_idx != new_idx) sys.context_switches++;
    }

    // 7. Execute one tick per busy CPU.
    for (size_t c = 0; c < sys.cpus.size(); ++c) {
        Cpu& cpu = sys.cpus[c];
        if (cpu.task != -1) {
            Task& t = sys.tasks[static_cast<std::size_t>(cpu.task)];
            if (t.state == State::Running) {
                t.remaining--;
                if (sys.algo == Algo::Rr) cpu.quantum_left--;
                if (sys.gantt.size() < 10000u) {
                    sys.gantt.push_back(Gantt{sys.time, t.id, static_cast<int>(c)});
                }
            }
            cpu.powered_on = true;
        } else {
            bool any_pending = false;
            for (const Task& t : sys.tasks) {
                if (t.state == State::Ready || t.state == State::New || t.state == State::Blocked) {
                    any_pending = true;
                    break;
                }
            }
            cpu.powered_on = any_pending;
        }
    }

    sys.time++;

    // 8. End of simulation.
    bool all_done = !sys.tasks.empty();
    for (const Task& t : sys.tasks) {
        if (t.state != State::Terminated) { all_done = false; break; }
    }
    if (all_done) {
        sys.finished = true;
        sys.time--;   // off-by-one correction
        for (Cpu& cpu : sys.cpus) { cpu.powered_on = false; cpu.task = -1; }

        int sum_w = 0, sum_t = 0, sum_r = 0, n = 0;
        for (const Task& t : sys.tasks) {
            sum_w += t.waiting;
            sum_t += t.turnaround;
            sum_r += t.response;
            ++n;
        }
        if (n > 0) {
            sys.avg_waiting = static_cast<float>(sum_w) / static_cast<float>(n);
            sys.avg_turnaround = static_cast<float>(sum_t) / static_cast<float>(n);
            sys.avg_response = static_cast<float>(sum_r) / static_cast<float>(n);
        }
    }
}

// Advances the simulation by exactly one tick. Returns false if already finished.
inline bool step(System& sys) {
    if (sys.finished) return false;
    StepCtx ctx;
    phase_schedule(sys, ctx);
    phase_execute(sys, ctx);
    return true;
}

// Returns the task index CPU 0 WOULD run after the next tick's scheduling, or -1
// if it would be idle / the sim is over. Runs the scheduling half on a throwaway
// copy, so it never mutates the live system or advances the clock — the answer a
// "predict the next task" round is graded against. (Deterministic for workloads
// that never reach the lottery tiebreak; curated puzzles avoid it.)
inline int peek_cpu0(const System& sys) {
    if (sys.finished || sys.cpus.empty()) return -1;
    System copy = sys;
    StepCtx ctx;
    phase_schedule(copy, ctx);
    return copy.cpus[0].task;
}

// Initializes derived per-task fields and the CPU array. Call after populating
// tasks + algo + quantum + num_cpus + alpha.
inline void prepare(System& sys) {
    for (Task& t : sys.tasks) {
        t.remaining = t.duration;
        t.dyn_priority = t.priority;
        t.state = State::New;
        t.start = -1;
        t.finish = -1;
        t.first_run = true;
        t.io_left = 0;
        t.won_by_lottery = false;
    }
    sys.cpus.assign(static_cast<size_t>(sys.num_cpus < 1 ? 1 : sys.num_cpus), Cpu{});
    sys.time = 0;
    sys.prev_running = -1;
    sys.finished = false;
    sys.preemptions = 0;
    sys.context_switches = 0;
    sys.next_queue_order = 0;
    sys.gantt.clear();
}

// Runs to completion (capped, matching the reference loop bound).
inline void run(System& sys) {
    for (int i = 0; !sys.finished && i < 100000; ++i) step(sys);
}

} // namespace sched
