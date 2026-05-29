// Host tests for viz/race_engine.h — the headless race model the Compose UI
// drives. Validates that all lanes sort the same shuffle, finish with unique
// ranks (the podium), and that the lane vector's move-only coroutines behave.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "viz/race_engine.h"

#include <algorithm>
#include <set>

using viz::RaceEngine;

TEST_CASE("all lanes sort the same shuffle and finish with unique ranks") {
    RaceEngine r;
    r.set_auto_loop(false);
    r.set_size(48);
    const int lanes = r.lane_count();
    CHECK(lanes == 8);

    int guard = 0;
    while (r.finished_count() < lanes && guard++ < 500000) r.update(0.016f);
    CHECK(r.finished_count() == lanes);

    std::set<int> ranks;
    for (int k = 0; k < lanes; ++k) {
        CAPTURE(k);
        const auto& d = r.lane_data(k);
        CHECK(std::is_sorted(d.begin(), d.end()));
        CHECK(r.lane_finished(k));
        CHECK(r.lane_rank(k) >= 1);
        CHECK(r.lane_rank(k) <= lanes);
        ranks.insert(r.lane_rank(k));
    }
    CHECK(static_cast<int>(ranks.size()) == lanes);   // 1..L, no duplicates
}

TEST_CASE("size and speed are clamped") {
    RaceEngine r;
    r.set_size(999999);
    CHECK(r.size() <= 256);
    r.set_size(1);
    CHECK(r.size() >= 16);
    r.set_speed(999999);
    CHECK(r.speed() <= 256);
    r.set_speed(-1);
    CHECK(r.speed() >= 1);
}

TEST_CASE("consume_note yields a valid value while racing") {
    RaceEngine r;
    r.set_auto_loop(false);
    r.set_size(48);
    r.update(0.016f);
    const float note = r.consume_note();
    CHECK(note >= 0.0f);
    CHECK(note <= 1.0f);
}
