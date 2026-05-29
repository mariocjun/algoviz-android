// SortVisualizer — the Phase 2 visual layer's model + ImGui view.
//
// It drives the EXACT Phase 1 coroutines: the algorithm picker enumerates the
// SortAlgo registry (algoviz::all_sort_names) and instantiates the chosen sort
// via algoviz::make_sort_by_index, then pulls Step values from the generator to
// animate. Each frame it advances `speed` steps (when playing), tracks the most
// recent compare/swap/pivot indices for highlighting, and draws bars whose
// height is the element value.
//
// This header stays ImGui-free (only <vector>/<cstdint> + the generator), so
// translation units that merely reference the class don't pull in the vendored
// ImGui headers; sort_visualizer.cpp is the only place that includes imgui.h.
#pragma once

#include "../algoviz/generator.h"
#include "../algoviz/step.h"

#include <cstdint>
#include <vector>

namespace viz {

class SortVisualizer {
public:
    SortVisualizer();

    // Build this frame's ImGui UI (controls + bar chart) and, if playing,
    // advance the animation. Call once per rendered frame, inside an active
    // ImGui frame (between ImGui::NewFrame and ImGui::Render).
    void draw();

private:
    void reset();             // rebuild the shuffled array + generator for algo_idx_
    void advance(int steps);  // pull up to `steps` Steps, updating highlights/counters
    void draw_bars();         // bar chart via the window draw list
    int at(int i) const { return data_[static_cast<std::size_t>(i)]; }

    std::vector<int> data_;
    algoviz::Generator<algoviz::Step> gen_;

    int algo_idx_ = 3;        // default selection (quick) — index into the registry
    int size_ = 96;           // element/bar count
    int speed_ = 8;           // Steps advanced per frame while playing
    bool playing_ = true;
    bool finished_ = false;
    std::uint64_t seed_ = 0xC0FFEEULL;

    // Highlight state from the most recent Step(s) this frame.
    int hi_a_ = -1;
    int hi_b_ = -1;
    int pivot_ = -1;
    algoviz::StepKind last_kind_ = algoviz::StepKind::Pivot;

    // Live operation counters (display only).
    long long compares_ = 0;
    long long swaps_ = 0;
    long long writes_ = 0;
    long long steps_ = 0;
};

} // namespace viz
