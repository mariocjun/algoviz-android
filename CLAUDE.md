# CLAUDE.md

Guidance for Claude Code (and any AI agent) working in this repository.
**This repo is a template** — a forkable C++/NDK Android base + on-device
profiler. If you're an agent that just landed here, read this top-to-bottom
once; it's the map. Cross-tool agents: see `AGENTS.md`. Invokable capabilities:
`.claude/skills/`.

> Everything down to "Author's device rig" is **generic** — true for any fork.
> The fenced section at the very bottom is the original author's specific
> hardware and is the only part you should replace when you fork.

## What this is

A Kotlin **`MainActivity`** (AppCompat) that loads `libalgoviz.so` and
calls into C++ through a thin **JNI bridge** (`app/src/main/cpp/jni.cpp`). The
C++ side is a profiler/benchmark harness under `app/src/main/cpp/bench/`. There
is no NativeActivity and no `android_native_app_glue` — an earlier revision used
those; they were removed when the UI moved to MainActivity. Don't reintroduce
the `-u ANativeActivity_onCreate` linker flag; it's gone for a reason.

**The load-bearing invariant:** the JNI function names in `jni.cpp`
(`Java_com_mariocjun_algoviz_MainActivity_*`) must match the Kotlin
package + class exactly, or the app crashes at the first native call. The
package appears in ~5 coupled places (Kotlin dir path, package decl, JNI
symbols, `applicationId`, `namespace`). **Never rename the package by hand** —
run `scripts/init-template.sh` (it moves the Kotlin dir and rewrites the JNI
symbols in lockstep).

## Project map

```
app/
  build.gradle.kts            AGP config: minSdk 29, compileSdk 34, NDK 26.1.10909125,
                              Kotlin, -std=c++23 via CMAKE_CXX_STANDARD
  src/main/
    AndroidManifest.xml       MainActivity launcher, INTERNET perm (paste.rs upload)
    kotlin/.../MainActivity.kt UI (programmatic, no XML): buttons w/ content-desc
                              test IDs (btn_run, btn_hwcaps, btn_viz, ...) + paste.rs upload
    kotlin/.../VizActivity.kt  native Jetpack Compose sort visualizer (Material 3 + Canvas)
    kotlin/.../VizBridge.kt    JNI surface to the C++ viz engine + AAudio synth
    cpp/
      CMakeLists.txt          harden_target() = strict warnings + -O3 + LTO +
                              dead-code stripping; per-file -march for dot_int8/i8mm/sve2
      jni.cpp                 JNI bridge + native crash handler (SA_SIGINFO -> dump)
      bench/
        registry.h            Benchmark<T> concept + Registry tuple + fold dispatch
        json.h                dependency-free JSON emitter (unit-tested)
        hwcaps.{h,cpp}        getauxval(AT_HWCAP) feature gates (authoritative)
        diag.{h,cpp}          current_bench tag read by the crash handler
        affinity / soc_info / timer   cluster detection, /proc fingerprint, timing
        cpu/                  stream, latency, neon_fma, dot_int8, i8mm, sve2,
                              perf_counters, sustained
        sensors/ camera/ stream/   ASensorManager enum, Camera2 enum, NDJSON streamer
      bench_main.cpp          standalone cppbench ELF entry (CLI)
      algoviz/                sort-visualisation engine (the imalgorithm port)
        generator.h           hand-rolled C++20 Generator<T> coroutine (libc++17: no std::generator)
        step.h                Step (Compare/Swap/Set/Pivot) — the mutation-log unit
        sorts.h               8 clean-room sorts as step-yielding coroutines
        sort_registry.h       SortAlgo concept + Sorts tuple + benchmark driver (NDK-free)
        sort_bench.{h,cpp}    per-cluster wrapper (pins core, runs run_all) -> bench registry
      viz/                    headless C++ viz engine + JNI for the Compose UI
        single_engine.{h,cpp} single-view model: stepping + bounded undo/redo + draw mode
        race_engine.{h,cpp}   race model: all sorts on one shuffle, ranks/podium
        viz_engine.{h,cpp}    facade: routes controls, packs per-frame int snapshot
        native_bridge.cpp     JNI (VizBridge): controls + nativeUpdate + nativeFill(buffer)
        audio_engine.{h,cpp}  AAudio pentatonic ASMR synth (lock-free note ring)
docs/icon.svg                 HD graph app icon (adaptive launcher icon in res/)
tests/                        host doctest unit tests (no NDK): json, registry concept,
                              sort correctness + step-replay, sort registry/benchmark
scripts/                      build/run/profile/template tooling (see below)
.github/workflows/            smoke, quality, tests, release, device-test
```

