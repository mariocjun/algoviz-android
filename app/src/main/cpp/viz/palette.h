// Shared color helpers for the visualizers. Bars are colored by VALUE (not
// position), so a sorted array resolves into a smooth left-to-right rainbow —
// the satisfying "payoff" frame. Hue spans red (low) -> violet (high).
#pragma once

#include "imgui.h"

namespace viz {

inline ImU32 hue_color(float value01, float sat, float val) {
    if (value01 < 0.0f) value01 = 0.0f;
    if (value01 > 1.0f) value01 = 1.0f;
    float r = 0.0f, g = 0.0f, b = 0.0f;
    ImGui::ColorConvertHSVtoRGB(value01 * 0.82f, sat, val, r, g, b);
    return ImGui::GetColorU32(ImVec4(r, g, b, 1.0f));
}

inline ImU32 white_u32() { return ImGui::GetColorU32(ImVec4(1.0f, 1.0f, 1.0f, 1.0f)); }

} // namespace viz
