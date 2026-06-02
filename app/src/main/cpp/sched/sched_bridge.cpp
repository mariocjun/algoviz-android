// SchedBridge JNI — runs the Maziero textbook configs through sched/sim.h and
// returns the result (final metrics + Gantt + per-task timing) as JSON for the
// SchedActivity Compose UI to render.
//
// v1 only: the workload + per-algorithm quantum are hardcoded (same 5-task
// table the golden test validates), so the UI can ship before a workload
// editor or the "predict the next task" mini-game land. Adding either later
// only touches the JSON producer here, not the engine.
#include "../bench/json.h"
#include "sim.h"
#include "sched_random.h"

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace {

struct AlgoCfg {
    const char* label;
    sched::Algo algo;
    int quantum;
};

// Same algorithms + per-algorithm quantum the golden test validates against
// Maziero's reference table. Keeping them in sync means the UI shows results a
// professor would grade as correct.
const AlgoCfg kAlgos[] = {
    {"FCFS",  sched::Algo::Fifo,  5},
    {"SJF",   sched::Algo::Sjf,   2},
    {"RR",    sched::Algo::Rr,    2},
    {"SRTF",  sched::Algo::Srtf,  5},
    {"PRIOc", sched::Algo::PrioC, 2},
    {"PRIOp", sched::Algo::PrioP, 5},
    {"PRIOd", sched::Algo::PrioD, 2},
};

struct Spec { int arrival, duration, priority; };

// The 5-task reference workload (Maziero, "Sistemas Operacionais", table 6.2).
const Spec kWorkload[] = {
    {0, 5, 2}, {0, 2, 3}, {1, 4, 1}, {3, 1, 4}, {5, 2, 5},
};

constexpr int kAlgoCount = static_cast<int>(sizeof(kAlgos) / sizeof(kAlgos[0]));

sched::System build_system(int algo_idx) {
    const AlgoCfg& cfg = kAlgos[algo_idx];
    sched::System sys;
    sys.algo = cfg.algo;
    sys.quantum = cfg.quantum;
    sys.num_cpus = 1;
    sys.alpha = 1;
    int id = 1;
    for (const Spec& s : kWorkload) {
        sched::Task t;
        t.id = id;
        t.name = "t" + std::to_string(id);
        t.arrival = s.arrival;
        t.duration = s.duration;
        t.priority = s.priority;
        sys.tasks.push_back(t);
        ++id;
    }
    sched::prepare(sys);
    return sys;
}

// Serializes a *finished* simulation to the JSON contract the Compose UI parses.
// Shared by the Maziero (teaching) and random (game) producers.
std::string serialize(const sched::System& sys, const char* label, int quantum) {
    std::vector<bench::Json> tasks;
    tasks.reserve(sys.tasks.size());
    for (const sched::Task& t : sys.tasks) {
        bench::Json j;
        j.kv("id", t.id)
         .kv("name", t.name)
         .kv("arrival", t.arrival)
         .kv("duration", t.duration)
         .kv("priority", t.priority)
         .kv("start", t.start)
         .kv("finish", t.finish)
         .kv("turnaround", t.turnaround)
         .kv("waiting", t.waiting)
         .kv("response", t.response);
        tasks.push_back(j);
    }

    std::vector<bench::Json> gantt;
    gantt.reserve(sys.gantt.size());
    for (const sched::Gantt& g : sys.gantt) {
        bench::Json j;
        j.kv("time", g.time).kv("task_id", g.task_id);
        gantt.push_back(j);
    }

    bench::Json out;
    out.kv("algo", label)
       .kv("quantum", quantum)
       .kv("total_time", sys.time)
       .kv("preemptions", sys.preemptions)
       .kv("context_switches", sys.context_switches)
       .kv("avg_turnaround", static_cast<double>(sys.avg_turnaround))
       .kv("avg_waiting", static_cast<double>(sys.avg_waiting))
       .kv("avg_response", static_cast<double>(sys.avg_response))
       .kv("tasks", tasks)
       .kv("gantt", gantt);
    return out.str();
}

std::string run_as_json(int algo_idx) {
    if (algo_idx < 0 || algo_idx >= kAlgoCount) {
        return std::string("{\"error\":\"unknown algo index\"}");
    }
    const AlgoCfg& cfg = kAlgos[algo_idx];
    sched::System sys = build_system(algo_idx);
    sched::run(sys);
    return serialize(sys, cfg.label, cfg.quantum);
}

// ---- Random workload (the "Jogo" mode) ------------------------------------
// The generator + its determinism/interestingness guarantees live in the NDK-free
// sched_random.h (host-unit-tested). Here we only map the algo index to its
// engine config and serialize the finished run.
std::string run_random_as_json(int algo_idx, std::uint64_t seed, int difficulty) {
    if (algo_idx < 0 || algo_idx >= kAlgoCount) {
        return std::string("{\"error\":\"unknown algo index\"}");
    }
    const AlgoCfg& cfg = kAlgos[algo_idx];
    sched::System sys = sched::generate_game(cfg.algo, cfg.quantum, seed, difficulty);
    if (sys.tasks.empty()) {              // generator gave up → canonical fallback
        sys = build_system(algo_idx);
        sched::run(sys);
    }
    return serialize(sys, cfg.label, cfg.quantum);
}

}  // namespace

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_com_mariocjun_algoviz_SchedBridge_nativeSchedListAlgos(JNIEnv* env, jobject /*thiz*/) {
    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(kAlgoCount), str_cls, nullptr);
    for (int i = 0; i < kAlgoCount; ++i) {
        jstring s = env->NewStringUTF(kAlgos[i].label);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_mariocjun_algoviz_SchedBridge_nativeSchedRunMaziero(JNIEnv* env, jobject /*thiz*/, jint algo_idx) {
    const std::string s = run_as_json(static_cast<int>(algo_idx));
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_mariocjun_algoviz_SchedBridge_nativeSchedRunRandom(
        JNIEnv* env, jobject /*thiz*/, jint algo_idx, jlong seed, jint difficulty) {
    const std::string s = run_random_as_json(
        static_cast<int>(algo_idx), static_cast<std::uint64_t>(seed), static_cast<int>(difficulty));
    return env->NewStringUTF(s.c_str());
}

}  // extern "C"
