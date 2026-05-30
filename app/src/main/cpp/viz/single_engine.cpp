#include "single_engine.h"

#include "../algoviz/sort_registry.h"

#include <algorithm>
#include <numeric>
#include <random>

namespace viz {

using algoviz::Step;
using algoviz::StepKind;

namespace {
constexpr float kAutoLoopDelay = 1.4f;
constexpr std::uint64_t kLcgA = 6364136223846793005ULL;
constexpr std::uint64_t kLcgC = 1442695040888963407ULL;

int clamp_int(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}

SingleEngine::SingleEngine() { reset(); }

void SingleEngine::rebuild_generator() {
    gen_ = algoviz::make_sort_by_index(static_cast<std::size_t>(algo_idx_), data_);
    mirror_ = data_;
    finished_ = false;
    finished_timer_ = 0.0f;
    hi_a_ = hi_b_ = -1;
    compares_ = swaps_ = writes_ = steps_ = 0;
    undo_.clear();
    redo_.clear();
    slow_accum_ = 0.0f;
}

void SingleEngine::reset() {
    data_.resize(static_cast<std::size_t>(size_));
    std::iota(data_.begin(), data_.end(), 1);
    std::mt19937_64 rng(seed_);
    std::shuffle(data_.begin(), data_.end(), rng);
    rebuild_generator();
}

void SingleEngine::shuffle() {
    seed_ = seed_ * kLcgA + kLcgC;
    reset();
}

void SingleEngine::set_algorithm(int idx) {
    algo_idx_ = clamp_int(idx, 0, static_cast<int>(algoviz::sort_count()) - 1);
    reset();
}

void SingleEngine::set_size(int n) {
    size_ = clamp_int(n, 16, 400);
    reset();
}

void SingleEngine::set_speed(int s) { speed_ = clamp_int(s, 1, 512); }

void SingleEngine::set_slow_period_ms(int ms) {
    slow_period_ = ms <= 0 ? 0.0f : static_cast<float>(ms) / 1000.0f;
    slow_accum_ = 0.0f;
}

void SingleEngine::set_draw_mode(bool d) {
    draw_mode_ = d;
    if (draw_mode_) {
        playing_ = false;
    } else {
        rebuild_generator();   // sort whatever was drawn
        playing_ = true;
    }
}

void SingleEngine::paint(int idx, float v01) {
    if (idx < 0 || idx >= static_cast<int>(data_.size())) return;
    if (v01 < 0.0f) v01 = 0.0f;
    if (v01 > 1.0f) v01 = 1.0f;
    data_[static_cast<std::size_t>(idx)] = 1 + static_cast<int>(v01 * static_cast<float>(size_ - 1));
}

void SingleEngine::account(const Step& s, int delta) {
    switch (s.kind) {
        case StepKind::Compare: compares_ += delta; hi_a_ = s.i; hi_b_ = s.j; break;
        case StepKind::Swap:    swaps_ += delta;    hi_a_ = s.i; hi_b_ = s.j; break;
        case StepKind::Set:     writes_ += delta;   hi_a_ = s.i; hi_b_ = -1;  break;
        case StepKind::Pivot:                       hi_a_ = s.i; hi_b_ = -1;  break;
    }
    steps_ += delta;
    if (compares_ < 0) compares_ = 0;
    if (swaps_ < 0) swaps_ = 0;
    if (writes_ < 0) writes_ = 0;
    if (steps_ < 0) steps_ = 0;
}

bool SingleEngine::step_forward() {
    if (!redo_.empty()) {
        const HistStep h = redo_.back();
        redo_.pop_back();
        const Step& s = h.step;
        if (s.kind == StepKind::Swap) {
            std::swap(data_[static_cast<std::size_t>(s.i)], data_[static_cast<std::size_t>(s.j)]);
            std::swap(mirror_[static_cast<std::size_t>(s.i)], mirror_[static_cast<std::size_t>(s.j)]);
        } else if (s.kind == StepKind::Set) {
            data_[static_cast<std::size_t>(s.i)] = s.value;
            mirror_[static_cast<std::size_t>(s.i)] = s.value;
        }
        undo_.push_back(h);
        if (undo_.size() > kMaxHistory) undo_.pop_front();
        account(s, +1);
        return true;
    }
    if (!gen_.next()) {
        finished_ = true;
        hi_a_ = hi_b_ = -1;
        return false;
    }
    const Step s = gen_.value();   // the coroutine already mutated data_
    HistStep h{s, 0};
    if (s.kind == StepKind::Set) h.prev = mirror_[static_cast<std::size_t>(s.i)];
    if (s.kind == StepKind::Swap) {
        std::swap(mirror_[static_cast<std::size_t>(s.i)], mirror_[static_cast<std::size_t>(s.j)]);
    } else if (s.kind == StepKind::Set) {
        mirror_[static_cast<std::size_t>(s.i)] = s.value;
    }
    undo_.push_back(h);
    if (undo_.size() > kMaxHistory) undo_.pop_front();
    account(s, +1);
    return true;
}

void SingleEngine::step_back() {
    if (undo_.empty()) return;
    const HistStep h = undo_.back();
    undo_.pop_back();
    const Step& s = h.step;
    if (s.kind == StepKind::Swap) {
        std::swap(data_[static_cast<std::size_t>(s.i)], data_[static_cast<std::size_t>(s.j)]);
        std::swap(mirror_[static_cast<std::size_t>(s.i)], mirror_[static_cast<std::size_t>(s.j)]);
    } else if (s.kind == StepKind::Set) {
        data_[static_cast<std::size_t>(s.i)] = h.prev;
        mirror_[static_cast<std::size_t>(s.i)] = h.prev;
    }
    redo_.push_back(h);
    finished_ = false;
    finished_timer_ = 0.0f;
    account(s, -1);
}

void SingleEngine::flag_note() {
    if (hi_a_ >= 0 && hi_a_ < static_cast<int>(data_.size())) {
        note01_ = static_cast<float>(at(hi_a_)) / static_cast<float>(size_);
        note_pending_ = true;
    }
}

float SingleEngine::consume_note() {
    if (!note_pending_) return -1.0f;
    note_pending_ = false;
    return note01_;
}

void SingleEngine::step_forward_one() {
    playing_ = false;
    if (step_forward()) flag_note();
}

void SingleEngine::step_back_one() {
    playing_ = false;
    step_back();
    flag_note();
}

void SingleEngine::update(float dt_seconds) {
    if (draw_mode_) return;
    if (playing_ && !finished_) {
        if (slow_period_ > 0.0f) {              // slow mode: 1 step per period
            slow_accum_ += dt_seconds;
            bool stepped = false;
            while (slow_accum_ >= slow_period_) {
                slow_accum_ -= slow_period_;
                if (!step_forward()) break;
                stepped = true;
            }
            if (stepped) flag_note();
        } else {
            for (int s = 0; s < speed_; ++s) {
                if (!step_forward()) break;
            }
            flag_note();
        }
    }
    if (finished_ && auto_loop_) {
        finished_timer_ += dt_seconds;
        if (finished_timer_ >= kAutoLoopDelay) {
            if (loop_random_) shuffle(); else reset();   // replay same ordering unless random
        }
    }
}

} // namespace viz
