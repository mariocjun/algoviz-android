#include "race_visualizer.h"

#include "audio_engine.h"
#include "palette.h"
#include "widgets.h"

#include "../algoviz/sort_registry.h"

#include "imgui.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <numeric>
#include <random>
#include <string>

namespace viz {

using algoviz::Step;
using algoviz::StepKind;

namespace {
constexpr float kAutoLoopDelay = 2.2f;

ImU32 rank_color(int rank) {
    switch (rank) {
        case 1:  return ImGui::GetColorU32(ImVec4(1.00f, 0.84f, 0.20f, 1.0f));  // gold
        case 2:  return ImGui::GetColorU32(ImVec4(0.80f, 0.82f, 0.86f, 1.0f));  // silver
        case 3:  return ImGui::GetColorU32(ImVec4(0.80f, 0.52f, 0.25f, 1.0f));  // bronze
        default: return ImGui::GetColorU32(ImVec4(0.85f, 0.88f, 0.95f, 1.0f));
    }
}
} // namespace

RaceVisualizer::RaceVisualizer() { reset(); }

void RaceVisualizer::reset() {
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

void RaceVisualizer::advance() {
    for (Lane& L : lanes_) {
        if (L.finished) continue;
        for (int s = 0; s < speed_; ++s) {
            if (!L.gen.next()) {
                L.finished = true;
                L.finish_rank = ++finished_count_;
                L.hi_a = L.hi_b = -1;
                break;
            }
            const Step& st = L.gen.value();
            ++L.steps;
            if (st.kind == StepKind::Compare || st.kind == StepKind::Swap) {
                L.hi_a = st.i; L.hi_b = st.j;
            } else {
                L.hi_a = st.i; L.hi_b = -1;     // Set / Pivot
            }
            const int idx = st.i >= 0 ? st.i : 0;
            if (idx < static_cast<int>(L.data->size())) {
                L.last_value = (*L.data)[static_cast<std::size_t>(idx)];
            }
        }
    }

    // Sparse, musical audio: a single note per frame, round-robin over the
    // lanes still running (a wall of 6 simultaneous note streams would be noise).
    if (audio_ != nullptr) {
        const int n = static_cast<int>(lanes_.size());
        for (int t = 0; t < n; ++t) {
            const int idx = (audio_rr_ + t) % n;
            if (!lanes_[static_cast<std::size_t>(idx)].finished) {
                audio_->note(static_cast<float>(lanes_[static_cast<std::size_t>(idx)].last_value) /
                             static_cast<float>(size_));
                audio_rr_ = (idx + 1) % n;
                break;
            }
        }
    }
}

void RaceVisualizer::grid_dims(int n, float aspect, int& cols, int& rows) {
    if (n <= 0) { cols = 1; rows = 1; return; }
    // Pick the column count that fits all n lanes with the fewest empty cells
    // and the most square-ish cell for the current viewport aspect. This shrinks
    // to fit in either orientation: 8 packs as 2x4 (portrait) / 4x2 (landscape)
    // with no wasted space; 6 as 2x3 / 3x2; etc.
    int best_cols = 1;
    double best_score = 1e9;
    for (int c = 1; c <= n; ++c) {
        const int r = (n + c - 1) / c;
        const int empties = c * r - n;
        const double cell_aspect =
            static_cast<double>(aspect) * static_cast<double>(r) / static_cast<double>(c);
        const double score = static_cast<double>(empties) * 4.0 + std::fabs(std::log(cell_aspect));
        if (score < best_score) {
            best_score = score;
            best_cols = c;
        }
    }
    cols = best_cols;
    rows = (n + best_cols - 1) / best_cols;
}

void RaceVisualizer::update() {
    if (playing_) advance();
    if (finished_count_ >= static_cast<int>(lanes_.size()) && auto_loop_) {
        finished_timer_ += ImGui::GetIO().DeltaTime;
        if (finished_timer_ >= kAutoLoopDelay) {
            seed_ = seed_ * 6364136223846793005ULL + 1442695040888963407ULL;
            reset();
        }
    }
}

float RaceVisualizer::controls_height() const {
    return 4.4f * ImGui::GetFrameHeightWithSpacing();   // transport + speed + size + podium(wrap)
}

void RaceVisualizer::draw_controls() {
    if (ImGui::Button(playing_ ? "Pause" : "Play")) playing_ = !playing_;
    ImGui::SameLine();
    if (ImGui::Button("Reset")) reset();
    ImGui::SameLine();
    if (ImGui::Button("Shuffle")) {
        seed_ = seed_ * 6364136223846793005ULL + 1442695040888963407ULL;
        reset();
    }
    param_int("Speed", &speed_, 1, 256, 1, 220.0f);
    if (param_int("Size", &size_, 16, 256, 8, 220.0f)) reset();

    std::string board = "Racing...";
    if (finished_count_ > 0) {
        board = "Podium: ";
        for (int rank = 1; rank <= finished_count_; ++rank) {
            for (const Lane& L : lanes_) {
                if (L.finish_rank == rank) {
                    board += std::to_string(rank) + "." + L.name + "  ";
                    break;
                }
            }
        }
    }
    ImGui::TextWrapped("%s", board.c_str());
}

void RaceVisualizer::draw_canvas() {
    const ImVec2 origin = ImGui::GetCursorScreenPos();
    const ImVec2 avail = ImGui::GetContentRegionAvail();
    if (avail.x < 24.0f || avail.y < 24.0f) return;

    ImGui::InvisibleButton("##racecanvas", avail);
    const bool hovered = ImGui::IsItemHovered();
    const bool active = ImGui::IsItemActive();
    ImGuiIO& io = ImGui::GetIO();
    if (hovered && ImGui::IsMouseDoubleClicked(ImGuiMouseButton_Left)) {
        playing_ = !playing_;
    }
    if (active && std::fabs(io.MouseDelta.x) > 1.0f &&
        std::fabs(io.MouseDelta.x) > std::fabs(io.MouseDelta.y)) {
        speed_ += static_cast<int>(io.MouseDelta.x * 0.3f);
        if (speed_ < 1) speed_ = 1;
        if (speed_ > 256) speed_ = 256;
    }

    draw_grid(origin, avail);
}

void RaceVisualizer::draw_grid(const ImVec2& origin, const ImVec2& avail) {
    const int n = static_cast<int>(lanes_.size());
    if (n == 0 || avail.x < 24.0f || avail.y < 24.0f) return;

    int cols = 1, rows = 1;
    grid_dims(n, avail.x / avail.y, cols, rows);
    const float cw = avail.x / static_cast<float>(cols);
    const float chh = avail.y / static_cast<float>(rows);

    ImDrawList* dl = ImGui::GetWindowDrawList();
    const ImU32 border = ImGui::GetColorU32(ImVec4(0.28f, 0.33f, 0.45f, 1.0f));
    const ImU32 white = white_u32();
    const float pad = 5.0f;
    const float th = ImGui::GetTextLineHeight();

    for (int i = 0; i < n; ++i) {
        const Lane& L = lanes_[static_cast<std::size_t>(i)];
        const int gc = i % cols;
        const int gr = i / cols;
        const float cx0 = origin.x + static_cast<float>(gc) * cw;
        const float cy0 = origin.y + static_cast<float>(gr) * chh;
        const float cx1 = cx0 + cw;
        const float cy1 = cy0 + chh;

        dl->AddRect(ImVec2(cx0 + 1.0f, cy0 + 1.0f), ImVec2(cx1 - 1.0f, cy1 - 1.0f), border, 5.0f);

        char title[64];
        if (L.finished) {
            std::snprintf(title, sizeof(title), "%s  #%d", L.name, L.finish_rank);
        } else {
            std::snprintf(title, sizeof(title), "%s", L.name);
        }
        dl->AddText(ImVec2(cx0 + pad + 2.0f, cy0 + pad),
                    L.finished ? rank_color(L.finish_rank) : white, title);

        const float bx0 = cx0 + pad;
        const float bx1 = cx1 - pad;
        const float by1 = cy1 - pad;
        const float by0 = cy0 + pad + th + 2.0f;
        const int m = static_cast<int>(L.data->size());
        if (bx1 - bx0 < 2.0f || by1 - by0 < 4.0f || m <= 0) continue;

        const float bw = (bx1 - bx0) / static_cast<float>(m);
        const float maxv = static_cast<float>(m);
        for (int k = 0; k < m; ++k) {
            const float value01 = static_cast<float>((*L.data)[static_cast<std::size_t>(k)]) / maxv;
            const float bh = value01 * (by1 - by0);
            const float x0 = bx0 + static_cast<float>(k) * bw;
            const float x1 = x0 + (bw > 2.0f ? bw - 0.5f : bw);
            ImU32 c;
            if (!L.finished && (k == L.hi_a || k == L.hi_b)) {
                c = white;
            } else if (L.finished) {
                c = hue_color(value01, 0.78f, 1.0f);
            } else {
                c = hue_color(value01, 0.58f, 0.90f);
            }
            dl->AddRectFilled(ImVec2(x0, by1 - bh), ImVec2(x1, by1), c);
        }
    }
}

} // namespace viz
