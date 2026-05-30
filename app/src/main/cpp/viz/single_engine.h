// SingleEngine — headless single-algorithm visualizer model.
//
// All the state + stepping/undo/draw logic of the single view, with NO ImGui,
// GLES, or audio dependency — so the native Compose UI just drives it and reads
// data()/highlights/stats to render, and so the back-step/undo correctness is
// host-unit-testable (tests/test_single_engine.cpp). Audio is decoupled: the
// engine flags a note value via consume_note(); the owner (JNI layer) plays it.
//
// Back-stepping: coroutines are forward-only and the sort mutates the array
// before yielding, so a lockstep `mirror_` copy recovers the value a Set
// overwrote, letting steps invert exactly. undo_/redo_ give a bounded scrub.
#pragma once

#include "../algoviz/generator.h"
#include "../algoviz/step.h"

#include <cstdint>
#include <deque>
#include <vector>

namespace viz {

class SingleEngine {
public:
    SingleEngine();

    // --- controls ---
    void set_algorithm(int idx);    // clamps + reshuffles
    void set_size(int n);           // clamps + reshuffles
    void set_speed(int s);
    void set_slow_period_ms(int ms);   // 0 = off (use speed); else 1 step / ms
    void set_auto_loop(bool b) { auto_loop_ = b; }
    void set_playing(bool p) { playing_ = p; }
    void toggle_play() { playing_ = !playing_; }
    void reset();                   // reshuffle (same size/algo)
    void shuffle();                 // advance seed + reshuffle
    void set_draw_mode(bool d);
    void paint(int idx, float v01); // author a bar height (draw mode)
    void step_forward_one();        // pause + one step forward
    void step_back_one();           // pause + one step back
    void update(float dt_seconds);  // advance `speed` steps if playing; auto-loop

    // --- accessors (render / JNI) ---
    int size() const { return size_; }
    int algorithm() const { return algo_idx_; }
    int speed() const { return speed_; }
    bool playing() const { return playing_; }
    bool draw_mode() const { return draw_mode_; }
    bool finished() const { return finished_; }
    int hi_a() const { return hi_a_; }
    int hi_b() const { return hi_b_; }
    long long compares() const { return compares_; }
    long long swaps() const { return swaps_; }
    long long writes() const { return writes_; }
    long long total_steps() const { return steps_; }
    const std::vector<int>& data() const { return data_; }

    // Audio hook: returns a note value in [0,1] once if a step occurred since
    // the last call, else -1.
    float consume_note();

private:
    struct HistStep { algoviz::Step step; int prev; };

    void rebuild_generator();
    bool step_forward();
    void step_back();
    void account(const algoviz::Step& s, int delta);
    void flag_note();
    int at(int i) const { return data_[static_cast<std::size_t>(i)]; }

    std::vector<int> data_;
    std::vector<int> mirror_;
    algoviz::Generator<algoviz::Step> gen_;

    int algo_idx_ = 3;
    int size_ = 96;
    int speed_ = 8;
    float slow_period_ = 0.0f;   // seconds between single steps (0 = off / use speed_)
    float slow_accum_ = 0.0f;    // time accumulator for slow mode
    bool playing_ = true;
    bool finished_ = false;
    bool auto_loop_ = true;
    bool draw_mode_ = false;
    float finished_timer_ = 0.0f;
    std::uint64_t seed_ = 0xC0FFEEULL;

    int hi_a_ = -1;
    int hi_b_ = -1;

    long long compares_ = 0;
    long long swaps_ = 0;
    long long writes_ = 0;
    long long steps_ = 0;

    bool note_pending_ = false;
    float note01_ = -1.0f;

    static constexpr std::size_t kMaxHistory = 20000;
    std::deque<HistStep> undo_;
    std::vector<HistStep> redo_;
};

} // namespace viz
