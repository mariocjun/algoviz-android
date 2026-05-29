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

// Major pentatonic (semitone offsets) spanning several octaves above A3. Mapping
// element values onto this scale makes any chord/cluster consonant.
constexpr int kScale[5] = {0, 2, 4, 7, 9};
constexpr int kOctaves = 4;
constexpr int kScaleSteps = 5 * kOctaves;
constexpr float kBaseHz = 220.0f;   // A3

constexpr float kAttackSeconds = 0.006f;   // soft, click-free onset
constexpr float kDecayTau = 0.16f;         // exponential decay time constant
constexpr float kVoiceAmp = 0.22f;

float pentatonic_hz(float value01) {
    if (value01 < 0.0f) value01 = 0.0f;
    if (value01 > 1.0f) value01 = 1.0f;
    int idx = static_cast<int>(value01 * static_cast<float>(kScaleSteps - 1) + 0.5f);
    if (idx < 0) idx = 0;
    if (idx > kScaleSteps - 1) idx = kScaleSteps - 1;
    const int octave = idx / 5;
    const int degree = idx % 5;
    const int semitones = octave * 12 + kScale[degree];
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
    ring_push(pentatonic_hz(value01));   // drop silently if the ring is full
}

void AudioEngine::trigger(float freq) {
    Voice& v = voices_[next_voice_];
    next_voice_ = (next_voice_ + 1) % kVoices;   // round-robin steal
    v.phase = 0.0f;
    v.inc = freq / static_cast<float>(sample_rate_);
    v.env = 0.0f;
    v.amp = kVoiceAmp;
    v.attacking = true;
    v.active = true;
}

void AudioEngine::render(float* out, int32_t num_frames) {
    // Pull pending notes (bounded so a flood can't starve rendering).
    for (int i = 0; i < kVoices; ++i) {
        float f;
        if (!ring_pop(f)) break;
        trigger(f);
    }

    const float attack_inc = 1.0f / (kAttackSeconds * static_cast<float>(sample_rate_));
    const float decay_mult = std::exp(-1.0f / (kDecayTau * static_cast<float>(sample_rate_)));
    const float gain = volume_.load(std::memory_order_relaxed);
    const int ch = channels_;

    for (int32_t frame = 0; frame < num_frames; ++frame) {
        float mix = 0.0f;
        for (Voice& v : voices_) {
            if (!v.active) continue;
            // Warm, rounded tone: fundamental + soft harmonics.
            const float p = 2.0f * kPi * v.phase;
            const float s = std::sin(p) + 0.20f * std::sin(2.0f * p) + 0.07f * std::sin(3.0f * p);
            mix += s * v.env * v.amp;

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
        // Soft saturation keeps dense passages smooth instead of clipping.
        const float sample = std::tanh(mix * 0.8f) * gain;
        for (int c = 0; c < ch; ++c) out[frame * ch + c] = sample;
    }
}

} // namespace viz