## Build / test / profile / release

```bash
# APK (needs Android Studio's Gradle or a standalone gradle on PATH)
gradle wrapper --gradle-version 8.7 --distribution-type bin   # once (wrapper is gitignored)
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug

# Standalone profiler ELF (arm64-v8a) — no APK
bash scripts/build-bench.sh    # -> build/bench-arm64/cppbench (sets BUILD_BENCH_EXECUTABLE=ON)
adb shell /data/local/tmp/cppbench --json|--sensors|--cameras|--stream|--list

# Host unit tests
cmake -S tests -B tests/build -G Ninja && cmake --build tests/build && ctest --test-dir tests/build

# Release: tag triggers build+publish (APK + ELF) and, via release.yml's final
# step, dispatches the physical device-test.
git tag v1.2.3 && git push origin v1.2.3
```

## Architecture invariants & gotchas (don't relearn these the hard way)

- **JNI⇄Kotlin package lockstep** — see above. `init-template.sh` is the only
  safe renamer.
- **HWCAP gating, not /proc/cpuinfo** — extension benches (dot_int8/i8mm/sve2/
  neon_fma fp16) check `bench::has_*()` (`getauxval(AT_HWCAP/AT_HWCAP2)`) before
  executing. /proc/cpuinfo lies on some kernels (lists a feature the kernel
  disabled for userspace → SIGILL). Always gate new SIMD on HWCAP.
- **Per-file `-march`** — `dot_int8.cpp` (`+dotprod`), `i8mm.cpp` (`armv8.6-a+i8mm`),
  `sve2.cpp` (`armv9-a+sve2`) get per-source `-march` via `set_source_files_properties`
  (arm64-v8a only). Project-wide stays baseline armv8-a so the autovectorizer
  can't emit those instructions into other TUs and SIGILL on older cores.
- **C++23 on Clang 17 (NDK r26b)** = `-std=c++2b`, NOT `-std=c++23` (that alias
  is Clang 18+). Don't hard-code `-std=` in Gradle; let `CMAKE_CXX_STANDARD 23`
  emit the right flag.
- **MSYS path mangling** (Windows git-bash): `export MSYS_NO_PATHCONV=1` for adb
  `/sdcard`,`/data/...` args, but turn it OFF (`MSYS_NO_PATHCONV=0`) for local
  `python.exe` calls (it mangles `/c/...`). `ui_tap.py` calls adb directly so
  it's immune.
- **Ephemeral debug keystore** — each CI runner generates its own debug key, so
  a newer release APK won't install over an older one
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Always `adb uninstall` before install
  (smoke-check.sh and device-harness ui-test do this).
- **Single-run mobile numbers are noise** (DVFS + scheduler). Any perf claim
  needs `full-stats` (≥5 runs, median + CV); root + `performance` governor for
  clean numbers.

## The AlgoViz sort engine (`app/src/main/cpp/algoviz/`)

This fork ports the *imalgorithm* sorting visualiser onto the template. Phase 1
= the headless engine + device benchmark (below). The visual layer began on
ImGui/GLES but was **migrated to fully-native Jetpack Compose** (Material 3 UI +
a Compose `Canvas` rendering a per-frame snapshot from the C++ engine over JNI;
ImGui/GLES removed) — see "The visual layer" below. Graph icon, pentatonic ASMR
audio, race mode + podium, rainbow bars, auto-loop, and gestures all carried
over. All validated on the N975F.

- **Single source of truth = coroutines.** Each sort (`bubble/cocktail/insertion/
  shell/selection/quick/merge/heap` in `sorts.h`) is a `Generator<Step>` that
  mutates its `std::vector<int>&` in place *and* `co_yield`s a `Step` per
  comparison/swap/write. The benchmark drains that stream and counts ops + times
  it; the Compose renderer consumes the *same* stream to animate. No algorithm
  is written twice.
