package com.extbridge.daemon;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.extbridge.probe.DriverProbe;

import org.json.JSONObject;

public class ExtBridge implements Runnable {
    public static final int HOST_UNKNOWN = 0;
    public static final int HOST_SHELL = 1;
    public static final int HOST_ROOT = 2;
    public static final int HOST_SYSTEM_SERVER = 3;
    public static final String DAEMON_VERSION = "4.7.13";
    private static final long CONFIG_DISPLAY_CHANGED = -2;

    private static final String CONF_PATH = "/data/local/tmp/extbridge.conf";
    private static final String APP_CONF_PATH = "/data/local/tmp/extbridge_apps.conf";
    private static final String PID_PATH = "/data/local/tmp/extbridge.pid";
    private static volatile int sHostMode = HOST_UNKNOWN;
    private static volatile boolean sStarted = false;
    private static volatile boolean sSystemServer = false;
    private static final int VD_FLAGS_COMPAT = 0x44CB; // includes ROTATES_WITH_CONTENT (0x0080)
    private static final int VD_FLAGS_DEX_FIXED = 0x444B; // strips ROTATES_WITH_CONTENT -> lock landscape desktop!

    private static Context sCtx;
    private static DisplayManager sDm;
    private static InputManager sIm;
    private static Object sIIm;
    private static Object sWm;
    private static Object sAtm;
    private static Handler sHandler;
    private static IBinder sCtlBinder;
    private static volatile IBinder sHelperProcToken;

    private static Method sMInjectInput;
    private static Method sMSetDisplayId;
    private static Method sMAddAssoc;
    private static Method sMRemAssoc;
    private static Method sMReplaceContent;
    private static Method sMMirrorDisplay;
    private static Method sMStartActivity;
    private static Method sMSetUserPreferredMode;

    private static volatile int sExtId = -1;
    private static volatile int sVdId = -1;
    private static volatile String sVdUniqueId;
    private static volatile VirtualDisplay sVd;
    private static volatile Object sBbq;
    private static volatile Surface sSurf;
    private static volatile SurfaceTexture sSt;
    private static volatile Object sRootSc;
    private static volatile Object sBgSc;
    private static volatile Object sScaleSc;
    private static volatile Object sMirrorSc;
    private static volatile String sRenderMode = "NONE";

    private static volatile int sPhysW = 2560;
    private static volatile int sPhysH = 1440;
    private static volatile float sPhysFps = 60.0f;
    private static volatile boolean sPhysicalModeConfirmed;
    private static final ConfigProgress NO_CONFIG = new ConfigProgress(0, null, ExternalModeVerification.Stage.NONE);
    private static volatile ConfigProgress sConfigProgress = NO_CONFIG;
    private static String sLastConfigObservation;
    private static volatile boolean sDrmReadback;
    private static volatile int sVirtW = 2560;
    private static volatile int sVirtH = 1440;
    private static volatile int sVirtDpi = 240;
    private static volatile float sVirtFps = 60.0f;
    private static float sVirtualRefreshRequest = 60f;
    // On xuanyuan, migrating the desktop triggers a roughly 1.3 s Smart Display limit.
    private static final ExternalModeVerification.DesktopRefreshStability sDesktopRefreshStability =
            new ExternalModeVerification.DesktopRefreshStability("xuanyuan".equals(Build.DEVICE) ? 2_000 : 0);
    // Requested physical refresh preference; zero follows the fastest matching output mode.
    private static volatile float sRequestedFps = 0f;
    private static int sModeAppliedExtId = -1;
    private static DisplayManager.DisplayListener sDisplayListener;
    private static final ExtBridge sMaintenance = new ExtBridge();
    private static LocalServerSocket sInstanceSocket;
    private static volatile boolean sMaintenancePending;
    private static volatile long sMaintenanceRuns;

    private static volatile int sInputToExt = 1;
    private static volatile int sLastExtInputCnt = -1;
    private static final ArrayList<String> sBoundDescs = new ArrayList<>();

    private static volatile float sCursorX = 1280f;
    private static volatile float sCursorY = 720f;
    private static volatile int sMouseButtons = 0;
    private static long sMouseDownTime = 0L;
    private static volatile boolean sShuttingDown = false;
    private static volatile boolean sForceRebuild = false;
    private static volatile int sMirrorMode = 0;       // 0=DeX独立桌面, 1=原生镜像
    private static volatile int sFullscreenPolicy = 0; // 1=强制横屏大屏全屏策略, 0=兼容
    private static volatile boolean sSavedSysPrefs = false;
    private static int sOrigForceResizable = 0;
    private static int sOrigNonResizableMw = 0;
    private static final HashSet<String> sCompatOverriddenPkgs = new HashSet<>();
    private static final HashMap<String, Integer> sAppModes = new HashMap<>(); // 0=default, 1=land_full, 2=port_compat, 3=freeform
    private static final java.util.Set<Integer> sMinimizedTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> sWindowChanges = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Rect> sWindowRestoreBounds = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong sWindowSerial = new java.util.concurrent.atomic.AtomicLong();

    private static volatile int sCachedWpId = Integer.MIN_VALUE;
    private static volatile byte[] sCachedWpBytes = null;
    private static volatile long sLastSysCheckMs = 0L;
    private static volatile String sCachedWifiSsid = "";
    private static volatile String sCachedBtName = "";
    private static volatile boolean sCachedBtOn;

    public static void main(String[] args) {
        // 诊断模式：app_process ... com.extbridge.daemon.ExtBridge probe
        // 不需要 ActivityThread/系统服务，root shell 里可直接跑，输出 JSON 后退出。
        if (args != null && args.length > 0 && ("probe".equals(args[0]) || "--probe".equals(args[0]))) {
            String mode = android.os.Process.myUid() == 0 ? "root" : "shell";
            System.out.print(DriverProbe.collectJson(null, mode));
            System.out.flush();
            return;
        }
        sHostMode = android.os.Process.myUid() == 0 ? HOST_ROOT : HOST_SHELL;
        try {
            // A boot module and manual activation must never own two desktops.
            sInstanceSocket = new LocalServerSocket("extbridge.daemon.singleton");
            Looper.prepareMainLooper();
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("systemMain").invoke(null);
            Context sysCtx = (Context) atCls.getMethod("getSystemContext").invoke(at);
            if (!startInternal(sysCtx, false)) {
                System.exit(1);
            }
            Looper.loop();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * LSPosed 模块入口：在 system_server 进程里启动同一套桥接逻辑。
     * 注意：system_server 已有主线程 Looper，不能再次 prepareMainLooper/Looper.loop，
     * 也不能调用 System.exit，只能把任务投递到已有 Handler 上。
     */
    public static synchronized boolean startInSystemServer(Context sysCtx) {
        if (sStarted) return true;
        sHostMode = HOST_SYSTEM_SERVER;
        sSystemServer = true;
        return startInternal(sysCtx, true);
    }

    private static synchronized boolean startInternal(Context sysCtx, boolean fromSystemServer) {
        if (sStarted) return true;
        sStarted = true;
        try {
            if (fromSystemServer) {
                sCtx = sysCtx;
            } else {
                try {
                    sCtx = sysCtx.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
                } catch (Throwable t) {
                    System.err.println("createPackageContext failed: " + t);
                    sCtx = sysCtx;
                }
                sCtx = fixShellContext(sCtx);
            }
            sDm = (DisplayManager) sCtx.getSystemService(Context.DISPLAY_SERVICE);
            sIm = (InputManager) sCtx.getSystemService(Context.INPUT_SERVICE);

            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method getSvc = smCls.getMethod("getService", String.class);

            IBinder wmB = (IBinder) getSvc.invoke(null, "window");
            sWm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, wmB);

            IBinder imB = (IBinder) getSvc.invoke(null, "input");
            sIIm = Class.forName("android.hardware.input.IInputManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, imB);

            IBinder atmB = (IBinder) getSvc.invoke(null, "activity_task");
            sAtm = Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, atmB);

            initReflection();
            loadConf();

            sCtlBinder = new BridgeBinder();
            try {
                IntentFilter flt = new IntentFilter();
                flt.addAction("com.extbridge.daemon.NEED_BINDER");
                flt.addAction("com.extbridge.daemon.SHUTDOWN");
                sCtx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (i == null) return;
                        String act = i.getAction();
                        if ("com.extbridge.daemon.NEED_BINDER".equals(act)) {
                            pushBinderToHelper();
                        } else if ("com.extbridge.daemon.SHUTDOWN".equals(act)) {
                            requestExit();
                        }
                    }
                }, flt, Context.RECEIVER_EXPORTED);
            } catch (Throwable ignored) {}

