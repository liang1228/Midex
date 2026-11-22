package com.extbridge.helper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BridgeClient {
    public static class DesktopWindow {
        public int displayId;
        public int taskId;
        public String pkg;
        public android.graphics.Rect bounds;
    }

    public static DesktopWindow getDesktopWindow() {
        IBinder binder = getService();
        if (binder == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (!binder.transact(43, data, reply, 0) || reply.readInt() != 1) return null;
            DesktopWindow window = new DesktopWindow();
            window.displayId = reply.readInt(); window.taskId = reply.readInt(); window.pkg = reply.readString();
            window.bounds = new android.graphics.Rect(reply.readInt(), reply.readInt(), reply.readInt(), reply.readInt());
            return window;
        } catch (Throwable ignored) { return null; }
        finally { data.recycle(); reply.recycle(); }
    }

    public static boolean controlWindow(int taskId, int action, android.graphics.Rect bounds) {
        IBinder binder = getService();
        if (binder == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        android.graphics.Rect rect = bounds == null ? new android.graphics.Rect() : bounds;
        try {
            data.writeInt(taskId); data.writeInt(action);
            data.writeInt(rect.left); data.writeInt(rect.top); data.writeInt(rect.right); data.writeInt(rect.bottom);
            return binder.transact(42, data, reply, 0) && reply.readInt() == 1;
        } catch (Throwable ignored) { return false; }
        finally { data.recycle(); reply.recycle(); }
    }
    public static final int SYS_WIFI = 1;
    public static final int SYS_BT = 2;
    public static final int SYS_AIRPLANE = 3;
    public static final int SYS_POWER_SAVE = 4;
    public static final int SYS_OPEN_SETTINGS = 5;

    public static class TaskEntry {
        public int taskId;
        public String pkg;
        public int flags;
        public boolean onExt() { return (flags & 1) != 0; }
        public boolean isRunning() { return (flags & 2) != 0; }
        public boolean isTopExt() { return (flags & 4) != 0; }
    }

    public static class SysStatus {
        public boolean valid;
        public boolean wifiOn;
        public String wifiSsid = "";
        public boolean btOn;
        public String btName = "";
        public boolean airplaneOn;
        public boolean powerSaveOn;
    }

    private static volatile Bitmap sCachedWpBitmap;
    public static class Status {
        public boolean connected;
        public int extId = -1;
        public int vdId = -1;
        public int physW = 0;
        public int physH = 0;
        public float physFps = 60f;
        public boolean physicalModeConfirmed;
        public boolean drmReadback;
        public int targetPhysW;
        public int targetPhysH;
        public float targetPhysFps;
        public long configRequestId;
        public long confirmedConfigRequestId;
        public String configStage = "";
        public int virtW = 2560;
        public int virtH = 1440;
        public int virtDpi = 240;
        public float virtFps = 60f;
        public float requestedFps = 0f;
        public int inputToExt = 1;
        public int cursorX = 1280;
        public int cursorY = 720;
        public int extInputCnt = 0;
        public String modesRaw = "";
        public String renderMode = "NONE";
        public int mirrorMode = 0;
        public int fullscreenPolicy = 0;
        public String hostMode = "";
        public String daemonVersion = "";

        public boolean hasPrivilegedBackend() {
            return "root".equals(hostMode) || "system_server".equals(hostMode);
        }
    }

    public static class RuntimeInfo {
        public boolean valid;
        public int hostMode;
        public String hostModeName = "";
        public int uid;
        public int pid;
        public String daemonVersion = "";
        public String selinux = "";
    }

    private static volatile IBinder sBinder;
    private static volatile Runnable sOnBinderReady;
    private static final java.util.concurrent.atomic.AtomicBoolean sRequestPending = new java.util.concurrent.atomic.AtomicBoolean();

    public static void setOnBinderReady(Runnable r) {
        sOnBinderReady = r;
    }

    public static void setBinder(IBinder b) {
        if (b != null) {
            sBinder = b;
            Runnable r = sOnBinderReady;
            if (r != null) r.run();
        }
    }

    public static void requestBinder(android.content.Context ctx) {
        // app_process has no registered ActivityThread receiver on some OEM builds.
        // A credential-checked, blocking socket wakes the daemon only on an explicit UI request.
        if (sRequestPending.compareAndSet(false, true)) new Thread(() -> {
            try (android.net.LocalSocket socket = new android.net.LocalSocket()) {
                socket.connect(new android.net.LocalSocketAddress("extbridge.daemon.singleton"));
            } catch (java.io.IOException ignored) {
                // Existing shell / older LSPosed backends still use the broadcasts below.
            } finally { sRequestPending.set(false); }
        }, "ExtBridge-binder-request").start();
        try {
            android.content.Intent i1 = new android.content.Intent("com.extbridge.daemon.NEED_BINDER");
            i1.setPackage("android");
            ctx.sendBroadcast(i1);
            android.content.Intent i2 = new android.content.Intent("com.extbridge.daemon.NEED_BINDER");
            i2.setPackage("com.android.shell");
            ctx.sendBroadcast(i2);
            ctx.sendBroadcast(new android.content.Intent("com.extbridge.daemon.NEED_BINDER"));
        } catch (Throwable ignored) {}
    }

    public static IBinder getService() {
        IBinder b = sBinder;
        if (b != null && b.pingBinder()) return b;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "extbridge");
            if (b != null) sBinder = b;
        } catch (Throwable ignored) {}
        return b;
    }

    public static Status getStatus() {
        Status st = new Status();
        IBinder b = getService();
        if (b == null) return st;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (b.transact(15, data, reply, 0)) {
                st.connected = true;
                st.extId = reply.readInt();
                st.vdId = reply.readInt();
                st.physW = reply.readInt();
                st.physH = reply.readInt();
                st.physFps = reply.readInt() / 100f;
                st.virtW = reply.readInt();
                st.virtH = reply.readInt();
                st.virtDpi = reply.readInt();
                st.virtFps = reply.readInt() / 100f;
                st.inputToExt = reply.readInt();
                st.cursorX = reply.readInt();
                st.cursorY = reply.readInt();
                st.extInputCnt = reply.readInt();
                st.modesRaw = reply.readString();
                st.renderMode = reply.readString();
                if (reply.dataAvail() > 0) {
                    st.mirrorMode = reply.readInt();
                    st.fullscreenPolicy = reply.readInt();
                }
                if (reply.dataAvail() > 0) {
                    st.hostMode = reply.readString();
                }
                if (reply.dataAvail() > 0) {
                    st.daemonVersion = reply.readString();
                }
                st.requestedFps = reply.dataAvail() > 0 ? reply.readInt() / 100f : st.virtFps;
                if (reply.dataAvail() >= 16) {
                    st.physicalModeConfirmed = reply.readInt() != 0;
                    st.targetPhysW = reply.readInt();
                    st.targetPhysH = reply.readInt();
                    st.targetPhysFps = reply.readInt() / 100f;
                }
                if (reply.dataAvail() >= 20) {
                    st.configRequestId = reply.readLong();
                    st.confirmedConfigRequestId = reply.readLong();
                    st.drmReadback = reply.readInt() != 0;
                }
                if (reply.dataAvail() > 0) st.configStage = reply.readString();
            }
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
        return st;
    }

    /** 读取守护进程运行模式（root / shell / system_server）。 */
    public static RuntimeInfo getRuntimeInfo() {
        RuntimeInfo ri = new RuntimeInfo();
        IBinder b = getService();
        if (b == null) return ri;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (b.transact(41, data, reply, 0)) {
                ri.valid = true;
                ri.hostMode = reply.readInt();
                ri.hostModeName = reply.readString();
                ri.uid = reply.readInt();
                ri.pid = reply.readInt();
                ri.daemonVersion = reply.readString();
                ri.selinux = reply.readString();
            }
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
        return ri;
    }

    /** 驱动/硬件信息 JSON（需要守护进程为 root 或 system_server 才能读全）。 */
    public static String getDriverJson() {
        IBinder b = getService();
        if (b == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (b.transact(40, data, reply, 0)) {
                return reply.readString();
            }
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
        return null;
    }

    public static String hostModeLabel(String mode) {
        if ("root".equals(mode)) return "Root 模式";
        if ("system_server".equals(mode)) return "LSPosed 系统框架模式";
        if ("shell".equals(mode)) return "ADB Shell 模式";
        return "未知模式";
    }

    public static int toggleInput() {
        return simpleCall(10);
    }

    public static int setInputMode(int mode) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(mode);
            b.transact(19, d, r, 0);
            return r.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendBack() { simpleOneWay(11); }
    public static void relaunchLauncher() { simpleOneWay(12); }
    public static void sendHome() { simpleOneWay(13); }
    public static void prepareDesktop() { simpleOneWay(44); }
    public static void rebuild() { simpleOneWay(21); }

    public static final long CONFIG_DISPLAY_CHANGED = -2;

    public static String forceHighestExternalRefresh(int expectedExtId) {
        IBinder service = getService();
        if (service == null) return "NOT_CONNECTED";
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInt(expectedExtId);
            return service.transact(50, request, reply, 0) ? reply.readString() : "OLD_DAEMON";
        } catch (RemoteException | RuntimeException e) {
            return "NOT_CONNECTED";
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    /** Positive request ID, zero for a legacy daemon, or a negative error code. */
    public static long applyConfig(int w, int h, int dpi, float fps, int physModeId, int expectedExtId) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(w);
            d.writeInt(h);
            d.writeInt(dpi);
            d.writeInt(Math.round(fps * 100f));
            d.writeInt(physModeId);
            d.writeInt(expectedExtId);
            if (!b.transact(16, d, r, 0)) return -1;
            if (r.readInt() != 1) {
                return r.dataAvail() >= 8 && r.readLong() == CONFIG_DISPLAY_CHANGED
                        ? CONFIG_DISPLAY_CHANGED : -1;
            }
            return r.dataAvail() >= 8 ? r.readLong() : 0;
        } catch (Throwable t) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int[] sendMouse(int actionType, float a1, float a2) {
        IBinder b = getService();
        if (b == null) return null;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(actionType);
            d.writeFloat(a1);
            d.writeFloat(a2);
            b.transact(17, d, r, 0);
            return new int[]{r.readInt(), r.readInt()};
        } catch (Throwable t) {
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendKey(int keyCode, int metaState) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(0);
            d.writeInt(keyCode);
            d.writeInt(metaState);
            d.writeString(null);
            b.transact(18, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendAscii(String txt) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(1);
            d.writeInt(0);
            d.writeInt(0);
            d.writeString(txt);
            b.transact(18, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendPaste(boolean useCtrlV) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(2);
            d.writeInt(useCtrlV ? 1 : 0);
            d.writeInt(0);
            d.writeString(null);
            b.transact(18, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void launchComponent(String pkg, String cls) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeString(pkg);
            d.writeString(cls);
            b.transact(20, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static Bitmap getWallpaperBitmap(boolean force) {
        if (!force && sCachedWpBitmap != null && !sCachedWpBitmap.isRecycled()) {
            return sCachedWpBitmap;
        }
        IBinder b = getService();
        if (b == null) return sCachedWpBitmap;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(force ? 1 : 0);
            if (b.transact(22, d, r, 0)) {
                byte[] bytes = r.createByteArray();
                if (bytes != null && bytes.length > 0) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        sCachedWpBitmap = bmp;
                        return bmp;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return sCachedWpBitmap;
    }

    public static List<TaskEntry> getTasks() {
        List<TaskEntry> out = new ArrayList<>();
        IBinder b = getService();
        if (b == null) return out;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            if (b.transact(23, d, r, 0)) {
                int n = r.readInt();
                for (int i = 0; i < n && i < 20; i++) {
                    TaskEntry te = new TaskEntry();
                    te.taskId = r.readInt();
                    te.pkg = r.readString();
                    te.flags = r.readInt();
                    if (te.pkg != null && !te.pkg.isEmpty()) out.add(te);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return out;
    }

    public static boolean switchToTask(int taskId, String pkg) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            d.writeString(pkg);
            b.transact(24, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean removeTask(int taskId) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            b.transact(25, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static SysStatus getSysStatus() {
        SysStatus st = new SysStatus();
        IBinder b = getService();
        if (b == null) return st;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            if (b.transact(26, d, r, 0)) {
                st.valid = true;
                st.wifiOn = r.readInt() == 1;
                String ws = r.readString();
                st.wifiSsid = ws != null ? ws : "";
                st.btOn = r.readInt() == 1;
                String bn = r.readString();
                st.btName = bn != null ? bn : "";
                st.airplaneOn = r.readInt() == 1;
                st.powerSaveOn = r.readInt() == 1;
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return st;
    }

    public static boolean execSysAction(int act, int arg, String str) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(act);
            d.writeInt(arg);
            d.writeString(str);
            b.transact(27, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    static List<Bundle> connectionRows(boolean wifi, boolean scan) {
        IBinder binder = getService();
        if (binder == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(wifi ? 1 : 0);
            data.writeInt(scan ? 1 : 0);
            return binder.transact(46, data, reply, 0) ? reply.createTypedArrayList(Bundle.CREATOR) : null;
        } catch (RuntimeException | RemoteException ignored) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static boolean changeConnection(boolean wifi, boolean connect, String id) {
        IBinder binder = getService();
        if (binder == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(wifi ? 1 : 0);
            data.writeInt(connect ? 1 : 0);
            data.writeString(id);
            return binder.transact(47, data, reply, 0) && reply.readInt() == 1;
        } catch (RuntimeException | RemoteException ignored) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static boolean joinWifi(String ssid, String security, String password) {
        IBinder binder = getService();
        if (binder == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeString(ssid);
            data.writeString(security);
            data.writeString(password);
            return binder.transact(48, data, reply, 0) && reply.readInt() == 1;
        } catch (RuntimeException | RemoteException ignored) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static boolean answerBluetoothPairing(String address, boolean accept, String pin) {
        IBinder binder = getService();
        if (binder == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeString(address);
            data.writeInt(accept ? 1 : 0);
            data.writeString(pin);
            if (!binder.transact(49, data, reply, 0)) {
                android.util.Log.w("ExtBridge", "Bluetooth pairing reply RPC unsupported");
                return false;
            }
            return reply.readInt() == 1;
        } catch (RuntimeException | RemoteException failure) {
            android.util.Log.w("ExtBridge", "Bluetooth pairing reply RPC failed", failure);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static int setMirrorMode(int mode) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(mode);
            b.transact(28, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int toggleMirrorMode() {
        return setMirrorMode(-1);
    }

    public static int setFullscreenPolicy(int enable) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(enable);
            b.transact(29, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int toggleFullscreenPolicy() {
        return setFullscreenPolicy(-1);
    }

    public static final int APP_MODE_DEFAULT = 0;
    public static final int APP_MODE_LAND_FULL = 1;
    public static final int APP_MODE_PORT_COMPAT = 2;
    public static final int APP_MODE_FREEFORM = 3;

    public static String appModeLabel(int mode) {
        switch (mode) {
            case APP_MODE_LAND_FULL: return "强制横屏全屏";
            case APP_MODE_PORT_COMPAT: return "竖屏居中兼容";
            case APP_MODE_FREEFORM: return "自由小窗模式";
            default: return "桌面默认窗口";
        }
    }

    public static String appModeBadge(int mode) {
        switch (mode) {
            case APP_MODE_LAND_FULL: return "横屏全屏";
            case APP_MODE_PORT_COMPAT: return "竖屏兼容";
            case APP_MODE_FREEFORM: return "自由小窗";
            default: return "";
        }
    }

    public static int prepareAppLaunch(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        IBinder b = getService();
        if (b == null) return 0;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeString(pkg);
            b.transact(30, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return 0;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static Map<String, Integer> getAllAppModes() {
        Map<String, Integer> map = new HashMap<>();
        IBinder b = getService();
        if (b == null) return map;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            b.transact(31, d, r, 0);
            String s = r.readString();
            if (s != null && !s.isEmpty()) {
                for (String item : s.split(";")) {
                    int eq = item.indexOf('=');
                    if (eq > 0 && eq < item.length() - 1) {
                        String pkg = item.substring(0, eq).trim();
                        int mode = Integer.parseInt(item.substring(eq + 1).trim());
                        if (!pkg.isEmpty() && mode >= 0 && mode <= 3) {
                            map.put(pkg, mode);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return map;
    }

    public static boolean setAppMode(String pkg, int mode, boolean restart) {
        if (pkg == null || pkg.isEmpty()) return false;
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeString(pkg);
            d.writeInt(mode);
            d.writeInt(restart ? 1 : 0);
            b.transact(32, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean resetAllAppModes() {
        return simpleCall(33) == 1;
    }

    private static int simpleCall(int code) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            b.transact(code, d, r, 0);
            return r.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static void simpleOneWay(int code) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        try {
            b.transact(code, d, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
        }
    }
}
