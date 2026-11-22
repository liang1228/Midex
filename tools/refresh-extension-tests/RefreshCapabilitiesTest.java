import com.extbridge.helper.RefreshCapabilities;
import com.extbridge.helper.RefreshCapabilities.Tier;
import com.extbridge.refreshunlock.RefreshUnlockContract;
import com.extbridge.refreshunlock.WindowDecorationContract;

public final class RefreshCapabilitiesTest {
    private static int checks;

    private static void expect(Tier expected, boolean root, boolean supported, long version,
            String report, int boot) {
        RefreshCapabilities state = new RefreshCapabilities(root, supported, version, report, boot);
        if (state.tier != expected) throw new AssertionError(expected + " != " + state.tier);
        if (state.loaded != (expected == Tier.READY || expected == Tier.ENABLED))
            throw new AssertionError("An unavailable module exposed privileged controls");
        if (state.enabled != (expected == Tier.ENABLED)) throw new AssertionError("Incorrect switch state");
        checks++;
    }

    public static void main(String[] args) {
        int current = RefreshUnlockContract.VERSION;
        String ready = RefreshUnlockContract.report(14, false, "initial");
        String enabled = RefreshUnlockContract.report(14, true, "challenge");
        expect(Tier.BASE, false, true, 0, null, 14);
        expect(Tier.BASE, false, true, current, enabled, 14);
        expect(Tier.BASE, false, false, current, enabled, 14);
        expect(Tier.UNSUPPORTED, true, false, current, enabled, 14);
        expect(Tier.INSTALL, true, true, 0, enabled, 14);
        expect(Tier.UPDATE, true, true, 1, enabled, 14);
        expect(Tier.UPDATE, true, true, current + 1, enabled, 14);
        expect(Tier.ACTIVATE, true, true, current, null, 14);
        expect(Tier.ACTIVATE, true, true, current, "", 14);
        expect(Tier.ACTIVATE, true, true, current, "error", 14);
        expect(Tier.ACTIVATE, true, true, current, enabled, 15);
        expect(Tier.ACTIVATE, true, true, current, enabled, -1);
        expect(Tier.ACTIVATE, true, true, current, "1:14:1:old", 14);
        expect(Tier.ACTIVATE, true, true, current, current + ":14:pending:initial", 14);
        expect(Tier.ACTIVATE, true, true, current, current + ":14:1:", 14);
        expect(Tier.ACTIVATE, true, true, current, current + ":14:1:initial:extra", 14);
        expect(Tier.READY, true, true, current, ready, 14);
        expect(Tier.ENABLED, true, true, current, enabled, 14);
        String otherModel = "Xiaomi/sample/sample:17/sample/OS4.1.0.0.SAMPLE:user/release-keys";
        if (!RefreshUnlockContract.supportsHooks(otherModel, "OS4.1.0.0.SAMPLE", true)
                || !RefreshUnlockContract.supportsHooks(otherModel, "OS4.0.3.0.XOACNXM", true)
                || !RefreshUnlockContract.supportsHooks(RefreshUnlockContract.FINGERPRINT, "OS4.0.6.0.XOTCNXM", true)
                || RefreshUnlockContract.supportsHooks(otherModel, "OS4.1.0.0.SAMPLE", false)
                || !RefreshUnlockContract.supportsHooks(RefreshUnlockContract.FINGERPRINT, "OS4.0.6.0.XOTCNXM", false))
            throw new AssertionError("Firmware and module scope");
        checks += 5;
        for (String otherVersion : new String[]{null, "", "OS3.0.0.1", "OS40.1", "OS4.", "OS4.preview"}) {
            if (RefreshUnlockContract.supportsHooks(otherModel, otherVersion, true))
                throw new AssertionError("Unexpected OS version admitted: " + otherVersion);
            checks++;
        }
        String windows = WindowDecorationContract.report(14, true, true, "challenge");
        RefreshCapabilities both = new RefreshCapabilities(true, true, current, enabled, windows, 14);
        if (!both.windowsLoaded || !both.squareWindows || !both.nativeCaption || !both.enabled)
            throw new AssertionError("Independent refresh and window acknowledgements");
        RefreshCapabilities noScope = new RefreshCapabilities(true, true, current, enabled, null, 14);
        if (noScope.windowsLoaded || !noScope.loaded) throw new AssertionError("SystemUI scope gate");
        RefreshCapabilities stale = new RefreshCapabilities(true, true, current, enabled, windows, 15);
        if (stale.windowsLoaded) throw new AssertionError("Stale window receipt");
        RefreshCapabilities noRoot = new RefreshCapabilities(false, true, current, enabled, windows, 14);
        if (noRoot.windowsLoaded) throw new AssertionError("Root gate");
        if (WindowDecorationContract.isLoaded("1:14:1:1:", 14)
                || WindowDecorationContract.isLoaded("1:14:1:unknown:n", 14)
                || WindowDecorationContract.isLoaded(windows + ":extra", 14))
            throw new AssertionError("Malformed window receipt");
        if (!WindowDecorationContract.hasCaption("1:14:388,389", 14, 388)
                || WindowDecorationContract.hasCaption("1:14:388,389", 15, 388)
                || WindowDecorationContract.hasCaption("1:14:388,389", 14, 38))
            throw new AssertionError("Caption task membership");
        if (!WindowDecorationContract.isDesktopTask(5, "ExtBridge-device", 5, "com.miui.calculator")
                || WindowDecorationContract.isDesktopTask(1, "ExtBridge-device", 5, "com.miui.calculator")
                || WindowDecorationContract.isDesktopTask(5, "OtherVirtualDisplay", 5, "com.miui.calculator")
                || WindowDecorationContract.isDesktopTask(5, "ExtBridge-device", 1, "com.miui.calculator")
                || WindowDecorationContract.isDesktopTask(5, "ExtBridge-device", 5, "com.extbridge.helper")
                || WindowDecorationContract.isDesktopTask(5, "ExtBridge-device", 5, "com.miui.securitycenter"))
            throw new AssertionError("Window scope guard");
        checks += 12;
        System.out.println("PASS: " + checks + " permission, firmware, version, boot and acknowledgement cases");
    }
}
