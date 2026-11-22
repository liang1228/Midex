package com.extbridge.hbr3trial;

public final class PhysicalTargetTest {
    private static int cases;
    private static void check(boolean expected, int width, int height, float peak, float vsync) {
        if (PhysicalTarget.matches(width, height, peak, vsync) != expected) {
            throw new AssertionError(width + "x" + height + " peak=" + peak + " vsync=" + vsync);
        }
        ++cases;
    }

    public static void main(String[] args) {
        check(true, 2560, 1440, 119.99759f, 119.99759f);
        check(true, 2560, 1440, 120.000015f, 120.000015f);
        check(true, 2560, 1440, 119.98f, 120.02f);
        check(false, 2560, 1440, 120.03f, 120f);
        check(false, 2560, 1440, 119.97f, 120f);
        check(false, 2560, 1440, 119.88f, 119.88f);
        check(false, 2560, 1440, 120f, 60f);
        check(false, 2560, 1440, 60f, 120f);
        check(false, 2560, 1440, 59.95055f, 59.95055f);
        check(false, 1920, 1080, 120f, 120f);
        check(false, 3200, 2136, 120f, 120f);
        check(false, 1440, 2560, 120f, 120f);
        check(false, 2560, 1440, Float.NaN, 120f);
        check(false, 2560, 1440, 120f, Float.POSITIVE_INFINITY);
        System.out.println("PASS " + cases + " physical mode matching cases");
    }
}
