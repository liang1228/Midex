package com.extbridge.helper;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.util.Locale;

/** A foreground window preference; never writes device-wide refresh settings. */
final class WindowRefreshRate implements DisplayManager.DisplayListener {
    private final Activity activity;
    private final DisplayManager manager;

    WindowRefreshRate(Activity activity) {
        this.activity = activity;
        manager = activity.getSystemService(DisplayManager.class);
    }

    void start() {
        manager.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
        apply();
    }

    void stop() {
        manager.unregisterDisplayListener(this);
        setRate(0f);
    }

    static boolean isEnabled(Activity activity) {
        return activity.getSharedPreferences("display_ui", 0).getBoolean("highRefresh", true);
    }

    static float highestRate(Display display) {
        Display.Mode current = display.getMode();
        float rate = current.getRefreshRate();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight())
                rate = Math.max(rate, mode.getRefreshRate());
        }
        return rate;
    }

    static String summary(Activity activity) {
        Display display = activity.getDisplay();
        if (display == null) return "等待窗口显示";
        return String.format(Locale.ROOT, "当前 %.0f Hz · 最高 %.0f Hz", display.getRefreshRate(), highestRate(display));
    }

    void apply() {
        Display display = activity.getDisplay();
        if (display == null) return;
        setRate(isEnabled(activity) ? highestRate(display) : 0f);
    }

    private void setRate(float rate) {
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        if (params.preferredRefreshRate != rate) {
            params.preferredRefreshRate = rate;
            activity.getWindow().setAttributes(params);
        }
        if (Build.VERSION.SDK_INT >= 35) requestViewRate(activity.getWindow().getDecorView(), rate);
    }

    private void requestViewRate(View view, float rate) {
        if (Build.VERSION.SDK_INT >= 35) view.setRequestedFrameRate(rate);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) requestViewRate(group.getChildAt(i), rate);
        }
    }

    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) {}
    @Override public void onDisplayChanged(int id) {
        Display display = activity.getDisplay();
        if (display != null && display.getDisplayId() == id) apply();
    }
}
