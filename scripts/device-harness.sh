#!/usr/bin/env bash
# device-harness.sh — persistent rooted-device test harness for cppbench.
#
# A reusable rooted-device session pattern (device-identity gate, asroot exec,
# recovery-slot root activation, verify-every-step) for installing and
# benchmarking the algoviz app on PHYSICAL devices. Root unlocks PMU counters
# (perf_event_open via CAP_PERFMON) and cpufreq governor pinning, neither of
# which is available on an unrooted device.
#
# SAFETY:
#   - Refuses to act on a device whose codename isn't in the registry.
#   - NEVER touches firmware / bootloader (this harness is OS/app level only:
#     reboot-recovery, adb install, su exec, cpufreq governor). All reversible.
#     Any device not in the registry is treated as root_method=none (app-level
#     only), so it is never rooted/modified.
#   - Governor changes are restored on 'restore'/'full'; they also reset on
#     any reboot.
#
# Usage:
#   bash scripts/device-harness.sh probe [serial]
#   bash scripts/device-harness.sh root <serial>
#   bash scripts/device-harness.sh pin-perf <serial>
#   bash scripts/device-harness.sh unpin <serial>
#   bash scripts/device-harness.sh install <serial> <apk>
#   bash scripts/device-harness.sh bench <serial> [filter]
#   bash scripts/device-harness.sh full <serial> [filter]   # root->pin->bench->unpin
#
# Env:
#   CPPBENCH   path to a cppbench arm64-v8a ELF (default: build/bench-arm64/cppbench,
#              falls back to downloading the latest release asset)
set -euo pipefail
export MSYS_NO_PATHCONV=1   # keep /data/local/tmp literal through MSYS adb

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
RESULTS="$REPO/results"
REMOTE=/data/local/tmp/cppbench

# --- Device registry --------------------------------------------------------
# Real device identifiers are PRIVATE: they live in an untracked, gitignored
# local file so they never enter version control. Copy the example and fill in:
#   cp scripts/device-registry.local.sh.example scripts/device-registry.local.sh
# (CI sets ROOTED_* from repo variables instead.) Without either, every device
# is treated as root_method=none (app-level only).
#   root_method: recovery = Magisk in recovery slot, `adb reboot recovery`
#                          activates root
#                magisk   = `su` works after a normal boot
#                none     = no root; app-level testing only
# Codename is re-verified at runtime against the device; a mismatch aborts.
ROOTED_SERIAL="${ROOTED_SERIAL:-}"              # USB serial of the rooted target
ROOTED_ADDR="${ROOTED_ADDR:-}"                  # network-adb address host:port, if any
ROOTED_CODENAME="${ROOTED_CODENAME:-}"          # expected ro.product.device
ROOTED_ROOT_METHOD="${ROOTED_ROOT_METHOD:-recovery}"
ROOTED_LABEL="${ROOTED_LABEL:-device}"
[ -f "$HERE/device-registry.local.sh" ] && source "$HERE/device-registry.local.sh"

is_rooted_target() {  # returns 0 if $1 is the configured rooted device
    { [ -n "$ROOTED_SERIAL" ] && [ "$1" = "$ROOTED_SERIAL" ]; } && return 0
    { [ -n "$ROOTED_ADDR" ] && [ "$1" = "$ROOTED_ADDR" ]; } && return 0
    return 1
}
device_codename()    { is_rooted_target "$1" && echo "$ROOTED_CODENAME" || echo ""; }
device_root_method() { is_rooted_target "$1" && echo "$ROOTED_ROOT_METHOD" || echo "none"; }
device_label()       { is_rooted_target "$1" && echo "$ROOTED_LABEL" || echo "unknown"; }

adbx()    { adb -s "$SERIAL" "$@"; }
asroot()  { adb -s "$SERIAL" shell "su -c '$1'"; }

# Repo slug for release-asset downloads. Auto-detected from the git remote so
# this harness works unchanged in any fork; override with REPO_SLUG=owner/name.
repo_slug() {
    echo "${REPO_SLUG:-$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || echo "")}"
}

