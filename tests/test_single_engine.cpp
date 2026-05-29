// Host tests for viz/single_engine.h — the headless single-view model that the
// Compose UI drives. Validates the parts that are easy to get wrong and hard to
// eyeball on-device: that every algorithm still sorts when driven through the
// engine, and that step-back/undo + redo are EXACT inverses (the lockstep
// mirror_ trick). No ImGui/GLES/audio here — pure logic.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "viz/single_engine.h"

#include <algorithm>
#include <vector>

using viz::SingleEngine;

namespace {
void run_to_finish(SingleEngine& e) {
    int guard = 0;
    while (!e.finished() && guard++ < 500000) e.update(0.016f);
}
}

TEST_CASE("every algorithm sorts when driven through the engine") {
    for (int idx = 0; idx < 8; ++idx) {
        CAPTURE(idx);
        SingleEngine e;
        e.set_auto_loop(false);   // stay finished so we can inspect the result
        e.set_size(64);
        e.set_algorithm(idx);
        e.set_playing(true);
        run_to_finish(e);
        CHECK(e.finished());
        CHECK(std::is_sorted(e.data().begin(), e.data().end()));
        CHECK(e.total_steps() > 0);
        CHECK(e.compares() >= 0);
    }
}

TEST_CASE("one step back then forward returns to the same array") {
    SingleEngine e;
    e.set_auto_loop(false);
    e.set_size(48);
    e.set_algorithm(0);   // bubble — plenty of steps
    e.set_playing(false);
    for (int i = 0; i < 120; ++i) e.step_forward_one();

    const std::vector<int> snapshot = e.data();
    e.step_back_one();
    e.step_forward_one();
    CHECK(e.data() == snapshot);
}

TEST_CASE("multi-step back-scrub then forward reproduces the exact state") {
    SingleEngine e;
    e.set_auto_loop(false);
    e.set_size(48);
    e.set_algorithm(4);   // merge — heavy on Set steps (the undo-prev path)
    e.set_playing(false);
    for (int i = 0; i < 200; ++i) e.step_forward_one();

    const std::vector<int> snapshot = e.data();
    const long long steps_before = e.total_steps();
    for (int i = 0; i < 80; ++i) e.step_back_one();
    CHECK(e.total_steps() < steps_before);
    for (int i = 0; i < 80; ++i) e.step_forward_one();
    CHECK(e.data() == snapshot);
    CHECK(e.total_steps() == steps_before);
}

TEST_CASE("draw mode authors an array that then sorts correctly") {
    SingleEngine e;
    e.set_auto_loop(false);
    e.set_size(32);
    e.set_draw_mode(true);
    // Paint a descending ramp (worst-ish case for many sorts).
    for (int i = 0; i < 32; ++i) {
        e.paint(i, 1.0f - static_cast<float>(i) / 31.0f);
    }
    e.set_draw_mode(false);   // sort what was drawn
    run_to_finish(e);
    CHECK(e.finished());
    CHECK(std::is_sorted(e.data().begin(), e.data().end()));
}

TEST_CASE("consume_note yields a value in [0,1] after a step, then -1") {
    SingleEngine e;
    e.set_auto_loop(false);
    e.set_size(40);
    e.set_algorithm(3);
    e.step_forward_one();
    const float note = e.consume_note();
    CHECK(note >= 0.0f);
    CHECK(note <= 1.0f);
    CHECK(e.consume_note() < 0.0f);   // consumed; nothing pending
}

TEST_CASE("size/speed are clamped to sane ranges") {
    SingleEngine e;
    e.set_size(999999);
    CHECK(e.size() <= 400);
    e.set_size(1);
    CHECK(e.size() >= 16);
    e.set_speed(100000);
    CHECK(e.speed() <= 512);
    e.set_speed(-5);
    CHECK(e.speed() >= 1);
}
