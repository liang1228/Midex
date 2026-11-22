package com.extbridge.helper;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import com.extbridge.refreshunlock.RefreshUnlockContract;
import com.extbridge.refreshunlock.WindowDecorationContract;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Optional Root extension. Reading capability state never executes su or changes settings. */
public final class RefreshExtension {
    private RefreshExtension() {}

    public static boolean supportsExternalRefreshTools() {
        return RefreshUnlockContract.FINGERPRINT.equals(Build.FINGERPRINT);
    }

    public enum ExternalRefreshAction {
        STATUS("status"), CHECK_144("check 144"), CHECK_165("check 165"),
        START_144("start 144"), START_165("start 165"), CANCEL("stop");

        private final String arguments;
        ExternalRefreshAction(String arguments) { this.arguments = arguments; }
    }

    // The optional bundle installs this script under the root-only /data/adb directory.
    public static String externalRefresh(ExternalRefreshAction action) {
        if (!supportsExternalRefreshTools()) return "UNSUPPORTED";
        RootBootstrap.CmdResult result = RootBootstrap.runSu(
                "script=/data/adb/extbridge-external-refresh/external-refresh.sh\n"
                + "[ -r \"$script\" ] || { echo NOT_INSTALLED; exit 0; }\n"
                + "exec /system/bin/sh \"$script\" " + action.arguments, 8000);
        return result.ok() ? result.output.trim() : "ERROR " + result.tail(240);
    }

    public enum ManualRefreshAction {
        STATUS("status"), BUDGET("budget"), START("start"), CANCEL("stop");

        private final String argument;
        ManualRefreshAction(String argument) { this.argument = argument; }
    }

    public static String manualRefresh(ManualRefreshAction action, int rate) {
        if (!supportsExternalRefreshTools()) return "UNSUPPORTED";
        if (action == ManualRefreshAction.START && (rate < 61 || rate > 240)) return "ERROR INVALID_RATE";
        RootBootstrap.CmdResult result = RootBootstrap.runSu(
                "script=/data/adb/extbridge-external-refresh/manual-v1/manual-refresh.sh\n"
                + "[ -r \"$script\" ] || { echo NOT_INSTALLED; exit 0; }\n"
                + "exec /system/bin/sh \"$script\" " + action.argument
                + (action == ManualRefreshAction.START ? " " + rate : ""), 8000);
        String output = result.output.trim();
        return result.ok() || output.startsWith("ERROR ") ? output : "ERROR " + result.tail(240);
    }

    public static RefreshCapabilities read(Context context) {
        boolean root = RootBootstrap.hasVerifiedRoot();
        long legacyVersion = legacyVersion(context);
        long version = legacyVersion != 0 ? legacyVersion : RefreshUnlockContract.VERSION;
        int boot = -1;
        String report = null;
        String windowsReport = null;
        try {
            boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            int fromBoot = Settings.Global.getInt(context.getContentResolver(), RefreshUnlockContract.INTEGRATED_FROM_BOOT, -1);
            if (legacyVersion != 0 || (fromBoot >= 0 && boot >= fromBoot)) {
                report = Settings.Global.getString(context.getContentResolver(), RefreshUnlockContract.STATUS);
                windowsReport = Settings.Global.getString(context.getContentResolver(), WindowDecorationContract.STATUS);
            }
        } catch (Exception ignored) {}
        return new RefreshCapabilities(root, supportsHooks(legacyVersion == 0),
                version, report, windowsReport, boot, legacyVersion != 0);
    }

    private static boolean supportsHooks(boolean integrated) {
        return RefreshUnlockContract.supportsHooks(Build.FINGERPRINT, Build.VERSION.INCREMENTAL, integrated);
    }

    private static String firmwareCheck() {
        return "[ \"$(getprop ro.build.fingerprint)\" = '" + Build.FINGERPRINT.replace("'", "'\\''") + "' ]\n";
    }

