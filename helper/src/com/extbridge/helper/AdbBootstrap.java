package com.extbridge.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Self-contained on-device ADB helper bundling native arm64-v8a adb (libadb.so),
 * NsdManager mDNS discovery for Wireless Debugging pairing/connect endpoints,
 * and RemoteInput notification support for typing the 6-digit pairing code
 * without dismissing Android's pairing popup.
 */
public final class AdbBootstrap {
    private static final String TAG = "ExtBridgeAdb";
    public static final String ACTION_PAIR_REPLY = "com.extbridge.helper.ACTION_PAIR_REPLY";
    public static final String ACTION_STOP_PAIR_NOTIF = "com.extbridge.helper.ACTION_STOP_PAIR_NOTIF";
    public static final String KEY_PAIR_INPUT = "pair_input";
    private static final String PAIR_CHANNEL_ID = "extbridge_adb_pair";
    private static final int PAIR_NOTIF_ID = 3901;
    private static final String SERVICE_PAIRING = "_adb-tls-pairing._tcp.";
    private static final String SERVICE_CONNECT = "_adb-tls-connect._tcp.";
    private static final String ADB_SERVER_PORT = "15037";

    private static final ExecutorService sExec = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    public static final class DiscoveryState {
        public String pairingHost = "";
        public int pairingPort = 0;
        public String connectHost = "";
        public int connectPort = 0;
    }

    public interface DiscoveryListener {
        void onDiscoveryUpdated(DiscoveryState state);
    }

    public interface ProgressListener {
        void onStep(String message);
        void onResult(boolean success, String message);
    }

    private static NsdManager sNsd;
    private static NsdManager.DiscoveryListener sPairDisc;
    private static NsdManager.DiscoveryListener sConnDisc;
    private static WifiManager.MulticastLock sMcastLock;
    private static final DiscoveryState sDiscState = new DiscoveryState();
    private static final ArrayDeque<NsdServiceInfo> sResolveQueue = new ArrayDeque<>();
    private static boolean sResolving = false;
    private static DiscoveryListener sDiscListener;

    private AdbBootstrap() {}

    public static synchronized DiscoveryState getDiscoveryState() {
        DiscoveryState copy = new DiscoveryState();
        copy.pairingHost = sDiscState.pairingHost;
        copy.pairingPort = sDiscState.pairingPort;
        copy.connectHost = sDiscState.connectHost;
        copy.connectPort = sDiscState.connectPort;
        return copy;
    }

    public static synchronized void startDiscovery(Context ctx, DiscoveryListener listener) {
        sDiscListener = listener;
        Context appCtx = ctx.getApplicationContext();
        if (sNsd != null) {
            notifyDiscListener();
            return;
        }
        try {
            WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && sMcastLock == null) {
                sMcastLock = wm.createMulticastLock("extbridge_mdns");
                sMcastLock.setReferenceCounted(false);
                sMcastLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "MulticastLock unavailable", t);
        }

