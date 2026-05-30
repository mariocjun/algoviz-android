#!/usr/bin/env python3
"""Resolution- and layout-independent UI tap by stable locator.

Instead of hardcoding pixel coordinates (which break the moment a button
moves, the layout reflows, or the screen resolution differs), this dumps the
LIVE uiautomator view tree, finds the node by a stable attribute, reads its
actual rendered bounds, and taps the center. The only thing it depends on is
the locator value — which we control (content-description test IDs set in
MainActivity.kt: btn_run, btn_hwcaps, btn_sensors, btn_cameras, btn_upload,
field_filter).

Usage:
    python scripts/ui_tap.py <serial> tap       <by> <value>
    python scripts/ui_tap.py <serial> doubletap <by> <value>
    python scripts/ui_tap.py <serial> longpress <by> <value> [ms]
    python scripts/ui_tap.py <serial> drag      <by> <value> <dx> <dy> [ms]
    python scripts/ui_tap.py <serial> swipe     <x1> <y1> <x2> <y2> [ms]
    python scripts/ui_tap.py <serial> find                # list all locatable nodes
    python scripts/ui_tap.py <serial> exists <by> <value> # exit 0 if present

  <by> = desc (content-description) | text | id (resource-id)
  Gestures use `adb shell input` (tap/swipe); drag/longpress are swipes with a
  duration. swipe takes raw pixels (for the Canvas, which has no locatable node).

Calls adb directly (not via a shell), so /sdcard paths aren't mangled by MSYS.
"""
from __future__ import annotations

import re
import subprocess
import sys
import time

REMOTE_DUMP = "/sdcard/_uidump.xml"
ATTR = {"desc": "content-desc", "text": "text", "id": "resource-id"}

# Windows consoles default to cp1252, which raises UnicodeEncodeError when
# printing UI text that contains non-Latin-1 glyphs (e.g. icon code points).
# Force UTF-8 so `find`/`tap` diagnostics never crash on Windows.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass


def adb(serial: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", serial, *args],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")


def dump_ui(serial: str, retries: int = 4) -> str:
    # Make sure the screen is on/awake — uiautomator dump fails on a black screen.
    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    for _ in range(retries):
        d = adb(serial, "shell", "uiautomator", "dump", REMOTE_DUMP)
        if "dumped" in (d.stdout + d.stderr).lower() or d.returncode == 0:
            cat = adb(serial, "shell", "cat", REMOTE_DUMP)
            if cat.stdout.strip().startswith("<?xml") or "<hierarchy" in cat.stdout:
                return cat.stdout
        time.sleep(1.0)
    raise RuntimeError("uiautomator dump failed after retries "
                       f"(last stderr: {d.stderr.strip()[:200]})")


def iter_nodes(xml: str):
    for m in re.finditer(r"<node\b[^>]*?/?>", xml):
        yield m.group(0)


def node_center(node_xml: str):
    bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node_xml)
    if not bm:
        return None
    x1, y1, x2, y2 = map(int, bm.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2, (x1, y1, x2, y2)


def find(xml: str, by: str, value: str):
    attr = ATTR[by]
    for s in iter_nodes(xml):
        am = re.search(rf'\b{re.escape(attr)}="([^"]*)"', s)
        if am and am.group(1) == value:
            c = node_center(s)
            if c:
                return c
    return None


def cmd_find(serial: str) -> int:
    xml = dump_ui(serial)
    print(f"{'class':<28}{'content-desc':<18}{'text':<22}{'bounds'}")
    print("-" * 90)
    for s in iter_nodes(xml):
        cls = (re.search(r'\bclass="([^"]*)"', s) or [None, ""])[1].split(".")[-1]
        desc = (re.search(r'\bcontent-desc="([^"]*)"', s) or [None, ""])[1]
        txt = (re.search(r'\btext="([^"]*)"', s) or [None, ""])[1][:20]
        bnd = (re.search(r'\bbounds="([^"]*)"', s) or [None, ""])[1]
        if desc or txt or cls in ("Button", "EditText"):
            print(f"{cls:<28}{desc:<18}{txt:<22}{bnd}")
    return 0


def cmd_tap(serial: str, by: str, value: str) -> int:
    xml = dump_ui(serial)
    hit = find(xml, by, value)
    if not hit:
        print(f"NOT FOUND: {by}={value!r} — current locatable elements:", file=sys.stderr)
        cmd_find(serial)
        return 2
    cx, cy, bounds = hit
    adb(serial, "shell", "input", "tap", str(cx), str(cy))
    print(f"tapped {by}={value!r} at ({cx},{cy})  bounds={bounds}")
    return 0


def cmd_exists(serial: str, by: str, value: str) -> int:
    xml = dump_ui(serial)
    return 0 if find(xml, by, value) else 1


def _locate(serial: str, by: str, value: str):
    hit = find(dump_ui(serial), by, value)
    if not hit:
        print(f"NOT FOUND: {by}={value!r}", file=sys.stderr)
    return hit


def cmd_swipe(serial: str, x1, y1, x2, y2, ms="300") -> int:
    adb(serial, "shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))
    print(f"swipe ({x1},{y1})->({x2},{y2}) {ms}ms")
    return 0


def cmd_doubletap(serial: str, by: str, value: str) -> int:
    hit = _locate(serial, by, value)
    if not hit:
        return 2
    cx, cy, _ = hit
    adb(serial, "shell", "input", "tap", str(cx), str(cy))
    time.sleep(0.08)
    adb(serial, "shell", "input", "tap", str(cx), str(cy))
    print(f"double-tap {by}={value!r} at ({cx},{cy})")
    return 0


def cmd_drag(serial: str, by: str, value: str, dx, dy, ms="400") -> int:
    hit = _locate(serial, by, value)
    if not hit:
        return 2
    cx, cy, _ = hit
    x2, y2 = cx + int(dx), cy + int(dy)
    adb(serial, "shell", "input", "swipe", str(cx), str(cy), str(x2), str(y2), str(ms))
    print(f"drag {by}={value!r} ({cx},{cy})->({x2},{y2}) {ms}ms")
    return 0


def cmd_longpress(serial: str, by: str, value: str, ms="700") -> int:
    hit = _locate(serial, by, value)
    if not hit:
        return 2
    cx, cy, _ = hit
    adb(serial, "shell", "input", "swipe", str(cx), str(cy), str(cx), str(cy), str(ms))
    print(f"long-press {by}={value!r} at ({cx},{cy}) {ms}ms")
    return 0


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    serial, action = sys.argv[1], sys.argv[2]
    if action == "find":
        return cmd_find(serial)
    if action == "tap":
        return cmd_tap(serial, sys.argv[3], sys.argv[4])
    if action == "exists":
        return cmd_exists(serial, sys.argv[3], sys.argv[4])
    if action == "doubletap":
        return cmd_doubletap(serial, sys.argv[3], sys.argv[4])
    if action == "longpress":
        return cmd_longpress(serial, sys.argv[3], sys.argv[4], *sys.argv[5:6])
    if action == "drag":
        return cmd_drag(serial, sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6], *sys.argv[7:8])
    if action == "swipe":
        return cmd_swipe(serial, *sys.argv[3:8])
    print(f"unknown action: {action}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
