#include "race_engine.h"

#include "../algoviz/sort_registry.h"

#include <algorithm>
#include <numeric>
#include <random>

namespace viz {

using algoviz::Step;
using algoviz::StepKind;

namespace {
constexpr float kAutoLoopDelay = 2.2f;
constexpr std::uint64_t kLcgA = 6364136223846793005ULL;
constexpr std::uint64_t kLcgC = 1442695040888963407ULL;

int clamp_int(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}

RaceEngine::RaceEngine() { reset(); }

void RaceEngine::reset() {
    lanes_.clear();
    std::vector<int> base(static_cast<std::size_t>(size_));
    std::iota(base.begin(), base.end(), 1);
    std::mt19937_64 rng(seed_);
    std::shuffle(base.begin(), base.end(), rng);

    const auto names = algoviz::all_sort_names();
    const std::size_t cnt = algoviz::sort_count();
    lanes_.reserve(cnt);
    for (std::size_t i = 0; i < cnt; ++i) {
        Lane lane;
        lane.data = std::make_unique<std::vector<int>>(base);   // same shuffle for all
        lane.name = names[i];
        lane.gen = algoviz::make_sort_by_index(i, *lane.data);
        lanes_.push_back(std::move(lane));
    }
    finished_count_ = 0;
    finished_timer_ = 0.0f;
}

void RaceEngine::shuffle() {
    seed_ = seed_ * kLcgA + kLcgC;
    reset();
}

void RaceEngine::set_size(int n) {
    size_ = clamp_int(n, 16, 256);
    reset();
}

void RaceEngine::set_speed(int s) { speed_ = clamp_int(s, 1, 256); }

void RaceEngine::advance() {
    for (Lane& L : lanes_) {
        if (L.finished) continue;
        for (int s = 0; s < speed_; ++s) {
            if (!L.gen.next()) {
                L.finished = true;
                L.rank = ++finished_count_;
                L.hi_a = L.hi_b = -1;
                break;
            }
            const Step& st = L.gen.value();
            if (st.kind == StepKind::Compare || st.kind == StepKind::Swap) {
                L.hi_a = st.i; L.hi_b = st.j;
            } else {
                L.hi_a = st.i; L.hi_b = -1;
            }
            const int idx = st.i >= 0 ? st.i : 0;
            if (idx < static_cast<int>(L.data->size())) {
                L.last_value = (*L.data)[static_cast<std::size_t>(idx)];
            }
        }
    }
    // Sparse, musical audio: one note/frame, round-robin over running lanes.
    const int n = static_cast<int>(lanes_.size());
    for (int t = 0; t < n; ++t) {
        const int idx = (audio_rr_ + t) % n;
        if (!lanes_[static_cast<std::size_t>(idx)].finished) {
            note01_ = static_cast<float>(lanes_[static_cast<std::size_t>(idx)].last_value) /
                      static_cast<float>(size_);
            note_pending_ = true;
            audio_rr_ = (idx + 1) % n;
            break;
        }
    }
}

float RaceEngine::consume_note() {
    if (!note_pending_) return -1.0f;
    note_pending_ = false;
    return note01_;
}

void RaceEngine::update(float dt_seconds) {
    if (playing_) advance();
    if (finished_count_ >= static_cast<int>(lanes_.size()) && auto_loop_) {
        finished_timer_ += dt_seconds;
        if (finished_timer_ >= kAutoLoopDelay) shuffle();
    }
}

} // namespace viz
