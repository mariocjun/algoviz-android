#include "audio_engine.h"

#include <aaudio/AAudio.h>
#include <android/log.h>

#include <cmath>

#define AUDIO_LOG_TAG "AlgoVizAudio"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, AUDIO_LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, AUDIO_LOG_TAG, __VA_ARGS__)

namespace viz {

namespace {

constexpr float kPi = 3.14159265358979323846f;

// "Bubbly water-drop" voice (ASMR). A real bubble is a resonator whose volume-
// mode frequency RISES as it shrinks, heard as a short upward pitch glide with a
// soft damped tail. So each note is a pure sine that glides up to its target
// pitch, with a low warm fundamental (the previous A3+4-octave range topped out
// near 3 kHz, which read as harsh/treble). Major pentatonic keeps any cluster
// consonant; a gentle master low-pass rounds off the last edge.
constexpr int kOctaves = 3;   // each scale spans this many octaves of the value range

// Scales as semitone offsets within one octave. Pentatonic + the seven
// Greek/church modes keep dense clusters consonant; whole-tone/blues add colour;
// chromatic is the full 12 tones. Order here == the menu order in the UI.
constexpr int kMajPent[]    = {0, 2, 4, 7, 9};
constexpr int kMinPent[]    = {0, 3, 5, 7, 10};
constexpr int kIonian[]     = {0, 2, 4, 5, 7, 9, 11};   // major
constexpr int kDorian[]     = {0, 2, 3, 5, 7, 9, 10};
constexpr int kPhrygian[]   = {0, 1, 3, 5, 7, 8, 10};
constexpr int kLydian[]     = {0, 2, 4, 6, 7, 9, 11};
constexpr int kMixolydian[] = {0, 2, 4, 5, 7, 9, 10};
constexpr int kAeolian[]    = {0, 2, 3, 5, 7, 8, 10};   // natural minor
constexpr int kLocrian[]    = {0, 1, 3, 5, 6, 8, 10};
constexpr int kWholeTone[]  = {0, 2, 4, 6, 8, 10};
constexpr int kBlues[]      = {0, 3, 5, 6, 7, 10};
constexpr int kChromatic[]  = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

struct Scale { const char* name; const int* degrees; int count; };
constexpr Scale kScales[] = {
    {"Maj Pentatonic", kMajPent, 5},
    {"Min Pentatonic", kMinPent, 5},
    {"Ionian (major)", kIonian, 7},
    {"Dorian", kDorian, 7},
    {"Phrygian", kPhrygian, 7},
    {"Lydian", kLydian, 7},
    {"Mixolydian", kMixolydian, 7},
    {"Aeolian (minor)", kAeolian, 7},
    {"Locrian", kLocrian, 7},
    {"Whole tone", kWholeTone, 6},
    {"Blues", kBlues, 6},
    {"Chromatic", kChromatic, 12},
};
constexpr int kScaleCount = static_cast<int>(sizeof(kScales) / sizeof(kScales[0]));
constexpr float kBaseHz = 98.0f;            // G2 — low/warm, range tops out ~660 Hz

constexpr float kAttackSeconds = 0.010f;    // soft, click-free onset
constexpr float kDecayTau = 0.30f;          // longer, rounder tail
constexpr float kVoiceAmp = 0.26f;
constexpr float kGlideTau = 0.045f;         // ~45 ms upward pitch glide ("bloop")
constexpr float kGlideStart = 0.7937f;      // start −4 semitones below target (2^(-4/12))
constexpr float kLowpassHz = 2600.0f;       // master one-pole LP cutoff
constexpr float kMinOnsetSeconds = 0.09f;   // >=90 ms between note onsets (~11/s
                                            // max) so notes separate, never fuse

float scale_hz(float value01, int scale_idx) {
    if (value01 < 0.0f) value01 = 0.0f;
    if (value01 > 1.0f) value01 = 1.0f;
    if (scale_idx < 0) scale_idx = 0;
    if (scale_idx > kScaleCount - 1) scale_idx = kScaleCount - 1;
    const Scale& sc = kScales[scale_idx];
    const int steps = sc.count * kOctaves;
    int idx = static_cast<int>(value01 * static_cast<float>(steps - 1) + 0.5f);
    if (idx < 0) idx = 0;
    if (idx > steps - 1) idx = steps - 1;
    const int octave = idx / sc.count;
    const int degree = idx % sc.count;
    const int semitones = octave * 12 + sc.degrees[degree];
    return kBaseHz * std::pow(2.0f, static_cast<float>(semitones) / 12.0f);
}

aaudio_data_callback_result_t data_callback(AAudioStream* /*stream*/, void* user,
                                            void* audio_data, int32_t num_frames) {
    static_cast<AudioEngine*>(user)->render(static_cast<float*>(audio_data), num_frames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

} // namespace

AudioEngine::~AudioEngine() { stop(); }

void AudioEngine::set_volume(float v) {
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    volume_.store(v, std::memory_order_relaxed);
}

bool AudioEngine::start() {
    if (running_.load(std::memory_order_relaxed)) return true;

    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) {
        ALOGE("AAudio_createStreamBuilder failed");
        return false;
    }
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(builder, data_callback, this);

    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK || stream_ == nullptr) {
        ALOGE("AAudio openStream failed: %s", AAudio_convertResultToText(r));
        stream_ = nullptr;
        return false;
    }

    // Honour what the device actually gave us (rate/channels may differ).
    sample_rate_ = AAudioStream_getSampleRate(stream_);
    channels_ = AAudioStream_getChannelCount(stream_);
    if (sample_rate_ <= 0) sample_rate_ = 48000;
    if (channels_ <= 0) channels_ = 2;

