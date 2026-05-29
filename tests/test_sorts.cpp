// Correctness tests for the AlgoViz coroutine sort engine.
//
// These exercise the REAL production headers (algoviz/generator.h, sorts.h) —
// the engine is NDK-free by design, so the host CI validates the exact code
// that runs on-device, not a reimplementation. Coverage:
//   - Generator<Step> pull semantics (lazy, move-only, terminates cleanly).
//   - Every algorithm sorts every distribution (random/sorted/reverse/equal/
//     tiny/empty) and agrees with std::sort.
//   - Known comparison/swap counts for the deterministic cases.
//   - The step-replay invariant: applying the Swap/Set log to a fresh copy of
//     the input reproduces the sorted array — the contract Phase 2's renderer
//     relies on.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "algoviz/generator.h"
#include "algoviz/sorts.h"
#include "algoviz/step.h"

#include <algorithm>
#include <functional>
#include <numeric>
#include <random>
#include <vector>

using namespace algoviz;

namespace {

// Drain a sort coroutine, returning the recorded step log. `data` is sorted in
// place by the coroutine as a side effect.
std::vector<Step> drain(Generator<Step> g) {
    std::vector<Step> steps;
    while (g.next()) steps.push_back(g.value());
    return steps;
}

// Apply a step log to `arr`, mutating it the same way the coroutine mutated its
// own array. Compare/Pivot are pure highlights (no mutation).
void replay(std::vector<int>& arr, const std::vector<Step>& steps) {
    for (const Step& s : steps) {
        switch (s.kind) {
            case StepKind::Swap:
                std::swap(arr[static_cast<std::size_t>(s.i)],
                          arr[static_cast<std::size_t>(s.j)]);
                break;
            case StepKind::Set:
                arr[static_cast<std::size_t>(s.i)] = s.value;
                break;
            case StepKind::Compare:
            case StepKind::Pivot:
                break;
        }
    }
}

using SortFn = std::function<Generator<Step>(std::vector<int>&)>;

struct Named { const char* name; SortFn fn; };

std::vector<Named> all_algorithms() {
    return {
        {"bubble",    [](std::vector<int>& a) { return bubble_sort(a); }},
        {"cocktail",  [](std::vector<int>& a) { return cocktail_sort(a); }},
        {"insertion", [](std::vector<int>& a) { return insertion_sort(a); }},
        {"shell",     [](std::vector<int>& a) { return shell_sort(a); }},
        {"selection", [](std::vector<int>& a) { return selection_sort(a); }},
        {"quick",     [](std::vector<int>& a) { return quick_sort(a); }},
        {"merge",     [](std::vector<int>& a) { return merge_sort(a); }},
        {"heap",      [](std::vector<int>& a) { return heap_sort(a); }},
    };
}

} // namespace

TEST_CASE("Generator yields values lazily and terminates") {
    // A tiny inline coroutine exercising the pull protocol directly.
    auto count_to = [](int n) -> Generator<Step> {
        for (int i = 0; i < n; ++i) co_yield Step::set(i, i * 10);
    };
    auto g = count_to(3);
    REQUIRE(g.next());
    CHECK(g.value().i == 0);
    CHECK(g.value().value == 0);
    REQUIRE(g.next());
    CHECK(g.value().i == 1);
    CHECK(g.value().value == 10);
    REQUIRE(g.next());
    CHECK(g.value().i == 2);
    CHECK(g.value().value == 20);
    CHECK_FALSE(g.next());   // exhausted
    CHECK(g.done());
    CHECK_FALSE(g.next());   // idempotent past the end
}

TEST_CASE("Generator on an empty coroutine yields nothing") {
    auto empty = []() -> Generator<Step> { co_return; };
    auto g = empty();
    CHECK_FALSE(g.next());
    CHECK(g.done());
}

TEST_CASE("Generator is move-only and the moved-from handle is inert") {
    auto one = []() -> Generator<Step> { co_yield Step::compare(1, 2); };
    auto g = one();
    auto h = std::move(g);
    CHECK(g.done());          // moved-from: empty handle
    CHECK_FALSE(g.next());
    REQUIRE(h.next());        // ownership transferred to h
    CHECK(h.value().i == 1);
    CHECK(h.value().j == 2);
}

