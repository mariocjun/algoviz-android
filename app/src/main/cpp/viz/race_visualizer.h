// RaceVisualizer — every algorithm racing the SAME shuffle, side by side.
//
// All registered sorts run simultaneously on identical input; you watch them
// pull ahead and finish in order (the fast O(n log n) sorts streak to the
// front, bubble/selection trail). The grid auto-sizes from the viewport aspect
// ratio so it fits the maximum number of algorithms in either orientation.
//
// Each lane owns a heap-allocated array (std::unique_ptr) so its coroutine's
// reference stays valid when lanes move inside the vector — the same
// captures-a-reference hazard the single view sidesteps by never moving.
#pragma once

#include "../algoviz/generator.h"
#include "../algoviz/step.h"

#include "imgui.h"

#include <cstdint>
#include <memory>
#include <vector>

namespace viz {

class AudioEngine;

class RaceVisualizer {
public:
    RaceVisualizer();

    void set_audio(AudioEngine* a) { audio_ = a; }
    void set_auto_loop(bool b) { auto_loop_ = b; }

    void update();          // advance all lanes once per frame (+ auto-loop)
    void draw_controls();   // panel widgets
    void draw_canvas();     // grid + gestures
    float controls_height() const;

private:
    struct Lane {
        std::unique_ptr<std::vector<int>> data;
        algoviz::Generator<algoviz::Step> gen;
        const char* name = "";
        bool finished = false;
        long long steps = 0;
        int hi_a = -1;
        int hi_b = -1;
        int finish_rank = 0;   // 0 = still running; else finishing order (1-based)
        int last_value = 0;
    };

    void reset();
    void advance();
    void draw_grid(const ImVec2& origin, const ImVec2& avail);
    static void grid_dims(int n, float aspect, int& cols, int& rows);

    std::vector<Lane> lanes_;
    int size_ = 64;
    int speed_ = 6;
    bool playing_ = true;
    bool auto_loop_ = true;
    int finished_count_ = 0;
    float finished_timer_ = 0.0f;
    std::uint64_t seed_ = 0xBADC0DEULL;
    int audio_rr_ = 0;

    AudioEngine* audio_ = nullptr;
};

} // namespace viz
