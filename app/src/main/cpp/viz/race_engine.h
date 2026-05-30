// RaceEngine — headless model for the race view: every algorithm sorts the same
// shuffle simultaneously. No ImGui/GLES/audio coupling (audio via consume_note),
// so the Compose UI reads lane_data()/ranks/highlights to render and the logic
// is host-unit-testable. Each lane owns a heap array (unique_ptr) so its
// coroutine's reference survives the lane vector moving.
#pragma once

#include "../algoviz/generator.h"
#include "../algoviz/step.h"

#include <cstdint>
#include <memory>
#include <vector>

namespace viz {

class RaceEngine {
public:
    RaceEngine();

    void set_size(int n);          // clamp + reset
    void set_speed(int s);
    void set_slow_period_ms(int ms);   // 0 = off; else 1 step / ms (slow mode)
    void set_input_mode(int mode);     // 0 = fair (same shuffle); 1 = worst case (reversed)
    void set_playing(bool p) { playing_ = p; }
    void toggle_play() { playing_ = !playing_; }
    void set_auto_loop(bool b) { auto_loop_ = b; }
    void reset();                  // rebuild lanes from current seed
    void shuffle();                // advance seed + reset
    void update(float dt_seconds);

    int lane_count() const { return static_cast<int>(lanes_.size()); }
    int size() const { return size_; }
    int speed() const { return speed_; }
    bool playing() const { return playing_; }
    int finished_count() const { return finished_count_; }

    const std::vector<int>& lane_data(int k) const { return *lanes_[static_cast<std::size_t>(k)].data; }
    bool lane_finished(int k) const { return lanes_[static_cast<std::size_t>(k)].finished; }
    int lane_rank(int k) const { return lanes_[static_cast<std::size_t>(k)].rank; }
    int lane_hi_a(int k) const { return lanes_[static_cast<std::size_t>(k)].hi_a; }
    int lane_hi_b(int k) const { return lanes_[static_cast<std::size_t>(k)].hi_b; }
    const char* lane_name(int k) const { return lanes_[static_cast<std::size_t>(k)].name; }

    float consume_note();

private:
    struct Lane {
        std::unique_ptr<std::vector<int>> data;
        algoviz::Generator<algoviz::Step> gen;
        const char* name = "";
        bool finished = false;
        int rank = 0;
        int hi_a = -1;
        int hi_b = -1;
        int last_value = 0;
    };

    void advance(int steps);

    std::vector<Lane> lanes_;
    int size_ = 64;
    int speed_ = 6;
    int input_mode_ = 0;        // 0 = fair (same shuffle), 1 = worst case (reversed)
    float slow_period_ = 0.0f;  // seconds per single step in slow mode (0 = off)
    float slow_accum_ = 0.0f;
    bool playing_ = true;
    bool auto_loop_ = true;
    int finished_count_ = 0;
    float finished_timer_ = 0.0f;
    std::uint64_t seed_ = 0xBADC0DEULL;
    int audio_rr_ = 0;
    bool note_pending_ = false;
    float note01_ = -1.0f;
};

} // namespace viz
