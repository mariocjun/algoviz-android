#!/usr/bin/env python3
"""Automated interaction-coverage harness for the algoviz app.

Drives the app on a physical device by stable content-desc locators (reusing
ui_tap) across a curated set of HIGH-VALUE cases: the main menu, every control
in the visualizer, all 12 scales and all 8 algorithms (scrolling the chip rows
into view), canvas gestures, both modes, edge values, and lifecycle (rotate /
background-resume / let a sort complete). The assertion for EVERY case is "the
app process is still alive" (i.e. no crash); on a crash the app is relaunched so
the run continues, and the case is reported FAIL. A few key states are
screenshotted. The app is force-stopped at the end (teardown).

Rationale: exhaustive permutation testing is intractable (~52! paths). This
covers the bug classes that actually occur — state transitions, edge values,
the JNI boundary, and lifecycle — and is repeatable on every build.

Usage:  python scripts/ui_cover.py <serial> [--shots <dir>]
Exits 0 if all cases pass (no crash), 1 otherwise.
"""
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ui_tap  # noqa: E402  (adb, dump_ui, find, node_center)

PKG = "com.mariocjun.algoviz"
SERIAL = None
SHOTS = None
SCREEN_W, SCREEN_H = 720, 1520
results = []  # (name, ok, detail)
ABORT = False  # set when the device drops off adb, so we stop cleanly

SCALES = ["Maj Pentatonic", "Min Pentatonic", "Ionian (major)", "Dorian",
          "Phrygian", "Lydian", "Mixolydian", "Aeolian (minor)", "Locrian",
          "Whole tone", "Blues", "Chromatic"]
ALGOS = ["bubble", "cocktail", "insertion", "shell", "selection", "quick",
         "merge", "heap"]


def sh(*a):
    return ui_tap.adb(SERIAL, *a)


def device_up():
    return sh("get-state").stdout.strip() == "device"


def alive():
    return sh("shell", "pidof", PKG).stdout.strip() != ""


def screen_size():
    global SCREEN_W, SCREEN_H
    out = sh("shell", "wm", "size").stdout
    m = re.search(r"Override size:\s*(\d+)x(\d+)", out) or \
        re.search(r"Physical size:\s*(\d+)x(\d+)", out)
    if m:
        SCREEN_W, SCREEN_H = int(m.group(1)), int(m.group(2))


def start(act):
    sh("shell", "am", "start", "-W", "-n", f"{PKG}/.{act}")
    time.sleep(1.0)


def tap_xy(x, y):
    sh("shell", "input", "tap", str(int(x)), str(int(y)))


def swipe(x1, y1, x2, y2, ms=200):
    sh("shell", "input", "swipe", str(int(x1)), str(int(y1)),
       str(int(x2)), str(int(y2)), str(ms))


def find(name):
    return ui_tap.find(ui_tap.dump_ui(SERIAL), "desc", name)


def tap_desc(name):
    hit = find(name)
    if not hit:
        return False
    cx, cy, _ = hit
    tap_xy(cx, cy)
    return True


def tap_either(*names):
    xml = ui_tap.dump_ui(SERIAL)
    for n in names:
        hit = ui_tap.find(xml, "desc", n)
        if hit:
            cx, cy, _ = hit
            tap_xy(cx, cy)
            return n
    return None


def tap_scroll(name, tries=8):
    """Tap a chip that may be scrolled off-screen in a horizontal row: locate it
    (its y is reliable even when x is off-screen) and scroll its row until the
    chip is comfortably on-screen, then tap."""
    for _ in range(tries):
        hit = find(name)
        if hit:
            cx, cy, _ = hit
            if 25 <= cx <= SCREEN_W - 25:
                tap_xy(cx, cy)
                return True
            ty = cy if 0 < cy < SCREEN_H else 1190
            if cx > SCREEN_W // 2:
                swipe(SCREEN_W - 40, ty, 80, ty, 250)
            else:
                swipe(80, ty, SCREEN_W - 40, ty, 250)
        else:
            swipe(SCREEN_W - 40, 1190, 80, 1190, 250)
        time.sleep(0.25)
    return False


def shot(name):
    if not SHOTS:
        return
    sh("shell", "screencap", "-p", "/sdcard/_cov.png")
    sh("pull", "/sdcard/_cov.png", os.path.join(SHOTS, name + ".png"))


def case(name, action, settle=0.4):
    global ABORT
    if ABORT:
        results.append((name, False, "skipped (device lost)"))
        return
    try:
        action()
    except Exception as e:  # noqa: BLE001
        if not device_up():
            results.append((name, False, "DEVICE DISCONNECTED"))
            ABORT = True
        else:
            results.append((name, False, f"harness exception: {e}"))
        return
    time.sleep(settle)
    if not device_up():            # a disconnect is NOT an app crash
        results.append((name, False, "DEVICE DISCONNECTED"))
        ABORT = True
        return
    ok = alive()
    results.append((name, ok, "" if ok else "PROCESS DIED (crash)"))
    if not ok:
        start("MainActivity")  # recover so the run can continue


