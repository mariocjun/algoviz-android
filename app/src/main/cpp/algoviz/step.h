// A single observable step of a sorting algorithm.
//
// Sort coroutines mutate their array in place AND co_yield a Step describing
// each observable operation. The step stream is a faithful *mutation log*: a
// consumer that starts from the same initial array and applies every Swap/Set
// in order ends up with the identical sorted array (Compare/Pivot are pure
// highlights and mutate nothing). That invariant is what lets the Phase 2
// visual layer animate the exact same run the benchmark measured, and it is
// unit-tested directly (tests/test_sorts.cpp "step replay").
#pragma once

#include <cstdint>

namespace algoviz {

enum class StepKind : std::uint8_t {
    Compare,  // read a[i] and a[j] to order them — no mutation
    Swap,     // exchanged a[i] and a[j]
    Set,      // wrote `value` into a[i] (merge/insertion write-back)
    Pivot,    // marks a[i] as the chosen pivot — viz highlight, no mutation
};

struct Step {
    StepKind kind;
    int i;      // primary index
    int j;      // secondary index (Compare/Swap); -1 when unused
    int value;  // Set: the value written; 0 otherwise

    static Step compare(int i, int j) { return Step{StepKind::Compare, i, j, 0}; }
    static Step swap(int i, int j)    { return Step{StepKind::Swap, i, j, 0}; }
    static Step set(int i, int v)     { return Step{StepKind::Set, i, -1, v}; }
    static Step pivot(int i)          { return Step{StepKind::Pivot, i, -1, 0}; }
};

} // namespace algoviz
