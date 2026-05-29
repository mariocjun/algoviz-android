// Sort-algorithm registry + benchmark driver (C++20 concept + tuple + fold).
//
// Mirrors bench/registry.h's Benchmark<T> design: each algorithm is a stateless
// wrapper struct exposing a compile-time name, big-O/stability metadata, and a
// `make()` factory returning the step coroutine. `SortAlgo<T>` enforces the
// contract; adding an algorithm is one wrapper struct + one tuple entry.
//
// This header is deliberately free of NDK/Android dependencies (it pulls only
// bench/json.h + bench/timer.h, both platform-agnostic) so the whole engine —
// coroutines, sorts, registry, benchmark — is unit-tested on the host CI
// against the real production code, not a reimplementation.
//
// The benchmark drains each algorithm's coroutine, counting comparisons /
// swaps / writes (algorithm-intrinsic, deterministic for a given input) and
// timing the drain (device-dependent — this is the throughput of the *visual
// engine*: each step is a coroutine resume, which is exactly what the Phase 2
// renderer pays per frame). Output is verified sorted; a failed sort reports
// correct=false and a -1 throughput sentinel.
#pragma once

#include "sorts.h"

#include "../bench/json.h"
#include "../bench/timer.h"

#include <algorithm>
#include <concepts>
#include <cstdint>
#include <random>
#include <string>
#include <tuple>
#include <vector>