def goto_viz():
    start("MainActivity")
    tap_desc("btn_viz")
    time.sleep(1.0)


def run():
    screen_size()
    # A. Main menu
    case("launch MainActivity", lambda: start("MainActivity"))
    for b in ["btn_hwcaps", "btn_sensors", "btn_cameras", "btn_run"]:
        case(f"main: {b}", lambda b=b: tap_desc(b))
        time.sleep(0.4)
    case("open visualizer", goto_viz)
    case("mode: Single", lambda: tap_desc("Single"))
    # B. All 12 scales (JNI nativeSetScale + audio)
    for sc in SCALES:
        case(f"scale: {sc}", lambda sc=sc: tap_scroll(sc))
    # C. All 8 algorithms (nativeSetAlgorithm + sort engine)
    for al in ALGOS:
        case(f"algo: {al}", lambda al=al: tap_scroll(al))
    # D. Transport + actions
    case("play/pause #1", lambda: tap_either("Pause", "Play"))
    case("step back", lambda: tap_desc("Step back"))
    case("step forward", lambda: tap_desc("Step forward"))
    case("reset", lambda: tap_desc("Reset"))
    case("shuffle", lambda: tap_desc("Shuffle"))
    case("play/pause #2", lambda: tap_either("Pause", "Play"))
    case("draw: enter", lambda: tap_either("Draw", "Sort"))
    case("draw: paint drag", lambda: swipe(80, 700, SCREEN_W - 60, 300, 400))
    case("draw: exit (sort)", lambda: tap_either("Sort", "Draw"))
    for sw in ["Sound", "Loop", "Finish FX"]:
        case(f"{sw}: off", lambda sw=sw: tap_desc(sw))
        case(f"{sw}: on", lambda sw=sw: tap_desc(sw))
    case("speed: -x6", lambda: [tap_desc("decrease Speed") for _ in range(6)])
    case("speed: +x10", lambda: [tap_desc("increase Speed") for _ in range(10)])
    case("size: -x10", lambda: [tap_desc("decrease Size") for _ in range(10)])
    case("size: +x10", lambda: [tap_desc("increase Size") for _ in range(10)])
    case("panel: Hide", lambda: tap_desc("Hide"))
    case("panel: reopen Menu", lambda: tap_desc("Menu"))
    # Canvas gestures
    case("canvas: tap-to-play", lambda: tap_xy(200, 380))
    case("canvas: double-tap",
         lambda: (tap_xy(360, 380), time.sleep(0.08), tap_xy(360, 380)))
    case("canvas: drag-speed", lambda: swipe(150, 380, SCREEN_W - 70, 380, 150))
    # E. Race mode
    case("mode: Race", lambda: tap_desc("Race"))
    shot("race")
    case("race: shuffle", lambda: tap_desc("Shuffle"))
    case("race: reset", lambda: tap_desc("Reset"))
    case("race: play/pause", lambda: tap_either("Pause", "Play"))
    case("mode: back to Single", lambda: tap_desc("Single"))
    # F. Lifecycle
    case("rotate landscape",
         lambda: sh("shell", "settings", "put", "system", "user_rotation", "1"),
         settle=1.2)
    shot("landscape")
    case("rotate portrait",
         lambda: sh("shell", "settings", "put", "system", "user_rotation", "0"),
         settle=1.2)
    case("background (home)",
         lambda: sh("shell", "input", "keyevent", "KEYCODE_HOME"), settle=1.0)
    case("resume visualizer", goto_viz, settle=1.0)
    case("let a sort complete (FX)", lambda: time.sleep(2.5), settle=0.3)
    shot("completed")
    # Teardown
    sh("shell", "am", "force-stop", PKG)


def main():
    global SERIAL, SHOTS
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    SERIAL = sys.argv[1]
    if "--shots" in sys.argv:
        SHOTS = sys.argv[sys.argv.index("--shots") + 1]
        os.makedirs(SHOTS, exist_ok=True)
    t0 = time.time()
    run()
    dt = time.time() - t0
    npass = sum(1 for _, ok, _ in results if ok)
    nfail = len(results) - npass
    print("\n=== COVERAGE RESULTS ===")
    for n, ok, d in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {n}{('  -- ' + d) if d else ''}")
    print(f"\n{len(results)} cases | {npass} pass | {nfail} fail | "
          f"{dt:.0f}s | app force-stopped (teardown)")
    return 0 if nfail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
