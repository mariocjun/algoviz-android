// VizApp — top-level visualizer controller + layout owner.
//
// Owns the two views and arranges a fullscreen canvas + a control panel that
// can be DOCKED (bottom in portrait, side rail in landscape so the bars stay
// tall) and COLLAPSED to a small recognizable "≡" pill for a fullscreen view.
// The AudioEngine lives in the JNI layer; VizApp holds a borrowed pointer to
// wire it into the views and the sound controls.
#pragma once

#include "race_visualizer.h"
#include "sort_visualizer.h"

namespace viz {

class AudioEngine;

class VizApp {
public:
    explicit VizApp(AudioEngine* audio);
    void draw();

private:
    void draw_chrome();             // mode / sound / vol / loop / dock / hide
    float chrome_height() const;

    AudioEngine* audio_;
    SortVisualizer single_;
    RaceVisualizer race_;
    int mode_ = 0;            // 0 = single, 1 = race
    bool sound_ = true;
    float volume_ = 0.6f;
    bool auto_loop_ = true;
    bool panel_open_ = true;  // control panel visible
    bool dock_bottom_ = true; // portrait: bottom (true) vs top (false)
};

} // namespace viz
