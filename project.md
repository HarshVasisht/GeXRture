# Project: Gesture-controlled Android dock for XREAL xbx a01+

> Shared context for Codex, Claude Code, and any subsequent contributor.
> Owner: Harsh Vasisht.
> Created: 2026-10-25.
> Status: Research and implementation plan only. No application has been built or validated by this document.
> Read this file before making changes. Preserve the distinction between user reports, upstream claims, design proposals, and device-tested facts.

## 1. What we are building and why

Build an installable Android APK that turns an existing phone into a lightweight spatial display host for XREAL xbx a01+ glasses. The owner wants to avoid buying a separate XREAL Beam Pro.

The core experience is one virtual screen that can be:
- Resized.
- Repositioned.
- Pinned in a chosen viewing direction using the glasses' head-rotation data.
- Recentered and switched back to a head-following mode.
- Controlled using hand gestures detected by a phone camera.

The immediate goal is a workable prototype, not a complete replacement for Beam Pro or Android's window manager. Validate hardware access before investing in UI polish.

The owner supplied a screenshot of **ScreenLab by nroro** with Resize, Anchor, Anchor2, Follow, and 3D Room controls. The owner reports it supports pinning in different modes but does not provide the desired hand-gesture interaction. Treat this as a functional reference, not source code or an integration API.

## 2. Hardware and known constraints

| Item | Role | Evidence/status |
|---|---|---|
| XREAL xbx a01+ | External display and source of head-motion data | Owner owns it; exact firmware and USB descriptors not collected |
| Samsung Galaxy S25 Ultra | Primary APK host, rendering, USB connection, initially camera processing | Owner owns it; Android/One UI version needs explicit confirmation |
| Motorola Edge 50 Fusion | Optional later camera/gesture controller | Owner owns it; manufacturer marks wired-display support unavailable [S3] |
| Development computer | Build, install, debug, inspect logs | OS and installed Android tools not yet confirmed |
| ScreenLab | Baseline for comparison | Owner reports pinning; exact version and stability not measured |

Use the Samsung for the direct USB-C connection. Manufacturer documentation supports wired display/DeX on the S25 family [S2]; that is not proof of this custom APK's sensor or display-session compatibility.

Start with one phone. Add the Motorola only after the Samsung-only path works. A second phone must not be required for basic touch-controlled rendering.

### 2.1 Meaning of 3DoF

3DoF means rotation: yaw, pitch, and roll. The target is a panel that remains in a chosen angular direction while the wearer turns their head.

Do not describe this as full room tracking, persistent spatial mapping, or a physical monitor fixed to a wall. Translation is not tracked. Moving sideways or walking will not provide correct positional parallax. Yaw drift and recentering are expected considerations.

The XBX manual describes built-in stabilization, available with 120 Hz input [S1]. This is not equivalent to arbitrary application-controlled screen anchoring. Do not require 120 Hz for the custom renderer merely because the built-in stabilization mode does.

The phone's IMU is not a substitute for the glasses' IMU when the phone is on a desk or in a pocket.

### 2.2 Camera placement

Do not assume the a01+ provides a hand-tracking camera or supports the One-series Eye accessory. The proposed input is the Samsung camera, or later the Motorola camera.

Hands must be visible to that camera. A phone in a pocket cannot track hands. Begin with a seated desk setup and explicit camera placement. Camera-relative 2D cursor mapping is sufficient for the first version; calibrated physical hand-to-world registration is out of scope.

## 3. Evidence from research

Sources were consulted on 2026-10-25. These are upstream statements, not our own hardware test results. Re-check relevant code, licenses, dependencies, and current documentation before implementation.

### 3.1 Exact-model head tracking: most important lead

**XRLinuxDriver v2.11.3** explicitly introduced XREAL xbx a01/a01+ support [S4]:
- Reported USB product IDs: A01 0x0440; A01+ 0x0442. Verify actual vendor/product IDs on the owner's hardware.
- XBX needs an MCU SDK-identification handshake and a recurring 100 ms heartbeat before opening the IMU stream.
- Its heartbeat thread owns MCU access while streaming; avoid competing reads.
- The release reports testing on actual A01+ hardware with approximately 1000 Hz IMU data.
- Protocol initialization depends on a companion change in the xrealInterfaceLibrary submodule.
- XBX display-mode/SBS commands use different encoding and were not implemented in that release.

Implication: there is a credible protocol starting point, but this is a Linux implementation, not a drop-in Android SDK. Inspect the exact release, submodule revision, companion patch, and license. Do not invent packet layouts, copy Air/One commands blindly, or equate USB product detection with working tracking.

### 3.2 ScreenLab establishes practical feasibility

ScreenLab's developer announced a01+ support for anchoring, smooth follow, and other display effects in v1.0.9 [S5]. Their posted tuning instructions were:
1. Open 3DoF settings, then Sensor Mapping.
2. Reset.
3. Gyro Axis Y Invert = 1.
4. Accel Axis X Invert = 0.
5. Accel Axis Z Invert = 0.

