// Small reusable ImGui control widgets for the visualizer panel.
//
// param_int / param_float render a "stepper + slider" row:  [-] [==slider==] [+] Label
// — the slider for coarse moves, the -/+ buttons for precise single steps (the
// brief: "quero que seja slide mas tbm com + e -"). Returns true when the value
// changed this frame.
#pragma once

#include "imgui.h"

namespace viz {

inline bool param_int(const char* label, int* v, int lo, int hi, int step, float slider_w) {
    bool changed = false;
    ImGui::PushID(label);
    if (ImGui::Button("-")) { *v -= step; if (*v < lo) *v = lo; changed = true; }
    ImGui::SameLine();
    ImGui::SetNextItemWidth(slider_w);
    if (ImGui::SliderInt("##slider", v, lo, hi)) changed = true;
    ImGui::SameLine();
    if (ImGui::Button("+")) { *v += step; if (*v > hi) *v = hi; changed = true; }
    ImGui::SameLine();
    ImGui::TextUnformatted(label);
    ImGui::PopID();
    return changed;
}

inline bool param_float(const char* label, float* v, float lo, float hi, float step,
                        float slider_w, const char* fmt) {
    bool changed = false;
    ImGui::PushID(label);
    if (ImGui::Button("-")) { *v -= step; if (*v < lo) *v = lo; changed = true; }
    ImGui::SameLine();
    ImGui::SetNextItemWidth(slider_w);
    if (ImGui::SliderFloat("##slider", v, lo, hi, fmt)) changed = true;
    ImGui::SameLine();
    if (ImGui::Button("+")) { *v += step; if (*v > hi) *v = hi; changed = true; }
    ImGui::SameLine();
    ImGui::TextUnformatted(label);
    ImGui::PopID();
    return changed;
}

} // namespace viz
