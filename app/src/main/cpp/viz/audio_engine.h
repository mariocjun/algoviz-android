// ASMR audio engine for the visualizer.
//
// Goal (from the brief): "a satisfying, rounded ASMR sound, pleasant at any
// speed and quantity." Three design choices make that true:
//   1. Pentatonic quantization — every element value maps to a note on a major
//      pentatonic scale, so ANY combination of simultaneous/rapid notes is
//      consonant. No matter how fast or how many fire, it can't sound wrong.
//   2. Pure-ish sine voices (fundamental + soft harmonics) with a short attack
//      and exponential decay — rounded, click-free, bell/marimba-like.
//   3. Polyphony cap + tanh soft-limiter — dense passages stay smooth, never
//      clip into harshness.
//
// Backend: AAudio (NDK built-in, no third-party dep). The audio runs on
// AAudio's realtime callback thread; the GL/UI thread only ever pushes note
// frequencies through a lock-free single-producer/single-consumer ring, so the
// callback never blocks. Voices are owned solely by the audio thread.
#pragma once

#include <atomic>
#include <cstdint>

struct AAudioStream;  // opaque; full type stays in the .cpp

namespace viz {

class AudioEngine {
public:
    AudioEngine() = default;
    ~AudioEngine();
    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    bool start();   // open + start the stream (idempotent); false if unavailable
    void stop();    // stop + close (idempotent)

    void set_enabled(bool e) { enabled_.store(e, std::memory_order_relaxed); }
    bool enabled() const { return enabled_.load(std::memory_order_relaxed); }
    void set_volume(float v);
    float volume() const { return volume_.load(std::memory_order_relaxed); }

    // Trigger a note for a normalized element value in [0,1]. Safe to call from
    // the GL thread; lock-free. No-op when disabled or the ring is full.
    void note(float value01);

    // Realtime render entry — called only by the AAudio data callback.
    void render(float* out, int32_t num_frames);

private:
    struct Voice {
        float phase = 0.0f;   // [0,1)
        float inc = 0.0f;     // freq / sample_rate
        float env = 0.0f;     // current envelope amplitude
        float amp = 0.0f;     // peak amplitude for this note
        bool  attacking = false;
        bool  active = false;
    };

    void trigger(float freq);          // audio thread only
    bool ring_push(float f);           // producer (GL thread)
    bool ring_pop(float& f);           // consumer (audio thread)

    AAudioStream* stream_ = nullptr;
    int sample_rate_ = 48000;
    int channels_ = 2;

    std::atomic<bool> enabled_{true};
    std::atomic<float> volume_{0.6f};
    std::atomic<bool> running_{false};

    static constexpr int kRing = 256;
    float ring_[kRing] = {};
    std::atomic<uint32_t> head_{0};    // written by producer
    std::atomic<uint32_t> tail_{0};    // written by consumer

    static constexpr int kVoices = 24;
    Voice voices_[kVoices] = {};
    uint32_t next_voice_ = 0;          // round-robin voice-steal cursor (audio thread)
};

} // namespace viz