These are ScreenLab-specific settings, not universal axis transformations for our driver. Derive our coordinate mapping from raw readings and controlled movements.

No documented ScreenLab API for programmatic pin/resize control was established during research. Do not assume a separate gesture app can command its renderer. Using the existing app remains a useful baseline experiment.

### 3.3 Gesture and spatial-shell references

- **nudou350/Xreal-tools** [S6]: Android gesture mouse using MediaPipe, Samsung DeX, and a One Pro Eye-camera input. Useful for studying gesture and input routing. Camera acquisition must be replaced for our phone-camera design; it does not establish a01+ compatibility.
- **SpendinFR/XReelOs** [S7]: spatial shell with gesture interaction and Android integration. Its documented validated setup is S24, Android 16/One UI 8, One Pro + Eye, Unity, and XREAL SDK. It uses Shizuku for parts of app integration. Useful architecture reference, not a verified APK for our hardware.
- **Google MediaPipe Hand Landmarker** [S8]: official Android implementation guide and sample for live hand landmarks. We implement the gesture state machine and mapping to window actions.
- **enricoros/android-nreal** [S9]: older Android/Nreal Air driver work. Reference only; not verified for XBX.
- **Skarian/one-xr** [S10]: Android library for One/One Pro motion access. Reference only; not verified for XBX.

### 3.4 Official SDK route

XREAL states that Nebula is no longer maintained and offers ControlGlasses for development on supported Android phones [S11]. SDK 3.1.0 documentation lists Samsung S25 among tested hosts and carries an Android 16 compatibility warning [S12].

Neither statement proves support for the owner's exact S25 Ultra OS version plus a01+ model. Do not assume SDK support from a shared brand name. Do not downgrade the owner's OS or change firmware automatically.

The default investigation is direct native access based on the XBX protocol. An official SDK path can replace it only after a small sample demonstrates this exact configuration.

### 3.5 Research limitations

YouTube, Medium, and Substack were searched, but no verified end-to-end a01+ gesture-dock build tutorial was established. GitHub, manufacturer documentation, Google documentation, and the ScreenLab developer's own demonstration were more useful.

A search result for taowen/ar-glass-lib mentioned XBX, but the repository returned 404 when opened. Do not treat it as an available dependency without re-verifying access.

## 4. Scope

### Required prototype

- Connection and permission diagnostics.
- Real glasses orientation telemetry, clearly identified.
- A single external-display panel with image/test content, then local video.
- Touch controls for move, resize, pin/unpin, and recenter.
- Phone-camera hand landmarks and deliberate gestures.
- Recovery from tracking loss, camera interruption, and cable disconnect.
- Exportable diagnostic logs.
- Repeatable local build and APK installation instructions.

### Later, after core validation

- User-authorized capture of phone/app content.
- Optional Motorola controller over a local connection.
- Window-layout persistence.
- Better smoothing, drift handling, and performance optimization.
- Multi-panel content owned by our app.

### Not in initial scope

- Full Beam Pro feature parity.
- Independent arbitrary Android apps embedded as separate spatial windows.
- DRM bypass or guaranteed protected-video capture.
- System-wide gesture input without separately validating Android permissions and routing.
- Full 6DoF, SLAM, room anchors, eye tracking, or glasses-camera tracking.
- Cloud backend, accounts, payments, AI APIs, hosted databases, or internet-dependent gesture processing.
- Rooting, OS downgrades, firmware modification, or automatic device configuration changes.

## 5. Proposed architecture

This is a design proposal; revise with measured evidence.

| Component | Responsibility |
|---|---|
| Kotlin Android host | Lifecycle, permissions, connection UI, settings, diagnostics |
| Glasses transport | USB discovery, permission, protocol initialization, heartbeat, packets, disconnect |
| Pose pipeline | Timestamp handling, calibration, coordinate conversion, sensor fusion if needed |
| External renderer | Android secondary-display surface with OpenGL ES |
| Panel controller | Placement, scale, pin/follow state, recenter |
| Gesture pipeline | CameraX frames, MediaPipe landmarks, cursor mapping, gesture state machine |
| Content source | Test texture, image, local video, later MediaProjection |
| Optional remote controller | Local authenticated gesture events from Motorola |

Use Android Studio and Gradle. Begin with Kotlin and minimal dependencies. Introduce C++/NDK/JNI only where the audited protocol implementation or measured performance justifies it.

Use Android Presentation/DisplayManager as the initial external-display route [S13]. A visible external display does not guarantee every mode or surface combination works. Record resolution, refresh rate, display ID, and lifecycle events.

Start with a flat panel rendered onto the supported output mode. Implement proper perspective compensation for head rotation. Stereo/SBS is a separate capability gate; do not claim stereo rendering until mode switching and per-eye layout are validated.

### 5.1 Core interfaces

Keep transport, pose, rendering, and input separable. Suggested conceptual interfaces:

- GlassesTransport: discover, connect, startStream, stopStream, disconnect.
- PoseSource: timestamped orientation, tracking status, calibration status.
- PanelState: placement/orientation, scale, pinned/follow mode.
- GestureEvent: cursorMove, selectDown, selectUp, resize, recenter.
- ContentSource: texture/surface lifecycle and content dimensions.

