package com.extbridge.helper;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Root 模式激活器：通过 su 启动 app_process 守护进程，完全绕开无线调试/电脑 ADB。
 *
 * 设计原则:
 *  - 只在用户点击时申请 root，不做静默提权；
 *  - 守护进程 jar 由 App 自己从 assets 解出，root 只负责拷贝和启动；
 *  - 所有失败都把 su 输出和 logcat 尾部带回 UI，方便定位；
 *  - 与 LSPosed 系统框架模式互斥：检测到 system_server 已接管时拒绝重复启动。
 */
public final class RootBootstrap {
    private static final String TAG = "ExtBridgeRoot";
    public static final String JAR_PATH = "/data/local/tmp/extbridge.jar";
    public static final String LOG_PATH = "/data/local/tmp/extbridge.log";
    public static final String AUTOSTART_PATH = "/data/adb/service.d/99-extbridge.sh";
    private static final String DOWNLOAD_DIR = "/sdcard/Download/DSH-Go";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Boolean sRootCache;

    public interface Listener {
        void onStep(String message);
        void onResult(boolean success, String message);
    }

    public interface JsonListener {
        void onResult(String json, String error);
    }

    private static final class CmdResult {
        int exit = -1;
        String output = "";
        boolean timedOut;

        boolean ok() {
            return exit == 0 && !timedOut;
        }

        String tail(int max) {
            if (output == null) return "";
            String s = output.trim();
            return s.length() <= max ? s : s.substring(s.length() - max);
        }
    }

    private RootBootstrap() {}

    public static boolean isRootAvailable() {
        Boolean c = sRootCache;
        if (c != null) return c;
        CmdResult r = runSu("id", 20000);
        boolean ok = r.ok() && r.output != null && r.output.contains("uid=0");
        sRootCache = ok;
        return ok;
    }

    public static String rootIdentity() {
        CmdResult r = runSu("id; cat /proc/self/attr/current 2>/dev/null", 8000);
        return r.output == null ? "" : r.output.trim();
    }

    /** root 一键激活：解 jar -> su 拷贝 -> app_process 启动 -> 等 Binder。 */
    public static void activateAsync(final Context ctx, final Listener listener) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            step(listener, "正在请求 root 权限 (KernelSU / Magisk 请点允许)…");
            CmdResult id = runSu("id", 60000);
            if (!id.ok() || id.output == null || !id.output.contains("uid=0")) {
                result(listener, false, "Root 授权失败或超时。\n" + id.tail(600)
                        + "\n\n请确认 KernelSU/Magisk 已授予 ExtBridge root 权限。");
                return;
            }
            sRootCache = true;

            BridgeClient.RuntimeInfo running = BridgeClient.getRuntimeInfo();
            if (running.valid && "system_server".equals(running.hostModeName)) {
                result(listener, false, "当前已由 LSPosed 系统框架模式接管，无需再启动 root 守护进程。\n"
                        + "如需改用 Root 模式，请在 LSPosed 中停用 ExtBridge 模块并重启。");
                return;
            }

            step(listener, "正在释放 extbridge.jar…");
            File cacheJar;
            try {
                cacheJar = extractJar(app);
            } catch (Throwable t) {
                result(listener, false, "释放 extbridge.jar 失败: " + t);
                return;
            }

            step(listener, "正在以 root 启动守护进程…");
            String script = "set -e\n"
                    + "cp '" + cacheJar.getAbsolutePath() + "' " + JAR_PATH + "\n"
                    + "chmod 644 " + JAR_PATH + "\n"
                    + "appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null || true\n"
                    + "appops set com.extbridge.helper POST_NOTIFICATION allow 2>/dev/null || true\n"
                    + "appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true\n"
                    + "pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null || true\n"
                    + "dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1 || true\n"
                    + "pkill -f '^app_process.*ExtBridge' 2>/dev/null || true\n"
                    + "sleep 0.4\n"
                    + ": > " + LOG_PATH + "\n"
                    + "cd /data/local/tmp\n"
                    + "CLASSPATH=" + JAR_PATH + " nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge "
                    + "</dev/null >>" + LOG_PATH + " 2>&1 &\n"
                    + "sleep 1.2\n"
                    + "echo ROOT_LAUNCH_OK\n";
            CmdResult launch = runSu(script, 90000);
            if (!launch.ok() || launch.output == null || !launch.output.contains("ROOT_LAUNCH_OK")) {
                result(listener, false, "root 启动命令失败:\n" + launch.tail(1500)
                        + "\n\nlog:\n" + readRoot(LOG_PATH, 3000));
                return;
            }