            if (!fromSystemServer) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    sShuttingDown = true;
                    cleanupDisplay(true);
                }));
            }

            writePidFile();
            markHostProperty();
            System.out.println("EXTBRIDGE_V4_DAEMON_STARTED mode=" + hostModeName()
                    + " uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid()
                    + " cfg=" + sVirtW + "x" + sVirtH + "@" + sVirtDpi + "dpi," + sVirtFps + "fps");
            System.out.flush();

            if (fromSystemServer) {
                HandlerThread worker = new HandlerThread("ExtBridge-display");
                worker.start();
                sHandler = new Handler(worker.getLooper());
            } else {
                sHandler = new Handler(Looper.getMainLooper());
            }
            registerDisplayListener();
            if (sInstanceSocket != null) startBinderRequests();
            if (sHostMode != HOST_ROOT) pushBinderToHelper();
            sHandler.post(sMaintenance);
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            sStarted = false;
            return false;
        }
    }

    private static void requestExit() {
        sShuttingDown = true;
        cleanupDisplay(true);
        stopHelperService();
        if (sSystemServer) {
            // 绝不能退出 system_server；停止渲染即可，彻底停用需在 LSPosed 里关闭模块或重启。
            System.out.println("EXTBRIDGE_SYSTEM_SERVER_KEEP_ALIVE");
            System.out.flush();
            return;
        }
        new Thread(() -> {
            SystemClock.sleep(80);
            System.exit(0);
        }).start();
    }

    private static void startBinderRequests() {
        Thread requests = new Thread(() -> {
            while (!sShuttingDown) {
                try (LocalSocket socket = sInstanceSocket.accept()) {
                    int uid = socket.getPeerCredentials().getUid();
                    int helperUid = sCtx.getPackageManager().getPackageUid("com.extbridge.helper", 0);
                    if (uid == 0 || uid == 1000 || uid == 2000 || uid == helperUid)
                        sHandler.post(ExtBridge::pushBinderToHelper);
                } catch (Throwable failure) {
                    if (!sShuttingDown) System.err.println("Binder request listener stopped: " + failure);
                    return;
                }
            }
        }, "ExtBridge-binder-requests");
        requests.setDaemon(true);
        requests.start();
    }

    private static void writePidFile() {
        try (FileWriter fw = new FileWriter(PID_PATH, false)) {
            fw.write(String.valueOf(android.os.Process.myPid()));
        } catch (Throwable ignored) {}
    }

    private static void markHostProperty() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, "extbridge.host", hostModeName());
            if (sHostMode == HOST_ROOT) set.invoke(null, "extbridge.root", "1");
            if (sHostMode == HOST_SYSTEM_SERVER) set.invoke(null, "extbridge.lsposed", "1");
        } catch (Throwable ignored) {
            // shell 模式通常没有 setprop 权限；root / system_server 模式可用。
        }
    }

    private static String readSelinux() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/attr/current"))) {
            String line = br.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String hostModeName() {
        switch (sHostMode) {
            case HOST_ROOT: return "root";
            case HOST_SYSTEM_SERVER: return "system_server";
            case HOST_SHELL: return "shell";
            default: return "unknown";
        }
    }

    private static void initReflection() {
        try {
            sMInjectInput = sIIm.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Throwable t) {
            for (Method m : sIIm.getClass().getMethods()) {
                if (m.getName().equals("injectInputEvent")) { sMInjectInput = m; break; }
            }
        }
        try {
            sMSetDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (Throwable ignored) {}
        try {
            sMAddAssoc = InputManager.class.getMethod("addUniqueIdAssociationByDescriptor", String.class, String.class);
            sMRemAssoc = InputManager.class.getMethod("removeUniqueIdAssociationByDescriptor", String.class);
        } catch (Throwable ignored) {}
        try {
            for (Method m : sWm.getClass().getMethods()) {
                if (m.getName().equals("replaceContentOnDisplay")) sMReplaceContent = m;
                else if (m.getName().equals("mirrorDisplay")) sMMirrorDisplay = m;
            }
        } catch (Throwable ignored) {}
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("startActivity") && m.getParameterCount() == 11) {
                    sMStartActivity = m;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        try {
            sMSetUserPreferredMode = DisplayManager.class.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
        } catch (Throwable ignored) {
            try { sMSetUserPreferredMode = DisplayManager.class.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class); }
            catch (Throwable unsupported) {}
        }
    }

    private static synchronized void registerDisplayListener() {
        if (sDm == null || sHandler == null || sDisplayListener != null) return;
        sDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                if (displayId != 0 && displayId != sVdId) scheduleDisplayTick();
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                synchronized (ExtBridge.class) {
                    if (displayId == sModeAppliedExtId) resetPhysicalMode();
                }
                if (displayId != 0) scheduleDisplayTick();
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId != 0 && displayId != sVdId) scheduleDisplayTick();
            }
        };
        try {
            sDm.registerDisplayListener(sDisplayListener, sHandler);
        } catch (Throwable t) {
            sDisplayListener = null;
        }
    }

    private static synchronized void scheduleDisplayTick() {
        if (sShuttingDown || sHandler == null) return;
        sHandler.removeCallbacks(sMaintenance);
        sMaintenancePending = sHandler.postDelayed(sMaintenance, 120);
    }

    private static void loadConf() {
        try {
            File f = new File(CONF_PATH);
            if (!f.exists()) return;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line = br.readLine();
                if (line == null) return;
                String[] parts = line.trim().split(",");
                if (parts.length >= 4) {
                    int w = Integer.parseInt(parts[0]);
                    int h = Integer.parseInt(parts[1]);
                    int d = Integer.parseInt(parts[2]);
                    float fps = Float.parseFloat(parts[3]);
                    if (w >= 640 && w <= 7680 && h >= 480 && h <= 4320 && d >= 100 && d <= 640) {
                        sVirtW = w;
                        sVirtH = h;
                        sVirtDpi = d;
                        sRequestedFps = Float.isFinite(fps) && fps >= 0f && fps <= 360f ? fps : 0f;
                        sCursorX = sVirtW * 0.5f;
                        sCursorY = sVirtH * 0.5f;
                    }
                    if (parts.length >= 5) {
                        sFullscreenPolicy = Integer.parseInt(parts[4].trim()) != 0 ? 1 : 0;
                    }
                }
            }
        } catch (Throwable ignored) {}
        loadAppModes();
    }

    private static final String HEAL_MARKER_PATH = "/data/local/tmp/extbridge_healed_v2";

    private static void loadAppModes() {
        File f = new File(APP_CONF_PATH);
        boolean firstConf = !f.exists();
        synchronized (sAppModes) {
            sAppModes.clear();
            if (firstConf) {
                saveAppModesLocked();
            } else {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String t = line.trim();
                        int eq = t.indexOf('=');
                        if (eq <= 0) continue;
                        String pkg = t.substring(0, eq).trim();
                        int m = Integer.parseInt(t.substring(eq + 1).trim());
                        if (!pkg.isEmpty() && m >= 1 && m <= 3) {
                            sAppModes.put(pkg, m);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (firstConf || !new File(HEAL_MARKER_PATH).exists()) {
            setGlobalIntSafe("force_resizable_activities", 0);
            setGlobalIntSafe("enable_non_resizable_multi_window", 0);
            healStrayCompatOverrides(false);
            try (FileWriter fw = new FileWriter(HEAL_MARKER_PATH, false)) {
                fw.write("1\n");
            } catch (Throwable ignored) {}
        }
    }

    private static void healStrayCompatOverrides(boolean resetConfiguredToo) {
        try {
            HashSet<String> toReset = new HashSet<>();
            toReset.add("com.tencent.mm");
            toReset.add("com.quark.browser");
            toReset.add("com.xunmeng.pinduoduo");
            toReset.add("com.ss.android.ugc.aweme");
            toReset.add("com.baidu.netdisk");
            String dump = runCmdOut("sh", "-c",
                    "dumpsys platform_compat 2>/dev/null | grep -E 'ChangeId\\((254631730|310816437|236283604|265464455|263259275);'");
            if (dump != null) {
                for (String line : dump.split("\n")) {
                    int idx = line.indexOf("packageOverrides={");
                    if (idx < 0) continue;
                    int start = idx + "packageOverrides={".length();
                    int end = line.indexOf('}', start);
                    if (end <= start) continue;
                    String inner = line.substring(start, end);
                    for (String kv : inner.split(",")) {
                        int eq = kv.indexOf('=');
                        if (eq <= 0) continue;
                        String pkg = kv.substring(0, eq).trim();
                        if (!pkg.isEmpty()) toReset.add(pkg);
                    }
                }
            }
            if (!resetConfiguredToo) {
                synchronized (sAppModes) {
                    for (Map.Entry<String, Integer> e : sAppModes.entrySet()) {
                        int m = e.getValue() == null ? 0 : e.getValue();
                        if (m == 1 || m == 2) toReset.remove(e.getKey());
                    }
                }
            }
            if (!toReset.isEmpty()) {
                StringBuilder cmd = new StringBuilder();
                for (String pkg : toReset) {
                    String qp = pkg.replace("'", "");
                    cmd.append("am compat reset-all --no-kill '").append(qp)
                       .append("' >/dev/null 2>&1 || am compat reset-all '").append(qp).append("' >/dev/null 2>&1; ");
                }
                runCmdOut("sh", "-c", cmd.toString());
            }
        } catch (Throwable ignored) {}
    }

    private static void saveAppModesLocked() {
        try (FileWriter fw = new FileWriter(APP_CONF_PATH, false)) {
            for (Map.Entry<String, Integer> e : sAppModes.entrySet()) {
                int m = e.getValue() == null ? 0 : e.getValue();
                if (m >= 1 && m <= 3) {
                    fw.write(e.getKey() + "=" + m + "\n");
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int getAppMode(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        synchronized (sAppModes) {
            Integer v = sAppModes.get(pkg);
            return v != null ? v : 0;
        }
    }

    private static void saveConf() {
        try (FileWriter fw = new FileWriter(CONF_PATH, false)) {
            fw.write(sVirtW + "," + sVirtH + "," + sVirtDpi + "," + sRequestedFps + "," + sFullscreenPolicy + "\n");
        } catch (Throwable ignored) {}
    }

    @Override
    public void run() {
        if (sShuttingDown) return;
        sMaintenancePending = false;
        sMaintenanceRuns++;
        try {
            tick();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        if (!sShuttingDown && sHandler != null) {
            sHandler.removeCallbacks(this);
            // The system display callback wakes us on hotplug. No idle polling, alarms or locks.
            long delay = sExtId >= 0 ? 1500 : (sDisplayListener == null ? 30000 : 0);
            sMaintenancePending = delay > 0 && sHandler.postDelayed(this, delay);
        }
    }

    private static Display findExternalDisplay() {
        Display[] displays = null;
        try {
            displays = sDm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        } catch (Throwable ignored) {}
        if (displays == null || displays.length == 0) {
            displays = sDm.getDisplays();
        }
        if (displays == null) return null;
        Display fallback = null;
        for (Display d : displays) {
            if (d == null || !d.isValid()) continue;
            int id = d.getDisplayId();
            if (id == 0 || id == sVdId) continue;
            int type = getDisplayType(d);
            // Display.TYPE_EXTERNAL == 2（DP/HDMI/部分无线投屏）
            if (type == 2) return d;
            // 部分 HyperOS / Android 17 版本 getType() 被裁剪，用 Presentation 标志兜底；
            // 排除我们自己创建的虚拟桌面，避免递归接管。
            if (type != 3 && type != 4 && (d.getFlags() & Display.FLAG_PRESENTATION) != 0) {
                String name = d.getName();
                if (name != null && !name.contains("ExtBridge") && !name.contains("RedmiKPad")) {
                    if (fallback == null) fallback = d;
                }
            }
        }
        return fallback;
    }

    private static int getDisplayType(Display d) {
        try {
            return (Integer) Display.class.getMethod("getType").invoke(d);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static synchronized void tick() {
        Display ext = findExternalDisplay();
        if (ext == null) {
            if (sModeAppliedExtId >= 0) resetPhysicalMode();
            if (sExtId >= 0 || sVd != null) {
                System.out.println("EXTBRIDGE_DETACHED");
                cleanupDisplay(true);
                stopHelperService();
            }
            return;
        }
        int extId = ext.getDisplayId();
        if (sModeAppliedExtId != extId) {
            preparePhysicalMode(ext);
            Display.Mode[] modes = ext.getSupportedModes();
            Display.Mode current = ext.getMode();
            Display.Mode target = choosePhysicalMode(modes, current, sVirtW, sVirtH, sRequestedFps, -1);
            // A temporary monitor can use its own supported rate without replacing the saved preference.
            if (target == null && sRequestedFps > 0f) {
                target = choosePhysicalMode(modes, current, sVirtW, sVirtH, 0f, -1);
            }
            requestPhysicalMode(ext, target, 0);
        }
        ExternalModeVerification.Readback readback = readExternalMode(ext, sDrmReadback);
        Display.Mode curMode = matchActivePhysicalMode(ext.getSupportedModes(), readback, sDrmReadback);
        sPhysicalModeConfirmed = curMode != null;
        if (curMode != null) {
            boolean sizeChanged = sPhysW != curMode.getPhysicalWidth() || sPhysH != curMode.getPhysicalHeight();
            sPhysW = curMode.getPhysicalWidth();
            sPhysH = curMode.getPhysicalHeight();
            sPhysFps = curMode.getRefreshRate();
            if (sizeChanged && sVd != null && sExtId == extId && sMirrorMode == 0) applySurfaceTransform();
        }
        // A surface hint/resize cannot change a VirtualDisplay's creation-time mode.
        if (sVd != null && sExtId == extId && sMirrorMode == 0) {
            float confirmedHz = sPhysicalModeConfirmed ? sPhysFps : 0f;
            if (sDesktopRefreshStability.shouldReplace(sVirtualRefreshRequest, confirmedHz, SystemClock.uptimeMillis())
                    && !replaceVirtualDisplayForRefresh(confirmedHz)) {
                updateConfigProgress(ext, readback);
                return;
            }
        } else {
            sDesktopRefreshStability.reset();
            if (sVd == null || sExtId != extId) sVirtFps = sPhysFps;
        }

        if (sMirrorMode == 1) {
            if (sVd != null || sRootSc != null) {
                cleanupDisplay(true);
            }
            sExtId = extId;
            sVdId = -1;
            sRenderMode = "NATIVE_MIRROR";
            stopHelperService();
            updateConfigProgress(ext, readback);
            return;
        }

        if (sForceRebuild && sVd != null) {
            sForceRebuild = false;
            cleanupDisplay(false);
            sVirtFps = sPhysFps;
        }

        if (extId != sExtId || sVd == null) {
            cleanupDisplay(false);
            attachDisplay(extId);
        } else {
            reassert();
            checkInput();
            repairDesktopWindowBounds();
            if (sFullscreenPolicy == 1) {
                enforceRunningExtTasksFullscreen();
            }
            if (sHelperProcToken == null || !sHelperProcToken.pingBinder()) {
                pushBinderToHelper();
            }
        }
        updateConfigProgress(ext, readback);
    }

    private static void resetPhysicalMode() {
        ConfigProgress progress = sConfigProgress;
        if (progress.requestId > 0 && sModeAppliedExtId > 0) {
            System.out.println("EXTBRIDGE_MODE_TRACE elapsedMs=" + SystemClock.elapsedRealtime()
                    + " request=" + progress.requestId
                    + " display=" + sModeAppliedExtId + " stage=DETACHED");
        }
        sModeAppliedExtId = -1;
        sConfigProgress = NO_CONFIG;
        sLastConfigObservation = null;
        sPhysicalModeConfirmed = false;
        sDrmReadback = false;
    }

    private static void preparePhysicalMode(Display ext) {
        resetPhysicalMode();
        Display.Mode initial = ext.getMode();
        sModeAppliedExtId = ext.getDisplayId();
        sDrmReadback = usesDrmReadback(ext);
        sPhysW = initial.getPhysicalWidth();
        sPhysH = initial.getPhysicalHeight();
        // Bootstrap the virtual desktop until the first verified physical sample.
        sPhysFps = sDrmReadback ? 60f : initial.getRefreshRate();
    }

    private static final class ConfigProgress {
        final long requestId;
        final Display.Mode target;
        final ExternalModeVerification.Stage stage;

        ConfigProgress(long requestId, Display.Mode target, ExternalModeVerification.Stage stage) {
            this.requestId = requestId;
            this.target = target;
            this.stage = stage;
        }
    }

    private static void updateConfigProgress(Display ext, ExternalModeVerification.Readback readback) {
        ConfigProgress progress = sConfigProgress;
        Display.Mode target = progress.target;
        if (progress.requestId == 0 || sModeAppliedExtId != ext.getDisplayId() || target == null) return;
        ExternalModeVerification.Stage stage = progress.stage;
        Display desktop = sVd == null ? null : sVd.getDisplay();
        if (stage != ExternalModeVerification.Stage.SUBMIT_FAILED) {
            stage = readback.stageFor(target.getPhysicalWidth(), target.getPhysicalHeight(),
                    target.getRefreshRate(), sDrmReadback);
            if (stage == ExternalModeVerification.Stage.CONFIRMED && !sPhysicalModeConfirmed) {
                stage = ExternalModeVerification.Stage.WAITING_FOR_SF;
            }
        }
        if (stage == ExternalModeVerification.Stage.CONFIRMED && sMirrorMode == 0) {
            if (sExtId != ext.getDisplayId() || !desktopMatchesSize(desktop)
                    || !ExternalModeVerification.sameRate(desktop.getMode().getRefreshRate(), sPhysFps)) {
                stage = ExternalModeVerification.Stage.WAITING_FOR_DESKTOP;
            }
        }
        if (stage != progress.stage) {
            sConfigProgress = new ConfigProgress(progress.requestId, target, stage);
        }
        Display.Mode dms = ext.getMode();
        Display.Mode virtualMode = desktop == null ? null : desktop.getMode();
        String evidence = "request=" + progress.requestId + " display=" + ext.getDisplayId()
                + " stage=" + stage + " dms=" + describeMode(dms)
                + " sf=" + readback.sfModeId + ":" + readback.width + "x" + readback.height + "@" + readback.activeHz
                + " renderHz=" + readback.renderHz + " drm=" + readback.scanout
                + " vd=" + sVdId + ":" + describeMode(virtualMode)
                + (sDrmReadback ? " sfPolicy=" + readSfModePolicy(ext) : "");
        if (!evidence.equals(sLastConfigObservation)) {
            sLastConfigObservation = evidence;
            System.out.println("EXTBRIDGE_MODE_TRACE elapsedMs=" + SystemClock.elapsedRealtime() + " " + evidence);
        }
    }

    private static String describeMode(Display.Mode mode) {
        if (mode == null) return "unavailable";
        return mode.getModeId() + ":" + mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight()
                + "@" + mode.getRefreshRate();
    }

    private static boolean desktopMatchesSize(Display desktop) {
        if (desktop == null || !desktop.isValid()) return false;
        Display.Mode mode = desktop.getMode();
        if (mode.getPhysicalWidth() != sVirtW || mode.getPhysicalHeight() != sVirtH) return false;
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        desktop.getRealMetrics(metrics);
        return metrics.densityDpi == sVirtDpi;
    }

    private static void attachDisplay(int extId) {
        try {
            System.out.println("EXTBRIDGE_ATTACHING extId=" + extId + " phys=" + sPhysW + "x" + sPhysH + "@" + sPhysFps + " virt=" + sVirtW + "x" + sVirtH + "@" + sVirtDpi);
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> bCls = Class.forName("android.view.SurfaceControl$Builder");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");

            Object bRoot = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bRoot, "ExtRoot");
            bCls.getMethod("setContainerLayer").invoke(bRoot);
            bCls.getMethod("setHidden", boolean.class).invoke(bRoot, false);
            sRootSc = bCls.getMethod("build").invoke(bRoot);

            Object bBg = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bBg, "ExtBg");
            bCls.getMethod("setColorLayer").invoke(bBg);
            bCls.getMethod("setParent", scCls).invoke(bBg, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bBg, false);
            sBgSc = bCls.getMethod("build").invoke(bBg);

            Object bScale = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bScale, "ExtScale");
            bCls.getMethod("setContainerLayer").invoke(bScale);
            bCls.getMethod("setParent", scCls).invoke(bScale, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bScale, false);
            sScaleSc = bCls.getMethod("build").invoke(bScale);

            boolean bbqOk = false;
            try {
                Object bMir = bCls.getConstructor().newInstance();
                bCls.getMethod("setName", String.class).invoke(bMir, "ExtDisplay");
                bCls.getMethod("setBLASTLayer").invoke(bMir);
                bCls.getMethod("setParent", scCls).invoke(bMir, sScaleSc);
                bCls.getMethod("setHidden", boolean.class).invoke(bMir, false);
                sMirrorSc = bCls.getMethod("build").invoke(bMir);

                Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                Constructor<?> bbqCtor = bbqCls.getConstructor(String.class, boolean.class);
                sBbq = bbqCtor.newInstance("ExtBBQ", true);
                bbqCls.getMethod("update", scCls, int.class, int.class, int.class).invoke(sBbq, sMirrorSc, sVirtW, sVirtH, 1);
                sSurf = (Surface) bbqCls.getMethod("createSurface").invoke(sBbq);
                bbqOk = (sSurf != null);
                sRenderMode = "BBQ";
            } catch (Throwable t) {
                t.printStackTrace();
                sRenderMode = "MIRROR";
            }

            if (!bbqOk) {
                sSt = new SurfaceTexture(0);
                sSt.setDefaultBufferSize(sVirtW, sVirtH);
                sSurf = new Surface(sSt);
            }

            if (sSurf != null && Build.VERSION.SDK_INT >= 30) {
                try {
                    sSurf.setFrameRate(sVirtFps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                } catch (Throwable ignored) {}
            }

            // Build VirtualDisplayConfig
            Class<?> vdcBCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object vdcB = vdcBCls.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(extBridgeDisplayName(), sVirtW, sVirtH, sVirtDpi);
            int vdFlags = (sFullscreenPolicy == 1) ? VD_FLAGS_DEX_FIXED : VD_FLAGS_COMPAT;
            vdcBCls.getMethod("setFlags", int.class).invoke(vdcB, vdFlags);
            try { vdcBCls.getMethod("setHomeSupported", boolean.class).invoke(vdcB, false); } catch (Throwable ignored) {}
            try { vdcBCls.getMethod("setRequestedRefreshRate", float.class).invoke(vdcB, sVirtFps); } catch (Throwable ignored) {}
            vdcBCls.getMethod("setSurface", Surface.class).invoke(vdcB, sSurf);
            Object vdc = vdcBCls.getMethod("build").invoke(vdcB);

            sVd = (VirtualDisplay) DisplayManager.class
                    .getMethod("createVirtualDisplay", Class.forName("android.hardware.display.VirtualDisplayConfig"))
                    .invoke(sDm, vdc);

            if (sVd == null || sVd.getDisplay() == null) {
                System.out.println("EXTBRIDGE_CREATE_VD_FAILED");
                cleanupDisplay(false);
                return;
            }
            Display vdDisp = sVd.getDisplay();
            sVdId = vdDisp.getDisplayId();
            sVirtualRefreshRequest = sVirtFps;
            sVirtFps = vdDisp.getMode().getRefreshRate();
            try {
                sVdUniqueId = (String) Display.class.getMethod("getUniqueId").invoke(vdDisp);
            } catch (Throwable t) {
                sVdUniqueId = null;
            }

            applyDisplayFullscreenPolicy(sVdId, sFullscreenPolicy == 1);

            if (!bbqOk && sMMirrorDisplay != null) {
                sMirrorSc = sMMirrorDisplay.invoke(sWm, sVdId);
                Object tx0 = txCls.getConstructor().newInstance();
                txCls.getMethod("reparent", scCls, scCls).invoke(tx0, sMirrorSc, sScaleSc);
                txCls.getMethod("apply").invoke(tx0);
            }

            sExtId = extId;
            applySurfaceTransform();
            reassert();
            sCursorX = sVirtW * 0.5f;
            sCursorY = sVirtH * 0.5f;
            syncInput();
            launchLauncher();
            pushBinderToHelper();
            notifyHelperSync();

            System.out.println("EXTBRIDGE_ATTACHED_OK vdId=" + sVdId + " render=" + sRenderMode);
            System.out.flush();
        } catch (Throwable t) {
            t.printStackTrace();
            cleanupDisplay(false);
        }
    }

    private static String extBridgeDisplayName() {
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        StringBuilder sb = new StringBuilder("ExtBridge-");
        for (int i = 0; i < model.length() && sb.length() < 40; i++) {
            char c = model.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void applySurfaceTransform() {
        try {
            if (sMirrorSc == null || sBgSc == null || sRootSc == null) return;
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getConstructor().newInstance();

            txCls.getMethod("setLayer", scCls, int.class).invoke(tx, sBgSc, 0);
            txCls.getMethod("setColor", scCls, float[].class).invoke(tx, sBgSc, new float[]{0f, 0f, 0f});
            txCls.getMethod("setAlpha", scCls, float.class).invoke(tx, sBgSc, 1.0f);
            txCls.getMethod("setWindowCrop", scCls, int.class, int.class).invoke(tx, sBgSc, sPhysW, sPhysH);
            txCls.getMethod("show", scCls).invoke(tx, sBgSc);

            float sx = sPhysW / (float) sVirtW;
            float sy = sPhysH / (float) sVirtH;
            float scale = Math.min(sx, sy);
            float posX = (sPhysW - sVirtW * scale) * 0.5f;
            float posY = (sPhysH - sVirtH * scale) * 0.5f;

            txCls.getMethod("setLayer", scCls, int.class).invoke(tx, sScaleSc, 1);
            txCls.getMethod("setMatrix", scCls, float.class, float.class, float.class, float.class)
                    .invoke(tx, sScaleSc, scale, 0.0f, 0.0f, scale);
            txCls.getMethod("setPosition", scCls, float.class, float.class).invoke(tx, sScaleSc, posX, posY);
            txCls.getMethod("show", scCls).invoke(tx, sScaleSc);

            txCls.getMethod("setLayer", scCls, int.class).invoke(tx, sMirrorSc, 0);
            txCls.getMethod("setAlpha", scCls, float.class).invoke(tx, sMirrorSc, 1.0f);
            txCls.getMethod("setWindowCrop", scCls, int.class, int.class).invoke(tx, sMirrorSc, sVirtW, sVirtH);
            txCls.getMethod("setPosition", scCls, float.class, float.class).invoke(tx, sMirrorSc, 0f, 0f);
            txCls.getMethod("show", scCls).invoke(tx, sMirrorSc);

            txCls.getMethod("setWindowCrop", scCls, int.class, int.class).invoke(tx, sRootSc, sPhysW, sPhysH);
            txCls.getMethod("apply").invoke(tx);
            System.out.println("EXTBRIDGE_TRANSFORM virt=" + sVirtW + "x" + sVirtH + " phys=" + sPhysW + "x" + sPhysH + " scale=" + scale + " pos=" + posX + "," + posY);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void reassert() {
        try {
            if (sExtId <= 0 || sRootSc == null || sWm == null || sMReplaceContent == null) return;
            sMReplaceContent.invoke(sWm, sExtId, sRootSc);
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getConstructor().newInstance();
            txCls.getMethod("setLayer", scCls, int.class).invoke(tx, sRootSc, Integer.MAX_VALUE);
            txCls.getMethod("setPosition", scCls, float.class, float.class).invoke(tx, sRootSc, 0f, 0f);
            txCls.getMethod("show", scCls).invoke(tx, sRootSc);
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable ignored) {}
    }

    private static synchronized void cleanupDisplay(boolean unbindWm) {
        sDesktopRefreshStability.reset();
        for (int taskId : new ArrayList<>(sMinimizedTasks)) setDesktopTaskHidden(taskId, false);
        sMinimizedTasks.clear();
        sWindowChanges.clear();
        sWindowRestoreBounds.clear();
        unbindInput();
        if (unbindWm || sShuttingDown) {
            restoreGlobalFullscreenPrefs();
            resetAppFullscreenCompatAll();
        }
        try {
            if (unbindWm && sExtId > 0 && sWm != null && sMReplaceContent != null) {
                try { sMReplaceContent.invoke(sWm, sExtId, null); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            if (sVd != null) { sVd.release(); sVd = null; }
        } catch (Throwable ignored) {}
        try {
            if (sBbq != null) {
                sBbq.getClass().getMethod("destroy").invoke(sBbq);
                sBbq = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (sSurf != null) { sSurf.release(); sSurf = null; }
        } catch (Throwable ignored) {}
        try {
            if (sSt != null) { sSt.release(); sSt = null; }
        } catch (Throwable ignored) {}
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getConstructor().newInstance();
            Method rem = txCls.getMethod("remove", scCls);
            if (sMirrorSc != null) { rem.invoke(tx, sMirrorSc); sMirrorSc = null; }
            if (sScaleSc != null) { rem.invoke(tx, sScaleSc); sScaleSc = null; }
            if (sBgSc != null) { rem.invoke(tx, sBgSc); sBgSc = null; }
            if (sRootSc != null) { rem.invoke(tx, sRootSc); sRootSc = null; }
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable ignored) {}
        sExtId = -1;
        sVdId = -1;
        sVdUniqueId = null;
        sRenderMode = "NONE";
    }

    private static Display.Mode choosePhysicalMode(Display.Mode[] modes, Display.Mode current,
            int width, int height, float fps, int modeId) {
        if (modeId > 0) {
            for (Display.Mode mode : modes) if (mode.getModeId() == modeId) return mode;
            return null;
        }
        boolean matchingSize = false;
        for (Display.Mode mode : modes)
            if (mode.getPhysicalWidth() == width && mode.getPhysicalHeight() == height) matchingSize = true;
        // Custom virtual sizes retain the physical resolution, including its refresh choices.
        if (!matchingSize) { width = current.getPhysicalWidth(); height = current.getPhysicalHeight(); }
        Display.Mode best = null;
        for (Display.Mode mode : modes) {
            if (mode.getPhysicalWidth() != width || mode.getPhysicalHeight() != height) continue;
            if (fps > 0f && Math.abs(mode.getRefreshRate() - fps) > 0.02f) continue;
            if (best == null || mode.getRefreshRate() > best.getRefreshRate()) best = mode;
        }
        // A virtual resolution must never silently lower the physical output resolution.
        return best;
    }

    private static long physicalDisplayId(Display display) throws ReflectiveOperationException {
        Object address = Display.class.getMethod("getAddress").invoke(display);
        return address == null ? -1 : (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
    }

    private static String readSfModePolicy(Display display) {
        try {
            long physicalId = physicalDisplayId(display);
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Object token = sc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physicalId);
            Object specs = sc.getMethod("getDesiredDisplayModeSpecs", IBinder.class).invoke(null, token);
            if (specs != null) {
                Class<?> cls = specs.getClass();
                return cls.getField("defaultMode").getInt(specs) + ":" + cls.getField("primaryRanges").get(specs);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return "unavailable";
    }

    private static ExternalModeVerification.Readback readExternalMode(Display display, boolean verifyDrm) {
        int active = -1;
        int width = 0;
        int height = 0;
        float fps = 0f;
        float renderHz = 0f;
        try {
            long physicalId = physicalDisplayId(display);
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Object info;
            try {
                info = sc.getMethod("getDynamicDisplayInfo", long.class).invoke(null, physicalId);
            } catch (NoSuchMethodException olderApi) {
                Object token = sc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physicalId);
                info = sc.getMethod("getDynamicDisplayInfo", IBinder.class).invoke(null, token);
            }
            active = info.getClass().getField("activeDisplayModeId").getInt(info);
            Object[] modes = (Object[]) info.getClass().getField("supportedDisplayModes").get(info);
            for (Object mode : modes) {
                Class<?> cls = mode.getClass();
                if (cls.getField("id").getInt(mode) != active) continue;
                width = cls.getField("width").getInt(mode);
                height = cls.getField("height").getInt(mode);
                try { fps = cls.getField("vsyncRate").getFloat(mode); }
                catch (NoSuchFieldException olderApi) { fps = cls.getField("refreshRate").getFloat(mode); }
                break;
            }
            renderHz = info.getClass().getField("renderFrameRate").getFloat(info);
        } catch (Throwable ignored) {}
        return new ExternalModeVerification.Readback(active, width, height, fps, renderHz,
                verifyDrm ? readDrmMode() : null);
    }

    private static Display.Mode matchActivePhysicalMode(Display.Mode[] candidates,
            ExternalModeVerification.Readback readback, boolean verifyDrm) {
        // SF and DMS assign separate mode IDs. Resolve the timing against the current DMS list.
        for (Display.Mode candidate : candidates) {
            if (readback.stageFor(candidate.getPhysicalWidth(), candidate.getPhysicalHeight(),
                    candidate.getRefreshRate(), verifyDrm) == ExternalModeVerification.Stage.CONFIRMED) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean usesDrmReadback(Display display) {
        return display != null && sHostMode == HOST_ROOT && "xuanyuan".equals(Build.DEVICE)
                && getDisplayType(display) == 2;
    }

    private static String readDrmMode() {
        java.lang.Process probe = null;
        try {
            String nativeLibraryDir = sCtx.getPackageManager()
                    .getApplicationInfo("com.extbridge.helper", 0).nativeLibraryDir;
            File executable = new File(nativeLibraryDir, "libextbridge_drm.so");
            if (!executable.canExecute()) return null;
            probe = new ProcessBuilder(executable.getAbsolutePath(), "--external")
                    .redirectErrorStream(true).start();
            if (!probe.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS) || probe.exitValue() != 0) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(probe.getInputStream()))) {
                return reader.readLine();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (probe != null && probe.isAlive()) probe.destroyForcibly();
        }
    }

    private static void requestPhysicalMode(Display ext, Display.Mode mode, long requestId) {
        ExternalModeVerification.Stage stage = ExternalModeVerification.Stage.SUBMIT_FAILED;
        sPhysicalModeConfirmed = false;
        if (mode != null && sMSetUserPreferredMode != null && ext.isValid()) {
            try {
                if (sMSetUserPreferredMode.getParameterCount() == 3)
                    sMSetUserPreferredMode.invoke(sDm, ext.getDisplayId(), mode, false);
                else sMSetUserPreferredMode.invoke(sDm, ext.getDisplayId(), mode);
                stage = ExternalModeVerification.Stage.SUBMITTED;
                System.out.println("EXTBRIDGE_PHYS_MODE_REQUEST=" + mode.getModeId() + "@" + mode.getRefreshRate());
            } catch (Throwable t) { t.printStackTrace(); }
        }
        sConfigProgress = new ConfigProgress(requestId, mode, stage);
        sLastConfigObservation = null;
        if (requestId > 0 && mode != null) {
            System.out.println("EXTBRIDGE_MODE_TRACE elapsedMs=" + SystemClock.elapsedRealtime()
                    + " request=" + requestId + " display=" + ext.getDisplayId() + " stage=" + stage
                    + " target=" + describeMode(mode));
        }
    }

    private static synchronized String forceHighestExternalRefresh(int expectedExtId) {
        Display ext = findExternalDisplay();
        if (ext == null || ext.getDisplayId() != expectedExtId) return "DISPLAY_CHANGED";
        if (!usesDrmReadback(ext) || android.os.Process.myUid() != 0) return "ROOT_REQUIRED";
        if (!OPlusAutoHr.isAvailable()) return "MODULE_REQUIRED";

        try {
            long physicalId = physicalDisplayId(ext);
            JSONObject status = OPlusAutoHr.call("status");
            if (physicalId < 0 || !status.optBoolean("connected")
                    || !Long.toString(physicalId).equals(status.optString("physicalId"))) {
                return "DISPLAY_CHANGED";
            }
            Display.Mode[] modes = ext.getSupportedModes();
            Display.Mode current = matchActivePhysicalMode(modes, readExternalMode(ext, true), true);
            if (current == null) return "CURRENT_MODE_UNVERIFIED";

            int width = current.getPhysicalWidth();
            int height = current.getPhysicalHeight();
            Display.Mode target = choosePhysicalMode(modes, current, width, height, 0f, -1);
            float refresh = target.getRefreshRate();
            if (ExternalModeVerification.sameRate(current.getRefreshRate(), refresh)) return "ALREADY_HIGHEST";

            JSONObject engine = status.optJSONObject("engine");
            if (engine == null || !engine.optBoolean("ready") || !engine.optBoolean("composerReady")
                    || !engine.optBoolean("dmcReady")) return "ENGINE_UNAVAILABLE";
            if (!sameExternalConnection(expectedExtId, physicalId)) return "DISPLAY_CHANGED";

            JSONObject applied = OPlusAutoHr.call("apply", Integer.toString(width), Integer.toString(height),
                    Float.toString(refresh));
            String token = OPlusAutoHr.pendingPreviewToken(applied);
            if (token == null) return "SWITCH_UNCONFIRMED";

            for (int attempt = 0; attempt < 5; attempt++) {
                if (!sameExternalConnection(expectedExtId, physicalId)) return "DISPLAY_CHANGED";
                if (readExternalMode(ext, true).stageFor(width, height, refresh, true)
                        == ExternalModeVerification.Stage.CONFIRMED) {
                    if (!sameExternalConnection(expectedExtId, physicalId)) return "DISPLAY_CHANGED";
                    JSONObject confirmation = OPlusAutoHr.call("confirm", token);
                    if (confirmation.optBoolean("confirmed") && token.equals(confirmation.optString("token"))) {
                        if (!sameExternalConnection(expectedExtId, physicalId)) return "DISPLAY_CHANGED";
                        sConfigProgress = new ConfigProgress(0, null, ExternalModeVerification.Stage.NONE);
                        sLastConfigObservation = null;
                        return "CONFIRMED";
                    }
                    return "SWITCH_UNCONFIRMED";
                }
                SystemClock.sleep(300);
            }
            return "READBACK_MISMATCH";
        } catch (Exception e) {
            System.err.println("EXTBRIDGE_OAH_ERROR=" + e);
            return "MODULE_ERROR";
        } finally {
            scheduleDisplayTick();
        }
    }

    private static boolean sameExternalConnection(int logicalId, long physicalId)
            throws ReflectiveOperationException {
        Display display = findExternalDisplay();
        return display != null && display.getDisplayId() == logicalId && physicalDisplayId(display) == physicalId;
    }

    private static boolean replaceVirtualDisplayForRefresh(float fps) {
        VirtualDisplay old = sVd;
        int oldId = sVdId;
        VirtualDisplay replacement = null;
        ArrayList<Object> tasks = new ArrayList<>();
        ArrayList<Integer> movedRoots = new ArrayList<>();
        try {
            List<?> running = invokeTaskQuery("getTasks");
            if (running == null) return false;
            for (Object task : running) if (readIntField(task, "displayId", -1) == oldId) tasks.add(task);
            // The recent-task query is capped. Migrate every root, including older/hidden tasks.
            List<?> roots = (List<?>) sAtm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class)
                    .invoke(sAtm, oldId);
            if (roots == null) return false;
            Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object builder = builderClass.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(extBridgeDisplayName(), sVirtW, sVirtH, sVirtDpi);
            builderClass.getMethod("setFlags", int.class).invoke(builder,
                    sFullscreenPolicy == 1 ? VD_FLAGS_DEX_FIXED : VD_FLAGS_COMPAT);
            builderClass.getMethod("setRequestedRefreshRate", float.class).invoke(builder, fps);
            builderClass.getMethod("setSurface", Surface.class).invoke(builder, sSurf);
            Object config = builderClass.getMethod("build").invoke(builder);
            old.setSurface(null);
            replacement = (VirtualDisplay) DisplayManager.class
                    .getMethod("createVirtualDisplay", Class.forName("android.hardware.display.VirtualDisplayConfig"))
                    .invoke(sDm, config);
            if (replacement == null) throw new IllegalStateException("Refresh display creation failed");
            int newId = replacement.getDisplay().getDisplayId();
            String newUniqueId = null;
            try { newUniqueId = (String) Display.class.getMethod("getUniqueId").invoke(replacement.getDisplay()); }
            catch (Throwable ignored) {}
            applyDisplayFullscreenPolicy(newId, sFullscreenPolicy == 1);
            Method move = sAtm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class);
            for (int i = roots.size() - 1; i >= 0; i--) {
                Object task = roots.get(i);
                int root = readIntField(task, "rootTaskId", readIntField(task, "taskId", -1));
                if (root < 0) throw new IllegalStateException("Missing display root task ID");
                if (movedRoots.contains(root)) continue;
                move.invoke(sAtm, root, newId);
                movedRoots.add(root);
            }
            if (!"BBQ".equals(sRenderMode)) {
                Object mirror = sMMirrorDisplay.invoke(sWm, newId);
                Class<?> sc = Class.forName("android.view.SurfaceControl");
                Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
                Object tx = txClass.getConstructor().newInstance();
                txClass.getMethod("reparent", sc, sc).invoke(tx, mirror, sScaleSc);
                txClass.getMethod("remove", sc).invoke(tx, sMirrorSc);
                txClass.getMethod("apply").invoke(tx);
                sMirrorSc = mirror;
            }
            float actualFps = replacement.getDisplay().getMode().getRefreshRate();
            sVd = replacement;
            sVdId = newId;
            sVdUniqueId = newUniqueId;
            sVirtualRefreshRequest = fps;
            sDesktopRefreshStability.reset();
            sVirtFps = actualFps;
            try { sSurf.setFrameRate(sVirtFps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT); }
            catch (Throwable ignored) {}
            try { old.release(); } catch (Throwable ignored) {}
            for (Object task : tasks) {
                int id = readIntField(task, "taskId", -1);
                if (!shouldSkipTaskPkg(extractTaskPkg(task)) && !sMinimizedTasks.contains(id)) {
                    Rect bounds;
                    try {
                        if (!isFreeformTask(task)) continue;
                        bounds = desktopTaskBounds(task);
                    } catch (Throwable ignored) { continue; }
                    long revision = sWindowChanges.getOrDefault(id, 0L);
                    sHandler.postDelayed(() -> {
                        if (sVdId == newId && !sMinimizedTasks.contains(id)
                                && sWindowChanges.getOrDefault(id, 0L) == revision)
                            controlDesktopWindow(id, 0, bounds);
                    }, 2200);
                }
            }
            syncInput(); reassert(); notifyHelperSync();
            System.out.println("EXTBRIDGE_REFRESH_SYNC " + oldId + "->" + newId + "@" + sVirtFps);
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            // Keep the original display and tasks if migration cannot complete.
            try {
                Method move = sAtm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class);
                for (int root : movedRoots) move.invoke(sAtm, root, oldId);
                if (replacement != null) replacement.release();
                old.setSurface(sSurf);
                sVd = old; sVdId = oldId;
            } catch (Throwable rollback) { rollback.printStackTrace(); }
            return false;
        }
    }

    private static synchronized long applyConfig(int reqW, int reqH, int reqDpi, int reqFpsX100,
            int physModeId, int expectedExtId) {
        try {
            if (reqW < 640 || reqW > 7680 || reqH < 480 || reqH > 4320 || reqDpi < 100 || reqDpi > 640) {
                return -1;
            }
            if (reqFpsX100 < 0 || reqFpsX100 > 36000) return -1;
            Display ext = findExternalDisplay();
            if (expectedExtId > 0 && (ext == null || ext.getDisplayId() != expectedExtId)) {
                return CONFIG_DISPLAY_CHANGED;
            }
            if (ext == null && physModeId > 0) return -1;
            Display.Mode target = ext == null ? null : choosePhysicalMode(ext.getSupportedModes(),
                    ext.getMode(), reqW, reqH, reqFpsX100 / 100f, physModeId);
            if (ext != null && target == null) return -1;
            long requestId = SystemClock.elapsedRealtimeNanos();
            sVirtW = reqW;
            sVirtH = reqH;
            sVirtDpi = reqDpi;
            sRequestedFps = reqFpsX100 / 100.0f;
            saveConf();

            // 1. Apply physical external display mode if supported & requested
            if (ext != null) {
                if (sModeAppliedExtId != ext.getDisplayId()) preparePhysicalMode(ext);
                requestPhysicalMode(ext, target, requestId);
                scheduleDisplayTick();
            } else {
                resetPhysicalMode();
                sConfigProgress = new ConfigProgress(requestId, null, ExternalModeVerification.Stage.NONE);
            }
            if (sVd == null || (!sForceRebuild && desktopMatchesSize(sVd.getDisplay()))) return requestId;

            // 2. Live resize VirtualDisplay + BBQ + hardware scale without tearing down running apps!
            if (sVd != null && "BBQ".equals(sRenderMode) && sMirrorSc != null) {
                try {
                    Class<?> scCls = Class.forName("android.view.SurfaceControl");
                    Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                    Object oldBbq = sBbq;
                    Surface oldSurf = sSurf;

                    Object newBbq = bbqCls.getConstructor(String.class, boolean.class).newInstance("ExtBBQ", true);
                    bbqCls.getMethod("update", scCls, int.class, int.class, int.class).invoke(newBbq, sMirrorSc, sVirtW, sVirtH, 1);
                    Surface newSurf = (Surface) bbqCls.getMethod("createSurface").invoke(newBbq);
                    if (newSurf != null && Build.VERSION.SDK_INT >= 30) {
                        try { newSurf.setFrameRate(sVirtFps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT); } catch (Throwable ignored) {}
                    }
                    sVd.resize(sVirtW, sVirtH, sVirtDpi);
                    sVd.setSurface(newSurf);

                    sBbq = newBbq;
                    sSurf = newSurf;
                    if (oldSurf != null) try { oldSurf.release(); } catch (Throwable ignored) {}
                    if (oldBbq != null) try { bbqCls.getMethod("destroy").invoke(oldBbq); } catch (Throwable ignored) {}

                    applySurfaceTransform();
                    if (sHandler != null) sHandler.postDelayed(ExtBridge::applySurfaceTransform, 150);
                    sCursorX = Math.min(sCursorX, sVirtW - 1f);
                    sCursorY = Math.min(sCursorY, sVirtH - 1f);
                    reassert();
                    notifyHelperSync();
                    System.out.println("EXTBRIDGE_RESIZED " + sVirtW + "x" + sVirtH + "@" + sVirtDpi + "fps=" + sVirtFps);
                    return requestId;
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            sForceRebuild = true;
            return requestId;
        } catch (Throwable t) {
            t.printStackTrace();
            return -1;
        }
    }

    private static void unbindInput() {
        try {
            if (sIm == null || sMRemAssoc == null) return;
            for (String desc : sBoundDescs) {
                try { sMRemAssoc.invoke(sIm, desc); } catch (Throwable ignored) {}
            }
            sBoundDescs.clear();
        } catch (Throwable ignored) {}
    }

    private static void syncInput() {
        try {
            unbindInput();
            if (sExtId < 0 || sVdUniqueId == null || sIm == null) return;
            int[] ids = sIm.getInputDeviceIds();
            int extCnt = 0;
            for (int id : ids) {
                InputDevice dev = sIm.getInputDevice(id);
                if (dev == null || !dev.isExternal() || dev.isVirtual()) continue;
                extCnt++;
                if (sInputToExt != 0 && sMAddAssoc != null) {
                    String desc = dev.getDescriptor();
                    if (desc != null) {
                        try {
                            sMAddAssoc.invoke(sIm, desc, sVdUniqueId);
                            sBoundDescs.add(desc);
                        } catch (Throwable ignored) {}
                    }
                }
            }
            sLastExtInputCnt = extCnt;
            System.out.println("EXTBRIDGE_INPUT_MODE=" + sInputToExt + " count=" + extCnt);
            notifyHelperSync();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void checkInput() {
        try {
            if (sInputToExt == 0 || sIm == null) return;
            int[] ids = sIm.getInputDeviceIds();
            int extCnt = 0;
            for (int id : ids) {
                InputDevice dev = sIm.getInputDevice(id);
                if (dev != null && dev.isExternal() && !dev.isVirtual()) extCnt++;
            }
            if (extCnt != sLastExtInputCnt) {
                syncInput();
            }
        } catch (Throwable ignored) {}
    }

    private static void injectKey(int keyCode, int metaState) {
        try {
            if (sVdId <= 0 || sIIm == null || sMInjectInput == null) return;
            long now = SystemClock.uptimeMillis();
            KeyEvent kd = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            KeyEvent ku = new KeyEvent(now, now + 15, KeyEvent.ACTION_UP, keyCode, 0, metaState, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            if (sMSetDisplayId != null) {
                sMSetDisplayId.invoke(kd, sVdId);
                sMSetDisplayId.invoke(ku, sVdId);
            }
            sMInjectInput.invoke(sIIm, kd, 0);
            sMInjectInput.invoke(sIIm, ku, 0);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void injectAscii(String text) {
        try {
            if (sVdId <= 0 || sIIm == null || sMInjectInput == null || text == null || text.isEmpty()) return;
            KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            KeyEvent[] evs = kcm.getEvents(text.toCharArray());
            if (evs == null) return;
            for (KeyEvent e : evs) {
                if (sMSetDisplayId != null) sMSetDisplayId.invoke(e, sVdId);
                sMInjectInput.invoke(sIIm, e, 0);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void injectMouseAction(int type, float a1, float a2) {
        try {
            if (sVdId <= 0 || sIIm == null || sMInjectInput == null) return;
            long now = SystemClock.uptimeMillis();
            switch (type) {
                case 0: // MOVE_REL
                    sCursorX = Math.max(0f, Math.min(sVirtW - 1f, sCursorX + a1));
                    sCursorY = Math.max(0f, Math.min(sVirtH - 1f, sCursorY + a2));
                    dispatchMouseMotion(sMouseButtons != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE,
                            now, sCursorX, sCursorY, sMouseButtons, 0, 0f, 0f);
                    break;
                case 1: // LEFT_CLICK
                    dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now, sCursorX, sCursorY, 0, 0, 0f, 0f);
                    sMouseDownTime = now;
                    dispatchMouseMotion(MotionEvent.ACTION_DOWN, now, sCursorX, sCursorY, MotionEvent.BUTTON_PRIMARY, 0, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now, sCursorX, sCursorY, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now + 20, sCursorX, sCursorY, 0, MotionEvent.BUTTON_PRIMARY, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_UP, now + 20, sCursorX, sCursorY, 0, 0, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now + 25, sCursorX, sCursorY, 0, 0, 0f, 0f);
                    break;
                case 2: // RIGHT_CLICK
                    dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now, sCursorX, sCursorY, 0, 0, 0f, 0f);
                    sMouseDownTime = now;
                    dispatchMouseMotion(MotionEvent.ACTION_DOWN, now, sCursorX, sCursorY, MotionEvent.BUTTON_SECONDARY, 0, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now, sCursorX, sCursorY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now + 20, sCursorX, sCursorY, 0, MotionEvent.BUTTON_SECONDARY, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_UP, now + 20, sCursorX, sCursorY, 0, 0, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now + 25, sCursorX, sCursorY, 0, 0, 0f, 0f);
                    break;
                case 3: // LEFT_DOWN (drag)
                    sMouseDownTime = now;
                    sMouseButtons = MotionEvent.BUTTON_PRIMARY;
                    dispatchMouseMotion(MotionEvent.ACTION_DOWN, now, sCursorX, sCursorY, MotionEvent.BUTTON_PRIMARY, 0, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now, sCursorX, sCursorY, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 0f, 0f);
                    break;
                case 4: // LEFT_UP (drag end)
                    sMouseButtons = 0;
                    dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, sCursorX, sCursorY, 0, MotionEvent.BUTTON_PRIMARY, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, 0, 0, 0f, 0f);
                    dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now + 5, sCursorX, sCursorY, 0, 0, 0f, 0f);
                    break;
                case 5: // SCROLL (hscroll=a1, vscroll=a2)
                    dispatchMouseMotion(MotionEvent.ACTION_SCROLL, now, sCursorX, sCursorY, sMouseButtons, 0, a1, a2);
                    break;
                case 7: // MOVE_ABS
                    sCursorX = Math.max(0f, Math.min(sVirtW - 1f, a1));
                    sCursorY = Math.max(0f, Math.min(sVirtH - 1f, a2));
                    dispatchMouseMotion(sMouseButtons != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE,
                            now, sCursorX, sCursorY, sMouseButtons, 0, 0f, 0f);
                    break;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void dispatchMouseMotion(int action, long now, float x, float y, int buttonState, int actionButton, float hscroll, float vscroll) throws Exception {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = (buttonState != 0) ? 1.0f : 0.0f;
        coords[0].size = 1.0f;
        if (action == MotionEvent.ACTION_SCROLL) {
            coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        }

        long downTime = (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) ? now : sMouseDownTime;
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, 1, props, coords,
                0, buttonState, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (actionButton != 0) {
            try { MotionEvent.class.getMethod("setActionButton", int.class).invoke(ev, actionButton); } catch (Throwable ignored) {}
        }
        if (sMSetDisplayId != null) sMSetDisplayId.invoke(ev, sVdId);
        sMInjectInput.invoke(sIIm, ev, 0);
        ev.recycle();
    }

    private static void launchComponentOnVd(String pkg, String cls) {
        try {
            if (sVdId <= 0 || sAtm == null || sMStartActivity == null) return;
            boolean desktop = "com.extbridge.helper".equals(pkg)
                    && "com.extbridge.helper.LauncherActivity".equals(cls);
            int mode = desktop ? 1 : getAppMode(pkg);
            boolean alreadyOnDesktop = false;
            List<?> previousTasks = invokeTaskQuery("getTasks");
            if (previousTasks != null) for (Object task : previousTasks) {
                if (readIntField(task, "displayId", -1) == sVdId && pkg.equals(extractTaskPkg(task))) {
                    alreadyOnDesktop = true;
                    int taskId = readIntField(task, "taskId", -1);
                    if (!desktop) {
                        setDesktopTaskHidden(taskId, false);
                        return;
                    }
                    break;
                }
            }
            applyAppModeCompat(pkg, mode, false);
            Intent intent = new Intent();
            ComponentName component = new ComponentName(pkg, cls);
            intent.setComponent(component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if ("com.extbridge.helper".equals(pkg) && sCtlBinder != null) {
                Bundle b = new Bundle();
                b.putBinder("cb", sCtlBinder);
                intent.putExtras(b);
                intent.putExtra("extId", sVdId);
            }
            ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode, component);
            sMStartActivity.invoke(sAtm, null, launchCallingPackage(), null, intent, null, null, null, 0, 0, null, opts.toBundle());
            if (!desktop && !alreadyOnDesktop && (mode == 0 || mode == 3)) {
                final int displayId = sVdId;
                sHandler.postDelayed(() -> {
                    if (sVdId != displayId) return;
                    List<?> tasks = invokeTaskQuery("getTasks");
                    if (tasks != null) for (Object task : tasks) {
                        if (readIntField(task, "displayId", -1) == displayId && pkg.equals(extractTaskPkg(task))) {
                            int id = readIntField(task, "taskId", -1);
                            if (!sWindowChanges.containsKey(id)) {
                                Rect bounds = portraitDefault(component, mode)
                                        ? calcFreeformRect(true) : defaultBoundsForTask(task, mode);
                                controlDesktopWindow(id, 0, bounds);
                            }
                            break;
                        }
                    }
                }, 2200);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static String launchCallingPackage() {
        return sSystemServer ? "android" : "com.android.shell";
    }

    private static Rect calcFreeformRect(boolean portrait) {
        int sw = Math.max(1, sVirtW);
        int sh = Math.max(1, sVirtH);
        int margin = Math.round(24 * sVirtDpi / 160f);
        int h = Math.max(1, Math.min(sh - margin * 4, Math.round(sh * 0.72f)));
        int w = Math.max(1, Math.min(sw - margin * 2, Math.round(portrait ? h * 9f / 16f : sw * 0.64f)));
        int left = (sw - w) / 2;
        int top = Math.max(0, (sh - h) / 2 - margin);
        return new Rect(left, top, left + w, top + h);
    }

    private static boolean portraitDefault(ComponentName component, int mode) {
        if (mode != 0 || component == null || sCtx == null) return false;
        try {
            int orientation = sCtx.getPackageManager().getActivityInfo(component, 0).screenOrientation;
            return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    || orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static Rect defaultBoundsForTask(Object task, int mode) {
        Object top = readObjField(task, "topActivity");
        Object base = readObjField(task, "baseActivity");
        return calcFreeformRect((top instanceof ComponentName && portraitDefault((ComponentName) top, mode))
                || (base instanceof ComponentName && portraitDefault((ComponentName) base, mode)));
    }

    private static ActivityOptions makeExtLaunchOptionsForMode(int displayId, int mode, ComponentName component) {
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId);
        if (displayId > 0) {
            try {
                if (mode == 1) {
                    ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 1);
                } else if (mode == 3 || mode == 0) {
                    ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 5);
                    opts.setLaunchBounds(calcFreeformRect(portraitDefault(component, mode)));
                }
            } catch (Throwable ignored) {}
        }
        return opts;
    }

    private static boolean shouldCompatOverridePkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return !"android".equals(pkg)
                && !"com.extbridge.helper".equals(pkg)
                && !"com.android.settings".equals(pkg)
                && !"com.android.systemui".equals(pkg)
                && !"com.miui.home".equals(pkg)
                && !"com.miui.securitycenter".equals(pkg);
    }

    private static void setGlobalIntSafe(String key, int val) {
        try {
            if (sCtx != null && Settings.Global.putInt(sCtx.getContentResolver(), key, val)) return;
        } catch (Throwable ignored) {}
        runCmdOut("settings", "put", "global", key, String.valueOf(val));
    }

    private static void restoreGlobalFullscreenPrefs() {
        if (!sSavedSysPrefs) return;
        setGlobalIntSafe("force_resizable_activities", sOrigForceResizable);
        setGlobalIntSafe("enable_non_resizable_multi_window", sOrigNonResizableMw);
        sSavedSysPrefs = false;
    }

    private static void applyDisplayFullscreenPolicy(int vdId, boolean enable) {
        // Never mutate global Settings.Global force_resizable_activities/enable_non_resizable_multi_window
        // as those globally affect tablet/phone Display 0 apps (WeChat, Quark, Pinduoduo, etc.).
        restoreGlobalFullscreenPrefs();

        if (vdId <= 0 || sWm == null) return;
        // Only our virtual desktop uses freeform; Display 0 is never changed.
        try {
            sWm.getClass().getMethod("setWindowingMode", int.class, int.class).invoke(sWm, vdId, 5);
        } catch (Throwable ignored) {
            runCmdOut("wm", "set-display-windowing-mode", "-d", String.valueOf(vdId), "5");
        }
        boolean ignoredApplied = false;
        try {
            for (Method m : sWm.getClass().getMethods()) {
                if (m.getName().equals("setIgnoreOrientationRequest") && m.getParameterCount() == 2) {
                    m.invoke(sWm, vdId, enable);
                    ignoredApplied = true;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        if (!ignoredApplied) {
            runCmdOut("wm", "set-ignore-orientation-request", "-d", String.valueOf(vdId), enable ? "true" : "false");
        }

        boolean rotApplied = false;
        try {
            if (enable) {
                for (Method m : sWm.getClass().getMethods()) {
                    if (m.getName().equals("freezeDisplayRotation") && m.getParameterCount() == 3) {
                        m.invoke(sWm, vdId, 0, "ExtBridge");
                        rotApplied = true;
                        break;
                    } else if (m.getName().equals("freezeDisplayRotation") && m.getParameterCount() == 2) {
                        m.invoke(sWm, vdId, 0);
                        rotApplied = true;
                        break;
                    }
                }
            } else {
                for (Method m : sWm.getClass().getMethods()) {
                    if (m.getName().equals("thawDisplayRotation") && m.getParameterCount() == 2) {
                        m.invoke(sWm, vdId, "ExtBridge");
                        rotApplied = true;
                        break;
                    } else if (m.getName().equals("thawDisplayRotation") && m.getParameterCount() == 1) {
                        m.invoke(sWm, vdId);
                        rotApplied = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (!rotApplied) {
            if (enable) {
                runCmdOut("wm", "user-rotation", "-d", String.valueOf(vdId), "lock", "0");
            } else {
                runCmdOut("wm", "user-rotation", "-d", String.valueOf(vdId), "free");
            }
        }

        try {
            for (Method m : sWm.getClass().getMethods()) {
                if (m.getName().equals("setDisplayWindowingMode") && m.getParameterCount() == 2) {
                    m.invoke(sWm, vdId, enable ? 1 : 0);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void applyAppModeCompat(String pkg, int mode, boolean forceApply) {
        if (!shouldCompatOverridePkg(pkg)) return;
        String qPkg = pkg.replace("'", "");
        if (mode == 0 || mode == 3) {
            boolean wasOverridden;
            synchronized (sCompatOverriddenPkgs) {
                wasOverridden = sCompatOverriddenPkgs.remove(pkg);
            }
            if (wasOverridden || forceApply) {
                runCmdOut("sh", "-c", "am compat reset-all --no-kill '" + qPkg
                        + "' >/dev/null 2>&1 || am compat reset-all '" + qPkg + "' >/dev/null 2>&1");
            }
            return;
        }
        synchronized (sCompatOverriddenPkgs) {
            if (!forceApply && sCompatOverriddenPkgs.contains(pkg)) return;
            sCompatOverriddenPkgs.add(pkg);
        }
        String cmd;
        if (mode == 1) {
            cmd = "for ch in 254631730 310816437 265464455 263259275; do "
                    + "am compat enable --no-kill $ch '" + qPkg + "' >/dev/null 2>&1; done; "
                    + "for ch in 265452344 236283604; do "
                    + "am compat disable --no-kill $ch '" + qPkg + "' >/dev/null 2>&1; done";
        } else {
            // mode == 2: portrait/phone ratio compat
            cmd = "for ch in 254631730 310816437 265464455; do "
                    + "am compat disable --no-kill $ch '" + qPkg + "' >/dev/null 2>&1; done; "
                    + "for ch in 265452344 236283604; do "
                    + "am compat enable --no-kill $ch '" + qPkg + "' >/dev/null 2>&1; done";
        }
        runCmdOut("sh", "-c", cmd);
    }

    private static void resetAppFullscreenCompatAll() {
        ArrayList<String> pkgs;
        synchronized (sCompatOverriddenPkgs) {
            if (sCompatOverriddenPkgs.isEmpty()) return;
            pkgs = new ArrayList<>(sCompatOverriddenPkgs);
            sCompatOverriddenPkgs.clear();
        }
        StringBuilder cmd = new StringBuilder();
        for (String pkg : pkgs) {
            String qPkg = pkg.replace("'", "");
            cmd.append("am compat reset-all --no-kill '").append(qPkg)
               .append("' >/dev/null 2>&1 || am compat reset-all '").append(qPkg).append("' >/dev/null 2>&1; ");
        }
        if (cmd.length() > 0) {
            runCmdOut("sh", "-c", cmd.toString());
        }
    }

    private static void forceTaskWindowingMode(int taskId, int wmode) {
        if (taskId <= 0 || sAtm == null) return;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("setTaskWindowingMode")) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                        m.invoke(sAtm, taskId, wmode);
                        return;
                    } else if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class && pt[2] == boolean.class) {
                        m.invoke(sAtm, taskId, wmode, false);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void enforceRunningExtTasksFullscreen() {
        if (sVdId <= 0 || sAtm == null) return;
        try {
            List<?> running = invokeTaskQuery("getTasks");
            if (running == null) return;
            for (Object t : running) {
                if (t == null) continue;
                int did = readIntField(t, "displayId", -1);
                if (did != sVdId) continue;
                String pkg = extractTaskPkg(t);
                if (!shouldCompatOverridePkg(pkg)) continue;
                int mode = getAppMode(pkg);
                applyAppModeCompat(pkg, mode, false);
                if (mode == 1) {
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    int wmode = readIntField(t, "windowingMode", 1);
                    if (tid > 0 && wmode != 1) {
                        forceTaskWindowingMode(tid, 1);
                    }
                } else if (mode == 3) {
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    int wmode = readIntField(t, "windowingMode", 5);
                    if (tid > 0 && wmode != 5) {
                        forceTaskWindowingMode(tid, 5);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void launchLauncher() {
        launchComponentOnVd("com.extbridge.helper", "com.extbridge.helper.LauncherActivity");
    }

    private static void pushBinderToHelper() {
        if (sCtx == null || sCtlBinder == null) return;
        Bundle res = null;
        try {
            Bundle b = new Bundle();
            b.putBinder("cb", sCtlBinder);
            try {
                ContentProviderClient cpc = sCtx.getContentResolver().acquireUnstableContentProviderClient("com.extbridge.helper.provider");
                if (cpc != null) {
                    try {
                        res = cpc.call("setBinder", null, b);
                    } finally {
                        try { cpc.close(); } catch (Throwable ignored) {}
                    }
                }
                if (res == null) {
                    res = sCtx.getContentResolver().call(Uri.parse("content://com.extbridge.helper.provider"), "setBinder", null, b);
                }
            } catch (Throwable ignored) {}
            if (res == null || !res.getBoolean("ok", false)) {
                res = callProviderViaAm("com.extbridge.helper.provider", "setBinder", null, b);
            }
            if (res != null) {
                IBinder tok = res.getBinder("proc");
                if (tok != null && tok != sHelperProcToken) {
                    sHelperProcToken = tok;
                    try {
                        tok.linkToDeath(() -> {
                            sHelperProcToken = null;
                            if (sHandler != null && !sShuttingDown && sVdId > 0) {
                                sHandler.postDelayed(() -> {
                                    if (sVdId > 0 && !sShuttingDown) {
                                        launchLauncher();
                                        pushBinderToHelper();
                                    }
                                }, 1500);
                            }
                        }, 0);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            System.err.println("pushBinderToHelper failed: " + t);
            t.printStackTrace();
        }
        startHelperService();
    }

    private static Bundle callProviderViaAm(String authority, String method, String arg, Bundle extras) {
        IBinder tok = new Binder();
        Object am = null;
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder amB = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "activity");
            am = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, amB);
            Object holder = am.getClass()
                    .getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                    .invoke(am, authority, 0, tok, "*extbridge*");
            if (holder == null) return null;
            Object prov = holder.getClass().getField("provider").get(holder);
            if (prov == null) return null;
            Method callAttr5 = null;
            Method callStr5 = null;
            Method callStr6 = null;
            StringBuilder sigs = new StringBuilder();
            for (Method m : prov.getClass().getMethods()) {
                if (!m.getName().equals("call")) continue;
                Class<?>[] pts = m.getParameterTypes();
                sigs.append(Arrays.toString(pts)).append("; ");
                if (pts.length == 5 && pts[0].getName().equals("android.content.AttributionSource")) callAttr5 = m;
                else if (pts.length == 5 && pts[0] == String.class) callStr5 = m;
                else if (pts.length == 6 && pts[0] == String.class) callStr6 = m;
            }
            if (callAttr5 != null) {
                Class<?> asBuilderCls = Class.forName("android.content.AttributionSource$Builder");
                Object builder = asBuilderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
                asBuilderCls.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
                Object attrSrc = asBuilderCls.getMethod("build").invoke(builder);
                return (Bundle) callAttr5.invoke(prov, attrSrc, authority, method, arg, extras);
            } else if (callStr5 != null) {
                return (Bundle) callStr5.invoke(prov, "com.android.shell", authority, method, arg, extras);
            } else if (callStr6 != null) {
                return (Bundle) callStr6.invoke(prov, "com.android.shell", null, authority, method, arg, extras);
            } else {
                System.err.println("callProviderViaAm no matching call overload: " + sigs);
            }
        } catch (Throwable t) {
            System.err.println("callProviderViaAm failed: " + t);
            t.printStackTrace();
        } finally {
            if (am != null) {
                try {
                    am.getClass().getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                            .invoke(am, authority, tok, 0);
                } catch (Throwable ignored) {
                    try {
                        am.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class)
                                .invoke(am, authority, tok);
                    } catch (Throwable ignored2) {}
                }
            }
        }
        return null;
    }

    private static Context fixShellContext(Context base) {
        try {
            Field fOp = base.getClass().getDeclaredField("mOpPackageName");
            fOp.setAccessible(true);
            fOp.set(base, "com.android.shell");
        } catch (Throwable ignored) {}
        try {
            Field fBase = base.getClass().getDeclaredField("mBasePackageName");
            fBase.setAccessible(true);
            fBase.set(base, "com.android.shell");
        } catch (Throwable ignored) {}
        try {
            Class<?> asBuilderCls = Class.forName("android.content.AttributionSource$Builder");
            Object builder = asBuilderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
            asBuilderCls.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
            Object attrSrc = asBuilderCls.getMethod("build").invoke(builder);
            Field fAttr = base.getClass().getDeclaredField("mAttributionSource");
            fAttr.setAccessible(true);
            fAttr.set(base, attrSrc);
        } catch (Throwable ignored) {}
        return base;
    }

    private static void startHelperService() {
        if (sVdId <= 0 || sShuttingDown) return;
        try {
            Intent svc = new Intent();
            svc.setComponent(new ComponentName("com.extbridge.helper", "com.extbridge.helper.BridgeService"));
            if (sCtlBinder != null) {
                Bundle b = new Bundle();
                b.putBinder("cb", sCtlBinder);
                svc.putExtras(b);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                sCtx.startForegroundService(svc);
            } else {
                sCtx.startService(svc);
            }
        } catch (Throwable ignored) {}
    }

    private static void stopHelperService() {
        try {
            Intent svc = new Intent().setComponent(new ComponentName(
                    "com.extbridge.helper", "com.extbridge.helper.BridgeService"));
            sCtx.stopService(svc);
        } catch (Throwable ignored) {}
    }

    private static void notifyHelperSync() {
        try {
            Intent b = new Intent("com.extbridge.helper.STATE_SYNC");
            b.setPackage("com.extbridge.helper");
            b.putExtra("extId", sExtId);
            b.putExtra("vdId", sVdId);
            b.putExtra("physW", sPhysW);
            b.putExtra("physH", sPhysH);
            b.putExtra("physFps", sPhysFps);
            b.putExtra("virtW", sVirtW);
            b.putExtra("virtH", sVirtH);
            b.putExtra("virtDpi", sVirtDpi);
            b.putExtra("virtFps", sVirtFps);
            b.putExtra("inputToExt", sInputToExt);
            sCtx.sendBroadcast(b);
        } catch (Throwable ignored) {}
    }

    private static String getModesSummary() {
        try {
            Display ext = findExternalDisplay();
            if (ext == null) return "";
            Display.Mode[] modes = ext.getSupportedModes();
            if (modes == null) return "";
            StringBuilder sb = new StringBuilder();
            for (Display.Mode m : modes) {
                if (sb.length() > 0) sb.append(";");
                sb.append(m.getModeId()).append(":")
                  .append(m.getPhysicalWidth()).append("x").append(m.getPhysicalHeight())
                  .append("@").append(String.format(java.util.Locale.US, "%.2f", m.getRefreshRate()));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static class BridgeBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (!((code >= 10 && code <= 33) || (code >= 40 && code <= 50) || code == 99)) {
                return super.onTransact(code, data, reply, flags);
            }
            int uid = Binder.getCallingUid();
            if (uid != 0 && (code == 50 || (uid != 1000 && uid != 2000))) {
                int helperUid = -1;
                try { helperUid = sCtx.getPackageManager().getPackageUid("com.extbridge.helper", 0); }
                catch (Throwable ignored) {}
                if (uid != helperUid) throw new SecurityException("Untrusted ExtBridge controller");
            }
            long identity = Binder.clearCallingIdentity();
            try {
                return dispatch(code, data, reply, flags);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private boolean dispatch(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 10) {
                sInputToExt = (sInputToExt == 0) ? 1 : 0;
                syncInput();
                if (reply != null) reply.writeInt(sInputToExt);
                return true;
            } else if (code == 11) {
                injectKey(KeyEvent.KEYCODE_BACK, 0);
                return true;
            } else if (code == 12) {
                launchLauncher();
                return true;
            } else if (code == 13) {
                injectKey(KeyEvent.KEYCODE_HOME, 0);
                return true;
            } else if (code == 14) {
                if (reply != null) reply.writeInt(sInputToExt);
                return true;
            } else if (code == 15) {
                if (reply != null) {
                    reply.writeInt(sExtId);
                    reply.writeInt(sVdId);
                    reply.writeInt(sPhysW);
                    reply.writeInt(sPhysH);
                    reply.writeInt(Math.round(sPhysFps * 100f));
                    reply.writeInt(sVirtW);
                    reply.writeInt(sVirtH);
                    reply.writeInt(sVirtDpi);
                    reply.writeInt(Math.round(sVirtFps * 100f));
                    reply.writeInt(sInputToExt);
                    reply.writeInt(Math.round(sCursorX));
                    reply.writeInt(Math.round(sCursorY));
                    reply.writeInt(sLastExtInputCnt);
                    reply.writeString(getModesSummary());
                    reply.writeString(sRenderMode);
                    reply.writeInt(sMirrorMode);
                    reply.writeInt(sFullscreenPolicy);
                    reply.writeString(hostModeName());
                    reply.writeString(DAEMON_VERSION);
                    reply.writeInt(Math.round(sRequestedFps * 100f));
                    ConfigProgress progress = sConfigProgress;
                    Display.Mode target = progress.target;
                    reply.writeInt(sPhysicalModeConfirmed ? 1 : 0);
                    reply.writeInt(target == null ? 0 : target.getPhysicalWidth());
                    reply.writeInt(target == null ? 0 : target.getPhysicalHeight());
                    reply.writeInt(target == null ? 0 : Math.round(target.getRefreshRate() * 100f));
                    reply.writeLong(progress.requestId);
                    reply.writeLong(progress.stage == ExternalModeVerification.Stage.CONFIRMED ? progress.requestId : 0);
                    reply.writeInt(sDrmReadback ? 1 : 0);
                    reply.writeString(progress.stage.name());
                }
                return true;
            } else if (code == 16) {
                int w = data.readInt();
                int h = data.readInt();
                int dpi = data.readInt();
                int fpsX100 = data.readInt();
                int physModeId = data.readInt();
                int expectedExtId = data.dataAvail() >= 4 ? data.readInt() : -1;
                long requestId = applyConfig(w, h, dpi, fpsX100, physModeId, expectedExtId);
                if (reply != null) {
                    reply.writeInt(requestId > 0 ? 1 : 0);
                    reply.writeLong(requestId);
                }
                return true;
            } else if (code == 17) {
                int actionType = data.readInt();
                float a1 = data.readFloat();
                float a2 = data.readFloat();
                injectMouseAction(actionType, a1, a2);
                if (reply != null) {
                    reply.writeInt(Math.round(sCursorX));
                    reply.writeInt(Math.round(sCursorY));
                }
                return true;
            } else if (code == 18) {
                int sub = data.readInt();
                int arg1 = data.readInt();
                int arg2 = data.readInt();
                String txt = data.readString();
                if (sub == 0) {
                    injectKey(arg1, arg2);
                } else if (sub == 1) {
                    injectAscii(txt);
                } else if (sub == 2) {
                    if (arg1 == 1) {
                        injectKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
                    } else {
                        injectKey(KeyEvent.KEYCODE_PASTE, 0);
                    }
                }
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 19) {
                int mode = data.readInt();
                sInputToExt = (mode != 0) ? 1 : 0;
                syncInput();
                if (reply != null) reply.writeInt(sInputToExt);
                return true;
            } else if (code == 20) {
                String pkg = data.readString();
                String cls = data.readString();
                launchComponentOnVd(pkg, cls);
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 21) {
                sForceRebuild = true;
                tick();
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 22) {
                boolean force = data.readInt() == 1;
                byte[] wp = getWallpaperBytes(force);
                if (reply != null) reply.writeByteArray(wp);
                return true;
            } else if (code == 23) {
                writeTasksToParcel(reply);
                return true;
            } else if (code == 24) {
                int tid = data.readInt();
                String pkg = data.readString();
                boolean ok = switchToTaskOnVd(tid, pkg);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 25) {
                int tid = data.readInt();
                boolean ok = removeTaskById(tid);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 26) {
                writeSysStatusToParcel(reply);
                return true;
            } else if (code == 27) {
                int act = data.readInt();
                int arg = data.readInt();
                String str = data.readString();
                boolean ok = execSysAction(act, arg, str);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 28) {
                int req = data.readInt();
                sMirrorMode = (req < 0) ? (sMirrorMode == 0 ? 1 : 0) : (req != 0 ? 1 : 0);
                tick();
                notifyHelperSync();
                if (reply != null) reply.writeInt(sMirrorMode);
                return true;
            } else if (code == 29) {
                int req = data.readInt();
                int next = (req < 0) ? (sFullscreenPolicy == 0 ? 1 : 0) : (req != 0 ? 1 : 0);
                if (next != sFullscreenPolicy) {
                    sFullscreenPolicy = next;
                    saveConf();
                    if (sFullscreenPolicy == 0) {
                        resetAppFullscreenCompatAll();
                    }
                    if (sVdId > 0) {
                        applyDisplayFullscreenPolicy(sVdId, sFullscreenPolicy == 1);
                    }
                    if (sMirrorMode == 0 && sVd != null) {
                        sForceRebuild = true;
                        tick();
                    }
                    notifyHelperSync();
                }
                if (reply != null) reply.writeInt(sFullscreenPolicy);
                return true;
            } else if (code == 30) {
                String pkg = data.readString();
                int mode = getAppMode(pkg);
                if (sMirrorMode == 0) {
                    applyAppModeCompat(pkg, mode, false);
                }
                if (reply != null) reply.writeInt(mode);
                return true;
            } else if (code == 31) {
                StringBuilder sb = new StringBuilder();
                synchronized (sAppModes) {
                    for (Map.Entry<String, Integer> e : sAppModes.entrySet()) {
                        if (e.getValue() == null || e.getValue() == 0) continue;
                        if (sb.length() > 0) sb.append(';');
                        sb.append(e.getKey()).append('=').append(e.getValue());
                    }
                }
                if (reply != null) reply.writeString(sb.toString());
                return true;
            } else if (code == 32) {
                String pkg = data.readString();
                int mode = Math.max(0, Math.min(3, data.readInt()));
                int restart = data.readInt();
                if (pkg != null && !pkg.isEmpty()) {
                    synchronized (sAppModes) {
                        if (mode == 0) sAppModes.remove(pkg);
                        else sAppModes.put(pkg, mode);
                        saveAppModesLocked();
                    }
                    if (restart == 1) {
                        runCmdOut("am", "force-stop", pkg);
                    }
                    applyAppModeCompat(pkg, mode, true);
                    if (restart == 1 && sVdId > 0 && sMirrorMode == 0 && sCtx != null) {
                        try {
                            Intent li = sCtx.getPackageManager().getLaunchIntentForPackage(pkg);
                            if (li != null) {
                                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode, li.getComponent());
                                sMStartActivity.invoke(sAtm, null, launchCallingPackage(), null, li, null, null, null, 0, 0, null, opts.toBundle());
                            }
                        } catch (Throwable ignored) {}
                    }
                    notifyHelperSync();
                }
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 33) {
                ArrayList<String> allPkgs = new ArrayList<>();
                synchronized (sAppModes) {
                    allPkgs.addAll(sAppModes.keySet());
                    sAppModes.clear();
                    saveAppModesLocked();
                }
                synchronized (sCompatOverriddenPkgs) {
                    allPkgs.addAll(sCompatOverriddenPkgs);
                    sCompatOverriddenPkgs.clear();
                }
                if (!allPkgs.isEmpty()) {
                    StringBuilder cmd = new StringBuilder();
                    for (String p : allPkgs) {
                        String qp = p.replace("'", "");
                        cmd.append("am compat reset-all --no-kill '").append(qp)
                           .append("' >/dev/null 2>&1 || am compat reset-all '").append(qp).append("' >/dev/null 2>&1; ");
                    }
                    runCmdOut("sh", "-c", cmd.toString());
                }
                healStrayCompatOverrides(true);
                notifyHelperSync();
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 40) {
                // 驱动/硬件信息：只在 binder 线程执行，不阻塞主线程
                String json;
                try {
                    json = DriverProbe.collectJson(sDm, hostModeName());
                } catch (Throwable t) {
                    json = "{\"error\":\"" + String.valueOf(t).replace("\"", "'") + "\"}";
                }
                if (reply != null) reply.writeString(json);
                return true;
            } else if (code == 41) {
                if (reply != null) {
                    reply.writeInt(sHostMode);
                    reply.writeString(hostModeName());
                    reply.writeInt(android.os.Process.myUid());
                    reply.writeInt(android.os.Process.myPid());
                    reply.writeString(DAEMON_VERSION);
                    reply.writeString(readSelinux());
                }
                return true;
            } else if (code == 42) {
                int taskId = data.readInt();
                int action = data.readInt();
                Rect bounds = new Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt());
                boolean ok = controlDesktopWindow(taskId, action, bounds);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 43) {
                writeFocusedDesktopWindow(reply);
                return true;
            } else if (code == 44) {
                List<?> tasks = invokeTaskQuery("getTasks");
                if (tasks != null) for (Object task : tasks) {
                    if (readIntField(task, "displayId", -1) == sVdId
                            && readBoolField(task, "isVisible", false)
                            && !shouldSkipTaskPkg(extractTaskPkg(task))) {
                        setDesktopTaskHidden(readIntField(task, "taskId", -1), true);
                    }
                }
                return true;
            } else if (code == 45) {
                if (reply != null) {
                    reply.writeInt(sDisplayListener != null ? 1 : 0);
                    reply.writeInt(sExtId);
                    reply.writeInt(sVdId);
                    reply.writeInt(sMaintenancePending ? 1 : 0);
                    reply.writeLong(sMaintenanceRuns);
                }
                return true;
            } else if (code == 46) {
                boolean wifi = data.readInt() == 1;
                boolean scan = data.readInt() == 1;
                if (reply != null) {
                    List<Bundle> rows = null;
                    try {
                        if (wifi) {
                            if (scan) runCmdOut("cmd", "wifi", "start-scan");
                            try {
                                rows = QuickConnections.wifi(sCtx, sCachedWifiSsid, null);
                            } catch (RuntimeException denied) {
                                rows = QuickConnections.wifi(sCtx, sCachedWifiSsid,
                                        runCmdOut("cmd", "wifi", "list-scan-results"));
                            }
                        } else {
                            try {
                                rows = QuickConnections.bluetooth(sCtx, sCachedBtName);
                            } catch (RuntimeException ignored) {}
                            if (rows == null || rows.isEmpty()) {
                                rows = QuickConnections.bluetoothDump(runCmdOut("dumpsys", "-t", "2", "bluetooth_manager"));
                            }
                        }
                    } catch (RuntimeException ignored) {}
                    reply.writeTypedList(rows);
                }
                return true;
            } else if (code == 47) {
                boolean wifi = data.readInt() == 1;
                boolean connect = data.readInt() == 1;
                String id = data.readString();
                boolean ok = false;
                try {
                    ok = wifi ? QuickConnections.wifiAction(sCtx, id, connect)
                            : QuickConnections.bluetoothAction(sCtx, id, connect);
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
                sLastSysCheckMs = 0L;
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 48) {
                String ssid = data.readString();
                String security = data.readString();
                String password = data.readString();
                boolean accepted = false;
                try {
                    accepted = QuickConnections.joinWifi(sCtx, ssid, security, password);
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
                sLastSysCheckMs = 0L;
                if (reply != null) reply.writeInt(accepted ? 1 : 0);
                return true;
            } else if (code == 49) {
                String address = data.readString();
                boolean accept = data.readInt() == 1;
                String pin = data.readString();
                boolean ok = sVdId > 0 && sHostMode != HOST_SHELL
                        && QuickConnections.answerBluetoothPairing(sCtx, address, accept, pin);
                android.util.Log.i("ExtBridge", "Bluetooth pairing reply result=" + ok + " host=" + sHostMode);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 50) {
                if (reply != null) reply.writeString(forceHighestExternalRefresh(data.readInt()));
                return true;
            } else if (code == 99) {
                if (reply != null) reply.writeInt(1);
                requestExit();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static Object findDesktopTask(int taskId) {
        List<?> tasks = invokeTaskQuery("getTasks");
        if (tasks == null || sVdId <= 0) return null;
        for (Object task : tasks) {
            if (readIntField(task, "taskId", -1) == taskId
                    && readIntField(task, "displayId", -1) == sVdId
                    && !shouldSkipTaskPkg(extractTaskPkg(task))) return task;
        }
        return null;
    }

    private static Rect desktopTaskBounds(Object task) throws Exception {
        Object config = readObjField(task, "configuration");
        Object window = readObjField(config, "windowConfiguration");
        return new Rect((Rect) window.getClass().getMethod("getBounds").invoke(window));
    }

    private static boolean isFreeformTask(Object task) throws Exception {
        Object window = readObjField(readObjField(task, "configuration"), "windowConfiguration");
        return (Integer) window.getClass().getMethod("getWindowingMode").invoke(window) == 5;
    }

    private static void repairDesktopWindowBounds() {
        List<?> tasks = invokeTaskQuery("getTasks");
        if (tasks == null) return;
        int caption = Math.round(34 * sVirtDpi / 160f);
        int bottom = sVirtH - Math.round(52 * sVirtDpi / 160f);
        for (Object task : tasks) {
            int id = readIntField(task, "taskId", -1);
            if (readIntField(task, "displayId", -1) != sVdId
                    || !readBoolField(task, "isVisible", false) || sMinimizedTasks.contains(id)
                    || shouldSkipTaskPkg(extractTaskPkg(task))) continue;
            try {
                int mode = getAppMode(extractTaskPkg(task));
                if (!isFreeformTask(task) || mode == 2) continue;
                Rect bounds = desktopTaskBounds(task);
                // HyperOS can replay tablet geometry after its permission/show transition.
                // Valid windows and full-screen/portrait compatibility modes stay untouched.
                if (bounds.left < 0 || bounds.top < caption || bounds.right > sVirtW
                        || bounds.bottom > bottom || bounds.isEmpty()) {
                    Rect saved = sWindowRestoreBounds.get(id);
                    if (controlDesktopWindow(id, 0, saved != null ? saved : defaultBoundsForTask(task, mode)))
                        System.out.println("EXTBRIDGE_WINDOW_BOUNDS_REPAIRED task=" + id + " from=" + bounds);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void writeFocusedDesktopWindow(Parcel reply) {
        if (reply == null) return;
        try {
            List<?> tasks = invokeTaskQuery("getTasks");
            if (tasks != null) for (Object task : tasks) {
                if (readIntField(task, "displayId", -1) != sVdId || !readBoolField(task, "isVisible", false)) continue;
                if (shouldSkipTaskPkg(extractTaskPkg(task))) break;
                Object window = readObjField(readObjField(task, "configuration"), "windowConfiguration");
                int mode = (Integer) window.getClass().getMethod("getWindowingMode").invoke(window);
                if (mode != 5) break;
                Rect bounds = desktopTaskBounds(task);
                reply.writeInt(1);
                reply.writeInt(sVdId);
                reply.writeInt(readIntField(task, "taskId", -1));
                reply.writeString(extractTaskPkg(task));
                reply.writeInt(bounds.left); reply.writeInt(bounds.top);
                reply.writeInt(bounds.right); reply.writeInt(bounds.bottom);
                return;
            }
        } catch (Throwable ignored) {}
        reply.writeInt(0);
    }

    // 0=move/resize, 1=minimize, 2=maximize, 3=restore, 4=close.
    private static boolean controlDesktopWindow(int taskId, int action, Rect requested) {
        Object task = findDesktopTask(taskId);
        if (task == null || action < 0 || action > 4) return false;
        sWindowChanges.put(taskId, sWindowSerial.incrementAndGet());
        if (action == 4) {
            boolean removed = removeTaskById(taskId);
            if (removed) {
                sMinimizedTasks.remove(taskId);
                sWindowRestoreBounds.remove(taskId);
                sWindowChanges.remove(taskId);
            }
            return removed;
        }
        if (action == 1) {
            boolean hidden = setDesktopTaskHidden(taskId, true);
            if (hidden) launchLauncher();
            return hidden;
        }
        try {
            Rect previous = desktopTaskBounds(task);
            Object token = readObjField(task, "token");
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> transactionClass = Class.forName("android.window.WindowContainerTransaction");
            Object transaction = transactionClass.getConstructor().newInstance();
            {
                int scale = Math.max(1, sVirtDpi);
                int caption = Math.round(34 * scale / 160f);
                int bottom = Math.round(52 * scale / 160f);
                Rect rect = action == 2 ? new Rect(0, caption, sVirtW, sVirtH - bottom) : requested;
                if (action == 3) {
                    rect = sWindowRestoreBounds.get(taskId);
                    if (rect == null) rect = defaultBoundsForTask(task, getAppMode(extractTaskPkg(task)));
                }
                if (rect == null) return false;
                int width = Math.min(sVirtW, Math.max(Math.round(240 * scale / 160f), rect.width()));
                int height = Math.min(sVirtH - caption - bottom, Math.max(Math.round(160 * scale / 160f), rect.height()));
                int left = Math.max(0, Math.min(sVirtW - width, rect.left));
                int top = Math.max(caption, Math.min(sVirtH - bottom - height, rect.top));
                rect = new Rect(left, top, left + width, top + height);
                transactionClass.getMethod("setWindowingMode", tokenClass, int.class).invoke(transaction, token, 5);
                transactionClass.getMethod("setBounds", tokenClass, Rect.class).invoke(transaction, token, rect);
                transactionClass.getMethod("reorder", tokenClass, boolean.class).invoke(transaction, token, true);
                // HyperOS also keeps a scaled shell leash. Normalize only this desktop task.
                try {
                    Class<?> infoClass = Class.forName("miui.app.MiuiFreeFormManager$MiuiFreeFormInfoChange");
                    Object info = infoClass.getConstructor().newInstance();
                    infoClass.getMethod("setMiuiFreeformScale", float.class).invoke(info, 1f);
                    transactionClass.getMethod("setMiuiFreeformInfoChange", tokenClass, infoClass).invoke(transaction, token, info);
                } catch (ClassNotFoundException | NoSuchMethodException ignored) {}
            }
            Object organizer = sAtm.getClass().getMethod("getWindowOrganizerController").invoke(sAtm);
            try {
                organizer.getClass().getMethod("startNewTransition", int.class, transactionClass).invoke(organizer, 6, transaction);
            } catch (NoSuchMethodException ignored) {
                organizer.getClass().getMethod("applyTransaction", transactionClass).invoke(organizer, transaction);
            }
            if (action == 2 && previous.width() < sVirtW && !previous.isEmpty())
                sWindowRestoreBounds.put(taskId, previous);
            return true;
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_WINDOW_CONTROL_FAILED task=" + taskId + " action=" + action + " " + t);
            return false;
        }
    }

    private static boolean setDesktopTaskHidden(int taskId, boolean hidden) {
        Object task = findDesktopTask(taskId);
        if (task == null) return false;
        try {
            boolean wasMinimized = sMinimizedTasks.contains(taskId);
            Rect restoreBounds = desktopTaskBounds(task);
            int displayId = sVdId;
            long revision = sWindowSerial.incrementAndGet();
            sWindowChanges.put(taskId, revision);
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> transactionClass = Class.forName("android.window.WindowContainerTransaction");
            Object transaction = transactionClass.getConstructor().newInstance();
            Object token = readObjField(task, "token");
            transactionClass.getMethod("setHidden", tokenClass, boolean.class).invoke(transaction, token, hidden);
            transactionClass.getMethod("reorder", tokenClass, boolean.class).invoke(transaction, token, !hidden);
            Object organizer = sAtm.getClass().getMethod("getWindowOrganizerController").invoke(sAtm);
            try {
                organizer.getClass().getMethod("startNewTransition", int.class, transactionClass).invoke(organizer, hidden ? 4 : 3, transaction);
            } catch (NoSuchMethodException ignored) {
                organizer.getClass().getMethod("applyTransaction", transactionClass).invoke(organizer, transaction);
            }
            if (hidden) sMinimizedTasks.add(taskId); else sMinimizedTasks.remove(taskId);
            // HyperOS may replay its saved internal-display geometry after the show transition.
            if (!hidden && wasMinimized) sHandler.postDelayed(() -> {
                if (sVdId != displayId || sMinimizedTasks.contains(taskId)
                        || !Long.valueOf(revision).equals(sWindowChanges.get(taskId))) return;
                Object current = findDesktopTask(taskId);
                if (current != null && readBoolField(current, "isVisible", false)) {
                    controlDesktopWindow(taskId, 0, restoreBounds);
                }
            }, 2200);
            return true;
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_WINDOW_VISIBILITY_FAILED task=" + taskId + " " + t);
            return false;
        }
    }

    private static byte[] getWallpaperBytes(boolean force) {
        try {
            WallpaperManager wm = (WallpaperManager) sCtx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) return null;
            int wpId = 0;
            try { wpId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM); } catch (Throwable ignored) {}
            if (!force && sCachedWpBytes != null && wpId != 0 && wpId == sCachedWpId) {
                return sCachedWpBytes;
            }

            Bitmap bmp = null;
            try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
                if (pfd != null) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, bounds);
                    int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                    int sample = 1;
                    while (maxDim / sample > 1600) sample *= 2;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
                }
            } catch (Throwable ignored) {}

            if (bmp == null) {
                try {
                    Drawable dr = wm.getDrawable();
                    if (dr != null) {
                        int dw = Math.max(1, dr.getIntrinsicWidth());
                        int dh = Math.max(1, dr.getIntrinsicHeight());
                        float sc = Math.min(1f, 1280f / Math.max(dw, dh));
                        int tw = Math.max(1, Math.round(dw * sc));
                        int th = Math.max(1, Math.round(dh * sc));
                        bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                        Canvas cv = new Canvas(bmp);
                        dr.setBounds(0, 0, tw, th);
                        dr.draw(cv);
                    }
                } catch (Throwable ignored) {}
            }

            if (bmp == null) return sCachedWpBytes;

            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (Math.max(bw, bh) > 1440) {
                float sc = 1440f / Math.max(bw, bh);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bw * sc)), Math.max(1, Math.round(bh * sc)), true);
                if (scaled != bmp) {
                    bmp.recycle();
                    bmp = scaled;
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            byte[] out = baos.toByteArray();
            if (out.length > 420_000) {
                baos.reset();
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                out = baos.toByteArray();
            }
            bmp.recycle();
            sCachedWpBytes = out;
            sCachedWpId = wpId;
            return out;
        } catch (Throwable t) {
            return sCachedWpBytes;
        }
    }

    private static final class TaskSnap {
        int taskId;
        int rootTaskId;
        String pkg;
        int flags; // bit0=onExt, bit1=isRunning, bit2=isTopExt
    }

    private static int readIntField(Object obj, String name, int def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(obj);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return def;
    }

    private static boolean readBoolField(Object obj, String name, boolean def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(obj);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return def;
    }

    private static Object readObjField(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static String extractTaskPkg(Object info) {
        Object top = readObjField(info, "topActivity");
        if (top instanceof ComponentName) {
            String p = ((ComponentName) top).getPackageName();
            if (!shouldSkipTaskPkg(p)) return p;
        }
        Object real = readObjField(info, "realActivity");
        if (real instanceof ComponentName) {
            String p = ((ComponentName) real).getPackageName();
            if (p != null && !p.isEmpty()) return p;
        }
        Object bi = readObjField(info, "baseIntent");
        if (bi instanceof Intent) {
            Intent intent = (Intent) bi;
            if (intent.getComponent() != null) return intent.getComponent().getPackageName();
            if (intent.getPackage() != null) return intent.getPackage();
        }
        Object ba = readObjField(info, "baseActivity");
        if (ba instanceof ComponentName) return ((ComponentName) ba).getPackageName();
        return null;
    }

    private static boolean shouldSkipTaskPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        return "com.extbridge.helper".equals(pkg)
                || "com.miui.home".equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "com.miui.securitycenter".equals(pkg)
                || "com.android.launcher".equals(pkg)
                || "com.android.launcher3".equals(pkg);
    }

    private static List<?> invokeTaskQuery(String name) {
        if (sAtm == null) return null;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pt = m.getParameterTypes();
                Object res = null;
                if ("getTasks".equals(name)) {
                    if (pt.length == 1) res = m.invoke(sAtm, 25);
                    else if (pt.length == 2) res = m.invoke(sAtm, 25, false);
                    else if (pt.length == 3) res = m.invoke(sAtm, 25, false, true);
                    else if (pt.length == 4) res = m.invoke(sAtm, 25, false, true, -1);
                } else if ("getRecentTasks".equals(name) && pt.length == 3) {
                    res = m.invoke(sAtm, 25, 0, 0);
                }
                if (res instanceof List<?>) return (List<?>) res;
                if (res != null) {
                    try {
                        Object list = res.getClass().getMethod("getList").invoke(res);
                        if (list instanceof List<?>) return (List<?>) list;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void writeTasksToParcel(Parcel reply) {
        if (reply == null) return;
        LinkedHashMap<String, TaskSnap> map = new LinkedHashMap<>();
        try {
            List<?> running = invokeTaskQuery("getTasks");
            boolean firstOnExtMarked = false;
            if (running != null) {
                for (Object t : running) {
                    if (t == null) continue;
                    String pkg = extractTaskPkg(t);
                    if (shouldSkipTaskPkg(pkg)) continue;
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    int rtid = readIntField(t, "rootTaskId", tid);
                    int did = readIntField(t, "displayId", -1);
                    boolean onExt = (sVdId > 0 && did == sVdId);
                    boolean isVis = readBoolField(t, "isVisible", false);
                    int fl = 2; // isRunning
                    if (onExt) {
                        fl |= 1;
                        if (!firstOnExtMarked && isVis) {
                            fl |= 4;
                            firstOnExtMarked = true;
                        }
                    }
                    TaskSnap ts = map.get(pkg);
                    if (ts == null) {
                        ts = new TaskSnap();
                        ts.taskId = tid;
                        ts.rootTaskId = rtid;
                        ts.pkg = pkg;
                        ts.flags = fl;
                        map.put(pkg, ts);
                    } else {
                        ts.flags |= fl;
                        if (onExt && ts.taskId <= 0) ts.taskId = tid;
                    }
                }
            }

            List<?> recents = invokeTaskQuery("getRecentTasks");
            if (recents != null) {
                for (Object t : recents) {
                    if (t == null) continue;
                    String pkg = extractTaskPkg(t);
                    if (shouldSkipTaskPkg(pkg)) continue;
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    int rtid = readIntField(t, "rootTaskId", tid);
                    int did = readIntField(t, "displayId", -1);
                    boolean isRun = readBoolField(t, "isRunning", false);
                    boolean onExt = (sVdId > 0 && did == sVdId);
                    int fl = (onExt ? 1 : 0) | (isRun ? 2 : 0);
                    TaskSnap ts = map.get(pkg);
                    if (ts == null) {
                        ts = new TaskSnap();
                        ts.taskId = tid;
                        ts.rootTaskId = rtid;
                        ts.pkg = pkg;
                        ts.flags = fl;
                        map.put(pkg, ts);
                    } else {
                        ts.flags |= fl;
                        if (ts.taskId <= 0 && tid > 0) ts.taskId = tid;
                    }
                }
            }
        } catch (Throwable ignored) {}

        ArrayList<TaskSnap> extList = new ArrayList<>();
        ArrayList<TaskSnap> otherList = new ArrayList<>();
        for (TaskSnap ts : map.values()) {
            if ((ts.flags & 1) != 0) extList.add(ts);
            else otherList.add(ts);
        }
        ArrayList<TaskSnap> combined = new ArrayList<>(10);
        for (TaskSnap ts : extList) {
            if (combined.size() >= 10) break;
            combined.add(ts);
        }
        for (TaskSnap ts : otherList) {
            if (combined.size() >= 10) break;
            combined.add(ts);
        }

        reply.writeInt(combined.size());
        for (TaskSnap ts : combined) {
            reply.writeInt(ts.taskId);
            reply.writeString(ts.pkg);
            reply.writeInt(ts.flags);
        }
    }

    private static boolean switchToTaskOnVd(int taskId, String pkg) {
        if (sVdId <= 0) return false;
        int mode = getAppMode(pkg);
        try {
            Intent launch = sCtx != null && pkg != null ? sCtx.getPackageManager().getLaunchIntentForPackage(pkg) : null;
            applyAppModeCompat(pkg, mode, false);
            if (taskId > 0 && sAtm != null) {
                if (findDesktopTask(taskId) != null) return setDesktopTaskHidden(taskId, false);
                if (sMinimizedTasks.contains(taskId) && !setDesktopTaskHidden(taskId, false)) return false;
                try {
                    for (Method m : sAtm.getClass().getMethods()) {
                        if (m.getName().equals("moveRootTaskToDisplay") && m.getParameterCount() == 2) {
                            m.invoke(sAtm, taskId, sVdId);
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
                if (mode == 1) forceTaskWindowingMode(taskId, 1);
                else if (mode == 3) forceTaskWindowingMode(taskId, 5);

                ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode, launch == null ? null : launch.getComponent());
                Bundle b = opts.toBundle();
                for (Method m : sAtm.getClass().getMethods()) {
                    if (m.getName().equals("startActivityFromRecents") && m.getParameterCount() == 2) {
                        m.invoke(sAtm, taskId, b);
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (pkg != null && !pkg.isEmpty()) {
                Intent li = sCtx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode, li.getComponent());
                    sMStartActivity.invoke(sAtm, null, launchCallingPackage(), null, li, null, null, null, 0, 0, null, opts.toBundle());
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean removeTaskById(int taskId) {
        if (taskId <= 0 || sAtm == null) return false;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("removeTask") && m.getParameterCount() == 1) {
                    Object r = m.invoke(sAtm, taskId);
                    return !(r instanceof Boolean) || (Boolean) r;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String runCmdOut(String... cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int lines = 0;
                while ((line = br.readLine()) != null && lines < 120) {
                    sb.append(line).append('\n');
                    lines++;
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static void refreshSysStringsIfNeeded(boolean wifiOn) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastSysCheckMs < 2500L) return;
        sLastSysCheckMs = now;

        String ssid = "";
        if (wifiOn) {
            try {
                String ws = runCmdOut("cmd", "wifi", "status");
                int idx = ws.indexOf("Wifi is connected to \"");
                if (idx >= 0) {
                    int s = idx + "Wifi is connected to \"".length();
                    int e = ws.indexOf('"', s);
                    if (e > s) ssid = ws.substring(s, e);
                }
            } catch (Throwable ignored) {}
        }
        sCachedWifiSsid = ssid;

        String btDev = "";
        boolean btOn = sCachedBtOn;
        try {
            String dump = runCmdOut("dumpsys", "-t", "2", "bluetooth_manager");
            boolean foundState = false;
            for (String line : dump.split("\n")) {
                String value = line.trim();
                if (!foundState && value.startsWith("State:")) {
                    btOn = "ON".equals(value.substring(6).trim());
                    foundState = true;
                    if (!btOn) break;
                }
                if (value.contains("mActiveDevice:") && !value.endsWith("null")) {
                    int start = value.indexOf('(');
                    int end = value.indexOf(')', start + 1);
                    if (start >= 0 && end > start) {
                        btDev = value.substring(start + 1, end).trim();
                        break;
                    }
                }
                if (value.startsWith("Connected:") || value.contains("state=CONNECTED")) {
                    int start = value.indexOf("name=");
                    if (start >= 0) {
                        int end = value.indexOf(',', start);
                        btDev = (end > start ? value.substring(start + 5, end) : value.substring(start + 5)).trim();
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {}
        sCachedBtOn = btOn;
        sCachedBtName = btDev;
    }

    private static void writeSysStatusToParcel(Parcel reply) {
        if (reply == null) return;
        boolean wifiOn = false;
        boolean airplaneOn = false;
        boolean powerSaveOn = false;
        try {
            WifiManager wm = (WifiManager) sCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) wifiOn = wm.isWifiEnabled();
        } catch (Throwable ignored) {}
        try {
            airplaneOn = Settings.Global.getInt(sCtx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Throwable ignored) {}
        try {
            PowerManager pm = (PowerManager) sCtx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) powerSaveOn = pm.isPowerSaveMode();
        } catch (Throwable ignored) {}

        refreshSysStringsIfNeeded(wifiOn);
        reply.writeInt(wifiOn ? 1 : 0);
        reply.writeString(sCachedWifiSsid);
        reply.writeInt(sCachedBtOn ? 1 : 0);
        reply.writeString(sCachedBtName);
        reply.writeInt(airplaneOn ? 1 : 0);
        reply.writeInt(powerSaveOn ? 1 : 0);
    }

    private static boolean execSysAction(int act, int arg, String str) {
        sLastSysCheckMs = 0L;
        try {
            if (act == 1) { // Wi-Fi toggle
                WifiManager wm = (WifiManager) sCtx.getSystemService(Context.WIFI_SERVICE);
                boolean target = (arg < 0) ? (wm == null || !wm.isWifiEnabled()) : (arg == 1);
                runCmdOut("cmd", "wifi", "set-wifi-enabled", target ? "enabled" : "disabled");
                return true;
            } else if (act == 2) { // Bluetooth toggle
                boolean target = (arg < 0) ? !sCachedBtOn : (arg == 1);
                runCmdOut("cmd", "bluetooth_manager", target ? "enable" : "disable");
                return true;
            } else if (act == 3) { // Airplane toggle
                boolean cur = Settings.Global.getInt(sCtx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                boolean target = (arg < 0) ? !cur : (arg == 1);
                runCmdOut("cmd", "connectivity", "airplane-mode", target ? "enable" : "disable");
                return true;
            } else if (act == 4) { // Power saver toggle
                PowerManager pm = (PowerManager) sCtx.getSystemService(Context.POWER_SERVICE);
                boolean target = (arg < 0) ? (pm == null || !pm.isPowerSaveMode()) : (arg == 1);
                boolean done = false;
                if (pm != null) {
                    try {
                        Object res = PowerManager.class.getMethod("setPowerSaveModeEnabled", boolean.class).invoke(pm, target);
                        done = !(res instanceof Boolean) || (Boolean) res;
                    } catch (Throwable ignored) {}
                }
                if (!done) {
                    Settings.Global.putInt(sCtx.getContentResolver(), "low_power", target ? 1 : 0);
                }
                return true;
            } else if (act == 5 && str != null && !str.isEmpty()) { // Open settings action on sVdId
                Intent i = new Intent(str);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                int targetDid = (arg >= 0) ? arg : (sVdId > 0 ? sVdId : 0);
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(targetDid);
                sMStartActivity.invoke(sAtm, null, launchCallingPackage(), null, i, null, null, null, 0, 0, null, opts.toBundle());
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