Names are suggestions, not a requirement to over-engineer the first APK. A single app module with separate packages is sufficient initially.

### 5.2 Tracking correctness

- Determine whether upstream output is raw gyro/accelerometer data or a fused orientation.
- Document units, handedness, axis definitions, timestamps, and quaternion convention.
- Use one consistent coordinate system end to end.
- If integrating raw gyro, use device timestamps when reliable and handle wrap/reset.
- Calibrate bias while the device is still; expose recenter separately.
- Rendering a pinned panel conceptually applies inverse head rotation to a fixed panel pose. Verify multiplication order against the chosen coordinate convention.
- Keep sampling and rendering independent. A high sensor rate is not a display-frame-rate promise.
- Avoid multiple readers competing for the same USB endpoint.
- Measure drift and jitter instead of hiding them behind excessive smoothing.
- Test interaction between hardware stabilization and software compensation; do not assume stacking them improves results.

### 5.3 Gestures

Proposed first mapping:

| Input | Action |
|---|---|
| Point/index position | Move visible cursor in calibrated camera-relative space |
| Pinch over title bar, then move | Drag panel |
| Pinch over resize handle, then move | Resize panel |
| Pinch over pin button | Toggle pinned/follow mode |
| Hold open palm deliberately | Recenter |
| Release pinch | Finish active operation |
| Tracking loss | Release drag/selection and stop movement |

Normalize pinch distance by hand scale. Use confidence thresholds, smoothing, debounce, and separate engage/release thresholds. Specify and test left/right mirroring. Prefer visible targets to ambiguous global gestures. Always preserve touch fallback.

Do not assume MediaPipe's relative hand-depth values are accurate room coordinates.

### 5.4 Content and system boundaries

MediaProjection is a later content source, with explicit user consent and required foreground-service handling [S14]. Rendering captured pixels does not grant control of the source app.

Avoid capturing our own external output recursively. Preserve aspect ratio and correctly map cursor coordinates if input routing is later added. Protected content may remain blank. Do not promise Netflix or other DRM playback in a captured texture.

Accessibility, Shizuku, or other system-input approaches require a separate design and permission review. They are not dependencies for gestures manipulating our own panel.

### 5.5 Optional second phone

After the single-phone prototype passes:
- Run the camera/landmark pipeline on Motorola.
- Send compact events, not raw video, over the local network.
- Pair devices explicitly; use an ephemeral token and reject unknown senders.
- Include timestamps, sequence numbers, and a stale-event timeout.
- Release all active input when the connection drops.
- Keep internet and cloud services unnecessary.

## 6. Milestones and acceptance gates

### M0 — Environment and baseline

Collect Android/One UI version, glasses firmware if exposed, ScreenLab version, actual cable, development OS, Android SDK/JDK/Gradle availability, and whether pinning currently works well.

Document how to install and gather logs. Wireless ADB may be useful because glasses occupy the USB-C port; use only on the owner's trusted network.

Deliverable: environment record and reproducible baseline steps. Do not block independent project setup on every missing detail.

### M1 — Hardware diagnostic APK: first implementation task

Implement external-display enumeration and USB discovery/permissions. Investigate the exact XBX protocol and dependencies. Add read-only diagnostic output, streaming state, packet/error counters, and exportable logs.

Acceptance:
- Real device is detected; descriptors are recorded without fabricating IDs.
- Permission grant/denial is handled.
- Actual motion readings respond to controlled head rotations.
- Continuous stream runs for 5 minutes with errors/drops reported.
- Disconnect/reconnect recovers, or clearly reports a reproducible limitation.
- Any simulated mode is visibly labelled and excluded from hardware validation.

Do not add camera tracking, cloud services, or a polished launcher at this stage.

### M2 — One touch-controlled spatial panel

Render a test image/grid to glasses. Add touch-driven placement, scale, pin/follow, and recenter.

Acceptance:
- Resize and move work without clipping surprises or distorted aspect ratio.
- Pinning is visibly compensated during yaw/pitch/roll.
- Recenter restores a usable view.
- Run a stationary 60-second drift test and slow-turn test; record observations and available measurements.
- Cable loss and tracking failure leave a recoverable UI.
- Touch-only use does not require camera permission.

### M3 — Phone-camera gesture input

Add CameraX + MediaPipe with local processing, then the gesture state machine.

Acceptance:
- Cursor and hand-confidence feedback are visible.
- Pinch drag, resize, pin toggle, and recenter work in the documented camera setup.
- Tracking loss never leaves a stuck drag.
- A repeated 20-attempt interaction trial records successes and failures.
- Performance is measured while rendering, not only in the standalone camera demo.

### M4 — Useful content

Add local video first. Then implement user-consented MediaProjection if requested.

Acceptance:
- Aspect ratio and playback controls work.
- Capture permission denial, cancellation, and session termination are handled.
- No recursive capture loop.
- Record protected-content limitations rather than presenting them as bugs to bypass.

