package com.extbridge.refreshunlock;

/** Independent acknowledgement for the SystemUI window hooks. */
public final class WindowDecorationContract {
    public static final String SQUARE = "extbridge_square_windows";
    public static final String CAPTION = "extbridge_native_caption";
    public static final String REQUEST = "extbridge_window_request";
    public static final String STATUS = "extbridge_window_status";
    public static final String TASKS = "extbridge_caption_tasks";

    private WindowDecorationContract() {}

    public static boolean isDesktopTask(int displayType, String displayName, int windowingMode,
            String pkg) {
        return displayType == 5 && displayName != null && displayName.startsWith("ExtBridge-")
                && windowingMode == 5 && pkg != null && !pkg.isEmpty()
                && !pkg.startsWith("com.extbridge.") && !"com.android.systemui".equals(pkg)
                && !"com.miui.securitycenter".equals(pkg)
                && !pkg.endsWith("permissioncontroller");
    }

    public static String report(int boot, boolean square, boolean caption, String nonce) {
        return "1:" + boot + ":" + (square ? "1" : "0") + ":" + (caption ? "1" : "0") + ":" + nonce;
    }

    public static boolean isLoaded(String report, int boot) {
        if (boot < 0 || report == null) return false;
        String[] p = report.split(":", -1);
        return p.length == 5 && "1".equals(p[0]) && String.valueOf(boot).equals(p[1])
                && bit(p[2]) && bit(p[3]) && !p[4].isEmpty();
    }

    private static boolean bit(String value) { return "0".equals(value) || "1".equals(value); }
    public static boolean square(String report, int boot) {
        return isLoaded(report, boot) && "1".equals(report.split(":", -1)[2]);
    }
    public static boolean caption(String report, int boot) {
        return isLoaded(report, boot) && "1".equals(report.split(":", -1)[3]);
    }
    public static boolean hasCaption(String tasks, int boot, int taskId) {
        if (tasks == null || boot < 0 || taskId <= 0) return false;
        String prefix = "1:" + boot + ":";
        if (!tasks.startsWith(prefix)) return false;
        for (String id : tasks.substring(prefix.length()).split(",")) {
            if (String.valueOf(taskId).equals(id)) return true;
        }
        return false;
    }
}