    private static long legacyVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(RefreshUnlockContract.PACKAGE, 0)
                    .getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return 0; }
    }

    /** Returns an error message, or null after the migration gate is ready. */
    public static String prepareIntegrated() {
        if (!supportsHooks(true)) return "当前固件暂无适配";
        String key = RefreshUnlockContract.INTEGRATED_FROM_BOOT;
        RootBootstrap.CmdResult result = RootBootstrap.runSu("set -e\n"
                + "[ \"$(id -u)\" = 0 ]\n"
                + "if pm path " + RefreshUnlockContract.PACKAGE + " >/dev/null; then echo LEGACY_PRESENT; exit 1; fi\n"
                + "boot=$(settings get global boot_count)\n"
                + "case \"$boot\" in ''|*[!0-9]*) echo BOOT_COUNT_UNAVAILABLE; exit 1;; esac\n"
                + "if [ \"$(settings get global " + key + ")\" = null ]; then\n"
                + "    settings put global " + key + " \"$((boot + 1))\"\n"
                + "fi\n"
                + "echo READY", 10000);
        if (!result.ok() || !result.output.trim().equals("READY"))
            return "准备内置扩展失败。请检查 Root 授权和模块配置。\n" + result.tail(240);
        return null;
    }

    public static String installRootTools(Context context) {
        if (!supportsExternalRefreshTools()) return "当前固件暂无适配";
        String[] files = {"external-refresh.sh", "drm-read-current", "live-mode-table", "extbridge_618_mode_cap.ko",
                "manual-v1/manual-refresh.sh", "manual-v1/trace-dp-policy.sh", "manual-v1/manual-timing",
                "manual-v1/manual-selector", "manual-v1/drm-read-props"};
        Path cache = null;
        try {
            cache = Files.createTempDirectory(context.getCacheDir().toPath(), "extbridge-root-");
            Files.createDirectory(cache.resolve("manual-v1"));
            for (String file : files) {
                try (InputStream input = context.getAssets().open("root-tools/" + file)) {
                    Files.copy(input, cache.resolve(file));
                }
            }
            String script;
            try (InputStream input = context.getAssets().open("root-tools/install.sh")) {
                script = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            RootBootstrap.CmdResult result = RootBootstrap.runSu("bundle='" + cache + "'\n"
                    + "fingerprint='" + RefreshUnlockContract.FINGERPRINT + "'\n" + script, 30000);
            return result.ok() ? "内置外屏工具已就绪" : "安装外屏工具失败。\n" + result.tail(400);
        } catch (Exception failure) {
            return "读取内置外屏工具失败。\n" + failure.getMessage();
        } finally {
            if (cache != null) {
                try {
                    for (String file : files) Files.deleteIfExists(cache.resolve(file));
                    Files.deleteIfExists(cache.resolve("manual-v1"));
                    Files.deleteIfExists(cache);
                } catch (Exception ignored) {}
            }
        }
    }

    /** A fresh module acknowledgement is required before changing the existing policy switch. */
    public static String setEnabled(Context context, boolean enabled) {
        RefreshCapabilities state = read(context);
        if (!state.loaded) return "模块尚未生效，请先在 LSPosed 中启用并重启设备";
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        String nonce = UUID.randomUUID().toString();
        String previous = RefreshUnlockContract.report(boot, state.enabled, nonce);
        String expected = RefreshUnlockContract.report(boot, enabled, nonce);
        String script = "set -e\n"
                + firmwareCheck()
                + "wait_report() { i=0; while [ \"$i\" -lt 20 ]; do "
                + "[ \"$(settings get global " + RefreshUnlockContract.STATUS + ")\" = \"$1\" ] && return 0; "
                + "sleep 0.2; i=$((i+1)); done; return 1; }\n"
                + "settings put global " + RefreshUnlockContract.REQUEST + " '" + nonce + "'\n"
                + "wait_report '" + previous + "' || { echo MODULE_NOT_RESPONDING; exit 1; }\n"
                + "saved=$(settings get global " + RefreshUnlockContract.SETTING + ")\n"
                + "settings put global " + RefreshUnlockContract.SETTING + " " + (enabled ? "1" : "0") + "\n"
                + "if wait_report '" + expected + "'; then echo EXTENSION_OK; else\n"
                + "if [ \"$saved\" = null ]; then settings delete global " + RefreshUnlockContract.SETTING + "; "
                + "else settings put global " + RefreshUnlockContract.SETTING + " \"$saved\"; fi\n"
                + "echo MODULE_UPDATE_FAILED; exit 1; fi\n";
        RootBootstrap.CmdResult result = RootBootstrap.runSu(script, 20000);
        if (!result.ok() || !result.output.contains("EXTENSION_OK"))
            return "模块未确认生效，已保留原设置。请检查 Root 授权和 LSPosed 状态。\n" + result.tail(400);
        return enabled ? "已解除接外屏时的内屏限刷，保留系统刷新率偏好" : "已恢复系统的内屏限刷策略";
    }

    public static void openManager(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        if (intent != null) context.startActivity(intent);
        else Toast.makeText(context, "请从 LSPosed 通知或快捷方式打开管理器", Toast.LENGTH_LONG).show();
    }

    public static String setWindowFeature(Context context, String key, boolean enabled) {
        if (!WindowDecorationContract.SQUARE.equals(key) && !WindowDecorationContract.CAPTION.equals(key))
            throw new IllegalArgumentException("Unknown window feature");
        RefreshCapabilities state = read(context);
        if (!state.windowsLoaded) return "窗口扩展尚未加载，请在 LSPosed 中勾选系统界面并重启";
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        String nonce = UUID.randomUUID().toString();
        String previous = WindowDecorationContract.report(boot, state.squareWindows, state.nativeCaption, nonce);
        String expected = WindowDecorationContract.report(boot,
                WindowDecorationContract.SQUARE.equals(key) ? enabled : state.squareWindows,
                WindowDecorationContract.CAPTION.equals(key) ? enabled : state.nativeCaption, nonce);
        String script = "set -e\n"
                + firmwareCheck()
                + "wait_report() { i=0; while [ \"$i\" -lt 30 ]; do "
                + "[ \"$(settings get global " + WindowDecorationContract.STATUS + ")\" = \"$1\" ] && return 0; "
                + "sleep 0.2; i=$((i+1)); done; return 1; }\n"
                + "settings put global " + WindowDecorationContract.REQUEST + " '" + nonce + "'\n"
                + "wait_report '" + previous + "' || { echo MODULE_NOT_RESPONDING; exit 1; }\n"
                + "saved=$(settings get global " + key + ")\n"
                + "settings put global " + key + " " + (enabled ? "1" : "0") + "\n"
                + "if wait_report '" + expected + "'; then echo WINDOW_EXTENSION_OK; else\n"
                + "if [ \"$saved\" = null ]; then settings delete global " + key + "; "
                + "else settings put global " + key + " \"$saved\"; fi\n"
                + "echo WINDOW_UPDATE_FAILED; exit 1; fi\n";
        RootBootstrap.CmdResult result = RootBootstrap.runSu(script, 25000);
        if (!result.ok() || !result.output.contains("WINDOW_EXTENSION_OK"))
            return "窗口模块未确认生效，已保留原设置。\n" + result.tail(400);
        return (WindowDecorationContract.SQUARE.equals(key) ? "直角窗口" : "内置窗口栏")
                + (enabled ? "已开启，仅作用于外屏桌面窗口" : "已关闭，恢复系统窗口样式");
    }
}