            step(listener, "守护进程已拉起，等待 Binder 握手…");
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline) {
                BridgeClient.requestBinder(app);
                BridgeClient.Status st = BridgeClient.getStatus();
                if (st.connected) {
                    BridgeClient.RuntimeInfo ri = BridgeClient.getRuntimeInfo();
                    String mode = ri.valid ? ri.hostModeName : st.hostMode;
                    result(listener, true, "Root 激活成功！\n运行模式: " + BridgeClient.hostModeLabel(mode)
                            + (ri.valid ? ("  uid=" + ri.uid + "  pid=" + ri.pid) : "")
                            + "\n" + (ri.valid && ri.selinux != null && !ri.selinux.isEmpty() ? ("SELinux: " + ri.selinux) : ""));
                    return;
                }
                sleep(400);
            }
            result(listener, false, "启动命令已执行，但未收到 Binder 握手。\nlog:\n" + readRoot(LOG_PATH, 3000));
        });
    }

    /** root 下直接跑 app_process probe，拿到完整驱动 JSON；不需要守护进程在线。 */
    public static void probeAsync(final Context ctx, final JsonListener listener) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            File jar;
            try {
                jar = extractJar(app);
            } catch (Throwable t) {
                postJson(listener, null, "释放 jar 失败: " + t);
                return;
            }
            String script = "cp '" + jar.getAbsolutePath() + "' " + JAR_PATH + " && chmod 644 " + JAR_PATH + "\n"
                    + "CLASSPATH=" + JAR_PATH + " app_process /data/local/tmp com.extbridge.daemon.ExtBridge probe\n";
            CmdResult r = runSu(script, 60000);
            if (!r.ok() || r.output == null) {
                postJson(listener, null, "驱动采集失败:\n" + r.tail(1200));
                return;
            }
            String out = r.output;
            int start = out.indexOf('{');
            int end = out.lastIndexOf('}');
            if (start < 0 || end <= start) {
                postJson(listener, null, "采集输出不是 JSON:\n" + r.tail(1200));
                return;
            }
            postJson(listener, out.substring(start, end + 1), null);
        });
    }

    /** 把 JSON 通过 root 写到 /sdcard/Download/DSH-Go，避免受 scoped storage 限制。 */
    public static void saveJsonAsync(final Context ctx, final String json, final String filename, final Listener listener) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                File tmp = new File(app.getCacheDir(), "extbridge-probe-export.json");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(json.getBytes("UTF-8"));
                }
                String safe = filename.replaceAll("[^A-Za-z0-9._-]", "_");
                String script = "mkdir -p '" + DOWNLOAD_DIR + "'\n"
                        + "cp '" + tmp.getAbsolutePath() + "' '" + DOWNLOAD_DIR + "/" + safe + "'\n"
                        + "chmod 664 '" + DOWNLOAD_DIR + "/" + safe + "'\n"
                        + "echo SAVED";
                CmdResult r = runSu(script, 20000);
                if (!r.ok() || r.output == null || !r.output.contains("SAVED")) {
                    result(listener, false, "保存失败:\n" + r.tail(800));
                    return;
                }
                final String path = DOWNLOAD_DIR + "/" + safe;
                MediaScannerConnection.scanFile(app, new String[]{path}, new String[]{"application/json"}, null);
                result(listener, true, "已保存到:\n" + path);
            } catch (Throwable t) {
                result(listener, false, "保存失败: " + t);
            }
        });
    }

    public static void setAutostartAsync(final Context ctx, final boolean enable, final Listener listener) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            if (!enable) {
                CmdResult r = runSu("rm -f '" + AUTOSTART_PATH + "'; echo REMOVED", 15000);
                result(listener, r.ok() && r.output != null && r.output.contains("REMOVED"),
                        r.ok() ? "已关闭 Root 开机自启" : ("关闭失败:\n" + r.tail(600)));
                return;
            }
            try {
                File scriptFile = new File(app.getCacheDir(), "99-extbridge.sh");
                try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                    fos.write(buildAutostartScript().getBytes("UTF-8"));
                }
                String script = "mkdir -p /data/adb/service.d\n"
                        + "cp '" + scriptFile.getAbsolutePath() + "' '" + AUTOSTART_PATH + "'\n"
                        + "chmod 755 '" + AUTOSTART_PATH + "'\n"
                        + "echo INSTALLED";
                CmdResult r = runSu(script, 20000);
                result(listener, r.ok() && r.output != null && r.output.contains("INSTALLED"),
                        r.ok() ? "已开启 Root 开机自启:\n" + AUTOSTART_PATH : ("安装失败:\n" + r.tail(800)));
            } catch (Throwable t) {
                result(listener, false, "安装失败: " + t);
            }
        });
    }

    public static boolean isAutostartEnabled() {
        CmdResult r = runSu("test -f '" + AUTOSTART_PATH + "' && echo YES || echo NO", 10000);
        return r.output != null && r.output.contains("YES");
    }

    /** 读取 LSPosed 安装/版本信息，并在 UI 中给出启用指引。 */
    public static String lsposedInfo(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            boolean manager = false;
            try {
                ctx.getPackageManager().getPackageInfo("org.lsposed.manager", 0);
                manager = true;
            } catch (Throwable ignored) {}
            sb.append(manager ? "LSPosed 管理器: 已安装\n" : "LSPosed 管理器: 未安装\n");
            CmdResult r = runSu("cat /data/adb/modules/zygisk_lsposed/module.prop 2>/dev/null | head -8", 10000);
            if (r.output != null && r.output.contains("version=")) {
                sb.append("LSPosed 框架: 已安装\n");
                for (String line : r.output.split("\n")) {
                    if (line.startsWith("version")) sb.append(line).append('\n');
                }
            } else {
                sb.append("LSPosed 框架: 未检测到 /data/adb/modules/zygisk_lsposed\n");
            }
            BridgeClient.RuntimeInfo ri = BridgeClient.getRuntimeInfo();
            if (ri.valid && "system_server".equals(ri.hostModeName)) {
                sb.append("\n状态: ✅ 模块已注入 system_server，当前由 LSPosed 模式接管\n");
            } else {
                sb.append("\n状态: 尚未注入 system_server\n")
                        .append("启用步骤:\n")
                        .append("1. 打开 LSPosed 管理器 → 模块 → 勾选 ExtBridge\n")
                        .append("2. 作用域勾选「系统框架」(android)\n")
                        .append("3. 重启平板（或软重启 system_server）\n")
                        .append("4. 回到本页，运行模式应显示「LSPosed 系统框架模式」\n");
            }
        } catch (Throwable t) {
            sb.append("读取失败: ").append(t);
        }
        return sb.toString();
    }

    private static String buildAutostartScript() {
        return "#!/system/bin/sh\n"
                + "# ExtBridge root autostart (generated by helper app)\n"
                + "(\n"
                + "  until [ \"$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done\n"
                + "  sleep 8\n"
                + "  # LSPosed 模块已接管时不要重复启动\n"
                + "  [ \"$(getprop extbridge.lsposed)\" = \"1\" ] && exit 0\n"
                + "  APK=$(pm path com.extbridge.helper | head -n1 | cut -d: -f2)\n"
                + "  [ -n \"$APK\" ] || exit 0\n"
                + "  unzip -p \"$APK\" assets/extbridge.jar > " + JAR_PATH + " 2>/dev/null\n"
                + "  [ -s " + JAR_PATH + " ] || exit 0\n"
                + "  chmod 644 " + JAR_PATH + "\n"
                + "  pkill -f '^app_process.*ExtBridge' 2>/dev/null\n"
                + "  sleep 0.3\n"
                + "  cd /data/local/tmp\n"
                + "  CLASSPATH=" + JAR_PATH + " nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge "
                + "</dev/null >>" + LOG_PATH + " 2>&1 &\n"
                + ") &\n";
    }

    private static File extractJar(Context ctx) throws Exception {
        File out = new File(ctx.getCacheDir(), "extbridge.jar");
        File tmp = new File(ctx.getCacheDir(), "extbridge.jar.tmp");
        try (InputStream in = ctx.getAssets().open("extbridge.jar");
             OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        if (out.exists() && !out.delete()) throw new Exception("无法覆盖旧 jar");
        if (!tmp.renameTo(out)) throw new Exception("无法写入 " + out);
        return out;
    }

    private static CmdResult runSu(String command, long timeoutMs) {
        CmdResult res = new CmdResult();
        java.lang.Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/su", "-c", command);
            pb.redirectErrorStream(true);
            p = pb.start();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (bos.size() < 128 * 1024) bos.write(buf, 0, Math.min(n, 128 * 1024 - bos.size()));
                    }
                } catch (Throwable ignored) {}
            });
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            res.timedOut = !finished;
            if (!finished) p.destroy();
            reader.join(600);
            res.output = new String(bos.toByteArray(), "UTF-8");
            res.exit = finished ? p.exitValue() : -1;
        } catch (Throwable t) {
            res.output = "执行 su 失败: " + t;
            res.exit = -1;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return res;
    }

    private static String readRoot(String path, int max) {
        CmdResult r = runSu("tail -c " + max + " '" + path + "' 2>/dev/null", 10000);
        return r.output == null ? "" : r.output.trim();
    }

    private static void step(final Listener l, final String msg) {
        if (l == null) return;
        MAIN.post(() -> l.onStep(msg));
    }

    private static void result(final Listener l, final boolean ok, final String msg) {
        if (l == null) return;
        MAIN.post(() -> l.onResult(ok, msg));
    }

    private static void postJson(final JsonListener l, final String json, final String err) {
        if (l == null) return;
        MAIN.post(() -> l.onResult(json, err));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
