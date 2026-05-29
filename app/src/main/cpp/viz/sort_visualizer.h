// SortVisualizer — single-algorithm view (Phase 2, extended in Phase 3).
//
// Drives the EXACT Phase 1 coroutines: the algorithm picker enumerates the
// SortAlgo registry and instantiates the chosen sort via make_sort_by_index,
// then pulls Step values to animate. Bars are colored by value (rainbow), the
// most-recent access is voiced through the AudioEngine (one note/frame so it
// stays musical at any speed), and on completion it auto-loops with a fresh
// shuffle — an endless, satisfying ambient.
//
// draw_body() renders into the CALLER's ImGui window (VizApp owns the window),
// so this no longer calls Begin/End.
#pragma once

#include "../algoviz/generator.h"
#include "../algoviz/step.h"

#include <cstdint>
#include <vector>

namespace viz {

class AudioEngine;

class SortVisualizer {
public:
    SortVisualizer();

    void set_audio(AudioEngine* a) { audio_ = a; }
    void set_auto_loop(bool b) { auto_loop_ = b; }

    void draw_body();   // controls + rainbow bars; assumes an active ImGui window

private:
    void reset();
    void advance(int steps);
    void draw_bars();
    int at(int i) const { return data_[static_cast<std::size_t>(i)]; }

    std::vector<int> data_;
    algoviz::Generator<algoviz::Step> gen_;

    int algo_idx_ = 3;        // default selection (quick)
    int size_ = 96;
    int speed_ = 8;
    bool playing_ = true;
    bool finished_ = false;
    bool auto_loop_ = true;
    float finished_timer_ = 0.0f;
    std::uint64_t seed_ = 0xC0FFEEULL;

    int hi_a_ = -1;
    int hi_b_ = -1;
    bool stepped_this_frame_ = false;

    long long compares_ = 0;
    long long swaps_ = 0;
    long long writes_ = 0;
    long long steps_ = 0;

    AudioEngine* audio_ = nullptr;
};

} // namespace viz
