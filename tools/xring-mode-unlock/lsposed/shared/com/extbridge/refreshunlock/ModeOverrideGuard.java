package com.extbridge.refreshunlock;

/** Pure decision logic for suppressing only a transient external-display resolution collapse. */
public final class ModeOverrideGuard {
    private ModeOverrideGuard() {}

    public static boolean shouldSuppress(boolean enabled, boolean internalDisplay,
            int baseWidth, int baseHeight, int activeWidth, int activeHeight,
            int callbackWidth, int callbackHeight) {
        if (!enabled || internalDisplay) return false;
        if (baseWidth <= 0 || baseHeight <= 0 || callbackWidth <= 0 || callbackHeight <= 0) {
            return false;
        }
        if (activeWidth != baseWidth || activeHeight != baseHeight) return false;
        return area(callbackWidth, callbackHeight) < area(baseWidth, baseHeight);
    }

    private static long area(int width, int height) {
        return (long) width * (long) height;
    }
}