namespace algoviz {

template <typename T>
concept SortAlgo = requires(std::vector<int>& data) {
    { T::name }       -> std::convertible_to<const char*>;
    { T::complexity } -> std::convertible_to<const char*>;
    { T::stable }     -> std::convertible_to<bool>;
    { T::make(data) } -> std::same_as<Generator<Step>>;
};

// -- Wrappers ---------------------------------------------------------------

struct BubbleSort {
    static constexpr const char* name = "bubble";
    static constexpr const char* complexity = "O(n^2)";
    static constexpr bool stable = true;
    static Generator<Step> make(std::vector<int>& a) { return bubble_sort(a); }
};

struct InsertionSort {
    static constexpr const char* name = "insertion";
    static constexpr const char* complexity = "O(n^2)";
    static constexpr bool stable = true;
    static Generator<Step> make(std::vector<int>& a) { return insertion_sort(a); }
};

struct SelectionSort {
    static constexpr const char* name = "selection";
    static constexpr const char* complexity = "O(n^2)";
    static constexpr bool stable = false;
    static Generator<Step> make(std::vector<int>& a) { return selection_sort(a); }
};

struct QuickSort {
    static constexpr const char* name = "quick";
    static constexpr const char* complexity = "O(n log n) avg";
    static constexpr bool stable = false;
    static Generator<Step> make(std::vector<int>& a) { return quick_sort(a); }
};

struct MergeSort {
    static constexpr const char* name = "merge";
    static constexpr const char* complexity = "O(n log n)";
    static constexpr bool stable = true;
    static Generator<Step> make(std::vector<int>& a) { return merge_sort(a); }
};

struct HeapSort {
    static constexpr const char* name = "heap";
    static constexpr const char* complexity = "O(n log n)";
    static constexpr bool stable = false;
    static Generator<Step> make(std::vector<int>& a) { return heap_sort(a); }
};

// -- Registry tuple ---------------------------------------------------------
// Adding an algorithm: write the wrapper above, then add its type here.
// SortAlgo<T> blocks compilation if a wrapper is malformed.
using Sorts = std::tuple<BubbleSort, InsertionSort, SelectionSort,
                         QuickSort, MergeSort, HeapSort>;

template <typename... Ss>
constexpr bool all_sorts(std::tuple<Ss...>*) {
    return (SortAlgo<Ss> && ...);
}
static_assert(all_sorts(static_cast<Sorts*>(nullptr)),
              "every entry in Sorts must satisfy SortAlgo<T>");

// -- Benchmark --------------------------------------------------------------

struct SortBenchConfig {
    std::size_t n = 4096;          // array size to sort
    int trials = 5;                // repeats per algorithm (median time)
    std::uint64_t seed = 0x9E3779B97F4A7C15ull;  // input PRNG seed
    std::string sub_filter;        // empty = all algorithms; else substring match
};

struct SortMetrics {
    std::string name;
    std::string complexity;
    bool stable = false;
    std::int64_t comparisons = 0;
    std::int64_t swaps = 0;
    std::int64_t writes = 0;        // Set steps + 2*swaps (element moves)
    std::int64_t total_steps = 0;
    double median_ms = -1.0;
    double msteps_per_sec = -1.0;   // million steps/sec — engine throughput
    bool correct = false;
};

inline std::vector<int> make_input(const SortBenchConfig& cfg) {
    std::vector<int> v(cfg.n);
    std::mt19937_64 rng(cfg.seed);
    std::uniform_int_distribution<int> dist(0, 1'000'000);
    for (auto& x : v) x = dist(rng);
    return v;
}

template <typename S>
SortMetrics measure_one(const SortBenchConfig& cfg, const std::vector<int>& input) {
    SortMetrics m;
    m.name = S::name;
    m.complexity = S::complexity;
    m.stable = S::stable;

    std::vector<double> times_ms;
    times_ms.reserve(static_cast<std::size_t>(cfg.trials > 0 ? cfg.trials : 1));

    const int trials = cfg.trials > 0 ? cfg.trials : 1;
    for (int t = 0; t < trials; ++t) {
        std::vector<int> data = input;  // fresh copy — sorts mutate in place
        std::int64_t comparisons = 0, swaps = 0, sets = 0, total = 0;

        const std::uint64_t t0 = bench::now_ns();
        auto gen = S::make(data);
        while (gen.next()) {
            ++total;
            switch (gen.value().kind) {
                case StepKind::Compare: ++comparisons; break;
                case StepKind::Swap:    ++swaps; break;
                case StepKind::Set:     ++sets; break;
                case StepKind::Pivot:   break;
            }
        }
        const std::uint64_t t1 = bench::now_ns();
        times_ms.push_back(static_cast<double>(t1 - t0) * 1e-6);

        // Step counts are deterministic across trials (same input) — record
        // them and the correctness check once.
        if (t == 0) {
            m.comparisons = comparisons;
            m.swaps = swaps;
            m.writes = sets + 2 * swaps;
            m.total_steps = total;
            m.correct = std::is_sorted(data.begin(), data.end());
        }
    }

    std::sort(times_ms.begin(), times_ms.end());
    m.median_ms = times_ms[times_ms.size() / 2];
    m.msteps_per_sec = m.median_ms > 0.0
        ? (static_cast<double>(m.total_steps) / (m.median_ms * 1e-3)) / 1e6
        : -1.0;
    return m;
}

inline bench::Json metrics_to_json(const SortMetrics& m) {
    bench::Json j;
    j.kv("name", m.name)
     .kv("complexity", m.complexity)
     .kv("stable", m.stable)
     .kv("comparisons", m.comparisons)
     .kv("swaps", m.swaps)
     .kv("writes", m.writes)
     .kv("total_steps", m.total_steps)
     .kv("median_ms", m.median_ms)
     .kv("msteps_per_sec", m.msteps_per_sec)
     .kv("correct", m.correct);
    return j;
}

inline bool sort_wanted(const std::string& sub_filter, const char* name) {
    return sub_filter.empty() || sub_filter.find(name) != std::string::npos;
}

// Run every (selected) algorithm on one shared random input. Returns a Json
// carrying the run config, a `per_algorithm` detail array, and flat
// `<name>_msteps_per_sec` headline keys for scripts/aggregate-runs.py.
inline bench::Json run_all(const SortBenchConfig& cfg) {
    const auto input = make_input(cfg);

    std::vector<SortMetrics> all;
    auto do_one = [&](auto&& s) {
        using S = std::decay_t<decltype(s)>;
        if (!sort_wanted(cfg.sub_filter, S::name)) return;
        all.push_back(measure_one<S>(cfg, input));
    };
    Sorts sorts{};
    std::apply([&](auto&&... ss) { (do_one(ss), ...); }, sorts);

    bench::Json out;
    out.kv("n", static_cast<std::int64_t>(cfg.n))
       .kv("trials", cfg.trials)
       .kv("seed", static_cast<std::int64_t>(cfg.seed))
       .kv("distribution", "uniform_random");

    // Flat headline metrics first (aggregator reads per_cluster.sort.<key>).
    for (const auto& m : all) {
        out.kv(m.name + "_msteps_per_sec", m.msteps_per_sec);
    }
    std::vector<bench::Json> rows;
    rows.reserve(all.size());
    for (const auto& m : all) rows.push_back(metrics_to_json(m));
    out.kv("per_algorithm", rows);
    return out;
}

template <typename... Ss>
std::vector<const char*> sort_names(std::tuple<Ss...>) {
    return {Ss::name...};
}

inline std::vector<const char*> all_sort_names() {
    return sort_names(Sorts{});
}

// Number of registered algorithms (compile-time tuple size).
inline constexpr std::size_t sort_count() {
    return std::tuple_size_v<Sorts>;
}

// Build the coroutine for the idx-th algorithm in the Sorts tuple. Used by the
// visualizer's algorithm picker: the same metaprogramming registry that drives
// the benchmark also enumerates + instantiates algorithms for the UI, so the
// two never drift. Returns an empty (done) generator if idx is out of range.
inline Generator<Step> make_sort_by_index(std::size_t idx, std::vector<int>& data) {
    Generator<Step> g;   // default = empty/done
    std::size_t k = 0;
    bool made = false;
    auto try_make = [&](auto&& s) {
        using S = std::decay_t<decltype(s)>;
        if (!made && k++ == idx) { g = S::make(data); made = true; }
    };
    std::apply([&](auto&&... ss) { (try_make(ss), ...); }, Sorts{});
    return g;
}

} // namespace algoviz
