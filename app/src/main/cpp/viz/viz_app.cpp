#include "viz_app.h"

#include "audio_engine.h"

#include "imgui.h"

namespace viz {

VizApp::VizApp(AudioEngine* audio) : audio_(audio) {
    single_.set_audio(audio_);
    race_.set_audio(audio_);
    single_.set_auto_loop(auto_loop_);
    race_.set_auto_loop(auto_loop_);
}

void VizApp::draw() {
    ImGuiIO& io = ImGui::GetIO();
    ImGui::SetNextWindowPos(ImVec2(0.0f, 0.0f), ImGuiCond_Always);
    ImGui::SetNextWindowSize(io.DisplaySize, ImGuiCond_Always);
    ImGui::Begin("AlgoViz", nullptr,
                 ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize |
                 ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoTitleBar |
                 ImGuiWindowFlags_NoBringToFrontOnFocus);

    ImGui::RadioButton("Single", &mode_, 0);
    ImGui::SameLine();
    ImGui::RadioButton("Race", &mode_, 1);
    ImGui::SameLine();
    if (ImGui::Checkbox("Sound", &sound_) && audio_ != nullptr) {
        audio_->set_enabled(sound_);
    }
    ImGui::SameLine();
    ImGui::SetNextItemWidth(150.0f);
    if (ImGui::SliderFloat("Vol", &volume_, 0.0f, 1.0f, "%.2f") && audio_ != nullptr) {
        audio_->set_volume(volume_);
    }
    ImGui::SameLine();
    if (ImGui::Checkbox("Loop", &auto_loop_)) {
        single_.set_auto_loop(auto_loop_);
        race_.set_auto_loop(auto_loop_);
    }
    ImGui::Separator();

    if (mode_ == 0) {
        single_.draw_body();
    } else {
        race_.draw_body();
    }
    ImGui::End();
}

} // namespace viz
