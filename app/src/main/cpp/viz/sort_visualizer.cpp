#include "sort_visualizer.h"

#include "../algoviz/sort_registry.h"

#include "imgui.h"

#include <algorithm>
#include <numeric>
#include <random>

namespace viz {

using algoviz::Step;
using algoviz::StepKind;

SortVisualizer::SortVisualizer() { reset(); }

void SortVisualizer::reset() {
    data_.resize(static_cast<std::size_t>(size_));
    std::iota(data_.begin(), data_.end(), 1);     // a permutation of 1..size
    std::mt19937_64 rng(seed_);
    std::shuffle(data_.begin(), data_.end(), rng);
    gen_ = algoviz::make_sort_by_index(static_cast<std::size_t>(algo_idx_), data_);
    finished_ = false;
    hi_a_ = hi_b_ = pivot_ = -1;
    last_kind_ = StepKind::Pivot;
    compares_ = swaps_ = writes_ = steps_ = 0;
}

void SortVisualizer::advance(int steps) {
    for (int s = 0; s < steps; ++s) {
        if (!gen_.next()) {
            finished_ = true;
            hi_a_ = hi_b_ = pivot_ = -1;
            return;
        }
        const Step& st = gen_.value();
        ++steps_;
        last_kind_ = st.kind;
        switch (st.kind) {
            case StepKind::Compare: ++compares_; hi_a_ = st.i; hi_b_ = st.j; break;
            case StepKind::Swap:    ++swaps_;    hi_a_ = st.i; hi_b_ = st.j; break;
            case StepKind::Set:     ++writes_;   hi_a_ = st.i; hi_b_ = -1;   break;
            case StepKind::Pivot:   pivot_ = st.i;                           break;
        }
    }
}

void SortVisualizer::draw() {
    ImGuiIO& io = ImGui::GetIO();
    ImGui::SetNextWindowPos(ImVec2(0.0f, 0.0f), ImGuiCond_Always);
    ImGui::SetNextWindowSize(io.DisplaySize, ImGuiCond_Always);
    ImGui::Begin("AlgoViz", nullptr,
                 ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize |
                 ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoTitleBar |
                 ImGuiWindowFlags_NoBringToFrontOnFocus);

    const auto names = algoviz::all_sort_names();
    const auto count = static_cast<int>(names.size());
    if (algo_idx_ >= count) algo_idx_ = 0;

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

    ImGui::SliderInt("Speed", &speed_, 1, 512, "%d steps/frame");
    if (ImGui::SliderInt("Size", &size_, 16, 400, "%d bars")) reset();

    ImGui::Text("%s   compares %lld   swaps %lld   writes %lld   steps %lld   %s",
                names[static_cast<std::size_t>(algo_idx_)],
                compares_, swaps_, writes_, steps_,
                finished_ ? "[DONE]" : (playing_ ? "[playing]" : "[paused]"));

    // Advance AFTER reading controls so a Size/Reset change this frame starts
    // clean, and BEFORE drawing so highlights match the rendered array.
    if (playing_ && !finished_) advance(speed_);

    draw_bars();
    ImGui::End();
}

void SortVisualizer::draw_bars() {
    const ImVec2 origin = ImGui::GetCursorScreenPos();
    const ImVec2 avail = ImGui::GetContentRegionAvail();
    const int n = static_cast<int>(data_.size());
    if (avail.y < 8.0f || avail.x < 4.0f || n <= 0) return;

    ImDrawList* dl = ImGui::GetWindowDrawList();
    const float bar_w = avail.x / static_cast<float>(n);
    const float gap = bar_w > 3.0f ? 1.0f : 0.0f;
    const float maxv = static_cast<float>(n);   // values are 1..n

    const ImU32 col_default = ImGui::GetColorU32(ImVec4(0.42f, 0.55f, 0.88f, 1.0f));
    const ImU32 col_compare = ImGui::GetColorU32(ImVec4(0.96f, 0.85f, 0.22f, 1.0f));
    const ImU32 col_write   = ImGui::GetColorU32(ImVec4(0.95f, 0.32f, 0.26f, 1.0f));
    const ImU32 col_pivot   = ImGui::GetColorU32(ImVec4(0.70f, 0.45f, 0.95f, 1.0f));
    const ImU32 col_done    = ImGui::GetColorU32(ImVec4(0.30f, 0.85f, 0.42f, 1.0f));

    for (int i = 0; i < n; ++i) {
        const float h = (static_cast<float>(at(i)) / maxv) * avail.y;
        const float x0 = origin.x + static_cast<float>(i) * bar_w;
        const float x1 = x0 + bar_w - gap;
        const float y1 = origin.y + avail.y;
        const float y0 = y1 - h;

        ImU32 c = col_default;
        if (finished_) {
            c = col_done;
        } else if (i == hi_a_ || i == hi_b_) {
            c = (last_kind_ == StepKind::Compare) ? col_compare : col_write;
        } else if (i == pivot_) {
            c = col_pivot;
        }
        dl->AddRectFilled(ImVec2(x0, y0), ImVec2(x1, y1), c);
    }
    ImGui::Dummy(avail);   // reserve the region so ImGui layout accounts for it
}

} // namespace viz
