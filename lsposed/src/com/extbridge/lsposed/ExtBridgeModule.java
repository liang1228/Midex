package com.extbridge.lsposed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.extbridge.daemon.ExtBridge;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口（作用域：系统框架 / android）。
 *
 * 为什么可以这样：
 *  - ExtBridge 的守护逻辑本来就需要 shell/root 权限才能调用 DisplayManager、
 *    SurfaceControl、IActivityTaskManager 等隐藏 API；
 *  - 把同一份代码注入 system_server 后，这些调用天然具有系统框架权限，
 *    不需要 app_process，也不需要每次开机用 su 拉起；
 *  - 驱动信息采集（DriverProbe）也能以 system uid 读取大部分 sysfs/debugfs。
 *
 * 安全/稳定性：
 *  - 只处理 packageName/processName 都为 "android" 的 system_server；
 *  - 延迟到 startOtherServices 之后再启动，避免 boot 阶段抢服务；
 *  - 支持 /data/adb/extbridge/disable_lsposed 紧急开关；
 *  - 绝不调用 System.exit，停止渲染只保留 system_server 进程。
 */
public class ExtBridgeModule implements IXposedHookLoadPackage {
    private static final String TAG = "ExtBridge-LSPosed";
    private static final String KILL_SWITCH = "/data/adb/extbridge/disable_lsposed";

    private static Handler sMain;
    private static boolean sScheduled;
    private static boolean sStarted;
    private static int sAttempts;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) return;
        if (!"android".equals(lpparam.packageName)) return;
        if (!"android".equals(lpparam.processName)) return;
        if (android.os.Process.myUid() != 1000) return;
        if (new File(KILL_SWITCH).exists()) {
            log("kill switch present, skip system_server bridge");
            return;
        }

        log("module loaded in system_server, waiting for boot services");
        try {
            // 首选：等 SystemServer.startOtherServices 完成后再启动，服务基本就绪。
            Class<?> systemServer = XposedHelpers.findClass("com.android.server.SystemServer", lpparam.classLoader);
            XposedBridge.hookAllMethods(systemServer, "startOtherServices", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    log("startOtherServices finished, scheduling bridge in 4s");
                    schedule(4000);
                }
            });
        } catch (Throwable t) {
            log("hook startOtherServices failed: " + t);
        }
        // 兜底：即使 OEM 改了方法名，也在 15 秒后尝试。
        schedule(15000);
    }

    private static synchronized void schedule(long delayMs) {
        if (sStarted) return;
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
        if (sScheduled) return;
        sScheduled = true;
        sMain.postDelayed(ExtBridgeModule::boot, delayMs);
    }

    private static synchronized void boot() {
        sScheduled = false;
        if (sStarted) return;
        sAttempts++;
        if (sAttempts > 30) {
            log("giving up after " + sAttempts + " attempts");
            return;
        }
        try {
            if (new File(KILL_SWITCH).exists()) {
                log("kill switch present during boot, abort");
                return;
            }
            if (!servicesReady(sAttempts)) {
                schedule(2000);
                return;
            }
            Context sys = systemContext();
            if (sys == null) {
                schedule(2000);
                return;
            }
            sStarted = true;
            boolean ok = ExtBridge.startInSystemServer(sys);
            log("ExtBridge.startInSystemServer -> " + ok);
            if (!ok) {
                sStarted = false;
                schedule(4000);
            }
        } catch (Throwable t) {
            log("boot failed: " + t);
            schedule(4000);
        }
    }

    private static boolean servicesReady(int attempt) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method get = sm.getMethod("getService", String.class);
            return get.invoke(null, "display") != null
                    && get.invoke(null, "activity") != null
                    && get.invoke(null, "package") != null
                    && get.invoke(null, "window") != null;
        } catch (Throwable t) {
            log("servicesReady check failed: " + t);
            return false;
        }
    }

    private static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object current = at.getMethod("currentActivityThread").invoke(null);
            if (current == null) return null;
            Object ctx = at.getMethod("getSystemContext").invoke(current);
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable t) {
            log("getSystemContext failed: " + t);
            return null;
        }
    }

    private static void log(String msg) {
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            android.util.Log.i(TAG, msg);
        }
    }
}
