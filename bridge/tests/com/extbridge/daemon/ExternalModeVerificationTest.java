package com.extbridge.daemon;

import com.extbridge.daemon.ExternalModeVerification.DesktopRefreshStability;
import com.extbridge.daemon.ExternalModeVerification.Readback;
import com.extbridge.daemon.ExternalModeVerification.Stage;

public final class ExternalModeVerificationTest {
    public static void main(String[] args) {
        check(true, 2560, 1440, 60, 60, "2560 1440 59.95055");
        check(true, 2560, 1440, 100, 100, "2560 1440 99.94644");
        check(false, 2560, 1440, 60, 60, "2560 1440 99.94644");
        check(false, 2560, 1440, 100, 100, "2560 1440 59.95055");
        check(false, 2560, 1440, 100, 60, "2560 1440 99.94644");
        check(false, 2560, 1440, 60, 100, "2560 1440 59.95055");
        check(false, 2560, 1440, 100, 100, "1920 1080 99.94644");
        check(false, 2560, 1440, 100, 100, "1440 3200 119.99988");
        check(false, 2560, 1440, 100, 100, null);
        check(false, 2560, 1440, 100, 100, "2560 1440 NaN");
        check(false, 2560, 1440, 100, 100, "2560 1440");
        check(false, 2560, 1440, 100, 100, "2560 1440 99.7");
        System.out.println("PASS: 12 external mode readback cases");

        Readback mode60 = new Readback(0, 2560, 1440, 60, 60, "2560 1440 59.95055");
        Readback mode100 = new Readback(1, 2560, 1440, 100, 100, "2560 1440 99.94644");
        checkStage(Stage.CONFIRMED, mode60, 60, true);
        checkStage(Stage.WAITING_FOR_SF, mode60, 100, true);
        checkStage(Stage.WAITING_FOR_DRM,
                new Readback(1, 2560, 1440, 100, 100, mode60.scanout), 100, true);
        checkStage(Stage.WAITING_FOR_RENDER,
                new Readback(1, 2560, 1440, 100, 60, mode100.scanout), 100, true);
        checkStage(Stage.CONFIRMED, mode100, 100, true);
        checkStage(Stage.WAITING_FOR_SF, mode100, 60, true);
        checkStage(Stage.WAITING_FOR_DRM,
                new Readback(0, 2560, 1440, 60, 60, mode100.scanout), 60, true);
        checkStage(Stage.WAITING_FOR_SF,
                new Readback(0, 1920, 1080, 100, 100, "1920 1080 100"), 100, true);
        checkStage(Stage.WAITING_FOR_DRM, new Readback(1, 2560, 1440, 100, 100, null), 100, true);
        checkStage(Stage.CONFIRMED, new Readback(1, 2560, 1440, 100, 0, null), 100, false);
        System.out.println("PASS: 10 external mode transition cases");

        DesktopRefreshStability stability = new DesktopRefreshStability(2_000);
        checkReplacement(stability, false, 120, 180, 100);
        checkReplacement(stability, true, 120, 180.00002f, 2_100);
        stability.reset();

        // Smart Display temporarily requests 120 Hz after the desktop migrates to 180 Hz.
        checkReplacement(stability, false, 180, 120, 2_390);
        checkReplacement(stability, false, 180, 120, 3_680);
        checkReplacement(stability, false, 180, 180, 3_690);
        checkReplacement(stability, false, 180, 120, 4_690);
        checkReplacement(stability, false, 180, 0, 5_290);
        checkReplacement(stability, false, 180, 120, 6_690);
        checkReplacement(stability, true, 180, 120, 8_690);

        stability.reset();
        checkReplacement(stability, false, 180, 120, 10_690);
        checkReplacement(stability, true, 180, 120, 12_690);
        checkReplacement(new DesktopRefreshStability(0), true, 120, 180, 100);
        System.out.println("PASS: 12 virtual desktop refresh stability cases");
    }

    private static void checkReplacement(DesktopRefreshStability stability, boolean expected,
            float desktopHz, float observedHz, long nowMs) {
        if (stability.shouldReplace(desktopHz, observedHz, nowMs) != expected) {
            throw new AssertionError("Expected replacement=" + expected + " for " + desktopHz + " -> "
                    + observedHz + " Hz at " + nowMs + " ms");
        }
    }

    private static void check(boolean expected, int width, int height, float activeHz,
            float renderHz, String scanout) {
        boolean actual = ExternalModeVerification.agreesWithDrm(width, height, activeHz, renderHz, scanout);
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " for " + activeHz + "/" + renderHz + " Hz and " + scanout);
        }
    }

    private static void checkStage(Stage expected, Readback readback, float targetHz, boolean requireDrm) {
        Stage actual = readback.stageFor(2560, 1440, targetHz, requireDrm);
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual + " for "
                    + readback.activeHz + " -> " + targetHz + " Hz with scanout " + readback.scanout);
        }
    }
}