TEST_CASE("every algorithm sorts every distribution and matches std::sort") {
    std::mt19937_64 rng(12345);
    std::uniform_int_distribution<int> dist(-1000, 1000);

    for (const auto& algo : all_algorithms()) {
        CAPTURE(algo.name);

        std::vector<std::vector<int>> inputs;
        inputs.push_back({});                      // empty
        inputs.push_back({42});                    // single
        inputs.push_back({2, 1});                  // two, reversed
        inputs.push_back({1, 2, 3, 4, 5, 6, 7});   // already sorted
        inputs.push_back({7, 6, 5, 4, 3, 2, 1});   // reverse sorted
        inputs.push_back({5, 5, 5, 5, 5});         // all equal
        inputs.push_back({3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5}); // dupes
        {
            std::vector<int> r(257);
            for (int& x : r) x = dist(rng);
            inputs.push_back(std::move(r));        // larger random
        }

        for (auto& in : inputs) {
            CAPTURE(in.size());
            std::vector<int> expected = in;
            std::sort(expected.begin(), expected.end());

            std::vector<int> work = in;
            (void)drain(algo.fn(work));
            CHECK(work == expected);
        }
    }
}

TEST_CASE("step replay reproduces the sorted array (viz contract)") {
    std::mt19937_64 rng(777);
    std::uniform_int_distribution<int> dist(0, 500);

    for (const auto& algo : all_algorithms()) {
        CAPTURE(algo.name);
        std::vector<int> original(129);
        for (int& x : original) x = dist(rng);

        std::vector<int> work = original;
        std::vector<Step> log = drain(algo.fn(work));   // work is now sorted

        // Replaying the log onto a fresh copy of the original must land on the
        // exact same array the coroutine produced.
        std::vector<int> replayed = original;
        replay(replayed, log);
        CHECK(replayed == work);
        CHECK(std::is_sorted(replayed.begin(), replayed.end()));
    }
}

TEST_CASE("step indices stay in bounds") {
    std::vector<int> data = {9, 3, 7, 1, 8, 2, 6, 4, 5, 0};
    const int n = static_cast<int>(data.size());
    for (const auto& algo : all_algorithms()) {
        CAPTURE(algo.name);
        std::vector<int> work = data;
        for (const Step& s : drain(algo.fn(work))) {
            CHECK(s.i >= 0);
            CHECK(s.i < n);
            if (s.kind == StepKind::Compare || s.kind == StepKind::Swap) {
                CHECK(s.j >= 0);
                CHECK(s.j < n);
            }
        }
    }
}

TEST_CASE("selection sort does exactly n(n-1)/2 comparisons regardless of input") {
    // Selection sort's comparison count is input-independent: the inner loop
    // always scans the full unsorted suffix.
    for (int n : {1, 2, 5, 10, 50}) {
        std::vector<int> data(static_cast<std::size_t>(n));
        std::iota(data.rbegin(), data.rend(), 0);   // reverse sorted

        std::int64_t comparisons = 0;
        std::vector<int> work = data;
        for (const Step& s : drain(selection_sort(work))) {
            if (s.kind == StepKind::Compare) ++comparisons;
        }
        CHECK(comparisons == static_cast<std::int64_t>(n) * (n - 1) / 2);
    }
}

TEST_CASE("bubble sort early-exits on already-sorted input") {
    // One full pass of n-1 comparisons, no swaps, then the swapped flag stops it.
    std::vector<int> sorted = {1, 2, 3, 4, 5, 6, 7, 8};
    std::int64_t comparisons = 0, swaps = 0;
    std::vector<int> work = sorted;
    for (const Step& s : drain(bubble_sort(work))) {
        if (s.kind == StepKind::Compare) ++comparisons;
        if (s.kind == StepKind::Swap) ++swaps;
    }
    CHECK(swaps == 0);
    CHECK(comparisons == 7);   // exactly one pass
}

TEST_CASE("merge sort uses Set writes and no Swaps") {
    std::vector<int> data = {5, 2, 8, 1, 9, 3, 7, 4, 6};
    std::int64_t swaps = 0, sets = 0;
    std::vector<int> work = data;
    for (const Step& s : drain(merge_sort(work))) {
        if (s.kind == StepKind::Swap) ++swaps;
        if (s.kind == StepKind::Set) ++sets;
    }
    CHECK(swaps == 0);
    CHECK(sets > 0);
}

TEST_CASE("quick sort emits a pivot marker per partitioned range") {
    std::vector<int> data = {5, 2, 8, 1, 9, 3, 7, 4, 6};
    std::int64_t pivots = 0;
    std::vector<int> work = data;
    for (const Step& s : drain(quick_sort(work))) {
        if (s.kind == StepKind::Pivot) ++pivots;
    }
    CHECK(pivots >= 1);
}
