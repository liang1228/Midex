package com.extbridge.refreshunlock;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.Display;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Removes only the verified OEM HDMI vote, without altering display capabilities. */
public class RefreshUnlockModule implements IXposedHookLoadPackage {
    private final boolean integrated;
    private volatile boolean enabled;
    private boolean observing;
    private volatile boolean transientModeSuppressed;
    private volatile boolean transientModeFailureLogged;

    public RefreshUnlockModule() { this(false); }

    RefreshUnlockModule(boolean integrated) { this.integrated = integrated; }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (android.os.Process.myUid() != 1000
                || !RefreshUnlockContract.supportsHooks(Build.FINGERPRINT, Build.VERSION.INCREMENTAL, integrated)) return;
        if ("com.android.systemui".equals(param.packageName)
                && "com.android.systemui".equals(param.processName)) {
            new WindowDecorationModule(integrated).install(param.classLoader);
            return;
        }
        if (!"android".equals(param.packageName) || !"android".equals(param.processName)) return;
        try {
            Class<?> director = XposedHelpers.findClass(
                    "com.android.server.display.mode.DisplayModeDirectorImpl", param.classLoader);
            Method limit = requireMethod(director, "shouldExtraDisplayLimitRefresh", boolean.class);
            Method register = requireMethod(director, "registerMiuiContentObserver", void.class,
                    ContentResolver.class, ContentObserver.class);
            requireMethod(director, "notifyDisplayModeSpecsChanged", void.class);
            requireMethod(XposedHelpers.findField(director, "mSettingsObserver").getType(),
                    "updateMiuiRefreshRateSettingLocked", void.class);
            requireField(director, "mAsyncHandler", Handler.class);
            requireField(director, "mLock", Object.class);
            XposedBridge.hookMethod(limit, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    if (enabled && Boolean.TRUE.equals(hook.getResult())) hook.setResult(false);
                }
            });
            XposedBridge.hookMethod(register, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.hasThrowable() || observing) return;
                    try {
                        ContentResolver resolver = (ContentResolver) hook.args[0];
                        Object instance = hook.thisObject;
                        Handler handler = (Handler) XposedHelpers.getObjectField(instance, "mAsyncHandler");
                        if (handler == null) return;
                        ContentObserver observer = new ContentObserver(handler) {
                            @Override
                            public void onChange(boolean selfChange) {
                                update(instance, resolver);
                            }
                        };
                        resolver.registerContentObserver(Settings.Global.getUriFor(RefreshUnlockContract.SETTING), false, observer);
                        resolver.registerContentObserver(Settings.Global.getUriFor(RefreshUnlockContract.REQUEST), false, observer);
                        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.BOOT_COUNT), false, observer);
                        observing = true;
                        handler.post(() -> update(instance, resolver));
                    } catch (Throwable failure) {
                        enabled = false;
                        log("observer unavailable: " + failure);
                    }
                }
            });
            log("display policy hooks installed; default disabled");
        } catch (Throwable failure) {
            enabled = false;
            log("unsupported framework: " + failure);
            return;
        }
        if (RefreshUnlockContract.FINGERPRINT.equals(Build.FINGERPRINT)) {
            try {
                Class<?> localDisplayDevice = XposedHelpers.findClass(
                        "com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice",
                        param.classLoader);
                XposedBridge.hookAllMethods(localDisplayDevice,
                        "onModeAndFrameRateOverridesChangedLocked", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam hook) {
                                beforeExternalModeOverride(hook);
                            }
                        });
            } catch (Throwable failure) { log("transient mode guard unavailable: " + failure); }
        }
    }

    static Method requireMethod(Class<?> owner, String name, Class<?> result, Class<?>... parameters) {
        Method method = XposedHelpers.findMethodExact(owner, name, parameters);
        if (method.getReturnType() != result)
            throw new IllegalStateException("Unexpected return type: " + method);
        return method;
    }

    static void requireField(Class<?> owner, String name, Class<?> type) {
        if (!type.isAssignableFrom(XposedHelpers.findField(owner, name).getType()))
            throw new IllegalStateException("Unexpected field type: " + owner.getName() + "." + name);
    }

    private void update(Object director, ContentResolver resolver) {
        if (integrated && !integratedEnabled(resolver)) return;
        try {
            boolean requested = Settings.Global.getInt(resolver, RefreshUnlockContract.SETTING, 0) == 1;
            Object lock = XposedHelpers.getObjectField(director, "mLock");
            Object observer = XposedHelpers.getObjectField(director, "mSettingsObserver");
            synchronized (lock) {
                enabled = requested;
                XposedHelpers.callMethod(observer, "updateMiuiRefreshRateSettingLocked");
            }
            XposedHelpers.callMethod(director, "notifyDisplayModeSpecsChanged");
            int boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1);
            String request = Settings.Global.getString(resolver, RefreshUnlockContract.REQUEST);
            Settings.Global.putString(resolver, RefreshUnlockContract.STATUS,
                    RefreshUnlockContract.report(boot, enabled, request == null ? "initial" : request));
            log("HDMI policy bypass enabled=" + enabled);
        } catch (Throwable failure) {
            enabled = false;
            try { Settings.Global.putString(resolver, RefreshUnlockContract.STATUS, "error"); }
            catch (Throwable ignored) {}
            log("policy update failed: " + failure);
        }
    }

    static boolean integratedEnabled(ContentResolver resolver) {
        try {
            int boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1);
            int fromBoot = Settings.Global.getInt(resolver, RefreshUnlockContract.INTEGRATED_FROM_BOOT, -1);
            if (fromBoot < 0 || boot < fromBoot) return false;
            Class<?> appGlobals = XposedHelpers.findClass("android.app.AppGlobals", null);
            Object pm = XposedHelpers.callStaticMethod(appGlobals, "getPackageManager");
            return pm != null && XposedHelpers.callMethod(pm, "getPackageInfo",
                    RefreshUnlockContract.PACKAGE, 0L, 0) == null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * The vendor stack can report the sink's fallback SF mode for one callback while DMD still
     * requires the full-resolution mode. Publishing that callback resizes Display 2 to 640x480.
     * Suppress only that resolution drop; every callback whose dimensions match DMD is untouched.
     */
    private void beforeExternalModeOverride(XC_MethodHook.MethodHookParam hook) {
        if (!enabled || hook.hasThrowable()) return;
        try {
            if (hook.args == null || hook.args.length < 1 || isInternalDisplay(hook.thisObject)) return;

            Object desired = XposedHelpers.getObjectField(hook.thisObject, "mDisplayModeSpecs");
            int baseModeId = XposedHelpers.getIntField(desired, "baseModeId");
            Display.Mode baseMode = findLogicalMode(hook.thisObject, baseModeId);
            Object activeMode = XposedHelpers.getObjectField(hook.thisObject, "mActiveSfDisplayMode");
            Object callbackMode = findSfMode(hook.thisObject, ((Number) hook.args[0]).intValue());
            if (baseMode == null || activeMode == null || callbackMode == null) return;

            int baseWidth = baseMode.getPhysicalWidth();
            int baseHeight = baseMode.getPhysicalHeight();
            int activeWidth = XposedHelpers.getIntField(activeMode, "width");
            int activeHeight = XposedHelpers.getIntField(activeMode, "height");
            int callbackWidth = XposedHelpers.getIntField(callbackMode, "width");
            int callbackHeight = XposedHelpers.getIntField(callbackMode, "height");

            if (!ModeOverrideGuard.shouldSuppress(enabled, false,
                    baseWidth, baseHeight, activeWidth, activeHeight,
                    callbackWidth, callbackHeight)) return;

            hook.setResult(null);
            if (!transientModeSuppressed) {
                transientModeSuppressed = true;
                log("suppressed transient external mode " + callbackWidth + "x" + callbackHeight
                        + "; base=" + baseWidth + "x" + baseHeight
                        + " active=" + activeWidth + "x" + activeHeight);
            }
        } catch (Throwable failure) {
            // Fail open so an unsupported transient callback never disables refresh-rate handling.
            if (!transientModeFailureLogged) {
                transientModeFailureLogged = true;
                log("transient mode guard unavailable: " + failure);
            }
        }
    }

    private static boolean isInternalDisplay(Object device) {
        Object staticInfo = XposedHelpers.getObjectField(device, "mStaticDisplayInfo");
        return XposedHelpers.getBooleanField(staticInfo, "isInternal");
    }

    private static Display.Mode findLogicalMode(Object device, int modeId) {
        Object supported = XposedHelpers.getObjectField(device, "mSupportedModes");
        if (!(supported instanceof SparseArray<?>)) return null;
        Object record = ((SparseArray<?>) supported).get(modeId);
        Object mode = record == null ? null : XposedHelpers.getObjectField(record, "mMode");
        return mode instanceof Display.Mode ? (Display.Mode) mode : null;
    }

    private static Object findSfMode(Object device, int sfModeId) {
        Object[] modes = (Object[]) XposedHelpers.getObjectField(device, "mSfDisplayModes");
        if (modes == null) return null;
        for (Object mode : modes) {
            if (mode != null && XposedHelpers.getIntField(mode, "id") == sfModeId) return mode;
        }
        return null;
    }

    private static void log(String message) {
        XposedBridge.log("ExtBridge-RefreshUnlock: " + message);
    }
}
