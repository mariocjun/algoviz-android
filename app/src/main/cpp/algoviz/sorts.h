// Clean-room sorting algorithms expressed as step-yielding coroutines.
//
// Each function mutates its `std::vector<int>&` in place and co_yields a Step
// for every comparison, swap, and write it performs (see step.h for the
// mutation-log invariant). They are textbook implementations written from the
// algorithm definitions — no third-party source was copied.
//
// Recursion is avoided on purpose: recursive coroutines would allocate a frame
// per call and need manual pumping at every level. Quicksort uses an explicit
// range stack and merge sort is bottom-up iterative; heap sort composes a
// child `heap_sift` generator (pumped by the parent) to show generator
// composition without duplicating the sift-down body.
//
// All indices are kept as `int` (Step carries int) with a single typed
// accessor lambda per function so element access stays free of
// signed/unsigned conversion noise under -Wsign-conversion.
#pragma once

#include "generator.h"
#include "step.h"

#include <algorithm>
#include <utility>
#include <vector>

namespace algoviz {

inline Generator<Step> bubble_sort(std::vector<int>& a) {
    auto A = [&a](int k) -> int& { return a[static_cast<std::size_t>(k)]; };
    const int n = static_cast<int>(a.size());
    for (int i = 0; i < n - 1; ++i) {
        bool swapped = false;
        for (int j = 0; j < n - 1 - i; ++j) {
            co_yield Step::compare(j, j + 1);
            if (A(j) > A(j + 1)) {
                std::swap(A(j), A(j + 1));
                co_yield Step::swap(j, j + 1);
                swapped = true;
            }
        }
        if (!swapped) break;  // input already ordered from here on
    }
}

inline Generator<Step> insertion_sort(std::vector<int>& a) {
    auto A = [&a](int k) -> int& { return a[static_cast<std::size_t>(k)]; };
    const int n = static_cast<int>(a.size());
    for (int i = 1; i < n; ++i) {
        const int key = A(i);
        int j = i - 1;
        while (j >= 0) {
            co_yield Step::compare(j, i);  // a[j] vs the key lifted from i
            if (A(j) <= key) break;
            A(j + 1) = A(j);
            co_yield Step::set(j + 1, A(j + 1));
            --j;
        }
        A(j + 1) = key;
        co_yield Step::set(j + 1, key);
    }
}

inline Generator<Step> selection_sort(std::vector<int>& a) {
    auto A = [&a](int k) -> int& { return a[static_cast<std::size_t>(k)]; };
    const int n = static_cast<int>(a.size());
    for (int i = 0; i < n - 1; ++i) {
        int min_idx = i;
        for (int j = i + 1; j < n; ++j) {
            co_yield Step::compare(j, min_idx);
            if (A(j) < A(min_idx)) min_idx = j;
        }
        if (min_idx != i) {
            std::swap(A(i), A(min_idx));
            co_yield Step::swap(i, min_idx);
        }
    }
}

inline Generator<Step> quick_sort(std::vector<int>& a) {
    auto A = [&a](int k) -> int& { return a[static_cast<std::size_t>(k)]; };
    const int n = static_cast<int>(a.size());
    if (n < 2) co_return;

    // Explicit range stack instead of recursion. Push the larger sub-range
    // first so the smaller one is processed next — bounds the stack at O(log n).
    std::vector<std::pair<int, int>> ranges;
    ranges.emplace_back(0, n - 1);
    while (!ranges.empty()) {
        const auto [lo, hi] = ranges.back();
        ranges.pop_back();
        if (lo >= hi) continue;

        // Lomuto partition around the last element.
        co_yield Step::pivot(hi);
        const int pivot = A(hi);
        int store = lo - 1;
        for (int j = lo; j < hi; ++j) {
            co_yield Step::compare(j, hi);
            if (A(j) < pivot) {
                ++store;
                if (store != j) {
                    std::swap(A(store), A(j));
                    co_yield Step::swap(store, j);
                }
            }
        }
        const int p = store + 1;
        if (p != hi) {
            std::swap(A(p), A(hi));
            co_yield Step::swap(p, hi);
        }

        const int left = p - lo;        // size of [lo, p-1]
        const int right = hi - p;       // size of [p+1, hi]
        if (left > right) {
            ranges.emplace_back(lo, p - 1);
            ranges.emplace_back(p + 1, hi);
        } else {
            ranges.emplace_back(p + 1, hi);
            ranges.emplace_back(lo, p - 1);
        }
    }
}

inline Generator<Step> merge_sort(std::vector<int>& a) {
    auto A = [&a](int k) -> int& { return a[static_cast<std::size_t>(k)]; };
    auto B = [](std::vector<int>& buf, int k) -> int& {
        return buf[static_cast<std::size_t>(k)];
    };
    const int n = static_cast<int>(a.size());
    if (n < 2) co_return;

    std::vector<int> buf(a.size());
    // Bottom-up: merge runs of width 1, 2, 4, ... until one run covers all.
    for (int width = 1; width < n; width *= 2) {
        for (int lo = 0; lo < n; lo += 2 * width) {
            const int mid = std::min(lo + width, n);
            const int hi = std::min(lo + 2 * width, n);
            int i = lo, j = mid, k = lo;
            while (i < mid && j < hi) {
                co_yield Step::compare(i, j);
                if (A(i) <= A(j)) B(buf, k++) = A(i++);
                else              B(buf, k++) = A(j++);
            }
            while (i < mid) B(buf, k++) = A(i++);
            while (j < hi)  B(buf, k++) = A(j++);
            for (int t = lo; t < hi; ++t) {
                A(t) = B(buf, t);
                co_yield Step::set(t, A(t));
            }
        }
    }
}

// Sift the element at `root` down into the max-heap occupying [0, end).
// A child generator so heap_sort doesn't duplicate the sift-down body for its
// build and extraction phases; the parent pumps it with `while(g.next())`.
inline Generator<Step> heap_sift(std::vector<int>& a, int root, int end) {
    auto A = [&a](int k) -> int& { return a[static_cast<std::size_t>(k)]; };
    for (;;) {
        int child = 2 * root + 1;
        if (child >= end) break;
        if (child + 1 < end) {
            co_yield Step::compare(child, child + 1);
            if (A(child) < A(child + 1)) ++child;  // pick the larger child
        }
        co_yield Step::compare(root, child);
        if (A(root) >= A(child)) break;
        std::swap(A(root), A(child));
        co_yield Step::swap(root, child);
        root = child;
    }
}

inline Generator<Step> heap_sort(std::vector<int>& a) {
    auto A = [&a](int k) -> int& { return a[static_cast<std::size_t>(k)]; };
    const int n = static_cast<int>(a.size());
    if (n < 2) co_return;

    // Build a max-heap bottom-up.
    for (int start = n / 2 - 1; start >= 0; --start) {
        auto g = heap_sift(a, start, n);
        while (g.next()) co_yield g.value();
    }
    // Repeatedly move the max to the end and restore the heap on the prefix.
    for (int end = n - 1; end > 0; --end) {
        std::swap(A(0), A(end));
        co_yield Step::swap(0, end);
        auto g = heap_sift(a, 0, end);
        while (g.next()) co_yield g.value();
    }
}

// Shell sort: gapped insertion. Halving gap sequence (n/2, n/4, ... 1); within
// each gap it's insertion sort over the strided subsequence. Distinct, springy
// visual (elements leap across the array on the big gaps).
inline Generator<Step> shell_sort(std::vector<int>& a) {
    auto A = [&a](int k) -> int& { return a[static_cast<std::size_t>(k)]; };
    const int n = static_cast<int>(a.size());
    for (int gap = n / 2; gap > 0; gap /= 2) {
        for (int i = gap; i < n; ++i) {
            const int key = A(i);
            int j = i;
            while (j >= gap) {
                co_yield Step::compare(j - gap, i);   // a[j-gap] vs the lifted key
                if (A(j - gap) <= key) break;
                A(j) = A(j - gap);
                co_yield Step::set(j, A(j));
                j -= gap;
            }
            A(j) = key;
            co_yield Step::set(j, key);
        }
    }
}

// Cocktail shaker sort: bidirectional bubble. Each round bubbles the largest to
// the right, then the smallest to the left, shrinking the active window from
// both ends — a back-and-forth sweep distinct from plain bubble.
inline Generator<Step> cocktail_sort(std::vector<int>& a) {
    auto A = [&a](int k) -> int& { return a[static_cast<std::size_t>(k)]; };
    const int n = static_cast<int>(a.size());
    int lo = 0;
    int hi = n - 1;
    bool swapped = true;
    while (swapped && lo < hi) {
        swapped = false;
        for (int j = lo; j < hi; ++j) {
            co_yield Step::compare(j, j + 1);
            if (A(j) > A(j + 1)) {
                std::swap(A(j), A(j + 1));
                co_yield Step::swap(j, j + 1);
                swapped = true;
            }
        }
        --hi;
        for (int j = hi; j > lo; --j) {
            co_yield Step::compare(j - 1, j);
            if (A(j - 1) > A(j)) {
                std::swap(A(j - 1), A(j));
                co_yield Step::swap(j - 1, j);
                swapped = true;
            }
        }
        ++lo;
    }
}

} // namespace algoviz
