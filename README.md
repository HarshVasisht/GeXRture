# GeXRture

Gesture-controlled spatial dock for XREAL xbx a01+. Read [project.md](project.md) first. Current state: M1 hardware diagnostic APK (source builds; hardware unvalidated).

## Build (Windows)

Requires JDK 17–21 (not Android Studio's bundled Java 25) and the Android SDK (`local.properties` has `sdk.dir`).

```powershell
$env:JAVA_HOME = "C:\Users\ASUS\.jdks\jbr-21.0.11"
.\gradlew.bat assembleDebug testDebugUnitTest
```

APK: `app\build\outputs\apk\debug\app-debug.apk`.

## Install

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The glasses occupy the phone's USB-C port, so use wireless ADB (Settings > Developer options > Wireless debugging; `adb pair`, `adb connect`) on a trusted network.

## Hardware check (M1)

1. Open GeXRture with the glasses connected. Grant the USB permission prompt.
2. Check the USB and Displays sections (record VID/PID, display mode).
3. Tap **Handshake + IMU** (writes the upstream-documented bootstrap to the glasses MCU). Turn your head; gyro/accel values should respond.
4. Leave it running 5 minutes; note timeouts, errors, timestamp regressions. Unplug and replug.
5. **Export Log** and attach it to the next handoff entry in project.md.
