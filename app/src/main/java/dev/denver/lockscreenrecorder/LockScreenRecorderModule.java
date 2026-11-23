package dev.denver.lockscreenrecorder;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LSPosed module (libxposed API 102) that lets Samsung SmartCapture
 * (com.samsung.android.app.smartcapture) work on the lock screen:
 * screen recording from the Quick Settings tile without a PIN prompt,
 * and AI select without the "blocked by security policy" toast.
 *
 * Strategy: inside the SmartCapture process only, pretend the keyguard is
 * inactive and that no Knox/MDM policy forbids screen capture.
 */
public class LockScreenRecorderModule extends XposedModule {

    private static final String TAG = "LockscreenRecorder";
    private static final String SMARTCAPTURE_PKG = "com.samsung.android.app.smartcapture";
    private static final int FLAG_SHOW_WHEN_LOCKED = 0x00000080;

    private static final XposedInterface.Hooker RETURN_FALSE = chain -> Boolean.FALSE;
    private static final XposedInterface.Hooker RETURN_TRUE = chain -> Boolean.TRUE;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded in process=" + param.getProcessName()
                + " | framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " (api=" + getApiVersion() + ")");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SMARTCAPTURE_PKG.equals(param.getPackageName())) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        log(Log.INFO, TAG, "Hooking SmartCapture (pkg=" + param.getPackageName() + ")");

        // Framework: keyguard always reports "inactive" in this process.
        // isKeyguardLocked() gates AI-select validation and defers screen recording;
        // isDeviceLocked() feeds DeviceUtils.isKeyguardEnabled().
        try {
            Class<?> keyguardManager = Class.forName("android.app.KeyguardManager", false, cl);
            hookBoolean(keyguardManager, "isKeyguardLocked", RETURN_FALSE);
            hookBoolean(keyguardManager, "isDeviceLocked", RETURN_FALSE);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook KeyguardManager", t);
        }

        // Framework: the QS tile must not ask for the PIN on the lock screen.
        // RecordScreenTile.onClick(): isLocked() ? unlockAndRun(...) : startRecording
        try {
            Class<?> tileService = Class.forName("android.service.quicksettings.TileService", false, cl);
            hookBoolean(tileService, "isLocked", RETURN_FALSE);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook TileService", t);
        }

        // SmartCapture utils: Knox/MDM policy queries + keyguard helpers.
        hookClass(cl, "com.samsung.android.app.smartcapture.baseutil.device.DeviceUtils", RETURN_FALSE,
                "isRestrictedByMdm", "isKeyguardEnabled", "isKeyguardLocked");
        hookClass(cl, "com.samsung.android.app.smartcapture.aiassist.util.CommonUtil", RETURN_FALSE,
                "isDeviceLocked");
        hookClass(cl, "com.samsung.android.app.smartcapture.screenrecorder.util.ScreenRecorderUtils", RETURN_TRUE,
                "isSettingsChangesAllowed");

        // Don't stop recording when the keyguard shows (RecordingStopReason.DEVICE_LOCKED).
        hookIgnoreDeviceLockedStop(cl);

        // Let SmartCapture dialogs appear over the lock screen.
        hookDialogShowWhenLocked(cl);

        // Skip the sound-choice popup (hidden behind the keyguard): start directly.
        hookSkipSoundPopup(cl);

        // The pre-flight screenshot check must not fail on the lock screen (SECURE_LAYER_POLICY).
        hookClass(cl, "com.samsung.android.app.smartcapture.baseutil.image.ImageUtils", RETURN_FALSE,
                "isEmpty");

