// Tests for the SortAlgo metaprogramming registry and benchmark driver.
//
// Unlike test_registry.cpp (which redeclares a stripped concept because
// bench/registry.h drags in NDK headers), algoviz/sort_registry.h is NDK-free,
// so we include and test the REAL registry: the SortAlgo concept, the fold
// dispatch over the Sorts tuple, and the run_all() benchmark output.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "algoviz/sort_registry.h"

#include <string>
#include <vector>

using namespace algoviz;

namespace {
// Malformed wrappers for the negative concept checks. Declared at namespace
// scope because C++ forbids static data members in function-local classes.
struct MissingMake {
    static constexpr const char* name = "x";
    static constexpr const char* complexity = "O(n)";
    static constexpr bool stable = true;
    // no make()
};
struct WrongReturn {
    static constexpr const char* name = "x";
    static constexpr const char* complexity = "O(n)";
    static constexpr bool stable = true;
    static int make(std::vector<int>&) { return 0; }  // not Generator<Step>
};
} // namespace

TEST_CASE("the Sorts tuple exposes the six expected algorithms in order") {
    auto names = all_sort_names();
    REQUIRE(names.size() == 6);
    CHECK(std::string(names[0]) == "bubble");
    CHECK(std::string(names[1]) == "insertion");
    CHECK(std::string(names[2]) == "selection");
    CHECK(std::string(names[3]) == "quick");
    CHECK(std::string(names[4]) == "merge");
    CHECK(std::string(names[5]) == "heap");
}

TEST_CASE("SortAlgo concept rejects a malformed wrapper") {
    static_assert(!SortAlgo<MissingMake>);
    static_assert(!SortAlgo<WrongReturn>);
    static_assert(SortAlgo<BubbleSort>);
    static_assert(SortAlgo<QuickSort>);
    CHECK(true);
}

TEST_CASE("run_all benchmarks every algorithm and all sort correctly") {
    SortBenchConfig cfg;
    cfg.n = 512;
    cfg.trials = 2;
    bench::Json j = run_all(cfg);
    const std::string s = j.str();

    // Run config echoed.
    CHECK(s.find(R"("n":512)") != std::string::npos);
    CHECK(s.find(R"("distribution":"uniform_random")") != std::string::npos);

    // Every algorithm reports correct:true. (If any sort were broken it would
    // emit correct:false here — this is the host-side correctness gate.)
    for (const char* name : all_sort_names()) {
        CAPTURE(name);
        const std::string tag = std::string(R"("name":")") + name + R"(")";
        CHECK(s.find(tag) != std::string::npos);
    }
    // No algorithm produced an incorrect result.
    CHECK(s.find(R"("correct":false)") == std::string::npos);
    // ...and at least one correct:true is present.
    CHECK(s.find(R"("correct":true)") != std::string::npos);
}

TEST_CASE("headline <name>_msteps_per_sec keys are emitted for the aggregator") {
    SortBenchConfig cfg;
    cfg.n = 256;
    cfg.trials = 1;
    const std::string s = run_all(cfg).str();
    for (const char* name : all_sort_names()) {
        CAPTURE(name);
        const std::string key = std::string(R"(")") + name + R"(_msteps_per_sec":)";
        CHECK(s.find(key) != std::string::npos);
    }
}

TEST_CASE("sub_filter selects a subset of algorithms") {
    SortBenchConfig cfg;
    cfg.n = 128;
    cfg.trials = 1;
    cfg.sub_filter = "quick";
    const std::string s = run_all(cfg).str();
    CHECK(s.find(R"("name":"quick")") != std::string::npos);
    CHECK(s.find(R"("name":"bubble")") == std::string::npos);
    CHECK(s.find(R"("name":"merge")") == std::string::npos);
}

TEST_CASE("step counts are deterministic for a fixed seed") {
    SortBenchConfig cfg;
    cfg.n = 300;
    cfg.trials = 3;          // counts must be identical across all trials
    const auto input = make_input(cfg);
    SortMetrics a = measure_one<QuickSort>(cfg, input);
    SortMetrics b = measure_one<QuickSort>(cfg, input);
    CHECK(a.comparisons == b.comparisons);
    CHECK(a.swaps == b.swaps);
    CHECK(a.total_steps == b.total_steps);
    CHECK(a.correct);
    CHECK(a.comparisons > 0);
}

TEST_CASE("measure_one reports sane metric relationships") {
    SortBenchConfig cfg;
    cfg.n = 1000;
    cfg.trials = 2;
    const auto input = make_input(cfg);

    SortMetrics bubble = measure_one<BubbleSort>(cfg, input);
    SortMetrics quick = measure_one<QuickSort>(cfg, input);

    CHECK(bubble.correct);
    CHECK(quick.correct);
    // On 1000 random elements, O(n^2) bubble must do far more comparisons than
    // O(n log n) quicksort — a coarse but reliable algorithmic-complexity check.
    CHECK(bubble.comparisons > quick.comparisons);
    // writes accounting: Set steps + 2 per swap, never negative.
    CHECK(bubble.writes >= 2 * bubble.swaps);
    CHECK(quick.total_steps > 0);
    CHECK(quick.msteps_per_sec > 0.0);
}
