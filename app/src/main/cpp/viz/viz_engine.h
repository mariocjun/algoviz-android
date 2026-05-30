// VizEngine — facade over SingleEngine + RaceEngine for the native Compose UI.
// Routes controls to the active mode, advances the active engine each frame,
// plays the consumed note through the AudioEngine, and packs a per-frame
// snapshot into a caller-provided int buffer for the Compose Canvas to draw.
//
// Snapshot buffer layout (ints):
//   single (mode 0): [0, n, hi_a, hi_b, finished, compares, swaps, writes,
//                     steps, draw_mode, v0..v(n-1)]
//   race   (mode 1): [1, L, n, then L*(finished,rank,hi_a,hi_b), then L*n values]
#pragma once

#include "race_engine.h"
#include "single_engine.h"

namespace viz {

class AudioEngine;

class VizEngine {
public:
    explicit VizEngine(AudioEngine* audio) : audio_(audio) {}

    void set_mode(int m) { mode_ = (m == 1) ? 1 : 0; }
    int mode() const { return mode_; }

    void set_algorithm(int i) { single_.set_algorithm(i); }
    void set_size(int n) { if (mode_ == 0) single_.set_size(n); else race_.set_size(n); }
    void set_speed(int s) { if (mode_ == 0) single_.set_speed(s); else race_.set_speed(s); }
    void set_playing(bool p) { if (mode_ == 0) single_.set_playing(p); else race_.set_playing(p); }
    void toggle_play() { if (mode_ == 0) single_.toggle_play(); else race_.toggle_play(); }
    void reset() { if (mode_ == 0) single_.reset(); else race_.reset(); }
    void shuffle() { if (mode_ == 0) single_.shuffle(); else race_.shuffle(); }
    void step(int dir) { if (dir < 0) single_.step_back_one(); else single_.step_forward_one(); }
    void set_draw_mode(bool d) { single_.set_draw_mode(d); }
    void paint(int idx, float v01) { single_.paint(idx, v01); }
    void set_auto_loop(bool b) { single_.set_auto_loop(b); race_.set_auto_loop(b); }
    void set_sound(bool e);
    void set_volume(float v);
    void set_scale(int i);
    void celebrate();

    void update(float dt_seconds);
    int fill(int* buf, int cap);   // returns ints written, 0 if cap too small

private:
    AudioEngine* audio_;
    SingleEngine single_;
    RaceEngine race_;
    int mode_ = 0;
};

} // namespace viz
