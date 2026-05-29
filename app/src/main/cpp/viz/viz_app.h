// VizApp — top-level visualizer controller.
//
// Owns the two views (single + race) and renders the fullscreen ImGui window
// with a top control bar (mode toggle, sound, volume, auto-loop), then
// delegates the body to the active view. The AudioEngine itself lives in the
// JNI layer (its lifecycle follows the Activity's resume/pause); VizApp only
// holds a borrowed pointer to wire it into the views and the sound controls.
#pragma once

#include "race_visualizer.h"
#include "sort_visualizer.h"

namespace viz {

class AudioEngine;

class VizApp {
public:
    explicit VizApp(AudioEngine* audio);
    void draw();   // full ImGui frame body (one fullscreen window)

private:
    AudioEngine* audio_;
    SortVisualizer single_;
    RaceVisualizer race_;
    int mode_ = 0;          // 0 = single, 1 = race
    bool sound_ = true;
    float volume_ = 0.6f;
    bool auto_loop_ = true;
};

} // namespace viz