        log(Log.INFO, TAG, "Hooks installed for " + param.getPackageName());
    }

    private void hookClass(ClassLoader cl, String className, XposedInterface.Hooker hooker, String... methodNames) {
        try {
            Class<?> clazz = Class.forName(className, false, cl);
            for (String name : methodNames) {
                hookBoolean(clazz, name, hooker);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook " + className + ": " + t);
        }
    }

    /** Hooks every boolean overload of a method with the given hooker. */
    private void hookBoolean(Class<?> clazz, String methodName, XposedInterface.Hooker hooker) {
        int count = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName) || m.getReturnType() != boolean.class) {
                continue;
            }
            try {
                m.setAccessible(true);
                hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(hooker);
                count++;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Cannot hook " + clazz.getName() + "#" + methodName + ": " + t);
            }
        }
        if (count > 0) {
            log(Log.INFO, TAG, "OK: " + clazz.getName() + "#" + methodName + " hooked (" + count + " overload(s))");
        } else {
            log(Log.WARN, TAG, "No boolean method " + clazz.getName() + "#" + methodName + " found");
        }
    }

    /** Dialog.show(): add FLAG_SHOW_WHEN_LOCKED before showing. */
    private void hookDialogShowWhenLocked(ClassLoader cl) {
        try {
            Class<?> dialogClass = Class.forName("android.app.Dialog", false, cl);
            int count = 0;
            for (Method m : dialogClass.getDeclaredMethods()) {
                if (!m.getName().equals("show") || m.getParameterCount() != 0) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept((XposedInterface.Hooker) chain -> {
                                try {
                                    Object window = chain.getThisObject().getClass()
                                            .getMethod("getWindow").invoke(chain.getThisObject());
                                    if (window != null) {
                                        window.getClass().getMethod("addFlags", int.class)
                                                .invoke(window, FLAG_SHOW_WHEN_LOCKED);
                                    }
                                } catch (Throwable ignored) {
                                }
                                return chain.proceed();
                            });
                    count++;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Cannot hook Dialog.show: " + t);
                }
            }
            log(Log.INFO, TAG, "OK: android.app.Dialog#show -> +FLAG_SHOW_WHEN_LOCKED (" + count + " overload(s))");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook Dialog", t);
        }
    }

    /** stopRecordingAccordingToAction(DEVICE_LOCKED): ignored, recording keeps running on the lock screen. */
    private void hookIgnoreDeviceLockedStop(ClassLoader cl) {
        try {
            Class<?> ctrl = Class.forName(
                    "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController", false, cl);
            int count = 0;
            for (Method m : ctrl.getDeclaredMethods()) {
                if (!m.getName().equals("stopRecordingAccordingToAction") || m.getParameterCount() != 1) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept((XposedInterface.Hooker) chain -> {
                                Object reason = chain.getArg(0);
                                if (reason instanceof Enum && "DEVICE_LOCKED".equals(((Enum<?>) reason).name())) {
                                    log(Log.INFO, TAG, "DEVICE_LOCKED stop ignored (recording on lock screen)");
                                    return null;
                                }
                                return chain.proceed();
                            });
                    count++;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Cannot hook stopRecordingAccordingToAction: " + t);
                }
            }
            if (count > 0) {
                log(Log.INFO, TAG, "OK: stopRecordingAccordingToAction -> DEVICE_LOCKED ignored");
            } else {
                log(Log.WARN, TAG, "stopRecordingAccordingToAction not found");
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook DEVICE_LOCKED stop", t);
        }
    }

    /** showSoundSettingSuggestionPopup(): replaced by checkPreConditionsThenStartRecording(). */
    private void hookSkipSoundPopup(ClassLoader cl) {
        try {
            Class<?> ctrl = Class.forName(
                    "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController", false, cl);
            int count = 0;
            for (Method m : ctrl.getDeclaredMethods()) {
                if (!m.getName().equals("showSoundSettingSuggestionPopup") || m.getParameterCount() != 0) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept((XposedInterface.Hooker) chain -> {
                                log(Log.INFO, TAG, "Sound popup skipped -> starting recording directly");
                                try {
                                    Method start = chain.getThisObject().getClass()
                                            .getDeclaredMethod("checkPreConditionsThenStartRecording");
                                    start.setAccessible(true);
                                    start.invoke(chain.getThisObject());
                                } catch (Throwable t) {
                                    log(Log.ERROR, TAG, "checkPreConditionsThenStartRecording failed", t);
                                }
                                return null;
                            });
                    count++;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Cannot hook showSoundSettingSuggestionPopup: " + t);
                }
            }
            if (count > 0) {
                log(Log.INFO, TAG, "OK: showSoundSettingSuggestionPopup -> direct start");
            } else {
                log(Log.WARN, TAG, "showSoundSettingSuggestionPopup not found");
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook sound popup", t);
        }
    }
}
