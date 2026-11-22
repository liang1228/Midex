package com.extbridge.daemon;

final class ExternalModeVerification {
    private ExternalModeVerification() {}

    static boolean sameRate(float first, float second) {
        return first > 0 && second > 0 && Math.abs(first - second) < 0.02f;
    }

    static final class DesktopRefreshStability {
        private final long settleMs;
        private float pendingHz;
        private long pendingSinceMs;

        DesktopRefreshStability(long settleMs) {
            this.settleMs = settleMs;
        }

        boolean shouldReplace(float desktopHz, float observedHz, long nowMs) {
            if (!(observedHz > 0) || sameRate(desktopHz, observedHz)) {
                reset();
                return false;
            }
            if (!sameRate(pendingHz, observedHz)) {
                pendingHz = observedHz;
                pendingSinceMs = nowMs;
            }
            return nowMs - pendingSinceMs >= settleMs;
        }

        void reset() {
            pendingHz = 0;
        }
    }

    static boolean agreesWithDrm(int width, int height, float activeHz, float renderHz, String scanout) {
        return sameRate(activeHz, renderHz) && scanoutMatches(width, height, activeHz, scanout);
    }

    private static boolean scanoutMatches(int width, int height, float activeHz, String scanout) {
        if (scanout == null) return false;
        String[] fields = scanout.trim().split("\\s+");
        if (fields.length != 3) return false;
        try {
            int drmWidth = Integer.parseInt(fields[0]);
            int drmHeight = Integer.parseInt(fields[1]);
            float drmHz = Float.parseFloat(fields[2]);
            // SF advertises nominal rates such as 100 Hz. DRM reports the timing from the pixel clock.
            return width == drmWidth && height == drmHeight && drmHz > 0
                    && Math.abs(activeHz - drmHz) < 0.15f;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    enum Stage {
        NONE, SUBMITTED, SUBMIT_FAILED, WAITING_FOR_SF, WAITING_FOR_RENDER,
        WAITING_FOR_DRM, WAITING_FOR_DESKTOP, CONFIRMED
    }

    static final class Readback {
        final int sfModeId;
        final int width;
        final int height;
        final float activeHz;
        final float renderHz;
        final String scanout;

        Readback(int sfModeId, int width, int height, float activeHz, float renderHz, String scanout) {
            this.sfModeId = sfModeId;
            this.width = width;
            this.height = height;
            this.activeHz = activeHz;
            this.renderHz = renderHz;
            this.scanout = scanout;
        }

        Stage stageFor(int targetWidth, int targetHeight, float targetHz, boolean requireDrm) {
            if (width != targetWidth || height != targetHeight || !sameRate(activeHz, targetHz)) {
                return Stage.WAITING_FOR_SF;
            }
            if (requireDrm && !agreesWithDrm(width, height, activeHz, renderHz, scanout)) {
                return sameRate(activeHz, renderHz) ? Stage.WAITING_FOR_DRM : Stage.WAITING_FOR_RENDER;
            }
            return Stage.CONFIRMED;
        }
    }
}
