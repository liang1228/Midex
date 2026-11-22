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
import java.util.function.Consumer;

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
    public static final String MODULE_PATH = "/data/adb/modules/extbridge_autostart";
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

    static final class CmdResult {
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

    static boolean hasVerifiedRoot() {
        return Boolean.TRUE.equals(sRootCache);
    }

    private static CmdResult verifyRoot(long timeoutMs) {
        CmdResult r = runSu("id -u", timeoutMs);
        sRootCache = r.ok() && "0".equals(r.output.trim());
        return r;
    }

    static void checkRootAccessAsync(Consumer<Boolean> callback) {
        EXEC.execute(() -> {
            verifyRoot(30000);
            boolean granted = hasVerifiedRoot();
            MAIN.post(() -> callback.accept(granted));
        });
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
            CmdResult id = verifyRoot(60000);
            if (!hasVerifiedRoot()) {
                result(listener, false, "Root 授权失败或超时。\n" + id.tail(600)
                        + "\n\n请确认 KernelSU/Magisk 已授予 Midex root 权限。");
                return;
            }

            BridgeClient.RuntimeInfo running = BridgeClient.getRuntimeInfo();
            if (running.valid && "system_server".equals(running.hostModeName)) {
                result(listener, false, "当前已由 LSPosed 系统框架模式接管，无需再启动 root 守护进程。\n"
                        + "如需改用 Root 模式，请在 LSPosed 中停用 Midex 模块并重启。");
                return;
            }
            if (running.valid && running.uid == 0 && "root".equals(running.hostModeName)
                    && BuildConfig.VERSION_NAME.equals(running.daemonVersion)
                    && BridgeClient.getStatus().connected) {
                result(listener, true, "Root 服务已运行，权限检查通过。\nuid="
                        + running.uid + "  pid=" + running.pid);
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
                    + "pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null || true\n"
                    + "pkill -f '^app_process.*ExtBridge' 2>/dev/null || true\n"
                    + "sleep 0.4\n"
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
                    if (!ri.valid || ri.uid != 0 || !"root".equals(ri.hostModeName)
                            || !BuildConfig.VERSION_NAME.equals(ri.daemonVersion)) {
                        sleep(400);
                        continue;
                    }
                    String mode = ri.hostModeName;
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
                CmdResult r = runSu("set -e\nif [ -d '" + MODULE_PATH + "' ]; then touch '"
                        + MODULE_PATH + "/disable'; fi\nrm -f '" + AUTOSTART_PATH + "'\necho REMOVED", 15000);
                result(listener, r.ok() && r.output != null && r.output.contains("REMOVED"),
                        r.ok() ? "已关闭模块自启动，当前桌面继续运行" : ("关闭失败:\n" + r.tail(600)));
                return;
            }
            try {
                File jar = extractJar(app);
                for (String name : new String[]{"module.prop", "service.sh"}) {
                    try (InputStream input = app.getAssets().open("autostart/" + name);
                         OutputStream output = new FileOutputStream(new File(app.getCacheDir(), name))) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                }
                String cache = app.getCacheDir().getAbsolutePath();
                String script = "set -e\n[ \"$(id -u)\" = 0 ] || exit 1\n"
                        + "if [ ! -d /data/adb/ksu ] && [ ! -d /data/adb/magisk ]; then echo 'KernelSU / Magisk module support required'; exit 1; fi\n"
                        + "mkdir -p '" + MODULE_PATH + "'\n"
                        + "cp '" + jar.getAbsolutePath() + "' '" + MODULE_PATH + "/extbridge.jar.tmp'\n"
                        + "chmod 644 '" + MODULE_PATH + "/extbridge.jar.tmp'\n"
                        + "mv -f '" + MODULE_PATH + "/extbridge.jar.tmp' '" + MODULE_PATH + "/extbridge.jar'\n"
                        + "cp '" + cache + "/module.prop' '" + MODULE_PATH + "/module.prop'\n"
                        + "cp '" + cache + "/service.sh' '" + MODULE_PATH + "/service.sh.tmp'\n"
                        + "chmod 755 '" + MODULE_PATH + "/service.sh.tmp'\n"
                        + "mv -f '" + MODULE_PATH + "/service.sh.tmp' '" + MODULE_PATH + "/service.sh'\n"
                        + "touch '" + MODULE_PATH + "/skip_mount'\n"
                        + "rm -f '" + MODULE_PATH + "/disable' '" + MODULE_PATH + "/remove' '" + AUTOSTART_PATH + "'\n"
                        + "sh '" + MODULE_PATH + "/service.sh'\n"
                        + "echo INSTALLED";
                CmdResult r = runSu(script, 20000);
                result(listener, r.ok() && r.output != null && r.output.contains("INSTALLED"),
                        r.ok() ? "即插即用模块已开启。无需保留控制台后台；未接屏时停止轮询和前台服务。"
                                : ("安装失败:\n" + r.tail(800)));
            } catch (Throwable t) {
                result(listener, false, "安装失败: " + t);
            }
        });
    }

    public static boolean isAutostartEnabled() {
        CmdResult r = runSu("[ -f '" + MODULE_PATH + "/service.sh' ] && [ ! -f '"
                + MODULE_PATH + "/disable' ] && [ ! -f '" + MODULE_PATH + "/remove' ] && echo YES || echo NO", 10000);
        return r.output != null && r.output.contains("YES");
    }

    /** 读取 LSPosed 安装/版本信息，并在 UI 中给出启用指引。 */
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

    static CmdResult runSu(String command, long timeoutMs) {
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
