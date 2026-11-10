<div align="center">

# GeXRture

**A smart dock for XR glasses. Share your screen, pin it in space, and control it with your hands.**

![Status](https://img.shields.io/badge/status-early%20stage-orange)
![Platform](https://img.shields.io/badge/platform-mobile-blue)
![License](https://img.shields.io/badge/license-MIT%20%2F%20Apache--2.0-green)
![PRs](https://img.shields.io/badge/PRs-welcome-brightgreen)

*GeXRture = **Ge**sture + **XR***

</div>

---

## What is GeXRture?

XR glasses give you a huge virtual display, but the apps around them mostly handle casting and layout. Almost none let you **reach out and interact with your hands**.

GeXRture is a mobile app that turns your phone into a dock for your glasses:

- **Share** your phone screen or a single app to the glasses
- **Pin** it in space, so it stays where you put it
- **Follow**: the screen can follow you when you move
- **Gesture control** *(next milestone)*: move, resize and switch windows with your hands

I started this after buying my first pair of XREAL glasses and finding that gesture input was missing everywhere. I then validated the idea with Reddit users and college students, and their feedback shaped the roadmap below.

---

## How it looks

```text
                    YOUR VIEW THROUGH THE GLASSES

   .----------------------------------------------------------------.
   |                                                                |
   |   .--------------------------------------------------------.  |
   |   |  GeXRture                                    _  []  x  |  |
   |   |--------------------------------------------------------|  |
   |   |                                                        |  |
   |   |        .----------------.     .------------------.     |  |
   |   |        |   App window   |     |   Shared phone   |     |  |
   |   |        |    (pinned)    |     |      screen      |     |  |
   |   |        '----------------'     '------------------'     |  |
   |   |                                                        |  |
   |   |           ~ a big virtual screen, pinned in space ~    |  |
   |   '--------------------------------------------------------'  |
   |                                                                |
   |            \o/   <- your hand (gesture control, coming)       |
   |                                                                |
   '----------------------------------------------------------------'
```

```text
                    THE SETUP

        .-------.          sharing           .--------------.
        | PHONE |  ----------------------->  |  XR GLASSES  |
        |  App  |      screen + app          |  (virtual    |
        '-------'                            |   big screen)|
            |                                '--------------'
            |   pin / follow / gestures             ^
            '---------------------------------------'

                       ___________
                      /  _     _  \
                     |  (_)___(_)  |     <- you, wearing XR glasses,
                     |     \_/     |        seeing a large pinned screen
                      \___________/
```

---

## Project status

### Done

| Feature | Status |
|---|---|
| Screen sharing to the glasses | ✅ Working |
| Application sharing | ✅ Working |
| Pinning a screen in space | ✅ Working |
| Pinned screen follows the user | ✅ Working |

### In progress

| Feature | Status |
|---|---|
| Hardware bring-up (M1): USB handshake and IMU data from the glasses | 🟡 Testing, see [Hardware check](#hardware-check-milestone-m1) |
| Hand-gesture control | 🟡 Design and research |
| Tests and CI | 🟡 Being set up |

### On the way

| Feature | Status |
|---|---|
| Documentation and contributor guide | ⬜ Planned |
| Support for more glasses brands | ⬜ Planned |
| Edge AI / Raspberry Pi support | ⬜ Planned |

> GeXRture is at an early stage. Expect rough edges, and please open an issue when you find one.

---

## Roadmap

```text
 PHASE 1            PHASE 2              PHASE 3             PHASE 4              PHASE 5
 Foundation   -->   Gestures       -->   More devices  -->   Edge AI / Pi   -->   Community
 [#####-----]       [##--------]         [----------]        [----------]         [##--------]
  in progress        design stage          planned             planned             ongoing
```

### Phase 1: Stabilise the foundation
- [x] Core sharing, pinning and follow
- [ ] Tests for sharing, pinning and follow
- [ ] CI that builds, lints and tests every PR
- [ ] README, setup guide, architecture overview and CONTRIBUTING
- [ ] Issue and PR templates

### Phase 2: Hand-gesture control
- [ ] Research mobile hand-tracking options (latency, battery, accuracy)
- [ ] Prototype a small gesture set: **pinch**, **swipe**, **grab / move**
- [ ] Map gestures to actions: move, resize, close, switch window
- [ ] Accuracy tests and false-positive handling
- [ ] Gather user feedback on how gestures feel

### Phase 3: Broader device support
- [ ] Separate the glasses-specific code behind a clean interface
- [ ] Add a second family of glasses
- [ ] Publish a compatibility matrix

### Phase 4: Edge AI and Raspberry Pi
- [ ] Research spike on running the gesture pipeline on low-power devices
- [ ] Port the pipeline to a Raspberry Pi
- [ ] Publish benchmarks for latency, power and accuracy

### Phase 5: Community and growth
- [ ] Demo videos and write-ups
- [ ] Well-scoped `good first issue` labels
- [ ] Regular releases with changelogs

The full plan, including time estimates, is in [ROADMAP.md](ROADMAP.md).

---

## Supported glasses

| Glasses | Status |
|---|---|
| XREAL | ✅ First supported brand |
| Others | 🔜 Planned (see Phase 3) |

---

## Getting started

```bash
git clone https://github.com/HarshVasisht/GeXRture.git
cd GeXRture
```

### Build (Windows)

Requires **JDK 17-21** (not Android Studio's bundled Java 25) and the **Android SDK** (`local.properties` must contain `sdk.dir`).

```powershell
$env:JAVA_HOME = "C:\path\to\your\jdk-21"
.\gradlew.bat assembleDebug testDebugUnitTest
```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

### Install

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> **Note:** the glasses occupy the phone's USB-C port, so use **wireless ADB** instead:
> Settings > Developer options > Wireless debugging, then `adb pair` and `adb connect`.
> Only do this on a trusted network.

### Hardware check (milestone M1)

If you have a pair of supported glasses, this check helps validate the connection layer:

1. Open GeXRture with the glasses connected and grant the USB permission prompt.
2. Check the **USB** and **Displays** sections and record the VID/PID and display mode.
3. Tap **Handshake + IMU**. This writes the upstream-documented bootstrap to the glasses MCU. Turn your head; the gyro and accelerometer values should respond.
4. Leave it running for 5 minutes and note any timeouts, errors or timestamp regressions. Then unplug and replug the glasses.
5. Tap **Export Log** and attach the log to your bug report or PR.

Testing results on other glasses are very welcome. Please open an issue with your log and glasses model.

---

## Contributing

Contributions are welcome, especially:

- Testing on other XR glasses
- Ideas and feedback on gesture design
- Bug reports with steps to reproduce

Look for issues labelled `good first issue` once they are available, and open an issue before starting large changes. A full `CONTRIBUTING.md` is part of Phase 1.

---


## License

Released under the **[MIT / Apache-2.0]** license. See [LICENSE](LICENSE).

---

<div align="center">

If you like where this is going, **star the repo** and tell other XR users.

</div>
