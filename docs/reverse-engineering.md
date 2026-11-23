# Reverse-engineering notes (how the hooks were found)

These notes describe **how** the lock-screen blocks in Samsung SmartCapture were located,
so the module can be re-targeted at another One UI / SmartCapture version.
Only behavioural facts are documented here — no Samsung code is copied into this repo.

## Workflow

1. Pull the APKs from a rooted, connected device (see `scripts/pull-apks.sh`):
   - `com.samsung.android.app.smartcapture` → `/system/app/SmartCapture/SmartCapture.apk`
   - `com.android.systemui` → `/system_ext/priv-app/SystemUI/SystemUI.apk`
2. Decompile with jadx CLI (any recent version):
   ```bash
   jadx -j 8 --no-res --show-bad-code -d decompiled/smartcapture apks/SmartCapture.apk
   ```
3. Grep for the interesting keywords:
   ```bash
   rg -l 'isKeyguardLocked|isDeviceLocked|KeyguardManager' decompiled/smartcapture/sources
   rg -l 'RestrictionPolicy|isScreenCaptureEnabled|KnoxPropertyManager|DevicePolicyManager' decompiled/smartcapture/sources
   ```

## Key findings (SmartCapture 6.2.44.29, One UI on SM-S948B / Android 17)

| Behaviour | Location |
|---|---|
| QS tile asks for PIN when locked | `com.samsung.android.app.screenrecorder.view.RecordScreenTile.onClick()` → `isLocked()` ? `unlockAndRun(...)` : start |
| Recording deferred until unlock | `screenrecorder/ScreenRecorderService.onStartCommand()` → `KeyguardManager.isKeyguardLocked()` → `semSetPendingIntentAfterUnlock(...)` |
| Recording stopped when keyguard shows | `screenrecorder/ScreenRecorderController` receiver for `com.samsung.keyguard.KEYGUARD_STATE_UPDATE` → `stopRecordingAccordingToAction(RecordingStopReason.DEVICE_LOCKED)` |
| Sound-choice popup | `ScreenRecorderController.showSoundSettingSuggestionPopup()` (window type `TYPE_APPLICATION_OVERLAY`, hidden behind keyguard by policy) → start via `checkPreConditionsThenStartRecording()` |
| Pre-flight screenshot check | `ScreenRecorderController.checkPreConditions()` → `CaptureUtils.captureScreen(...)` → empty bitmap ⇒ `ScreenRecorderError.SECURE_LAYER_POLICY` |
| Knox/MDM "security policy" blocks | `baseutil/device/DeviceUtils.isRestrictedByMdm(ctx, "isScreenCaptureEnabled" / "isSmartClipModeAllowed")` → queries `content://com.sec.knox.provider/RestrictionPolicy3` |
| AI-select launch validation | `aiassist/controller/launchvalidation/LaunchValidator` chain → `DeviceLockedValidationHandler` (`CommonUtil.isDeviceLocked`) shows `R.string.ai_select_blocked_security_policy`; `MdmRestrictionValidationHandler` / `EdmRestrictionValidationHandler` for Knox |
| Recording start (no consent dialog) | `screenrecorder/recorder/core/MP4Recorder` uses Samsung `SepWrapper.MediaProjectionManager.semGetMediaProjection(...)` |

## Re-targeting another version

- Framework hooks (`KeyguardManager`, `TileService`, `Dialog`) are stable across versions.
- SmartCapture-internal class/method names may be obfuscated or renamed on other builds.
  The module fails safe: every hook is wrapped in try/catch with `ExceptionMode.PROTECTIVE`
  and logs a warning when a target is missing, so worst case some hooks are skipped.
- To adapt: decompile the new SmartCapture, re-locate the methods above, update the
  class/method names in `app/src/main/java/dev/denver/lockscreenrecorder/LockScreenRecorderModule.java`.