- **The mutation-log invariant (tested):** replaying only the `Swap`/`Set` steps
  onto a fresh copy of the input reproduces the coroutine's sorted array.
  `Compare`/`Pivot` are pure highlights. This is the contract the visual layer
  relies on — `tests/test_sorts.cpp` "step replay" enforces it.
- **NDK-free core.** `generator.h`/`step.h`/`sorts.h`/`sort_registry.h` depend
  only on libc++ + `bench/json.h`+`bench/timer.h`, so the host CI unit-tests the
  *real* engine (1400+ assertions), not a reimplementation. Only
  `sort_bench.cpp` (cpu-pinning per cluster) touches `bench/affinity.h`.
- **`SortAlgo<T>` mirrors `Benchmark<T>`:** concept + `Sorts` tuple + fold
  dispatch. Add a sort = one wrapper struct (name/complexity/stable/`make`) +
  one tuple entry; a malformed wrapper is a compile error.
- **Wired as the `sort` benchmark** (opt-in: the O(n²) sorts yield tens of
  millions of steps). Run it: `--filter=sort` (`--elems=N` sets array size,
  `--iters=N` the trial count). Reports per algorithm: comparisons, swaps,
  writes, total_steps, median_ms, `msteps_per_sec` (coroutine-step throughput =
  the metric the visual engine actually pays), and a `correct` self-check.
- **Why coroutines and not a templated `Observer`:** the visualiser needs to
  *pause* mid-sort (one step per frame); a pull-generator is the natural fit and
  the reason `std::generator` had to be hand-rolled (NDK r26b ships libc++17).

## The visual layer (`VizActivity.kt` + `viz/`)

Native **Jetpack Compose** (Material 3), launched from MainActivity's "Visualize"
button (`btn_viz`). It started on ImGui/GLES but migrated to Compose: the dataset
is tiny (a few hundred ints) so GL is unnecessary, and native buys Material
theming, accessibility, and real touch gestures. Validated on the N975F (both
orientations + Single/Race).

- **C++ owns the model, Compose owns the pixels.** `viz/{single,race}_engine`
  hold the data + stepping (+ bounded undo/redo for the ◀▶ back-step, via a
  lockstep `mirror_` that recovers Set-overwritten values) + draw mode. They're
  NDK-free and **host-unit-tested** (`tests/test_{single,race}_engine.cpp`).
  `viz_engine` is the facade; `native_bridge.cpp` is the JNI for `object VizBridge`.
- **Per-frame snapshot, zero-copy.** Compose's `withFrameNanos` loop calls
  `nativeUpdate(dt)` then `nativeFill(directByteBuffer)`; C++ writes bar values +
  highlights + stats into a caller-owned direct buffer (no per-frame alloc/GC). A
  Compose `Canvas` reads it and draws — rainbow by value; race = lane grid with
  `drawText` podium labels.
- **Same coroutines.** The engine instantiates a sort by index via
  `algoviz::make_sort_by_index` (fold over the `Sorts` tuple) — UI and benchmark
  can't drift from the algorithms.
- **Audio** stays C++ AAudio (`audio_engine`): the engine flags a note value per
  step (`consume_note`), `VizEngine` plays it. Lifecycle: VizActivity
  `onResume/onPause` → `nativeAudioResume/Pause`.
- **Automation:** Compose widgets expose semantics (unlike ImGui), so
  content-desc/text automation can see the controls; for the `Canvas` itself use
  pixel taps + `screencap`.

### Phase 3 — the addictive / ASMR pass (validated on the N975F, both orientations)

- **App icon** — a 6-node graph (one per sort) as an adaptive VectorDrawable
  launcher icon (`res/`) + HD `docs/icon.svg`.
- **ASMR audio** (`viz/audio_engine.*`, AAudio, no dep) — realtime sine voices on
  AAudio's callback thread; the per-frame update feeds note frequencies through a
  lock-free SPSC ring. **Pentatonic value→pitch** quantization makes any density
  of notes consonant; soft attack + rising "bubble" pitch glide + exp decay +
  tanh limiter + low-pass keep it rounded/ASMR at any speed. One note/frame
  (single) / round-robin (race) so it stays musical, never noise. `note()` is
  lock-free; `nativeAudioResume/Pause` (VizActivity onResume/onPause) start/stop.
