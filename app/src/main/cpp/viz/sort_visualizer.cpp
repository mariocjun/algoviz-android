#include "sort_visualizer.h"

#include "audio_engine.h"
#include "palette.h"
#include "widgets.h"

#include "../algoviz/sort_registry.h"

#include "imgui.h"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <random>

namespace viz {

using algoviz::Step;
using algoviz::StepKind;

namespace {
constexpr float kAutoLoopDelay = 1.4f;
constexpr std::uint64_t kLcgA = 6364136223846793005ULL;
constexpr std::uint64_t kLcgC = 1442695040888963407ULL;
}

SortVisualizer::SortVisualizer() { reset(); }

void SortVisualizer::rebuild_generator() {
    gen_ = algoviz::make_sort_by_index(static_cast<std::size_t>(algo_idx_), data_);
    mirror_ = data_;
    finished_ = false;
    finished_timer_ = 0.0f;
    hi_a_ = hi_b_ = -1;
    compares_ = swaps_ = writes_ = steps_ = 0;
    undo_.clear();
    redo_.clear();
}

void SortVisualizer::reset() {
    data_.resize(static_cast<std::size_t>(size_));
    std::iota(data_.begin(), data_.end(), 1);
    std::mt19937_64 rng(seed_);
    std::shuffle(data_.begin(), data_.end(), rng);
    rebuild_generator();
}

void SortVisualizer::account(const Step& s, int delta) {
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

bool SortVisualizer::step_forward() {
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
    if (s.kind == StepKind::Set) {
        h.prev = mirror_[static_cast<std::size_t>(s.i)];   // mirror_ is the pre-step state
    }
    // Bring mirror_ into sync with data_.
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

void SortVisualizer::step_back() {
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

void SortVisualizer::play_current_note() {
    if (audio_ != nullptr && hi_a_ >= 0 && hi_a_ < static_cast<int>(data_.size())) {
        audio_->note(static_cast<float>(at(hi_a_)) / static_cast<float>(size_));
    }
}

void SortVisualizer::update() {
    if (draw_mode_) return;   // playback is paused while authoring the array
    if (playing_ && !finished_) {
        for (int s = 0; s < speed_; ++s) {
            if (!step_forward()) break;
        }
        play_current_note();
    }
    if (finished_ && auto_loop_) {
        finished_timer_ += ImGui::GetIO().DeltaTime;
        if (finished_timer_ >= kAutoLoopDelay) {
            seed_ = seed_ * kLcgA + kLcgC;
            reset();
        }
    }
}

float SortVisualizer::controls_height() const {
    // combo + transport + speed + size + stats ~= 5 rows.
    return 5.4f * ImGui::GetFrameHeightWithSpacing();
}

void SortVisualizer::draw_controls() {
    const auto names = algoviz::all_sort_names();
    const auto count = static_cast<int>(names.size());
    if (algo_idx_ >= count) algo_idx_ = 0;

    ImGui::SetNextItemWidth(220.0f);
    if (ImGui::BeginCombo("Algorithm", names[static_cast<std::size_t>(algo_idx_)])) {
        for (int i = 0; i < count; ++i) {
            const bool selected = (i == algo_idx_);
            if (ImGui::Selectable(names[static_cast<std::size_t>(i)], selected)) {
                algo_idx_ = i;
                reset();
            }
            if (selected) ImGui::SetItemDefaultFocus();
        }
        ImGui::EndCombo();
    }

    // Transport: Play/Pause + step back/forward (◀ ▶ via ASCII so the default
    // ProggyClean font always renders them) + Reset/Shuffle + Draw toggle.
    if (ImGui::Button(playing_ ? "Pause" : "Play")) playing_ = !playing_;
    ImGui::SameLine();
    if (ImGui::Button(" < ")) { playing_ = false; step_back(); play_current_note(); }
    ImGui::SameLine();
    if (ImGui::Button(" > ")) { playing_ = false; if (step_forward()) play_current_note(); }
    ImGui::SameLine();
    if (ImGui::Button("Reset")) reset();
    ImGui::SameLine();
    if (ImGui::Button("Shuffle")) { seed_ = seed_ * kLcgA + kLcgC; reset(); }
    ImGui::SameLine();
    if (ImGui::Button(draw_mode_ ? "Sort drawn" : "Draw")) {
        draw_mode_ = !draw_mode_;
        if (draw_mode_) { playing_ = false; }   // pause while painting
        else { rebuild_generator(); playing_ = true; }   // sort what was drawn
    }

    param_int("Speed", &speed_, 1, 512, 1, 220.0f);
    if (param_int("Size", &size_, 16, 400, 8, 220.0f)) reset();

    ImGui::Text("%s   compares %lld  swaps %lld  writes %lld  steps %lld   %s",
                names[static_cast<std::size_t>(algo_idx_)],
                compares_, swaps_, writes_, steps_,
                draw_mode_ ? "[draw]" : finished_ ? "[done]" : playing_ ? "[play]" : "[paused]");
}

void SortVisualizer::draw_canvas() {
    const ImVec2 origin = ImGui::GetCursorScreenPos();
    const ImVec2 avail = ImGui::GetContentRegionAvail();
    const int n = static_cast<int>(data_.size());
    if (avail.x < 8.0f || avail.y < 8.0f || n <= 0) return;

    ImGui::InvisibleButton("##canvas", avail);
    const bool hovered = ImGui::IsItemHovered();
    const bool active = ImGui::IsItemActive();
    ImGuiIO& io = ImGui::GetIO();

    // Double-tap anywhere on the field toggles play/pause.
    if (hovered && ImGui::IsMouseDoubleClicked(ImGuiMouseButton_Left)) {
        playing_ = !playing_;
    }

    if (active) {
        if (draw_mode_) {
            // Paint: x -> bar index, y -> value (top = max). Drag to draw a curve.
            const int idx = static_cast<int>((io.MousePos.x - origin.x) / avail.x * static_cast<float>(n));
            if (idx >= 0 && idx < n) {
                float t = 1.0f - (io.MousePos.y - origin.y) / avail.y;
                if (t < 0.0f) t = 0.0f;
                if (t > 1.0f) t = 1.0f;
                data_[static_cast<std::size_t>(idx)] = 1 + static_cast<int>(t * static_cast<float>(n - 1));
            }
        } else if (std::fabs(io.MouseDelta.x) > 1.0f &&
                   std::fabs(io.MouseDelta.x) > std::fabs(io.MouseDelta.y)) {
            // Horizontal swipe scrubs playback speed.
            speed_ += static_cast<int>(io.MouseDelta.x * 0.35f);
            if (speed_ < 1) speed_ = 1;
            if (speed_ > 512) speed_ = 512;
        }
    }

    ImDrawList* dl = ImGui::GetWindowDrawList();
    const float bar_w = avail.x / static_cast<float>(n);
    const float gap = bar_w > 3.0f ? 1.0f : 0.0f;
    const float maxv = static_cast<float>(n);
    const ImU32 white = white_u32();

    for (int i = 0; i < n; ++i) {
        const float value01 = static_cast<float>(at(i)) / maxv;
        const float h = value01 * avail.y;
        const float x0 = origin.x + static_cast<float>(i) * bar_w;
        const float x1 = x0 + bar_w - gap;
        const float y1 = origin.y + avail.y;
        ImU32 c;
        if (!finished_ && !draw_mode_ && (i == hi_a_ || i == hi_b_)) {
            c = white;
        } else if (finished_) {
            c = hue_color(value01, 0.75f, 1.0f);
        } else {
            c = hue_color(value01, 0.60f, 0.92f);
        }
        dl->AddRectFilled(ImVec2(x0, y1 - h), ImVec2(x1, y1), c);
    }
}

} // namespace viz
