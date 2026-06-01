---
name: validate-before-ship
description: Mandatory validation pipeline before shipping/closing any UI change or release in algoviz. Use BEFORE tagging a release, before declaring a feature done, or when the user says "fechar", "validar", "pode lançar/lança", "está pronto", "termina". Runs build → on-device touch test (real taps + frame sequence) → critical UX review → HCI heuristic review → logs every finding to docs/VALIDATION_LOG.md with a severity. Blocks closing while any 🔴 critical finding is open; 🟡/🟢 may stay as tracked debt and the log is expected to GROW over time.
---

# validate-before-ship — algoviz mandatory ship gate

This is the **obrigatório** checklist between "I think it's done" and "ship it"
(commit a release tag, tell the owner it's ready). The owner holds an
**Apple-grade** bar and reviews on a physical N975F + tablet. Treat UX like
tests for code. **Never declare a UI change done without running these gates.**

> The point is not to fix everything at once. The point is that nothing ships
> with an **open 🔴**, and every weakness is **written down** in
> `docs/VALIDATION_LOG.md` so the backlog of findings can grow and be burned
> down deliberately over releases.

## Companion skills (use them — they were installed for this)

- **mobile-native-android**, **jetpack-compose** — Android/Compose conventions,
  build loop, emulator/device screenshot verification.
- **ux-researcher-designer** — usability-testing frameworks, heuristic
  evaluation, personas/journey maps for Gate 3.
- **onboarding-cro** — first-run / activation / "aha moment" / time-to-value —
  use when the change affects how a new user *learns* a feature (onboarding,
  tutorials, affordances), since the owner cares about long sessions/retention.

## The gates (run in order; do not skip)

### Gate 0 — Build & install
- `./gradlew assembleDebug --console=plain --no-daemon` (JDK = Android Studio JBR;
  the Gradle wrapper is gitignored — generate with 9.5.0 if missing).
- `adb -s <serial> uninstall com.mariocjun.algoviz` then `install -r` (debug
  keystore differs per build → always uninstall first).
- A compile error here is a 🔴 by definition.

### Gate 1 — On-device touch test (real taps, not build-and-assume)
- Wake + keep awake: `input keyevent KEYCODE_WAKEUP`, `wm dismiss-keyguard`,
  `svc power stayon true` (restore `false` when done).
- Locate controls by **content-desc** (`uiautomator dump`), not pixel guesses.
- Drive each affected flow with real taps; **stress rapid/Repeated taps** on
  every interactive control (the owner explicitly asks for this).
- Capture a **sequence of frames** (`screencap`), not just the end state — judge
  transitions and first-run.
- Exercise the **39-min AutoCloseGuard** path if the screen was touched at the
  bottom 20% (10-tap reveal → disable button must appear and dismiss; bottom
  controls must NOT be swallowed).
- `logcat -d` must show **zero** FATAL / ANR / SIGILL / abort from algoviz.
- Note pixel/logical-resolution gotcha: taps run in the display's override
  resolution; mini-app activities are `exported=false` (launch via HomeActivity).

### Gate 2 — Critical UX review (2 rounds, per CLAUDE.md)
- Round A — **accessibility**: tap targets 44–50 dp, content-desc on controls,
  status narrated as text (Canvas is mute to screen readers).
- Round B — **tablet / proportion**: sizing scales, nothing tiny or clipped,
  hierarchy holds in both orientations.
- Time-retention lens: does the eye land in the right place, does it feel alive,
  does it pull you in?

### Gate 3 — HCI heuristic review (the hard one)
Invoke a critic with **Nielsen heuristics + Norman (affordances vs signifiers,
gulf of execution/evaluation)**, grounded in the Gate-1 frames + the code.
Check, at minimum:
- **Onboarding / forced tutorial**: does a first-time user know what changed and
  what to do? Compare against the app's own bar (Min Cash Flow forces a tutorial;
  Sort shows pseudocode). A new interactive mode with no onboarding is a 🔴.
- **Affordance & signifier of the gesture**: is it clear whether to tap / drag /
  long-press? A control whose only signifier is a border is suspect.
- **Feedback not by colour alone** (daltonism): pair green/red with icon/shape.
- **Gulf of execution / evaluation (feedforward)**: after an action, is the next
  step communicated, and is the *transition* clean? Capture a rapid frame BURST
  per action (tap → +200ms → +500ms → +1s), not just before/after — temporal
  leaks hide here (e.g. v0.6.4 leaked the next answer in the gap *between*
  challenge questions; static frames missed it, the burst caught it).
- For a deeper, owner-facing pass, spin a dedicated critic agent ("ex-Apple HCI
  reviewer"); feed it the real frames and the diff.

### Gate 4 — Log every finding
Append to `docs/VALIDATION_LOG.md` (create if missing): one row per finding with
**date · version · area · severity · finding · principle · status**. Resolved
findings are marked `✅ resolvido (vX.Y.Z)`, **never deleted** — the log is the
growing memory of the project's UX debt.

## Severity & the closing rule

| Severity | Meaning | Blocks ship? |
|---|---|---|
| 🔴 critical | First-use failure, crash, data loss, broken affordance | **YES** |
| 🟡 medium | Real friction, accessibility gap, inconsistency | No — tracked debt |
| 🟢 polish | Refinement, nice-to-have | No — tracked debt |

**Closing rule:** do NOT ship (tag a release / say "pronto") while any 🔴 is
open in `docs/VALIDATION_LOG.md` for the area being shipped. 🟡/🟢 may remain
and accumulate across releases — that is expected and healthy.

## Output back to the owner
Report: which gates ran, the new/closed findings (with severity), the current
🔴 count for the shipping area, and an explicit **ship / do-not-ship** verdict.
Be honest — the owner wants the problems, not reassurance.