- **Race mode** (`viz/race_engine.*`) — all 8 sorts race the SAME shuffle; the
  Compose Canvas lays them in an aspect-fit grid (2×4 / 4×2) with `drawText`
  finish-order **podium** labels (#1/#2/#3…).
- **Rainbow + loop** — bars colored by VALUE→hue, so a sorted array is a smooth
  rainbow (the payoff); active accesses flash white. Auto-loop reshuffles + reruns
  on completion — endless ambient. The Compose `VizActivity` is the controller
  (Single/Race chips, Sound/Loop switches, Vol slider, transport ◀▶, steppers).

## Extending: add a benchmark

The one extension point you'll use most. See `.claude/skills/add-benchmark/`
for the step-by-step. In short: write `bench/cpu/yourbench.{h,cpp}` exposing
`run_yourbench_per_cluster(...)`, add a wrapper struct to `bench/registry.h`'s
tuple (the `Benchmark<T>` concept makes a malformed wrapper a compile error),
add the source to `CMakeLists.txt`. `bench_main.cpp` and the JNI bridge don't
change — dispatch is automatic.

## CI workflows

| Workflow | Runs on | Purpose |
|---|---|---|
| `smoke` | GitHub emulator (API 30 x86_64) | install APK + launch + assert process alive + JNI_OnLoad log |
| `quality` | GitHub-hosted | clang-tidy + lizard cyclomatic complexity (informational artifacts) |
| `tests` | GitHub-hosted | host doctest unit tests |
| `release` | GitHub-hosted | build + publish APK & cppbench ELF on `v*`; dispatches device-test |
| `device-test` | self-hosted (optional) | bench (full-stats) + content-desc UI smoke on a real rooted device |

`device-test` is **owner-only** (`workflow_dispatch` + a `repository_owner`
guard) — never `pull_request`, because a self-hosted runner on a public repo
must be unreachable by fork PRs.

## scripts/

`build-bench.sh` (build ELF) · `run-bench.sh` / `compare.py` (push+run+diff) ·
`device-harness.sh` (rooted-device session: probe/root/pin-perf/install/bench/
ui-test) · `ui_tap.py` (locate UI by content-desc, not coordinates) ·
`aggregate-runs.py` (median/CV over N runs) · `dashboard.py` / `live-dashboard.sh`
(live NDJSON terminal view) · `init-template.sh` (fork rename engine) ·
`smoke-check.sh` (emulator smoke logic).

---

<!-- ================= AUTHOR'S DEVICE RIG — replace for your project ================= -->
## Author's device rig (example — replace when you fork)

The original author's physical tester is a **rooted SM-N975F (Exynos 9825)**,
serial `REDACTED_SERIAL` (Tailscale `REDACTED_HOST:5555`), rooted via Magisk in the
recovery slot (lineage: a sibling WiFi-CSI project's `flash_f2.sh`). The
`device-harness.sh` device registry, the `device-test.yml` runner labels
(`self-hosted, n975f`), and the baseline below are all specific to that rig.
**If you fork:** edit the `device-harness.sh` registry to your device(s), or
`init-template.sh --minimal` to remove the device rig entirely.

Calibrated Exynos 9825 baseline (root, `performance` governor, median of 7):

| metric | A55 @1.95G | A75 @2.40G | M4 @2.73G |
|---|---|---|---|
| NEON FP32 FMA (GFLOPS) | 13.8 | 19.1 | 45.0 |
| SDOT int8 (GOps) | 61.9 | 38.2 | 173.3 |
| STREAM triad (GB/s) | 6.5 | 17.7 | 23.0 |
| IPC (PMU, FMA loop) | 1.25 | 1.66 | 1.67 |

Finding: the Cortex-A75 has **half-rate DotProd** (0.5 SDOT/cycle vs A55's 1.0,
M4's 2.0) — real, reproducible (CV 0%), not measurement noise. Self-hosted
runner `redacted-runner`; persisted via a current-user logon Task Scheduler task,
or `svc.cmd install` as admin for a true service.

## Local dev environment (author)

Windows + git-bash/MSYS2; Android Studio is the supported IDE (CLion's Android
support is limited). Build runs against NDK 26.1.10909125.