    r = AAudioStream_requestStart(stream_);
    if (r != AAUDIO_OK) {
        ALOGE("AAudio requestStart failed: %s", AAudio_convertResultToText(r));
        AAudioStream_close(stream_);
        stream_ = nullptr;
        return false;
    }
    running_.store(true, std::memory_order_relaxed);
    ALOGI("audio started: %d Hz, %d ch", sample_rate_, channels_);
    return true;
}

void AudioEngine::stop() {
    if (stream_ != nullptr) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
    running_.store(false, std::memory_order_relaxed);
    // Silence any lingering voices so a later start() begins clean.
    for (Voice& v : voices_) v.active = false;
    lp_state_ = 0.0f;
    has_pending_ = false;
    frames_since_onset_ = 1 << 20;   // let the first note after a restart fire at once
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
}

bool AudioEngine::ring_push(float f) {
    const uint32_t head = head_.load(std::memory_order_relaxed);
    const uint32_t next = (head + 1) % kRing;
    if (next == tail_.load(std::memory_order_acquire)) return false;  // full
    ring_[head] = f;
    head_.store(next, std::memory_order_release);
    return true;
}

bool AudioEngine::ring_pop(float& f) {
    const uint32_t tail = tail_.load(std::memory_order_relaxed);
    if (tail == head_.load(std::memory_order_acquire)) return false;  // empty
    f = ring_[tail];
    tail_.store((tail + 1) % kRing, std::memory_order_release);
    return true;
}

void AudioEngine::note(float value01) {
    if (!enabled_.load(std::memory_order_relaxed)) return;
    if (!running_.load(std::memory_order_relaxed)) return;
    const int scale = scale_idx_.load(std::memory_order_relaxed);
    ring_push(scale_hz(value01, scale));   // drop silently if the ring is full
}

int AudioEngine::scale_count() { return kScaleCount; }

const char* AudioEngine::scale_name(int i) {
    if (i < 0 || i >= kScaleCount) return "";
    return kScales[i].name;
}

void AudioEngine::trigger(float freq) {
    // Prefer a free voice; otherwise steal the QUIETEST (most-decayed) one. Blind
    // round-robin used to cut off notes that were still prominent, which is what
    // made dense passages rasp and restart instead of ringing out cleanly.
    int pick = 0;
    float quietest = 1e30f;
    for (int i = 0; i < kVoices; ++i) {
        if (!voices_[i].active) { pick = i; break; }
        const float loud = voices_[i].env * voices_[i].amp;
        if (loud < quietest) { quietest = loud; pick = i; }
    }
    Voice& v = voices_[pick];
    v.phase = 0.0f;
    v.inc_target = freq / static_cast<float>(sample_rate_);
    v.inc = v.inc_target * kGlideStart;   // start a few semitones low, glide up
    v.env = 0.0f;
    v.amp = kVoiceAmp;
    v.attacking = true;
    v.active = true;
}

void AudioEngine::render(float* out, int32_t num_frames) {
    // Onset throttle. Collapse the whole backlog to the most recent pitch and
    // fire AT MOST one note per kMinOnsetSeconds. The engine flags a note every
    // UI frame (~60/s); playing all of them is the "buzz" — notes never separate
    // and voices restart on top of each other. Spacing the onsets makes it an
    // ASMR trickle that still tracks the sort.
    { float f; while (ring_pop(f)) { pending_freq_ = f; has_pending_ = true; } }
    const int min_onset_frames =
        static_cast<int>(kMinOnsetSeconds * static_cast<float>(sample_rate_));
    if (frames_since_onset_ < (1 << 24)) frames_since_onset_ += num_frames;
    if (has_pending_ && frames_since_onset_ >= min_onset_frames) {
        trigger(pending_freq_);
        has_pending_ = false;
        frames_since_onset_ = 0;
    }

    const float sr_f = static_cast<float>(sample_rate_);
    const float attack_inc = 1.0f / (kAttackSeconds * sr_f);
    const float decay_mult = std::exp(-1.0f / (kDecayTau * sr_f));
    const float glide_coef = 1.0f - std::exp(-1.0f / (kGlideTau * sr_f));   // pitch glide
    const float lp_coef = 1.0f - std::exp(-2.0f * kPi * kLowpassHz / sr_f); // warmth LP
    const float gain = volume_.load(std::memory_order_relaxed);
    const int ch = channels_;

    for (int32_t frame = 0; frame < num_frames; ++frame) {
        float mix = 0.0f;
        for (Voice& v : voices_) {
            if (!v.active) continue;
            mix += std::sin(2.0f * kPi * v.phase) * v.env * v.amp;   // pure sine = round

            v.inc += (v.inc_target - v.inc) * glide_coef;            // rising "bloop"
            v.phase += v.inc;
            if (v.phase >= 1.0f) v.phase -= 1.0f;

            if (v.attacking) {
                v.env += attack_inc;
                if (v.env >= 1.0f) { v.env = 1.0f; v.attacking = false; }
            } else {
                v.env *= decay_mult;
                if (v.env < 0.0008f) v.active = false;
            }
        }
        const float sat = std::tanh(mix * 0.9f);          // soft-limit dense passages
        lp_state_ += (sat - lp_state_) * lp_coef;          // one-pole low-pass
        const float sample = lp_state_ * gain;
        for (int c = 0; c < ch; ++c) out[frame * ch + c] = sample;
    }
}

} // namespace viz
