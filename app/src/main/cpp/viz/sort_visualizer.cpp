#include "sort_visualizer.h"

#include "audio_engine.h"
#include "palette.h"

#include "../algoviz/sort_registry.h"

#include "imgui.h"

#include <algorithm>
#include <numeric>
#include <random>

namespace viz {

using algoviz::Step;
using algoviz::StepKind;

namespace {
constexpr float kAutoLoopDelay = 1.4f;   // seconds to admire the sorted rainbow
}

SortVisualizer::SortVisualizer() { reset(); }

void SortVisualizer::reset() {
    data_.resize(static_cast<std::size_t>(size_));
    std::iota(data_.begin(), data_.end(), 1);     // permutation of 1..size
    std::mt19937_64 rng(seed_);
    std::shuffle(data_.begin(), data_.end(), rng);
    gen_ = algoviz::make_sort_by_index(static_cast<std::size_t>(algo_idx_), data_);
    finished_ = false;
    finished_timer_ = 0.0f;
    hi_a_ = hi_b_ = -1;
    compares_ = swaps_ = writes_ = steps_ = 0;
}

void SortVisualizer::advance(int steps) {
    stepped_this_frame_ = false;
    for (int s = 0; s < steps; ++s) {
        if (!gen_.next()) {
            finished_ = true;
            hi_a_ = hi_b_ = -1;
            return;
        }
        const Step& st = gen_.value();
        ++steps_;
        stepped_this_frame_ = true;
        switch (st.kind) {
            case StepKind::Compare: ++compares_; hi_a_ = st.i; hi_b_ = st.j; break;
            case StepKind::Swap:    ++swaps_;    hi_a_ = st.i; hi_b_ = st.j; break;
            case StepKind::Set:     ++writes_;   hi_a_ = st.i; hi_b_ = -1;   break;
            case StepKind::Pivot:   hi_a_ = st.i;                            break;
        }
    }
}

void SortVisualizer::draw_body() {
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

    if (ImGui::Button(playing_ ? "Pause" : "Play")) playing_ = !playing_;
    ImGui::SameLine();
    if (ImGui::Button("Step")) { playing_ = false; advance(1); }
    ImGui::SameLine();
    if (ImGui::Button("Reset")) reset();
    ImGui::SameLine();
    if (ImGui::Button("Shuffle")) {
        seed_ = seed_ * 6364136223846793005ULL + 1442695040888963407ULL;
        reset();
    }

    ImGui::SetNextItemWidth(260.0f);
    ImGui::SliderInt("Speed", &speed_, 1, 512, "%d steps/frame");
    ImGui::SetNextItemWidth(260.0f);
    if (ImGui::SliderInt("Size", &size_, 16, 400, "%d bars")) reset();

    ImGui::Text("%s   compares %lld   swaps %lld   writes %lld   steps %lld   %s",
                names[static_cast<std::size_t>(algo_idx_)],
                compares_, swaps_, writes_, steps_,
                finished_ ? "[DONE]" : (playing_ ? "[playing]" : "[paused]"));

    if (playing_ && !finished_) advance(speed_);

    // One note per frame keeps the sound musical at any speed (pitch = the most
    // recently accessed value, mapped to a pentatonic scale by the engine).
    if (audio_ != nullptr && playing_ && stepped_this_frame_ && hi_a_ >= 0 &&
        hi_a_ < static_cast<int>(data_.size())) {
        audio_->note(static_cast<float>(at(hi_a_)) / static_cast<float>(size_));
    }

    // Auto-loop: linger on the finished rainbow, then reshuffle and run again.
    if (finished_ && auto_loop_) {
        finished_timer_ += ImGui::GetIO().DeltaTime;
        if (finished_timer_ >= kAutoLoopDelay) {
            seed_ = seed_ * 6364136223846793005ULL + 1442695040888963407ULL;
            reset();
        }
    }

    draw_bars();
}

void SortVisualizer::draw_bars() {
    const ImVec2 origin = ImGui::GetCursorScreenPos();
    const ImVec2 avail = ImGui::GetContentRegionAvail();
    const int n = static_cast<int>(data_.size());
    if (avail.y < 8.0f || avail.x < 4.0f || n <= 0) return;

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
        const float y0 = y1 - h;

        ImU32 c;
        if (!finished_ && (i == hi_a_ || i == hi_b_)) {
            c = white;                          // active comparison/swap playhead
        } else if (finished_) {
            c = hue_color(value01, 0.75f, 1.0f);  // vivid sorted rainbow
        } else {
            c = hue_color(value01, 0.60f, 0.92f);
        }
        dl->AddRectFilled(ImVec2(x0, y0), ImVec2(x1, y1), c);
    }
    ImGui::Dummy(avail);
}

} // namespace viz
