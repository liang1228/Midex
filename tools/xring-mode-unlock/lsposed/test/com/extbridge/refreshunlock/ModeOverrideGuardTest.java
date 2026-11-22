package com.extbridge.refreshunlock;

public final class ModeOverrideGuardTest {
    private static int passed;

    public static void main(String[] args) {
        expect("disabled module", false, false,
                2560, 1440, 2560, 1440, 640, 480, false);
        expect("internal display", true, true,
                3200, 2136, 3200, 2136, 640, 480, false);

        expect("2K collapse to 640x480", true, false,
                2560, 1440, 2560, 1440, 640, 480, true);
        expect("2K collapse to 720x400", true, false,
                2560, 1440, 2560, 1440, 720, 400, true);
        expect("2K stale callback at 1080p", true, false,
                2560, 1440, 2560, 1440, 1920, 1080, true);

        expect("2K 60 Hz", true, false,
                2560, 1440, 2560, 1440, 2560, 1440, false);
        expect("2K 100 Hz", true, false,
                2560, 1440, 2560, 1440, 2560, 1440, false);
        expect("2K 120 Hz", true, false,
                2560, 1440, 2560, 1440, 2560, 1440, false);
        expect("2K 144 Hz", true, false,
                2560, 1440, 2560, 1440, 2560, 1440, false);
        expect("2K 165 Hz", true, false,
                2560, 1440, 2560, 1440, 2560, 1440, false);

        expect("user selected 1080p", true, false,
                1920, 1080, 1920, 1080, 1920, 1080, false);
        expect("1080p collapse to 640x480", true, false,
                1920, 1080, 1920, 1080, 640, 480, true);
        expect("base/active mismatch", true, false,
                2560, 1440, 1920, 1080, 640, 480, false);
        expect("missing base mode", true, false,
                0, 0, 2560, 1440, 640, 480, false);
        expect("missing active mode", true, false,
                2560, 1440, 0, 0, 640, 480, false);
        expect("missing callback mode", true, false,
                2560, 1440, 2560, 1440, 0, 0, false);
        expect("larger callback", true, false,
                2560, 1440, 2560, 1440, 3840, 2160, false);

        System.out.println("PASS: " + passed + " mode override guard cases");
    }

    private static void expect(String name, boolean enabled, boolean internal,
            int baseWidth, int baseHeight, int activeWidth, int activeHeight,
            int callbackWidth, int callbackHeight, boolean want) {
        boolean actual = ModeOverrideGuard.shouldSuppress(enabled, internal,
                baseWidth, baseHeight, activeWidth, activeHeight,
                callbackWidth, callbackHeight);
        if (actual != want) {
            throw new AssertionError(name + ": expected " + want + ", got " + actual);
        }
        passed++;
    }
}
