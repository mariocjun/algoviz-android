#include "viz_engine.h"

#include "audio_engine.h"

namespace viz {

void VizEngine::set_sound(bool e) { if (audio_ != nullptr) audio_->set_enabled(e); }
void VizEngine::set_volume(float v) { if (audio_ != nullptr) audio_->set_volume(v); }
void VizEngine::set_scale(int i) { if (audio_ != nullptr) audio_->set_scale(i); }
void VizEngine::celebrate() { if (audio_ != nullptr) audio_->celebrate(); }

void VizEngine::update(float dt_seconds) {
    float note;
    if (mode_ == 0) {
        single_.update(dt_seconds);
        note = single_.consume_note();
    } else {
        race_.update(dt_seconds);
        note = race_.consume_note();
    }
    if (note >= 0.0f && audio_ != nullptr) audio_->note(note);
}

int VizEngine::fill(int* buf, int cap) {
    if (mode_ == 0) {
        const std::vector<int>& d = single_.data();
        const int n = static_cast<int>(d.size());
        const int need = 10 + n;
        if (cap < need) return 0;
        buf[0] = 0;
        buf[1] = n;
        buf[2] = single_.hi_a();
        buf[3] = single_.hi_b();
        buf[4] = single_.finished() ? 1 : 0;
        buf[5] = static_cast<int>(single_.compares());
        buf[6] = static_cast<int>(single_.swaps());
        buf[7] = static_cast<int>(single_.writes());
        buf[8] = static_cast<int>(single_.total_steps());
        buf[9] = single_.draw_mode() ? 1 : 0;
        for (int i = 0; i < n; ++i) buf[10 + i] = d[static_cast<std::size_t>(i)];
        return need;
    }

    const int L = race_.lane_count();
    const int n = race_.size();
    if (L <= 0) return 0;
    const int need = 3 + 4 * L + L * n;
    if (cap < need) return 0;
    buf[0] = 1;
    buf[1] = L;
    buf[2] = n;
    for (int k = 0; k < L; ++k) {
        const int base = 3 + 4 * k;
        buf[base + 0] = race_.lane_finished(k) ? 1 : 0;
        buf[base + 1] = race_.lane_rank(k);
        buf[base + 2] = race_.lane_hi_a(k);
        buf[base + 3] = race_.lane_hi_b(k);
    }
    int off = 3 + 4 * L;
    for (int k = 0; k < L; ++k) {
        const std::vector<int>& d = race_.lane_data(k);
        const int m = static_cast<int>(d.size());
        for (int i = 0; i < n; ++i) buf[off++] = (i < m) ? d[static_cast<std::size_t>(i)] : 0;
    }
    return need;
}

} // namespace viz
