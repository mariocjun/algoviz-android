// SortVisualizer — single-algorithm view.
//
// Split for the dockable/collapsible layout VizApp owns:
//   update()        — advance playback once/frame (+ audio, auto-loop)
//   draw_controls() — the panel widgets (combo, transport ◀▶, steppers, draw)
//   draw_canvas()   — the bar field as an InvisibleButton + gestures
//
// Back-stepping (◀): coroutines are forward-only and the sort mutates the array
// itself before yielding, so the value a Set overwrote is already gone by the
// time we see the Step. We keep a lockstep `mirror_` copy advanced one step at a
// time, so just before applying a Set we can read the previous value from
// mirror_ and record it for undo. undo_/redo_ give a bounded scrub.
#pragma once

#include "../algoviz/generator.h"
#include "../algoviz/step.h"

#include <cstdint>
#include <deque>
#include <vector>

namespace viz {

class AudioEngine;

class SortVisualizer {
public:
    SortVisualizer();

    void set_audio(AudioEngine* a) { audio_ = a; }
    void set_auto_loop(bool b) { auto_loop_ = b; }

    void update();          // advance playback (once per frame)
    void draw_controls();   // panel widgets; assumes an active ImGui window/child
    void draw_canvas();     // bar field + gestures; assumes an active window/child
    float controls_height() const;  // pixels the control panel needs (for docking)

private:
    struct HistStep {
        algoviz::Step step;
        int prev;   // value overwritten by a Set (for undo); unused otherwise
    };

    void reset();               // iota + shuffle, then rebuild
    void rebuild_generator();   // generator over current data_, clear undo/redo
    bool step_forward();        // apply one step; false at completion
    void step_back();           // undo one step
    void account(const algoviz::Step& s, int delta);  // counters + highlight (+1 fwd / -1 back)
    void play_current_note();
    int at(int i) const { return data_[static_cast<std::size_t>(i)]; }

    std::vector<int> data_;
    std::vector<int> mirror_;   // lockstep copy: recovers Set-overwritten values
    algoviz::Generator<algoviz::Step> gen_;

    int algo_idx_ = 3;          // default: quick
    int size_ = 96;
    int speed_ = 8;
    bool playing_ = true;
    bool finished_ = false;
    bool auto_loop_ = true;
    bool draw_mode_ = false;    // paint-your-own-array
    float finished_timer_ = 0.0f;
    std::uint64_t seed_ = 0xC0FFEEULL;

    int hi_a_ = -1;
    int hi_b_ = -1;

    long long compares_ = 0;
    long long swaps_ = 0;
    long long writes_ = 0;
    long long steps_ = 0;

    static constexpr std::size_t kMaxHistory = 20000;  // bounded back-scrub
    std::deque<HistStep> undo_;
    std::vector<HistStep> redo_;

    AudioEngine* audio_ = nullptr;
};

} // namespace viz
