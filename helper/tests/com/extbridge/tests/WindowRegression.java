package com.extbridge.tests;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;

import com.extbridge.helper.BridgeClient;

/** Runs against the connected desktop and its real task/window controller. */
public final class WindowRegression extends Instrumentation {
    private static final String PKG = "com.miui.calculator";
    private int taskId = -1;
    private boolean nativeCaption;
    private String power;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        nativeCaption = arguments != null && "true".equals(arguments.getString("nativeCaption"));
        power = arguments == null ? null : arguments.getString("power");
        start();
    }

    @Override public void onStart() {
        if ("motion".equals(power)) { MotionRegression.run(this); return; }
        if (power != null) { PowerRegression.run(this, power); return; }
        if (nativeCaption) { NativeWindowRegression.run(this); return; }
        Bundle result = new Bundle();
        try {
            long deadline = SystemClock.uptimeMillis() + 15000;
            while (BridgeClient.getService() == null && SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(100);
            BridgeClient.Status status = BridgeClient.getStatus();
            require(status.connected && status.vdId > 0, "desktop connected");
            BridgeClient.launchComponent(PKG, PKG + ".cal.CalculatorActivity");
            BridgeClient.DesktopWindow window = awaitWindow(null);
            taskId = window.taskId;
            Rect custom = new Rect(180, 130, 1280, 950);
            for (int i = 0; i < 3; i++) {
                require(BridgeClient.controlWindow(taskId, 0, custom), "resize accepted");
                awaitWindow(custom);
                require(BridgeClient.controlWindow(taskId, 2, null), "maximize accepted");
                awaitWindow(new Rect(0, Math.round(34 * status.virtDpi / 160f),
                        status.virtW, status.virtH - Math.round(52 * status.virtDpi / 160f)));
                require(BridgeClient.controlWindow(taskId, 3, null), "restore accepted");
                awaitWindow(custom);
                require(BridgeClient.controlWindow(taskId, 1, null), "minimize accepted");
                SystemClock.sleep(2500);
                window = BridgeClient.getDesktopWindow();
                require(window == null || window.taskId != taskId, "minimized task hidden");
                require(BridgeClient.switchToTask(taskId, PKG), "show accepted");
                awaitWindow(custom);
                // Wait beyond HyperOS's late show/launch transitions, then check again.
                SystemClock.sleep(2800);
                awaitWindow(custom);
            }
            require(!BridgeClient.controlWindow(-1, 2, null), "invalid task rejected");
            result.putString("stream", "PASS: 3 real resize/maximize/restore/minimize/show cycles; late bounds retained\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            finish(0, result);
        }
    }

    private BridgeClient.DesktopWindow awaitWindow(Rect expected) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        BridgeClient.DesktopWindow last = null;
        do {
            last = BridgeClient.getDesktopWindow();
            if (last != null && PKG.equals(last.pkg) && (taskId < 0 || last.taskId == taskId)
                    && (expected == null || expected.equals(last.bounds))) return last;
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Expected " + expected + "; observed "
                + (last == null ? "no window" : last.pkg + ":" + last.bounds));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
