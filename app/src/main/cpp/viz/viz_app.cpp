#include "viz_app.h"

#include "audio_engine.h"

#include "imgui.h"

namespace viz {

namespace {
constexpr ImGuiWindowFlags kFullFlags =
    ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoMove |
    ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoBringToFrontOnFocus |
    ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoScrollWithMouse;

constexpr ImGuiWindowFlags kCanvasFlags =
    ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoScrollWithMouse;
}

VizApp::VizApp(AudioEngine* audio) : audio_(audio) {
    single_.set_audio(audio_);
    race_.set_audio(audio_);
    single_.set_auto_loop(auto_loop_);
    race_.set_auto_loop(auto_loop_);
}

float VizApp::chrome_height() const {
    return 2.4f * ImGui::GetFrameHeightWithSpacing();   // two rows of chrome
}

void VizApp::draw_chrome() {
    ImGui::RadioButton("Single", &mode_, 0);
    ImGui::SameLine();
    ImGui::RadioButton("Race", &mode_, 1);
    ImGui::SameLine();
    if (ImGui::Checkbox("Sound", &sound_) && audio_ != nullptr) audio_->set_enabled(sound_);
    ImGui::SameLine();
    if (ImGui::Checkbox("Loop", &auto_loop_)) {
        single_.set_auto_loop(auto_loop_);
        race_.set_auto_loop(auto_loop_);
    }

    ImGui::SetNextItemWidth(140.0f);
    if (ImGui::SliderFloat("Vol", &volume_, 0.0f, 1.0f, "%.2f") && audio_ != nullptr) {
        audio_->set_volume(volume_);
    }
    ImGui::SameLine();
    if (ImGui::Button(dock_bottom_ ? "Dock^" : "Dockv")) dock_bottom_ = !dock_bottom_;
    ImGui::SameLine();
    if (ImGui::Button("Hide")) panel_open_ = false;
}

void VizApp::draw() {
    ImGuiIO& io = ImGui::GetIO();

    // Advance the active view once per frame, independent of layout/order.
    if (mode_ == 0) single_.update(); else race_.update();

    ImGui::SetNextWindowPos(ImVec2(0.0f, 0.0f), ImGuiCond_Always);
    ImGui::SetNextWindowSize(io.DisplaySize, ImGuiCond_Always);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0.0f, 0.0f));
    ImGui::Begin("##algoviz", nullptr, kFullFlags);
    ImGui::PopStyleVar();

    const ImVec2 full = ImGui::GetContentRegionAvail();
    const bool landscape = io.DisplaySize.x > io.DisplaySize.y;

    const float view_ctrl_h = (mode_ == 0) ? single_.controls_height() : race_.controls_height();
    float panel_h = chrome_height() + view_ctrl_h;
    if (panel_h > full.y * 0.55f) panel_h = full.y * 0.55f;   // never eat the canvas

    if (!panel_open_) {
        ImGui::BeginChild("canvas", full, false, kCanvasFlags);
        if (mode_ == 0) single_.draw_canvas(); else race_.draw_canvas();
        ImGui::EndChild();
    } else if (landscape) {
        float rail = full.x * 0.42f;
        if (rail < 360.0f) rail = 360.0f;
        if (rail > 620.0f) rail = 620.0f;
        ImGui::BeginChild("canvas", ImVec2(full.x - rail, full.y), false, kCanvasFlags);
        if (mode_ == 0) single_.draw_canvas(); else race_.draw_canvas();
        ImGui::EndChild();
        ImGui::SameLine(0.0f, 0.0f);
        ImGui::BeginChild("panel", ImVec2(rail, full.y), true);
        draw_chrome();
        ImGui::Separator();
        if (mode_ == 0) single_.draw_controls(); else race_.draw_controls();
        ImGui::EndChild();
    } else {  // portrait: dock top or bottom
        const ImVec2 canvas_sz(full.x, full.y - panel_h);
        const ImVec2 panel_sz(full.x, panel_h);
        if (!dock_bottom_) {
            ImGui::BeginChild("panel", panel_sz, true);
            draw_chrome();
            ImGui::Separator();
            if (mode_ == 0) single_.draw_controls(); else race_.draw_controls();
            ImGui::EndChild();
        }
        ImGui::BeginChild("canvas", canvas_sz, false, kCanvasFlags);
        if (mode_ == 0) single_.draw_canvas(); else race_.draw_canvas();
        ImGui::EndChild();
        if (dock_bottom_) {
            ImGui::BeginChild("panel", panel_sz, true);
            draw_chrome();
            ImGui::Separator();
            if (mode_ == 0) single_.draw_controls(); else race_.draw_controls();
            ImGui::EndChild();
        }
    }

    ImGui::End();

    // Collapsed: a recognizable floating pill to bring the menu back. Separate
    // top-level window so its tap can't be swallowed by the canvas child.
    if (!panel_open_) {
        ImGui::SetNextWindowPos(ImVec2(12.0f, 12.0f), ImGuiCond_Always);
        ImGui::Begin("##pill", nullptr,
                     ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize |
                     ImGuiWindowFlags_NoMove | ImGuiWindowFlags_AlwaysAutoResize |
                     ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoCollapse);
        if (ImGui::Button("= Menu")) panel_open_ = true;
        ImGui::End();
    }
}

} // namespace viz