# --- Gates ------------------------------------------------------------------
require_device() {
    adbx get-state >/dev/null 2>&1 \
        || { echo "FAIL: $SERIAL not connected (check 'adb devices')"; exit 1; }
    local want got
    want=$(device_codename "$SERIAL")
    got=$(adbx shell getprop ro.product.device | tr -d '\r')
    if [ -n "$want" ] && [ "$got" != "$want" ]; then
        echo "FAIL: $SERIAL codename '$got' != registry '$want' — REFUSING"; exit 1
    fi
    echo "device: $SERIAL  model=$(adbx shell getprop ro.product.model | tr -d '\r')  codename=$got  label=$(device_label "$SERIAL")"
}

is_root() { [ "$(asroot 'id -u' 2>/dev/null | tr -d '\r')" = 0 ]; }

ensure_root() {
    if is_root; then echo "root: already active"; return 0; fi
    local method; method=$(device_root_method "$SERIAL")
    case "$method" in
        recovery)
            echo "root: not active — rebooting into recovery slot (Magisk)..."
            adbx reboot recovery
            adbx wait-for-device
            local i
            for i in $(seq 1 80); do
                [ "$(adbx shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
                sleep 3
            done
            is_root && { echo "root: active after recovery boot"; return 0; }
            echo "FAIL: root still not active after recovery boot"; exit 1 ;;
        *)
            echo "FAIL: device $SERIAL root_method='$method' — cannot get root here"; exit 1 ;;
    esac
}

# --- cpufreq governor pinning (root) ---------------------------------------
list_policies() { asroot "ls -d /sys/devices/system/cpu/cpufreq/policy*" 2>/dev/null | tr -d '\r'; }

pin_perf() {
    ensure_root
    echo "pin-perf: setting all cpufreq policies to 'performance'"
    asroot 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo performance > $p/scaling_governor; done'
    asroot 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo "  $(basename $p): $(cat $p/scaling_governor) max=$(cat $p/scaling_cur_freq)"; done'
}

unpin() {
    ensure_root
    echo "unpin: restoring 'schedutil' governor"
    asroot 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo schedutil > $p/scaling_governor; done'
}

# --- cppbench provisioning --------------------------------------------------
resolve_cppbench() {
    local p="${CPPBENCH:-$REPO/build/bench-arm64/cppbench}"
    if [ -f "$p" ]; then echo "$p"; return 0; fi
    # Fall back to the latest release asset.
    local dldir="$REPO/build"
    local dl="$dldir/cppbench-release"
    mkdir -p "$dldir"
    echo "cppbench not at $p — downloading latest release asset..." >&2
    local slug; slug=$(repo_slug)
    # Two MSYS/git-bash gotchas handled here:
    #   1. gh's `--output` is ignored when `--pattern` is used, so download into
    #      a dir; the asset keeps its release name (cppbench-vX.Y.Z-arm64-v8a).
    #   2. gh.exe is a NATIVE Windows binary — hand it an MSYS '/c/...' dir and it
    #      silently writes nowhere (exit 0, no file). Convert with cygpath; on
    #      Linux/macOS cygpath is absent and we use the path as-is.
    rm -f "$dldir"/cppbench-*-arm64-v8a
    local windir; windir="$(cygpath -w "$dldir" 2>/dev/null || echo "$dldir")"
    gh release download ${slug:+--repo "$slug"} --pattern 'cppbench-*-arm64-v8a' \
        --dir "$windir" --clobber >&2 \
        || { echo "FAIL: gh release download failed (auth? set GH_TOKEN / REPO_SLUG)" >&2; exit 1; }
    local got; got=$(ls -t "$dldir"/cppbench-*-arm64-v8a 2>/dev/null | head -1)
    [ -f "$got" ] || { echo "FAIL: download produced no cppbench-*-arm64-v8a asset in $dldir" >&2; exit 1; }
    cp -f "$got" "$dl"
    echo "$dl"
}

push_cppbench() {
    local bin; bin=$(resolve_cppbench)
    echo "push: $bin -> $REMOTE"
    adbx push "$(cygpath -w "$bin" 2>/dev/null || echo "$bin")" "$REMOTE" >/dev/null
    adbx shell "chmod 755 $REMOTE"
}

