# Lockscreen Recorder

[![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-blue)](https://github.com/LSPosed/LSPosed)
[![libxposed](https://img.shields.io/badge/libxposed-API%20102-brightgreen)](https://github.com/libxposed/api)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

LSPosed module (modern **libxposed API 102**) that lets **Samsung SmartCapture**
(`com.samsung.android.app.smartcapture`) run on the **lock screen** of a Samsung
device (developed & tested on a Galaxy S26 Ultra, SM-S948B, Android 17 / One UI):

- **Screen recording** from the Quick Settings tile directly on the lock screen — no PIN asked
- **AI select** (Smart Select) unblocked — no more *"blocked by security policy"* toast

## How it works

Inside the SmartCapture process only, the module makes the app believe the device is
unlocked and that no Knox/MDM policy forbids capture:

| Hook | Effect |
|---|---|
| `KeyguardManager#isKeyguardLocked()` / `isDeviceLocked()` → `false` | unblocks AI-select validation and stops the recorder service from deferring until unlock |
| `TileService#isLocked()` → `false` | the Quick Settings tile no longer calls `unlockAndRun()` (the PIN prompt) on the lock screen |
| `DeviceUtils#isRestrictedByMdm(...)` → `false` | ignores Knox `RestrictionPolicy3` (`isScreenCaptureEnabled`, `isSmartClipModeAllowed`, …) |
| `DeviceUtils#isKeyguardEnabled/isKeyguardLocked`, `CommonUtil#isDeviceLocked` → `false` | keyguard helpers report "unlocked" |
| `ScreenRecorderUtils#isSettingsChangesAllowed` → `true` | tile tap allowed even under a Knox settings policy |
| `ScreenRecorderController#stopRecordingAccordingToAction(DEVICE_LOCKED)` ignored | recording keeps running when the keyguard shows |
| `Dialog#show()` + `FLAG_SHOW_WHEN_LOCKED` | SmartCapture dialogs can appear over the lock screen |
| `showSoundSettingSuggestionPopup()` → `checkPreConditionsThenStartRecording()` | skips the sound-choice popup (hidden behind the keyguard) — recording starts directly |
| `ImageUtils#isEmpty(Bitmap)` → `false` | pre-flight screenshot check can't fail with `SECURE_LAYER_POLICY` |

All hooks are wrapped in try/catch with `ExceptionMode.PROTECTIVE`: if a target is
missing on another One UI version, the hook is skipped with a warning in the Xposed
log — the module never crashes the app. Framework hooks (`KeyguardManager`,
`TileService`, `Dialog`) are stable AOSP APIs and work on any version.

The module declares `staticScope=true` + `META-INF/xposed/scope.list`, so LSPosed
pins the scope to SmartCapture only.

## Requirements

- Device: Samsung with One UI + **LSPosed 2.x / Vector 2.2+** (API 102), Zygisk on, rooted
- Build machine: JDK 17+, Android SDK with `platforms;android-37` and `build-tools;36.0.0`

## Build

```bash
./gradlew :app:assembleDebug    # quick local build (debug-signed)
./gradlew :app:assembleRelease  # signed release — needs a local keystore (see below)
```

Outputs: `app/build/outputs/apk/{debug,release}/`

Release signing uses a **local, gitignored** keystore. Create one once:

```bash
mkdir -p .signing
keytool -genkeypair -keystore .signing/lockscreen-recorder.jks \
  -alias lockscreen -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android -dname "CN=Lockscreen Recorder"
```

Override with env vars if needed: `LSR_KEYSTORE`, `LSR_ALIAS`, `LSR_STORE_PASS`, `LSR_KEY_PASS`.
(`*.jks` / `.signing/` are in `.gitignore` — never commit keystores.)

## Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then in the **LSPosed manager**: enable *Lockscreen Recorder* (scope is pre-filled
thanks to `staticScope`), and restart the scoped app (or reboot):

```bash
adb shell su -c 'am force-stop com.samsung.android.app.smartcapture'
```

## Usage

On the lock screen, open the Quick Panel and tap **Screen recorder** — recording
starts immediately (countdown, then recording), no PIN. Tap again (or the
notification) to stop. Videos land in `DCIM/Screen recordings/`.

Verified on-device: with the phone locked, `ACTION_START` went straight to
`MP4Recorder startMuxer` with an active `TYPE_SCREEN_CAPTURE` media projection and
produced a valid mp4.

## Logs

- Module logs (tag `LockscreenRecorder`): Xposed log → `/data/adb/lspd/log/verbose_*.log`
- SmartCapture recorder logs: `logcat` with tags `[SC_ScreenRecorder]_*`

## Repo layout

```
app/                 module source (Gradle, AGP, libxposed api:102.0.0 compileOnly)
docs/                reverse-engineering notes (how the hooks were found)
scripts/             helpers (pull APKs from a rooted device, ...)
```

`apks/`, `decompiled/`, `tools/`, `device-db/`, `artifacts/` are gitignored local
working dirs (Samsung binaries/sources stay on your machine — see
`docs/reverse-engineering.md` and `scripts/pull-apks.sh` to redo the analysis).

## License

MIT — see [LICENSE](LICENSE). This project contains no Samsung code; it only hooks
behaviour at runtime on your own device.