### M5 — Optional second phone and usability

Add Motorola controller only when needed. Test pairing, stale packets, disconnect release, and touch fallback. Improve onboarding and saved settings after core behavior is stable.

## 7. Performance and verification

Initial engineering goals, not established device performance:
- Aim for 60 fps rendering where supported; record actual frame timings.
- Aim for at least 20–30 hand-inference updates per second if thermal load permits.
- Minimize end-to-end interaction latency; distinguish measured latency from estimates.
- Observe heat, battery drain, drift, and jitter over 15 minutes before calling a milestone usable.

Useful automated tests:
- Packet decoding from attributed, sanitized real captures or verified fixtures.
- Timestamp wrap/reset behavior.
- Coordinate transforms and known orientation cases.
- Gesture hysteresis, release on tracking loss, and state transitions.

Hardware checks cannot be replaced by emulator tests, mocks, screenshots, or a successful Gradle build.

For each milestone, report separately:
1. Source/build status.
2. Automated checks run and their results.
3. APK install status.
4. Physical hardware behavior verified by the owner.
5. Remaining unknowns.

## 8. Working agreement for Codex and Claude Code

Suggested division: one primary implementer and one independent reviewer. Either tool may take either role.

- Read project.md and the latest status before editing.
- Inspect the current repository and git diff; preserve unrelated user changes.
- Use one shared repository and a clear task owner. Avoid concurrent edits to the same files; use branches/worktrees when parallel work is explicitly assigned.
- Keep this file canonical. Do not maintain conflicting architecture copies in agent-specific files.
- In AGENTS.md and CLAUDE.md, add a short instruction to read project.md; do not assume either tool automatically loads arbitrary Markdown.
- Pin dependency versions and record upstream commits/submodules used.
- Review third-party licenses before incorporating or redistributing code, native binaries, or models.
- Label unknowns. Never silently replace real device input with generated values.
- Make small milestone-scoped changes; do not rewrite working hardware code merely to match a preferred style.
- Do not expand into a full spatial OS before M1/M2 pass.
- Do not change OS, firmware, root state, or broad system settings as an automatic troubleshooting step.
- Keep diagnostic logs local and avoid camera recordings by default.
- Update status after meaningful work. Include changed files, commands run, test evidence, and the next concrete task.

### Agent bootstrap instruction

Read project.md as the shared project specification. Inspect the repository and current status. Work on the earliest incomplete milestone, starting with M0/M1. Separate upstream claims from physical-device validation. Do not implement gestures or a launcher before real display and IMU access have been demonstrated. Produce a buildable diagnostic APK, reproducible setup instructions, and an honest validation report.

## 9. Current status and handoff

| Area | Status |
|---|---|
| User goal/hardware | Established |
| Research references | Collected; listed below |
| Repository/project scaffold | Created (Kotlin, single app module); NOT under git yet |
| Driver code inspection | Done: xrealInterfaceLibrary (MIT) commit abeed62 |
| Dependencies/licenses | Protocol source MIT; app deps are AndroidX/Material only |
| APK build | Builds; installed and run on owner's S25 Ultra |
| Real sensor access | M1 COMPLETE on device (see handoff log) |
| External renderer | Not implemented (M2) |
| Gestures | Not implemented (M3) |

Next task: M2. See the handoff log below for the detailed status (updated 2026-10-25).

Open questions to collect during setup:
- Exact Samsung Android and One UI versions?
- Glasses firmware and observed USB vendor/product IDs?
- Does current ScreenLab Anchor stay stable, drift, or move in the wrong direction?
- Which camera placement is comfortable?
- Is initial content mainly local videos, browser content, or general phone mirroring?

### Handoff log

