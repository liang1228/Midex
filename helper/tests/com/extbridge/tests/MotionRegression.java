package com.extbridge.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import com.extbridge.helper.LauncherActivity;

/** Real drawer clicks interrupted mid-transition must not leave an invisible input blocker. */
final class MotionRegression {
    static void run(Instrumentation test) {
        Bundle result = new Bundle();
        Activity activity = null;
        int code = -1;
        String original = Settings.Global.getString(test.getTargetContext().getContentResolver(), "animator_duration_scale");
        try {
            Instrumentation.ActivityMonitor monitor = test.addMonitor(LauncherActivity.class.getName(), null, false);
            shell(test, "am start --display 0 -n com.extbridge.helper/.LauncherActivity");
            activity = test.waitForMonitorWithTimeout(monitor, 8000);
            test.removeMonitor(monitor);
            require(activity != null, "test desktop opened on display 0");
            Activity target = activity;
            SystemClock.sleep(1200);
            View root = activity.getWindow().getDecorView();
            View apps = find(root, "应用"), close = find(root, "关闭应用抽屉");
            require(apps != null && close != null, "actual drawer controls");
            for (int i = 0; i < 3; i++) {
                click(test, apps); SystemClock.sleep(40);
                click(test, close); SystemClock.sleep(30);
                click(test, apps); SystemClock.sleep(260);
                test.runOnMainSync(() -> require(close.isShown(), "reopened drawer visible"));
                click(test, close); SystemClock.sleep(260);
                test.runOnMainSync(() -> require(!close.isShown(), "closed drawer releases input"));
            }
            shell(test, "settings put global animator_duration_scale 0");
            SystemClock.sleep(400);
            click(test, apps); SystemClock.sleep(50);
            test.runOnMainSync(() -> require(close.isShown(), "zero-scale drawer opens"));
            click(test, close); SystemClock.sleep(50);
            test.runOnMainSync(() -> require(!close.isShown(), "zero-scale drawer closes immediately"));
            test.runOnMainSync(target::finishAndRemoveTask);
            result.putString("stream", "PASS: 3 interrupted drawer reversals and animator=0 immediate open/close\n");
        } catch (Throwable failure) {
            if (activity != null) { Activity target = activity; test.runOnMainSync(target::finishAndRemoveTask); }
            result.putString("stream", "FAIL: " + failure + "\n");
            code = 0;
        } finally {
            shell(test, original == null ? "settings delete global animator_duration_scale"
                    : "settings put global animator_duration_scale " + Float.parseFloat(original));
        }
        test.finish(code, result);
    }

    private static void shell(Instrumentation test, String command) {
        try (android.os.ParcelFileDescriptor fd = test.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer = new byte[128]; while (input.read(buffer) != -1) {}
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void click(Instrumentation test, View view) { test.runOnMainSync(view::performClick); }
    private static View find(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