bench() {
    local filter="${1:-}"
    ensure_root
    push_cppbench
    mkdir -p "$RESULTS"
    local ts label out
    ts=$(date +%Y%m%d-%H%M%S)
    label=$(device_label "$SERIAL")
    out="$RESULTS/${label}-${filter:-default}-${ts}.json"
    local args="--json"
    [ -n "$filter" ] && args="$args --filter=$filter"
    echo "bench (as root): $REMOTE $args"
    # Run as root so PMU counters (perf_event_open) and any privileged sysfs work.
    asroot "$REMOTE $args" | tr -d '\r' > "$out"
    echo "saved: $out  ($(wc -c < "$out") bytes)"
    echo "--- env + first benchmark name ---"
    python3 -c "import json,sys; d=json.load(open('$out')); e=d.get('env',{}); print('SoC:', e.get('soc_identified'), '| clusters:', len(e.get('cpu_clusters',[])), '| benches:', [b['name'] for b in d.get('benchmarks',[])])" 2>/dev/null || head -c 300 "$out"
    echo
    echo "$out"
}

install_apk() {
    local apk="$1"
    require_device
    echo "install: $apk"
    adbx install -r "$(cygpath -w "$apk" 2>/dev/null || echo "$apk")"
}

# Multi-run: execute cppbench R times (as root, under whatever governor is
# currently set — call pin-perf first for clean numbers), then aggregate
# median + CV across runs to separate real throughput from DVFS/scheduler
# noise (the A75 mid-cluster reads 14-19 GFLOPS run-to-run; the median tells
# the truth, the CV flags the noise).
bench_stats() {
    local filter="${1:-}" runs="${2:-7}"
    ensure_root
    push_cppbench
    mkdir -p "$RESULTS"
    local ts label tag i out
    ts=$(date +%Y%m%d-%H%M%S)
    label=$(device_label "$SERIAL")
    tag="${filter:-default}"
    local args="--json"; [ -n "$filter" ] && args="$args --filter=$filter"
    echo "bench-stats: $runs runs of '$REMOTE $args' (as root)"
    local glob="$RESULTS/${label}-${tag}-stats-${ts}"
    for i in $(seq 1 "$runs"); do
        out="${glob}-run${i}.json"
        asroot "$REMOTE $args" | tr -d '\r' > "$out"
        printf "  run %d/%d -> %s (%s bytes)\n" "$i" "$runs" "$(basename "$out")" "$(wc -c < "$out")"
    done
    echo "--- aggregate ---"
    # The aggregator is a local (no-adb) step, but our global MSYS_NO_PATHCONV=1
    # would feed Windows python.exe an MSYS '/c/...' path it can't open. Run it
    # from the repo with relative paths and path-conversion re-enabled just for
    # this call.
    ( cd "$REPO" && MSYS_NO_PATHCONV=0 "${PYTHON:-python}" scripts/aggregate-runs.py \
        "results/$(basename "$glob")-run"*.json )
}

# UI smoke test driven by stable content-desc locators (scripts/ui_tap.py),
# NOT pixel coordinates — survives layout/resolution changes. Installs the
# APK, taps HW caps + Run, verifies each produced its result file in the
# app's external-files dir.
resolve_apk() {
    local a="${1:-}"
    if [ -n "$a" ] && [ -f "$a" ]; then echo "$a"; return; fi
    local dl="$REPO/build/AlgoViz-release.apk"
    if [ ! -f "$dl" ]; then
        mkdir -p "$(dirname "$dl")"
        local slug; slug=$(repo_slug)
        gh release download ${slug:+--repo "$slug"} --pattern '*.apk' \
            --output "$dl" --clobber >&2 \
            || { echo "FAIL: could not download release APK" >&2; exit 1; }
    fi
    echo "$dl"
}