**2026-10-25 — Claude Code (second implementer, continuing a prior agent's scaffold) — no git repo yet**
- Active milestone: M1 (source complete for diagnostics; hardware acceptance NOT started).
- Source/build: `assembleDebug` succeeds (APK ~14 MB, `app/build/outputs/apk/debug/app-debug.apk`). compileSdk/targetSdk 35 (AGP 8.7.3; installed SDK platform `android-37.0` is not resolvable by this AGP — Gradle auto-installs 35). Needs JDK 17–21: Android Studio's bundled JBR is Java 25 and breaks Gradle 8.9; use `C:\Users\ASUS\.jdks\jbr-21.0.11`. Fixed: `settings.gradle.kts` (`dependencyResolutionManagement`), generated `gradle-wrapper.jar`.
- Automated checks: `testDebugUnitTest` 5/5 pass (`XbxProtocolTest`). These are synthetic/self-consistency tests plus the standard CRC32 vector, NOT real captures.
- Driver inspection DONE: the xrealInterfaceLibrary submodule is public + MIT (`gitlab.com/wheaney/nrealAirLinuxDriver`, commit abeed62, HEAD 61ecd4c). The earlier note that it was "private/unavailable" was wrong. Full bootstrap/heartbeat/IMU layout recorded in `XbxProtocolNotes.kt`, implemented in `XbxProtocol.kt` + `XbxSession.kt`.
- New in the app: "Handshake + IMU" section. It WRITES to the glasses MCU (upstream sequence), only on button press, then streams IMU with counters (reports, timeouts, errors, timestamp gaps/regressions, device-timestamp rate) and logs exportable.
- APK install: not performed. Hardware: nothing tested on the S25 Ultra or a01+.
- Known risks / unknowns: MCU interface may lack an OUT endpoint (code falls back to HID SET_REPORT, logs which); Samsung may block interface claims; unit/axis conventions unknown; upstream's IMU start message (0x19) not used for XBX upstream — add only if the stream stays silent after a successful handshake.
- Next action (owner): install APK, connect glasses, press Scan → grant permission → Handshake + IMU; export the log. Then check: motion responds to head turns, 5-minute run, unplug/replug. Use the log to fix whatever fails first. Do not start M2 before this passes.

**2026-10-25 (later) — Claude Code — first owner hardware run + crash fix**
- Hardware/configuration tested by owner: Samsung SM-S938B, Android 17 (SDK 37), build CP2A.260605.016.S938BXXUCDZIF, SoC SM8750; XREAL xbx a01+.
- OBSERVED ON DEVICE (owner logs, 17:49):
  - USB: VID 0x3318 / PID 0x0442 confirmed; bcdDevice 0x0001; 3 interfaces, all HID class 3, each with interrupt IN + interrupt OUT, maxPkt 64, interval 1 (if0: IN 0x82/OUT 0x03; if1: IN 0x84/OUT 0x05; if2: IN 0x86/OUT 0x07). Matches upstream's MCU=if0, IMU=if1 expectation structurally (not yet functionally).
  - USB permission already granted; claimInterface(force) succeeded on all 3.
  - External display: id 344 "xbx a01+", 1920×1080 @60 Hz, modes 60/90/120, flags PRESENTATION|SECURE|PROTECTED_BUFFERS.
  - Passive raw read (no handshake): zero bytes on all reads. Android returns -1 on timeout, so this is "no data", consistent with upstream's claim that the IMU stream is gated.
  - Pressing Handshake + IMU: app crashed. No stack trace captured (no ADB connection).
- Fixes (cause not confirmed; all plausible crash paths closed): background-coroutine exceptions are now caught and reported instead of killing the process; raw reader and session are joined before a new session claims interfaces; workers are joined before the USB connection is closed on detach/destroy; -1 is counted as an empty read (it was counted as an error and the session gave up after 200); USB permission PendingIntent uses an explicit package (Android 14+ throws otherwise); Scan while connected no longer opens a second connection; uncaught exceptions are written to `last-crash.txt` and the session log is mirrored to `last-session.log`. Both appear in the next Export Log.
- Automated: assembleDebug OK, 5/5 unit tests pass.
- Next action (owner): install new APK, press Handshake + IMU directly (no need for the raw reader first), Export Log. If it crashes again, relaunch and export: the crash trace is included. If the crash is native (no file), `adb logcat -b crash` is needed.

**2026-10-25 18:03 — Claude Code — second owner run: handshake + first real IMU sample**
- OBSERVED ON DEVICE:
  - Full upstream bootstrap acknowledged (0x26, 0x57, 0x12, 0x02, 0x34, 0x35, 0x31 "3.1.1", 2× heartbeat 0x1A). Every reply is a 64-byte 0xFD frame echoing the command id at offset 15, status byte 00 at offset 22, payload from 23.
  - 0x26 reply payload begins "19.1.01.1" (likely MCU firmware version; truncated in log).
  - IMU stream opened immediately after the handshake. First sample: accel (-0.071, 0.452, 0.899) → |a| = 1.01 g (layout and accel scaling confirmed); gyro (-0.64, -6.06, -14.31), unit still unconfirmed; temp 29.7 °C; device timestamp 26.38 s (ns).
  - Heartbeat writes to OUT 0x03 started timing out ~3.9 s after streaming began. Next run: a stale heartbeat ack was still queued on IN 0x82 and the very first bootstrap write failed → MCU stayed wedged across app restarts. Session worker then hung (no FAILED log line) → attributed to releaseInterface.
  - Previous run's process ended with no Java crash file (cause unknown: native crash, kill, or user close).
- Diagnosis: Android does not auto-read the MCU IN endpoint as Linux hidraw does, so heartbeat acks accumulate and the MCU stops accepting writes.
- Fixes: continuous MCU IN drain coroutine while streaming; ack matching requires the echoed command id; no releaseInterface calls; STATS line logged every 5 s to the crash-surviving session log; clearer message suggesting a replug when bootstrap fails.
- Automated: 8/8 unit tests pass, 3 from real captures above.
- Next action (owner): unplug/replug glasses (to clear the wedged MCU), install, Handshake + IMU, turn head for ~1 min, leave for 5 min, Export Log.

**2026-10-25 18:16 — Claude Code — third owner run: sustained IMU stream**
- OBSERVED ON DEVICE (after replug, with the MCU drain):
  - MCU firmware string (0x26 reply): `19.1.01.115_20260611`.
  - 39 s streaming (stopped by owner): 38,726 samples, device-timestamp rate 962–975 Hz (steady ~970 Hz), 0 timestamp regressions, 0 unknown/short reports, max gap 26 ms (occurred in the first 4 s, never exceeded after). Heartbeats 392 sent, 0 write failures, so the wedge is fixed.
  - Accel stayed ≈1 g throughout. Gyro values respond to head motion (peaks seen: gx 34.7, gz −64.9, gz 91.1); at rest ≈0.5. Magnitudes are consistent with deg/s (91 rad/s is impossible for a head) but this is NOT yet confirmed by a controlled turn.
  - Temperature rose 31.3 → 34.4 °C over 40 s.
  - Device sends MCU messages 0x6c02 then 0x6c12 (5 ms apart) about every 20 s. Upstream names them P_START_HEARTBEAT / P_END_HEARTBEAT and ignores them. Header byte 14 is 0x02 on these and 0x01 on command replies (meaning unconfirmed).
  - The device timestamp restarts on replug (25.26 s at first sample, i.e. device uptime-like).
- Bugs found from this log and fixed: the drained-ack counter was overwritten by IMU stats publishing (it showed 0/1, so the real ack rate is still unknown); the ATTACHED-intent re-scan opened a second USB connection; the raw reader showed a stale status after being stopped.
- Added: gap histogram + estimated missing samples (assumes 1 ms period); MCU event counter; a "Zero" turn test (1 s bias average while still, then integrates gyro over device time, diagnostic only, no fusion).

**M1 acceptance status (2026-10-25)**

| Criterion | Status |
|---|---|
| Real device detected, descriptors recorded | PASS (on device) |
| Permission grant handled | PASS (grant observed); denial not yet tested |
| Motion responds to controlled head rotation | PARTIAL: responds to free movement; controlled turn test (units/axes) pending |
| 5-minute continuous stream with drops reported | PENDING: 39 s so far, clean |
| Disconnect/reconnect recovers or limitation reported | PENDING: replug between runs requires re-pressing Handshake; unplug during streaming not tested |
| Simulated mode labelled/excluded | PASS: none exists |

- Next action (owner): turn test (Zero while still, then turn head right ~90° and back, then nod up ~45° and back, then tilt ear to shoulder), then leave streaming ≥5 min, export. Then unplug while streaming, replug, press Handshake again, export.

**2026-10-25 18:32–19:23 — Claude Code — fourth owner run: turn test + long stream**
- OBSERVED ON DEVICE (export truncated by chat paste limit; analysis from the 19:19 session and most of the 18:32 session):
  - 19:19 session: 262 s, 255,857 samples, 973.9 Hz, 0 timestamp regressions, max gap 16 ms, 0 gaps > 20 ms. Heartbeats 2,578 / 0 failures; MCU acks drained 2,604 = heartbeats + 26 events. So the MCU acks every heartbeat, and draining is required on Android.
  - 18:32 session: ≥239 s with the same profile (max gap 13 ms), ended "Stopped (disconnect or new session)"; that reason was ambiguous, so it is now split.
  - Missing samples: 6,848 in gaps > 1.5 ms ≈ 2.6% (assuming a 1 ms device period; 974 Hz / (1 − 0.026) ≈ 1000 Hz supports this). Likely cause (hypothesis): synchronous one-transfer-at-a-time reads leave the interrupt endpoint unpolled between calls. Candidate fix: several queued UsbRequests. Not blocking, since integration uses device timestamps.
  - GYRO UNIT = deg/s, CONFIRMED by cross-check against gravity (accelerometer), independent of human angle estimates:
    - Pitch move: gyro-integrated ΔX = −26.0°, accel tilt change = −25.3°.
    - Roll move: gyro ΔY = +53.3°, accel tilt change = +55°. (18:32 session: +57.4° vs +54.9°.)
    - Yaw: Z reached −74.2° on the turn and came back to +0.2° at centre (no gravity reference for yaw).
  - AXES (glasses frame, from these moves; signs assume the owner followed the instructed order): Z = yaw (turn right → Z negative), X = pitch (nod up → X negative), Y = roll.
  - Gyro bias at rest ≈ (−0.13, −0.14, 0.01) deg/s; residual drift after bias removal ≈ a few degrees over 4 min.
  - Temperature rises 37.5 → 43.5 °C in 4 min of streaming (watch for bias drift with temperature).
  - New MCU message 0x6c05 (upstream P_BUTTON_PRESSED) appeared in bursts at 18:34:47–50, payload byte 22 = 03, byte 26 cycling 10/11/01 (press/hold/release? unconfirmed); presumably the owner pressed a glasses button.
- Fixes/changes: display log no longer re-logs unchanged displays and lists distinct refresh rates (the export overflowed the paste limit); stop reasons split ("USB disconnected" / "Replaced by a new session" / "Raw reader started" / user); session auto-resumes after replug if it was streaming when the glasses detached; button events counted and logged.

**M1 acceptance status (updated 2026-10-25 19:30)**

| Criterion | Status |
|---|---|
| Real device detected, descriptors recorded | PASS |
| Permission grant handled | PASS (grant); denial not tested |
| Motion responds to controlled head rotation | PASS: units and axes established, pitch/roll cross-checked against gravity within ~5% |
| 5-minute continuous stream with drops reported | NEAR: 4 min 22 s clean (+ ≥4 min earlier); one ≥5 min run still needed |
| Disconnect/reconnect recovers or limitation reported | PENDING: clean stop on (probable) unplug; reconnect + auto-resume not yet observed |
| Simulated mode labelled/excluded | PASS: none exists |

- Next action (owner): Handshake + IMU, leave ≥6 min untouched, then unplug while streaming, replug, grant permission, and confirm the session restarts by itself ("Auto-resumed after USB reconnect" in the log). Export once at the end; it should now be far shorter.

**2026-10-25 19:33 — Claude Code — fifth owner run: long stationary stream**
- OBSERVED ON DEVICE (glasses lying still on a table, upside-down: accel ≈ (−0.01, −0.30, −0.955) g):
  - 528 s (8 min 48 s) continuous, stopped by owner: 512,566 samples, 969.9 Hz, 0 timestamp regressions, max gap 21 ms (one gap > 20 ms), est. missing ≈ 3.0% (same pattern as before). Heartbeats 5,186 / 0 failures; acks 5,238 = heartbeats + 52 events. 5-MINUTE CRITERION: PASS.
  - Stationary drift after a 1 s bias zero (bias this session (−0.266, −0.208, −0.025) deg/s, which differs from the previous session's (−0.133, −0.137, 0.006), so bias must be re-estimated each session): after ~507 s of integration, X −6.8°, Y −7.4°, Z (yaw) +1.6°. That is pitch/roll ≈ 0.8–0.9°/min (gravity can correct these in fusion) and yaw ≈ 0.19°/min. Drift was steady and linear while temperature rose 32.4 → 38.6 °C (a residual bias of only ~0.013 deg/s, plausibly thermal).
  - Hot-plug while the app was already open: attach → permission → connection worked; the second ATTACHED-intent scan correctly kept the existing connection (fix verified).
  - Display log is now compact (fix verified). The session log still overflowed the paste limit (110 STATS lines), so STATS are now every 5 s for 30 s, then every 30 s, in a shorter format; periodic 0x6c02/0x6c12 are logged once, then only counted.
  - Unplug/replug during streaming: NOT performed in this run.

**M1 acceptance status (updated 2026-10-25 19:45)**

| Criterion | Status |
|---|---|
| Real device detected, descriptors recorded | PASS |
| Permission grant handled | PASS (grant); denial not tested |
| Motion responds to controlled head rotation | PASS (deg/s; X pitch, Y roll, Z yaw; cross-checked with gravity) |
| 5-minute continuous stream with drops reported | PASS (8 min 48 s; ≈3% short gaps ≤ 21 ms, reported) |
| Disconnect/reconnect recovers or limitation reported | PENDING: only remaining M1 item |
| Simulated mode labelled/excluded | PASS: none exists |

- Next action (owner, ~2 min): Handshake + IMU → wait 20 s → unplug glasses → replug → grant permission → confirm "Auto-resumed after USB reconnect" and samples rising → Export.

**2026-10-25 19:43 — Claude Code — sixth owner run: unplug/replug during streaming → M1 COMPLETE**
- OBSERVED ON DEVICE: detach 19:43:45.790 → workers stopped and connection closed 15 ms later (no crash) → attach 19:43:50.776 → permission prompt granted 19:43:51.739 → session auto-resumed, bootstrap acked 19:43:51.807, first IMU sample 19:43:51.808. Then 32 s streaming: 991 Hz, 0 heartbeat failures, 0 regressions, max gap 13 ms.
  - The device timestamp restarted at 2.64 s, so the glasses MCU reboots on replug; the timestamp behaves like device uptime.
  - Android asks for USB permission again after every replug (expected: per-attach grant).
  - Gap: the auto-resumed session truncated the file log of the session cut short by the unplug. Fixed: auto-resume now appends to that file, and Export reads the file.

**M1 acceptance status (final, 2026-10-25)**

| Criterion | Status |
|---|---|
| Real device detected, descriptors recorded | PASS |
| Permission grant/denial handled | PASS for grant (incl. re-grant on replug); the denial branch exists but is NOT exercised on device |
| Motion responds to controlled head rotation | PASS (deg/s; X pitch, Y roll, Z yaw; cross-checked with gravity) |
| 5-minute continuous stream with drops reported | PASS (8 min 48 s; ≈3% short gaps ≤ 21 ms) |
| Disconnect/reconnect recovers | PASS (auto-resume ≈1 s after attach, including the permission prompt) |
| Simulated mode labelled/excluded | PASS: none exists |

Known M1 limitations carried into M2: ~3% of IMU samples lost in short gaps (hypothesis: synchronous single-transfer reads; try queued UsbRequests); per-session gyro bias must be re-estimated at start; pitch/roll drift ~0.9°/min and yaw ~0.2°/min uncorrected (no fusion yet); repository is not under version control.

Next: M2 (one touch-controlled spatial panel on display "xbx a01+", 1920×1080 @60 Hz, PRESENTATION).

**2026-10-25 — Claude Code — M2 first build (branch `feature/m2-spatial-panel`), NOT yet tested on hardware**
- Built:
  - `pose/` (Quat, Mahony filter kp=1.0 ki=0.02, per-session still-window gyro bias, IMU→GL axis map from measured conventions with a "flip nod/tilt" fallback).
  - `panel/` (PanelController: follow/pin/recenter, drag/pinch, FOV calibration, auto-recenter on a new tracking frame; GLES2 renderer at display vsync with tints: yellow = calibrating, red = tracking lost/frozen; Presentation on the first PRESENTATION display).
  - Phone control page ("🕶 Spatial panel (M2)") with touchpad + buttons. PANEL stats lines (fps, max frame interval, pose age, head YPR) every 10 s in Export. No camera permission involved.
- Design choices: one flat mono panel (no SBS); pinned panel kept upright in the world frame; yaw uncorrected (Recenter); FOV default 25.8° vertical from the upstream 50° diagonal claim, adjustable; no pose prediction yet (pose age is measured instead).
- Automated: 23/23 unit tests (8 protocol, 9 pose incl. measured-convention end-to-end cases, 6 panel geometry).
- Known assumption to verify first on device: nod-up sign (X = left). If nod/tilt move the panel the wrong way, use Flip.

### Handoff entry template

- Date / agent / branch / commit:
- Active milestone:
- Changes made:
- Commands and automated checks:
- Hardware/configuration tested:
- Measured results or owner observations:
- Known issues:
- Next action:
- Changes to assumptions/decisions:

## 10. Source registry

Links are included so another agent can independently verify the evidence. Sources were consulted on 2026-10-25; support and repository state can change.

- **[S1] XREAL XBX manual:** https://tutorials.xreal.com/docs/glasses/xbxa01/ — display connection and built-in stabilization.
- **[S2] Samsung S25 multi-device/DeX documentation:** https://www.samsung.com/us/support/answer/ANS10004603/ — wired display capability.
- **[S3] Motorola compatibility chart:** https://en-emea.support.motorola.com/app/answers/detail/a_id/160550/~/smart-connect-compatibility-chart — Edge 50 Fusion wired display marked unavailable.
- **[S4] XRLinuxDriver v2.11.3:** https://github.com/wheaney/XRLinuxDriver/releases/tag/v2.11.3 — exact-model tracking support and protocol caveats. Repository: https://github.com/wheaney/XRLinuxDriver
- **[S5] ScreenLab developer announcement/demo:** https://www.reddit.com/r/Xreal/comments/1v76yjr/xbx_3dof_vr360_side_view_more_screenlab_xr_v109/ — a01+ support and tuning. App: https://play.google.com/store/apps/details?id=com.northnroro.nroro_shader
- **[S6] Xreal-tools:** https://github.com/nudou350/Xreal-tools — gesture mouse reference for different hardware.
- **[S7] XReelOs:** https://github.com/SpendinFR/XReelOs — spatial shell and hardware validation notes for different hardware.
- **[S8] MediaPipe Hand Landmarker Android:** https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker/android — official implementation guide. Samples: https://github.com/google-ai-edge/mediapipe-samples
- **[S9] Android-Nreal:** https://github.com/enricoros/android-nreal — older Air-specific Android reference.
- **[S10] one-xr:** https://github.com/Skarian/one-xr — One/One Pro-specific Android reference.
- **[S11] XREAL developer downloads:** https://developer.xreal.com/download/ — Nebula maintenance and ControlGlasses information.
- **[S12] XREAL SDK release and compatibility:** https://docs.xreal.com/Release%20Note/XREAL%20SDK%203.1.0 and https://docs.xreal.com/XREALDevices/Compatibility — version-specific host/model constraints.
- **[S13] Android Presentation API:** https://developer.android.com/reference/android/app/Presentation — secondary-display presentation.
- **[S14] Android MediaProjection:** https://developer.android.com/media/grow/media-projection — capture lifecycle, consent, and service requirements.

## 11. Decision log

| Date | Decision | Reason |
|---|---|---|
| 2026-10-25 | Native Kotlin Android first | Direct USB, rendering, camera, and lifecycle control |
| 2026-10-25 | S25 Ultra is primary host | Existing hardware with wired display capability |
| 2026-10-25 | Exact-model driver validation precedes UI | Largest technical uncertainty |
| 2026-10-25 | One panel, touch first, gestures second | Isolate tracking/rendering failures from input failures |
| 2026-10-25 | No backend for prototype | All core functions can be designed for local execution |
| 2026-10-25 | Motorola optional | Avoid networking complexity until it adds clear value |
| 2026-10-25 | Official SDK is an evaluated alternative | Exact a01+/OS combination not established |

