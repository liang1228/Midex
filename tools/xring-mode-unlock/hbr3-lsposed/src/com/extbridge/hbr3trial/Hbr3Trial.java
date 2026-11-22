package com.extbridge.hbr3trial;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.Display;
import java.io.PrintWriter;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import static de.robv.android.xposed.XposedHelpers.*;

/** Temporary DMD preference for a real external mode. No persistent display settings. */
public final class Hbr3Trial implements IXposedHookLoadPackage {
    private static final String DIRECTOR = "com.android.server.display.mode.DisplayModeDirector";
    private static final String COMMAND = "extbridge-hbr3-120";
    private volatile Window active;

    private static final class Window {
        final Object director;
        final int display;
        final long until = SystemClock.elapsedRealtime() + 12000;
        Window(Object director, int display) { this.director = director; this.display = display; }
    }

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!"android".equals(pkg.packageName) || !"android".equals(pkg.processName)
                || android.os.Process.myUid() != 1000 || !PhysicalTarget.ROM.equals(Build.FINGERPRINT)) return;
        findAndHookMethod(DIRECTOR, pkg.classLoader, "getDesiredDisplayModeSpecs", int.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                Window w = active;
                if (w == null || p.hasThrowable() || p.thisObject != w.director
                        || (int) p.args[0] != w.display || SystemClock.elapsedRealtime() >= w.until) return;
                Object stock = p.getResult();
                if (stock == null) return;
                synchronized (getObjectField(w.director, "mLock")) {
                    SparseArray<?> byDisplay = (SparseArray<?>) getObjectField(w.director, "mSupportedModesByDisplay");
                    Display.Mode mode = logicalMode((Display.Mode[]) byDisplay.get(w.display));
                    if (mode == null || active != w) return;
                    Object preferred = newInstance(stock.getClass());
                    callMethod(preferred, "copyFrom", stock);
                    setIntField(preferred, "baseModeId", mode.getModeId());
                    for (String field : new String[]{"primary", "appRequest"}) {
                        Object physical = getObjectField(getObjectField(preferred, field), "physical");
                        setFloatField(physical, "min", mode.getRefreshRate());
                        setFloatField(physical, "max", mode.getRefreshRate());
                    }
                    p.setResult(preferred);
                }
            }
        });
        findAndHookMethod(DIRECTOR + "$DisplayObserver", pkg.classLoader, "onDisplayRemoved", int.class,
                new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                Window w = active;
                if (w != null && w.display == (int) p.args[0]) release(w);
            }
        });
        findAndHookMethod("com.android.server.display.DisplayManagerShellCommand", pkg.classLoader,
                "onCommand", String.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!COMMAND.equals(p.args[0])) return;
                p.setResult(1);
                PrintWriter out = (PrintWriter) callMethod(p.thisObject, "getOutPrintWriter");
                try {
                    if (Binder.getCallingUid() != 0) { out.println("ROOT_REQUIRED"); return; }
                    String action = (String) callMethod(p.thisObject, "getNextArg");
                    if ("stop".equals(action)) {
                        Window w = active;
                        if (w != null) release(w);
                    } else if ("status".equals(action)) {
                        out.println("native2k120Id=" + PhysicalTarget.nativeMode() + " trial=" + (active != null));
                    } else if ("trial".equals(action)) {
                        // The root runner supplies the sample time only after checking the live DP link and EDID.
                        String proof = (String) callMethod(p.thisObject, "getNextArg");
                        long sampled = Long.parseLong((String) callMethod(p.thisObject, "getNextArg"));
                        long age = SystemClock.elapsedRealtime() / 1000 - sampled;
                        if (!"HBR3x2-497750".equals(proof) || age < 0 || age > 2) {
                            out.println("FRESH_LINK_SAMPLE_REQUIRED"); return;
                        }
                        if (PhysicalTarget.nativeMode() < 0) { out.println("NO_NATIVE_2K120"); return; }
                        Object service = getObjectField(p.thisObject, "mService");
                        Context context = (Context) callMethod(service, "getContext");
                        Display display = externalDisplay(context.getSystemService(DisplayManager.class));
                        if (display == null || logicalMode(display.getSupportedModes()) == null) {
                            out.println("NO_FRAMEWORK_2K120"); return;
                        }
                        Object director = getObjectField(service, "mDisplayModeDirector");
                        start(director, display.getDisplayId());
                        out.println("PREFERENCE_REQUESTED_12S");
                    } else { out.println("Expected status, trial or stop"); return; }
                    p.setResult(0);
                } catch (Throwable error) {
                    out.println("UNAVAILABLE " + error.getClass().getSimpleName());
                    XposedBridge.log(error);
                }
            }
        });
    }

    private static Display externalDisplay(DisplayManager manager) {
        for (Display d : manager.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            Object address = callMethod(d, "getAddress");
            if (address != null && "android.view.DisplayAddress$Physical".equals(address.getClass().getName())
                    && (long) callMethod(address, "getPhysicalDisplayId") == PhysicalTarget.PHYSICAL_ID) return d;
        }
        return null;
    }

    private static Display.Mode logicalMode(Display.Mode[] modes) {
        Display.Mode match = null;
        if (modes != null) for (Display.Mode mode : modes) {
            if (!PhysicalTarget.matches(mode.getPhysicalWidth(), mode.getPhysicalHeight(),
                    mode.getRefreshRate(), (float) callMethod(mode, "getVsyncRate"))) continue;
            if (match != null) return null;
            match = mode;
        }
        return match;
    }

    private void start(Object director, int id) {
        Window w = new Window(director, id);
        Handler handler = (Handler) getObjectField(director, "mHandler");
        synchronized (this) {
            if (active != null) throw new IllegalStateException("Trial busy");
            if (!handler.postDelayed(() -> release(w), 12000)) throw new IllegalStateException("No timer");
            active = w;
        }
        callMethod(director, "notifyDesiredDisplayModeSpecsChanged");
    }

    private void release(Window w) {
        synchronized (this) {
            if (active != w) return;
            active = null;
        }
        callMethod(w.director, "notifyDesiredDisplayModeSpecsChanged");
    }
}
