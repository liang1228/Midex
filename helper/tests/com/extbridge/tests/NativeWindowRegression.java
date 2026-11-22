package com.extbridge.tests;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;

import com.extbridge.helper.BridgeClient;
import com.extbridge.refreshunlock.WindowDecorationContract;

import java.io.FileInputStream;

/** Exercises the actual SystemUI caption input surface and the root task controller. */
final class NativeWindowRegression {
    private final Instrumentation runner;
    private static final String PKG = "com.miui.calculator";
    private BridgeClient.Status status;
    private int taskId;
    private String phase = "connect";
    private final Rect custom = new Rect(180, 130, 1280, 950);

    private NativeWindowRegression(Instrumentation runner) { this.runner = runner; }
    static void run(Instrumentation runner) { new NativeWindowRegression(runner).run(); }

    private void run() {
        Bundle result = new Bundle();
        try {
            long connectedBy = SystemClock.uptimeMillis() + 15000;
            while (BridgeClient.getService() == null && SystemClock.uptimeMillis() < connectedBy)
                SystemClock.sleep(100);
            status = BridgeClient.getStatus();
            require(status.connected && status.vdId > 0, "desktop connected");
            phase("launch on display " + status.vdId + " at " + status.virtFps + "Hz");
            BridgeClient.launchComponent(PKG, PKG + ".cal.CalculatorActivity");
            taskId = await(null, false).taskId;
            SystemClock.sleep(3000);
            require(BridgeClient.controlWindow(taskId, 0, custom), "resize accepted");
            await(custom, true);
            settle(custom);
            for (int cycle = 0; cycle < 3; cycle++) {
                phase("cycle " + cycle + " maximize");
                tap(custom, 2);
                Rect max = new Rect(0, dp(34), status.virtW, status.virtH - dp(52));
                await(max, true);
                settle(max);
                phase("cycle " + cycle + " restore");
                tap(max, 2);
                await(custom, true);
                settle(custom);
                phase("cycle " + cycle + " minimize");
                tap(custom, 1);
                long deadline = SystemClock.uptimeMillis() + 10000;
                while (SystemClock.uptimeMillis() < deadline) {
                    BridgeClient.DesktopWindow w = BridgeClient.getDesktopWindow();
                    if (w == null || w.taskId != taskId) break;
                    SystemClock.sleep(100);
                }
                BridgeClient.DesktopWindow w = BridgeClient.getDesktopWindow();
                require(w == null || w.taskId != taskId, "caption minimized task");
                require(BridgeClient.switchToTask(taskId, PKG), "show accepted");
                await(custom, true);
                settle(custom);
            }
            phase("drag");
            shell("input -d " + status.vdId + " swipe 460 154 560 214 600");
            Rect moved = new Rect(custom); moved.offset(100, 60);
            await(moved, true);
            SystemClock.sleep(1500);
            phase("close");
            tap(moved, 4);
            long deadline = SystemClock.uptimeMillis() + 10000;
            while (SystemClock.uptimeMillis() < deadline) {
                if (!hasCaption()) break;
                SystemClock.sleep(100);
            }
            require(!hasCaption(), "caption and task released after close");
            result.putString("stream", "PASS: native caption 3 maximize/restore/minimize/show cycles, drag and close\n");
            runner.finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL at " + phase + ": " + error + "\n");
            runner.finish(0, result);
        }
    }

    private int dp(int value) { return Math.round(value * status.virtDpi / 160f); }

    private void phase(String value) {
        phase = value;
        Bundle progress = new Bundle();
        progress.putString("stream", SystemClock.uptimeMillis() + ": " + value + "\n");
        runner.sendStatus(1, progress);
    }

    private void settle(Rect expected) {
        // HyperOS completes show/launch transitions after the first TaskInfo bounds callback.
        SystemClock.sleep(3000);
        await(expected, true);
    }

    private void tap(Rect bounds, int action) throws Exception {
        require(BridgeClient.getStatus().vdId == status.vdId, "display changed during input test");
        int indexFromRight = action == 4 ? 0 : action == 2 ? 1 : 2;
        int x = bounds.right - Math.round(dp(38) * (indexFromRight + 0.5f));
        shell("input -d " + status.vdId + " tap " + x + " " + (bounds.top + dp(16)));
    }

    private boolean hasCaption() {
        int boot = Settings.Global.getInt(runner.getTargetContext().getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        return WindowDecorationContract.hasCaption(Settings.Global.getString(
                runner.getTargetContext().getContentResolver(), WindowDecorationContract.TASKS), boot, taskId);
    }

    private BridgeClient.DesktopWindow await(Rect bounds, boolean caption) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        BridgeClient.DesktopWindow last;
        do {
            require(BridgeClient.getStatus().vdId == status.vdId, "display changed during " + phase);
            last = BridgeClient.getDesktopWindow();
            if (last != null && PKG.equals(last.pkg) && (taskId == 0 || taskId == last.taskId)
                    && (bounds == null || bounds.equals(last.bounds)) && (!caption || hasCaption())) return last;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Expected " + bounds + " caption=" + caption + "; observed "
                + (last == null ? "none" : last.bounds) + " receipt=" + hasCaption());
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = runner.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
            byte[] buffer = new byte[256];
            while (input.read(buffer) >= 0) { }
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
