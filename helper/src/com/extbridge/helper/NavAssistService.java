package com.extbridge.helper;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

public class NavAssistService extends AccessibilityService {
    private static volatile NavAssistService sInstance;
    private static volatile Runnable sOnStateChanged;

    public static boolean isRunning() {
        return sInstance != null;
    }

    public static NavAssistService getInstance() {
        return sInstance;
    }

    public static void setOnStateChanged(Runnable r) {
        sOnStateChanged = r;
    }

    public static void performBack() {
        if (sInstance != null) sInstance.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static void performHome() {
        if (sInstance != null) sInstance.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public static void performRecents() {
        if (sInstance != null) sInstance.performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Runnable r = sOnStateChanged;
        if (r != null) r.run();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        Runnable r = sOnStateChanged;
        if (r != null) r.run();
        return super.onUnbind(intent);
    }
}
