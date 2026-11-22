package com.extbridge.refreshunlock;

/** Wire format shared by the optional module and the ordinary console. */
public final class RefreshUnlockContract {
    public static final String PACKAGE = "com.extbridge.refreshunlock";
    public static final int VERSION = 4;
    // The refresh hooks are unchanged; an already loaded v3 hook remains compatible.
    private static final int REFRESH_PROTOCOL = 3;
    public static final String FINGERPRINT =
            "Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys";
    public static final String SETTING = "extbridge_unlock_builtin_refresh";
    public static final String REQUEST = "extbridge_refresh_unlock_request";
    public static final String STATUS = "extbridge_refresh_unlock_status";
    public static final String INTEGRATED_FROM_BOOT = "extbridge_integrated_hooks_from_boot";

    private RefreshUnlockContract() {}

    public static boolean supportsHooks(String fingerprint, String incremental, boolean integrated) {
        if (!integrated) return FINGERPRINT.equals(fingerprint);
        return incremental != null && incremental.startsWith("OS4.") && incremental.length() > 4
                && incremental.charAt(4) >= '0' && incremental.charAt(4) <= '9';
    }

    public static String report(int boot, boolean enabled, String request) {
        return REFRESH_PROTOCOL + ":" + boot + ":" + (enabled ? "1" : "0") + ":" + request;
    }

    public static boolean isLoaded(String report, int boot) {
        if (boot < 0 || report == null) return false;
        String[] parts = report.split(":", -1);
        return parts.length == 4 && String.valueOf(REFRESH_PROTOCOL).equals(parts[0])
                && String.valueOf(boot).equals(parts[1])
                && ("0".equals(parts[2]) || "1".equals(parts[2])) && !parts[3].isEmpty();
    }

    public static boolean isEnabled(String report, int boot) {
        return isLoaded(report, boot) && "1".equals(report.split(":", -1)[2]);
    }
}