        try {
            sNsd = (NsdManager) appCtx.getSystemService(Context.NSD_SERVICE);
            if (sNsd == null) return;
            sPairDisc = makeDiscoveryListener(true);
            sConnDisc = makeDiscoveryListener(false);
            sNsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, sPairDisc);
            sNsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, sConnDisc);
        } catch (Throwable t) {
            Log.e(TAG, "startDiscovery failed", t);
        }
    }

    public static synchronized void stopDiscovery() {
        sDiscListener = null;
        if (sNsd != null) {
            try { if (sPairDisc != null) sNsd.stopServiceDiscovery(sPairDisc); } catch (Throwable ignored) {}
            try { if (sConnDisc != null) sNsd.stopServiceDiscovery(sConnDisc); } catch (Throwable ignored) {}
        }
        sPairDisc = null;
        sConnDisc = null;
        sNsd = null;
        synchronized (sResolveQueue) {
            sResolveQueue.clear();
            sResolving = false;
        }
        if (sMcastLock != null) {
            try { if (sMcastLock.isHeld()) sMcastLock.release(); } catch (Throwable ignored) {}
            sMcastLock = null;
        }
    }

    private static NsdManager.DiscoveryListener makeDiscoveryListener(final boolean isPairing) {
        return new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onDiscoveryStopped(String serviceType) {}

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                enqueueResolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                synchronized (AdbBootstrap.class) {
                    if (isPairing) {
                        sDiscState.pairingPort = 0;
                    } else {
                        sDiscState.connectPort = 0;
                    }
                    notifyDiscListener();
                }
            }
        };
    }

    private static void enqueueResolve(NsdServiceInfo info) {
        synchronized (sResolveQueue) {
            sResolveQueue.addLast(info);
            pumpResolveLocked();
        }
    }

    @SuppressWarnings("deprecation")
    private static void pumpResolveLocked() {
        if (sResolving || sResolveQueue.isEmpty() || sNsd == null) return;
        final NsdServiceInfo next = sResolveQueue.pollFirst();
        if (next == null) return;
        sResolving = true;
        try {
            sNsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    synchronized (sResolveQueue) {
                        sResolving = false;
                        pumpResolveLocked();
                    }
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    handleResolved(resolved);
                    synchronized (sResolveQueue) {
                        sResolving = false;
                        pumpResolveLocked();
                    }
                }
            });
        } catch (Throwable t) {
            sResolving = false;
            pumpResolveLocked();
        }
    }

    private static void handleResolved(NsdServiceInfo resolved) {
        String type = resolved.getServiceType();
        if (type == null) type = "";
        InetAddress addr = resolved.getHost();
        String host = addr != null ? addr.getHostAddress() : "";
        if (host != null && host.contains("%")) {
            host = host.substring(0, host.indexOf('%'));
        }
        int port = resolved.getPort();
        if (port <= 0) return;

        synchronized (AdbBootstrap.class) {
            if (type.contains("pairing")) {
                sDiscState.pairingHost = host != null ? host : "";
                sDiscState.pairingPort = port;
            } else if (type.contains("connect")) {
                sDiscState.connectHost = host != null ? host : "";
                sDiscState.connectPort = port;
            }
            notifyDiscListener();
        }
    }

    private static void notifyDiscListener() {
        final DiscoveryListener l = sDiscListener;
        if (l == null) return;
        final DiscoveryState snap = getDiscoveryState();
        sMain.post(() -> l.onDiscoveryUpdated(snap));
    }

    public static String getLocalIpv4() {
        String best = null;
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase();
                if (name.startsWith("tun") || name.startsWith("utun") || name.startsWith("ppp")
                        || name.startsWith("dummy")) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                            return ip;
                        }
                        if (!name.startsWith("rmnet") && !name.startsWith("ccmni")) {
                            if (best == null) best = ip;
                        } else if (fallback == null) {
                            fallback = ip;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (best != null) return best;
        if (fallback != null) return fallback;
        return "127.0.0.1";
    }

    public static String buildPcAdbCommand() {
        return "adb shell \"" + buildShellBootstrap() + "\"";
    }

    private static String buildShellBootstrap() {
        // Root activation may already own the legacy jar and log paths.
        return "id -u | grep -qx 2000 || { echo ADB_SHELL_REQUIRED; exit 1; }; "
                + "mkdir -p /data/local/tmp/extbridge-shell && cd /data/local/tmp/extbridge-shell || exit 1; "
                + "(pm path com.extbridge.helper | head -n1 | cut -d: -f2; echo assets/extbridge.jar) "
                + "| xargs unzip -p > extbridge.jar && test -s extbridge.jar || exit 1; "
                + "appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null; "
                + "appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null; "
                + "pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null; "
                + "dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1; "
                + "pkill -u 2000 -f '^app_process.*ExtBridge' 2>/dev/null; sleep 0.3; "
                + "(CLASSPATH=/data/local/tmp/extbridge-shell/extbridge.jar nohup app_process /data/local/tmp "
                + "com.extbridge.daemon.ExtBridge </dev/null >extbridge.log 2>&1 &); "
                + "sleep 1; pgrep -u 2000 -f '^app_process.*ExtBridge' >/dev/null "
                + "&& echo LAUNCH_DONE || { cat extbridge.log; exit 1; }";
    }

    public static boolean isNativeAdbAvailable(Context ctx) {
        File f = getAdbFile(ctx);
        return f.exists() && f.canExecute();
    }

    private static File getAdbFile(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libadb.so");
    }

    public static void pairAndBootstrapAsync(
            final Context ctx,
            final String pairHost,
            final int pairPort,
            final String pairCode,
            final String connectHost,
            final int connectPort,
            final ProgressListener listener) {
        final Context appCtx = ctx.getApplicationContext();
        sExec.execute(() -> {
            try {
                pairAndBootstrapSync(appCtx, pairHost, pairPort, pairCode, connectHost, connectPort, listener);
            } catch (Throwable t) {
                Log.e(TAG, "pairAndBootstrap failed", t);
                postResult(listener, false, "执行异常: " + t.getMessage());
            }
        });
    }

    public static void connectAndBootstrapAsync(
            final Context ctx,
            final String connectHost,
            final int connectPort,
            final ProgressListener listener) {
        final Context appCtx = ctx.getApplicationContext();
        sExec.execute(() -> {
            try {
                connectAndBootstrapSync(appCtx, connectHost, connectPort, listener);
            } catch (Throwable t) {
                Log.e(TAG, "connectAndBootstrap failed", t);
                postResult(listener, false, "执行异常: " + t.getMessage());
            }
        });
    }

    public static void pairFromNotificationAsync(
            final Context ctx,
            final String rawInput,
            final ProgressListener listener) {
        final Context appCtx = ctx.getApplicationContext();
        sExec.execute(() -> {
            DiscoveryState ds = getDiscoveryState();
            String cleaned = rawInput == null ? "" : rawInput.trim().replace('：', ':');
            String code = "";
            String host = !ds.pairingHost.isEmpty() ? ds.pairingHost : getLocalIpv4();
            int pairPort = ds.pairingPort;

            String[] parts = cleaned.split("[\\s:]+");
            if (parts.length == 1) {
                code = parts[0];
            } else if (parts.length == 2) {
                // Either "CODE PORT" or "PORT CODE"
                if (parts[0].length() == 6 && parts[1].length() != 6) {
                    code = parts[0];
                    pairPort = parseIntSafe(parts[1], pairPort);
                } else {
                    pairPort = parseIntSafe(parts[0], pairPort);
                    code = parts[1];
                }
            } else if (parts.length >= 3) {
                host = parts[0];
                pairPort = parseIntSafe(parts[1], pairPort);
                code = parts[2];
            }

            if (code.isEmpty()) {
                postResult(listener, false, "请输入 6 位配对码");
                return;
            }
            if (pairPort <= 0) {
                postResult(listener, false, "未自动发现配对端口，请按「配对码:端口」输入（例如 551857:36565）");
                return;
            }
            pairAndBootstrapSync(appCtx, host, pairPort, code, ds.connectHost, ds.connectPort, listener);
        });
    }

    private static void pairAndBootstrapSync(
            Context appCtx,
            String pairHost,
            int pairPort,
            String pairCode,
            String connectHost,
            int connectPort,
            ProgressListener listener) {
        if (!isNativeAdbAvailable(appCtx)) {
            postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用");
            return;
        }
        String pHost = (pairHost == null || pairHost.trim().isEmpty()) ? getLocalIpv4() : pairHost.trim();
        String code = pairCode == null ? "" : pairCode.trim();
        if (pairPort <= 0 || code.isEmpty()) {
            postResult(listener, false, "请填写完整的配对端口和 6 位配对码");
            return;
        }

        postStep(listener, "正在启动内置 ADB 服务...");
        CmdRes startRes = runAdb(appCtx, 10000, "start-server");
        if (startRes.code != 0) {
            postResult(listener, false, "ADB 服务启动失败:\n" + startRes.output);
            return;
        }

        String pairTarget = pHost + ":" + pairPort;
        postStep(listener, "正在与 " + pairTarget + " 配对...");
        CmdRes pairRes = runAdb(appCtx, 15000, "pair", pairTarget, code);
        String pOut = pairRes.output == null ? "" : pairRes.output.trim();
        boolean pairOk = pairRes.code == 0 && !pOut.toLowerCase().contains("failed") && !pOut.toLowerCase().contains("error");
        if (!pairOk) {
            postResult(listener, false, "配对失败 (" + pairTarget + "):\n" + pOut
                    + "\n提示：请确保没有关闭系统的「与设备配对」弹窗导致配对码刷新。");
            return;
        }

        postStep(listener, "配对成功！正在等待无线调试连接端口...");
        String cHost = (connectHost != null && !connectHost.trim().isEmpty()) ? connectHost.trim() : pHost;
        int cPort = connectPort;
        if (cPort <= 0) {
            long deadline = System.currentTimeMillis() + 4500;
            while (System.currentTimeMillis() < deadline) {
                DiscoveryState ds = getDiscoveryState();
                if (ds.connectPort > 0) {
                    cPort = ds.connectPort;
                    if (!ds.connectHost.isEmpty()) cHost = ds.connectHost;
                    break;
                }
                try { Thread.sleep(250); } catch (InterruptedException ignored) { break; }
            }
        }
        if (cPort <= 0) {
            postResult(listener, true, "已成功完成设备配对！请关闭系统的配对弹窗，输入无线调试主界面显示的连接端口后点击「直接连接并启动」。");
            return;
        }
        connectAndBootstrapSync(appCtx, cHost, cPort, listener);
    }

    private static void connectAndBootstrapSync(
            Context appCtx,
            String connectHost,
            int connectPort,
            ProgressListener listener) {
        if (!isNativeAdbAvailable(appCtx)) {
            postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用");
            return;
        }
        String host = (connectHost == null || connectHost.trim().isEmpty()) ? getLocalIpv4() : connectHost.trim();
        if (connectPort <= 0) {
            postResult(listener, false, "请填写无线调试主页显示的端口号");
            return;
        }
        String target = host + ":" + connectPort;

        postStep(listener, "正在连接无线调试 " + target + "...");
        runAdb(appCtx, 8000, "start-server");
        CmdRes connRes = runAdb(appCtx, 12000, "connect", target);
        String cOut = connRes.output == null ? "" : connRes.output.trim();
        String cLow = cOut.toLowerCase();
        boolean connOk = (cLow.contains("connected to") || cLow.contains("already connected"))
                && !cLow.contains("failed") && !cLow.contains("cannot connect") && !cLow.contains("unable to connect");
        if (!connOk && !"127.0.0.1".equals(host)) {
            String fbTarget = "127.0.0.1:" + connectPort;
            CmdRes fbRes = runAdb(appCtx, 10000, "connect", fbTarget);
            String fbOut = fbRes.output == null ? "" : fbRes.output.trim();
            String fbLow = fbOut.toLowerCase();
            boolean fbOk = (fbLow.contains("connected to") || fbLow.contains("already connected"))
                    && !fbLow.contains("failed") && !fbLow.contains("cannot connect") && !fbLow.contains("unable to connect");
            if (fbOk) {
                target = fbTarget;
                connOk = true;
            }
        }
        if (!connOk) {
            postResult(listener, false, "连接无线调试失败 (" + target + "):\n" + cOut
                    + "\n若为新设备，请先用「配对码」完成配对并确认连接端口已更新。");
            return;
        }

        postStep(listener, "已连接！正在释放 extbridge.jar 并授权启动守护进程...");
        CmdRes shRes = runAdb(appCtx, 18000, "-s", target, "shell", buildShellBootstrap());
        if (shRes.code != 0 || shRes.output == null || !shRes.output.contains("LAUNCH_DONE")) {
            postResult(listener, false, "远程 Shell 启动失败:\n" + shRes.output);
            return;
        }

        postStep(listener, "守护进程已拉起，正在验证 Binder 连接...");
        long deadline = System.currentTimeMillis() + 7000;
        while (System.currentTimeMillis() < deadline) {
            BridgeClient.requestBinder(appCtx);
            BridgeClient.RuntimeInfo runtime = BridgeClient.getRuntimeInfo();
            if (runtime.valid && runtime.uid == 2000 && BridgeClient.getStatus().connected) {
                cancelPairingNotification(appCtx);
                postResult(listener, true, "无线调试激活成功，Midex 服务已就绪！");
                return;
            }
            try { Thread.sleep(350); } catch (InterruptedException ignored) { break; }
        }
        postResult(listener, false, "暂未收到 ADB Shell 服务的握手。日志位于 /data/local/tmp/extbridge-shell/extbridge.log");
    }

    private static final class CmdRes {
        final int code;
        final String output;
        CmdRes(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    private static CmdRes runAdb(Context ctx, long timeoutMs, String... args) {
        Process p = null;
        try {
            File adbBin = getAdbFile(ctx);
            File homeDir = new File(ctx.getFilesDir(), "adb_home");
            File dotAndroid = new File(homeDir, ".android");
            File tmpDir = new File(ctx.getCacheDir(), "adb_tmp");
            if (!dotAndroid.exists()) dotAndroid.mkdirs();
            if (!tmpDir.exists()) tmpDir.mkdirs();

            String[] cmd = new String[args.length + 1];
            cmd[0] = adbBin.getAbsolutePath();
            System.arraycopy(args, 0, cmd, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(homeDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = new HashMap<>(pb.environment());
            env.put("HOME", homeDir.getAbsolutePath());
            env.put("ANDROID_USER_HOME", dotAndroid.getAbsolutePath());
            env.put("ANDROID_SDK_HOME", homeDir.getAbsolutePath());
            env.put("TMPDIR", tmpDir.getAbsolutePath());
            env.put("ANDROID_ADB_SERVER_PORT", ADB_SERVER_PORT);
            env.put("ADB_MDNS", "0");
            env.put("ADB_MDNS_OPENSCREEN", "0");
            pb.environment().clear();
            pb.environment().putAll(env);

            p = pb.start();
            final StringBuilder sb = new StringBuilder();
            final Process proc = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() < 8192) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(line);
                        }
                    }
                } catch (Throwable ignored) {}
            });
            reader.start();

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                reader.join(500);
                return new CmdRes(-1, "命令执行超时 (" + timeoutMs + "ms)\n" + sb);
            }
            reader.join(1000);
            return new CmdRes(p.exitValue(), sb.toString());
        } catch (Throwable t) {
            if (p != null) try { p.destroyForcibly(); } catch (Throwable ignored) {}
            return new CmdRes(-1, "执行出错: " + t.getMessage());
        }
    }

    public static void showPairingNotification(Context ctx, String statusText) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    PAIR_CHANNEL_ID, "无线调试快速配对", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("在系统配对码弹窗上方直接输入6位配对码完成激活");
            nm.createNotificationChannel(ch);
        }

        RemoteInput remoteInput = new RemoteInput.Builder(KEY_PAIR_INPUT)
                .setLabel("输入6位配对码（或 配对码:端口）")
                .build();
        Intent replyIntent = new Intent(ctx, AdbPairingReceiver.class);
        replyIntent.setAction(ACTION_PAIR_REPLY);
        int mutFlag = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent replyPi = PendingIntent.getBroadcast(
                ctx, 2001, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT | mutFlag);
        Notification.Action replyAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_send, "输入配对码", replyPi)
                .addRemoteInput(remoteInput)
                .build();

        Intent openIntent = new Intent(ctx, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                ctx, 2002, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        DiscoveryState ds = getDiscoveryState();
        String body = statusText;
        if (body == null || body.isEmpty()) {
            body = ds.pairingPort > 0
                    ? ("已发现配对端口 " + ds.pairingPort + "，下拉后点「输入配对码」填6位数字即可")
                    : "打开系统「使用配对码配对设备」后，点下方「输入配对码」直接输入（格式: 配对码 或 配对码:端口）";
        }

        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, PAIR_CHANNEL_ID)
                : new Notification.Builder(ctx);
        nb.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Midex 无线调试配对助手")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openPi)
                .addAction(replyAction)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false);

        nm.notify(PAIR_NOTIF_ID, nb.build());
    }

    public static void cancelPairingNotification(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(PAIR_NOTIF_ID);
    }

    private static void postStep(final ProgressListener l, final String msg) {
        if (l != null) sMain.post(() -> l.onStep(msg));
    }

    private static void postResult(final ProgressListener l, final boolean ok, final String msg) {
        if (l != null) sMain.post(() -> l.onResult(ok, msg));
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }
}
