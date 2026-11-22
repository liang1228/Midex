package com.extbridge.hbr3trial;

import android.os.Build;

/** Reads the native mode list for the single physical external port on this firmware. */
public final class PhysicalTarget {
    static final long PHYSICAL_ID = 1L;
    static final String ROM =
            "Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys";

    static boolean matches(int width, int height, float refresh, float vsync) {
        return width == 2560 && height == 1440 && Math.abs(refresh - 120f) <= 0.02f
                && Math.abs(vsync - 120f) <= 0.02f;
    }

    static int nativeMode() throws ReflectiveOperationException {
        if (!ROM.equals(Build.FINGERPRINT)) return -1;
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Object port = sc.getMethod("getStaticDisplayInfo", long.class).invoke(null, PHYSICAL_ID);
        if (port == null || port.getClass().getField("isInternal").getBoolean(port)
                || ((Number) port.getClass().getField("port").get(port)).intValue() != 1) return -1;
        Object info = sc.getMethod("getDynamicDisplayInfo", long.class).invoke(null, PHYSICAL_ID);
        if (info == null) return -1;
        Object[] modes = (Object[]) info.getClass().getField("supportedDisplayModes").get(info);
        int found = -1;
        for (Object mode : modes) {
            Class<?> type = mode.getClass();
            if (!matches(type.getField("width").getInt(mode), type.getField("height").getInt(mode),
                    type.getField("peakRefreshRate").getFloat(mode),
                    type.getField("vsyncRate").getFloat(mode))) continue;
            if (found >= 0) return -1;
            found = type.getField("id").getInt(mode);
        }
        return found;
    }

    /** Read-only app_process probe, also usable before LSPosed reloads system_server. */
    public static void main(String[] args) throws ReflectiveOperationException {
        System.out.println("physicalId=" + PHYSICAL_ID + " native2k120Id=" + nativeMode());
    }
}