ui_test() {
    local apk; apk=$(resolve_apk "${1:-}")
    local PKG=com.mariocjun.algoviz
    local FILES=/storage/emulated/0/Android/data/$PKG/files
    local PY="${PYTHON:-python}"
    echo "ui-test: install $apk"
    # Uninstall first: release APKs are debug-signed with an EPHEMERAL keystore
    # generated per CI run, so a newer build won't install over an older one
    # (INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match).
    adbx uninstall "$PKG" >/dev/null 2>&1 || true
    adbx install -r "$(cygpath -w "$apk" 2>/dev/null || echo "$apk")" >/dev/null
    ensure_root  # so we can read/clear the app's external files dir
    asroot "rm -f $FILES/hwcaps-*.json $FILES/benchmarks-*.json" 2>/dev/null || true
    echo "ui-test: launch HomeActivity (launcher) then open the Profiler tile"
    adbx shell am start -W -n "$PKG/.HomeActivity" >/dev/null
    sleep 2
    ui_tap tile_profiler || { echo "  FAIL: tile_profiler not found (HomeActivity launcher)"; return 1; }
    sleep 2

    echo "ui-test: tap btn_hwcaps"
    ui_tap btn_hwcaps || { echo "  FAIL: locator btn_hwcaps not found"; return 1; }
    sleep 2
    asroot "ls $FILES/hwcaps-*.json" >/dev/null 2>&1 \
        && echo "  PASS: hwcaps file written" || { echo "  FAIL: no hwcaps file"; return 1; }

    echo "ui-test: tap btn_run (default suite, ~25s)"
    ui_tap btn_run || { echo "  FAIL: locator btn_run not found"; return 1; }
    local i ok=0
    for i in $(seq 1 30); do
        asroot "ls $FILES/benchmarks-*.json" >/dev/null 2>&1 && { ok=1; break; }
        sleep 2
    done
    [ "$ok" = 1 ] && echo "  PASS: benchmark file written" || { echo "  FAIL: no benchmark file after Run"; return 1; }

    echo "ui-test: PASS (locators resolved by content-desc, both jobs produced output)"
}

# Tap a UI element by its content-desc locator. Path-conversion off for the
# local python.exe call (global MSYS_NO_PATHCONV=1 would mangle scripts/...).
ui_tap() {
    ( cd "$REPO" && MSYS_NO_PATHCONV=0 "${PYTHON:-python}" scripts/ui_tap.py "$SERIAL" tap desc "$1" )
}

probe() {
    require_device
    echo "root_method (registry): $(device_root_method "$SERIAL")"
    echo "root active now: $(is_root && echo yes || echo no)"
    echo "android: $(adbx shell getprop ro.build.version.release | tr -d '\r')  build: $(adbx shell getprop ro.build.version.incremental | tr -d '\r')"
    echo "abilist: $(adbx shell getprop ro.product.cpu.abilist | tr -d '\r')"
    echo "cpufreq policies:"
    if is_root; then
        asroot 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo "  $(basename $p): gov=$(cat $p/scaling_governor) cpus=$(cat $p/affected_cpus) max=$(cat $p/cpuinfo_max_freq)"; done'
        echo "perf_event_paranoid: $(asroot 'cat /proc/sys/kernel/perf_event_paranoid' | tr -d '\r')  (root bypasses via CAP_PERFMON)"
    else
        echo "  (no root — limited probe)"
    fi
}

# --- main -------------------------------------------------------------------
CMD="${1:-}"; shift || true
SERIAL="${1:-${ROOTED_SERIAL:-}}"; shift || true

case "$CMD" in
    probe)     probe ;;
    root)      require_device; ensure_root ;;
    pin-perf)  require_device; pin_perf ;;
    unpin)     require_device; unpin ;;
    install)   install_apk "$1" ;;
    ui-test)   require_device; ui_test "${1:-}" ;;
    bench)     require_device; bench "${1:-}" ;;
    bench-stats) require_device; bench_stats "${1:-}" "${2:-7}" ;;
    full)
        require_device; ensure_root; pin_perf
        bench "${1:-}"
        unpin
        ;;
    full-stats)
        require_device; ensure_root; pin_perf
        bench_stats "${1:-}" "${2:-7}"
        unpin
        ;;
    *)
        echo "usage: $0 <cmd> [serial] [args]"
        echo "  cmds: probe | root | pin-perf | unpin | install <apk>"
        echo "        bench [filter] | bench-stats [filter] [runs]"
        echo "        full [filter] | full-stats [filter] [runs]"
        echo "  (full-stats = pin-perf -> N runs -> aggregate median/CV -> unpin)"
        exit 1 ;;
esac
