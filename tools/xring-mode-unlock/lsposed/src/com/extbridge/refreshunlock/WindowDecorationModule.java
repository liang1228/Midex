package com.extbridge.refreshunlock;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.provider.Settings;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.extbridge.refreshunlock.RefreshUnlockModule.requireField;
import static com.extbridge.refreshunlock.RefreshUnlockModule.requireMethod;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** HyperOS WM Shell adapter for ExtBridge desktop windows. */
final class WindowDecorationModule {
    private static final String DECOR = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.";
    private final Map<Integer, Decoration> decorations = new ConcurrentHashMap<>();
    private final Map<Integer, Object> controllers = new ConcurrentHashMap<>();
    private final Executor background = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ClassLoader loader;
    private Context context;
    private Executor executor;
    private volatile boolean square;
    private volatile boolean caption;
    private boolean hooksReady;
    private Object repository;
    private String lastTasks = "";
    private final boolean integrated;
    private volatile boolean active;

    WindowDecorationModule(boolean integrated) { this.integrated = integrated; }

    void install(ClassLoader classLoader) {
        loader = classLoader;
        try {
            Class<?> organizer = XposedHelpers.findClass("com.android.wm.shell.ShellTaskOrganizer", loader);
            Class<?> appearedInfo = XposedHelpers.findClass("android.window.TaskAppearedInfo", loader);
            Class<?> repositoryClass = XposedHelpers.findClass(
                    "com.android.wm.shell.multitasking.common.taskmanager.MultiTaskingTaskRepository", loader);
            Class<?> task = XposedHelpers.findClass(
                    "com.android.wm.shell.multitasking.common.taskmanager.MiuiFreeformModeTaskInfo", loader);
            Class<?> transaction = SurfaceControl.Transaction.class;
            Class<?> controller = XposedHelpers.findClass(DECOR + "MiuiDecorationController", loader);
            Class<?> base = XposedHelpers.findClass(DECOR + "MiuiDecorationBase", loader);
            Class<?> windowTransaction = XposedHelpers.findClass("android.window.WindowContainerTransaction", loader);
            Class<?> windowToken = XposedHelpers.findClass("android.window.WindowContainerToken", loader);
            requireField(organizer, "mTasks", android.util.SparseArray.class);
            requireField(controller, "mRunningTaskInfo", ActivityManager.RunningTaskInfo.class);
            requireField(base, "mMiuiDecorationController", controller);
            requireMethod(organizer, "applyTransaction", void.class, windowTransaction);
            requireMethod(repositoryClass, "getMiuiFreeformTaskInfo", task, int.class);
            requireMethod(controller, "relayout", void.class, ActivityManager.RunningTaskInfo.class, String.class);
            if (task.getMethod("getTaskInfo").getReturnType() != ActivityManager.RunningTaskInfo.class)
                throw new IllegalStateException("Unexpected freeform task info type");
            requireMethod(transaction, "setCornerRadius", transaction, SurfaceControl.class, float.class);
            requireMethod(windowTransaction, "setMiWindowCornerRadii", windowTransaction, windowToken, float[].class);
            requireMethod(windowTransaction, "addInsetsSource", windowTransaction, windowToken, IBinder.class,
                    int.class, int.class, Rect.class, Rect[].class, int.class);
            requireMethod(windowTransaction, "removeInsetsSource", windowTransaction, windowToken, IBinder.class,
                    int.class, int.class);
            XposedBridge.hookAllConstructors(organizer, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !hooksReady) return;
                    for (Object arg : p.args) if (arg instanceof Executor) initialize((Executor) arg);
                }
            });
            XposedBridge.hookMethod(requireMethod(organizer, "onTaskAppeared", void.class, appearedInfo), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) appeared(p.thisObject, p.args[0]);
                }
            });
            XposedBridge.hookMethod(requireMethod(organizer, "onTaskInfoChanged", void.class,
                    ActivityManager.RunningTaskInfo.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || executor == null) return;
                    Object tasks = XposedHelpers.getObjectField(p.thisObject, "mTasks");
                    Object appeared = XposedHelpers.callMethod(tasks, "get", ((ActivityManager.RunningTaskInfo) p.args[0]).taskId);
                    if (appeared != null) appeared(p.thisObject, appeared);
                }
            });
            XposedBridge.hookMethod(requireMethod(organizer, "onTaskVanished", void.class,
                    ActivityManager.RunningTaskInfo.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (executor == null) return;
                    int id = ((ActivityManager.RunningTaskInfo) p.args[0]).taskId;
                    executor.execute(() -> {
                        Decoration d = decorations.remove(id);
                        if (d != null) d.close();
                        controllers.remove(id);
                        publishTasks();
                    });
                }
            });
            XposedBridge.hookAllConstructors(repositoryClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) repository = p.thisObject;
                }
            });
            XC_MethodHook corners = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!square || p.args.length < 2 || !(p.args[0] instanceof SurfaceControl)) return;
                    boolean arrayArgument = p.args.length == 2 && (p.args[1] == null || p.args[1] instanceof float[]);
                    if (!arrayArgument) {
                        for (int i = 1; i < p.args.length; i++) if (!(p.args[i] instanceof Float)) return;
                    }
                    for (Decoration d : decorations.values()) {
                        if (d.parent != null && eligibleTask(d.task)
                                && Boolean.TRUE.equals(XposedHelpers.callMethod(p.args[0], "isSameSurface", d.parent))) {
                            if (arrayArgument) {
                                float[] requested = (float[]) p.args[1];
                                if (requested != null) for (float radius : requested)
                                    if (radius > 0) { d.originalRadii = requested.clone(); break; }
                                p.args[1] = new float[8];
                            } else {
                                float x = ((Number) p.args[1]).floatValue();
                                float y = p.args.length > 2 ? ((Number) p.args[2]).floatValue() : x;
                                if (x > 0 || y > 0) d.originalRadii = new float[]{x, y, x, y, x, y, x, y};
                                for (int i = 1; i < p.args.length; i++) p.args[i] = 0f;
                            }
                            return;
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(transaction, "setCornerRadius", corners);
            XposedBridge.hookMethod(requireMethod(transaction, "setMiCornerRadii", transaction,
                    SurfaceControl.class, float[].class), corners);
            XposedBridge.hookAllConstructors(controller, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !hooksReady) return;
                    rememberController(p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(controller, "relayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (hooksReady && !p.hasThrowable()) rememberController(p.thisObject);
                }
            });
            XposedBridge.hookMethod(requireMethod(controller, "close", void.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!hooksReady) return;
                    ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo)
                            XposedHelpers.getObjectField(p.thisObject, "mRunningTaskInfo");
                    controllers.remove(task.taskId, p.thisObject);
                }
            });
            XC_MethodHook radius = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!square) return;
                    ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo)
                            XposedHelpers.callMethod(p.thisObject, "getTaskInfo");
                    Decoration d = decorations.get(info.taskId);
                    if (eligibleTask(d == null ? info : d.task)) p.setResult(0f);
                }
            };
            for (String name : new String[]{"getCornerRadius", "getMiWindowCornerRadiusX", "getMiWindowCornerRadiusY"})
                XposedBridge.hookMethod(requireMethod(task, name, float.class), radius);
            Method indexedRadius = XposedHelpers.findMethodExactIfExists(task, "getCornerRadius", int.class);
            if (indexedRadius != null && indexedRadius.getReturnType() == float.class)
                XposedBridge.hookMethod(indexedRadius, radius);
            // Hide the OEM handle only after our caption surface and its inset were installed.
            XposedBridge.hookMethod(requireMethod(base, "updateVisibility", void.class, transaction), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!caption || p.hasThrowable()) return;
                    Object c = XposedHelpers.getObjectField(p.thisObject, "mMiuiDecorationController");
                    ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo)
                            XposedHelpers.getObjectField(c, "mRunningTaskInfo");
                    Decoration d = decorations.get(task.taskId);
                    if (d != null && d.host != null && p.args.length == 1) d.hideOem(p.args[0]);
                }
            });
            hooksReady = true;
            log("window hooks installed; waiting for WM Shell");
        } catch (Throwable error) {
            log("unsupported window framework: " + error);
        }
    }

    private void rememberController(Object controller) {
        ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo)
                XposedHelpers.getObjectField(controller, "mRunningTaskInfo");
        controllers.put(task.taskId, controller);
    }

    private synchronized void initialize(Executor shell) {
        if (context != null) return;
        try {
            Context ctx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader), "currentApplication");
            if (ctx == null) return;
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) { executor.execute(() -> updateSettings()); }
            };
            context = ctx;
            executor = shell;
            for (String key : new String[]{WindowDecorationContract.SQUARE, WindowDecorationContract.CAPTION,
                    WindowDecorationContract.REQUEST, Settings.Global.BOOT_COUNT})
                ctx.getContentResolver().registerContentObserver(Settings.Global.getUriFor(key), false, observer);
            shell.execute(() -> updateSettings());
        } catch (Throwable error) { log("window observer unavailable: " + error); }
    }

    private void updateSettings() {
        if (integrated && !RefreshUnlockModule.integratedEnabled(context.getContentResolver())) return;
        try {
            active = true;
            square = Settings.Global.getInt(context.getContentResolver(), WindowDecorationContract.SQUARE, 0) == 1;
            caption = Settings.Global.getInt(context.getContentResolver(), WindowDecorationContract.CAPTION, 0) == 1;
            for (Decoration d : decorations.values()) {
                d.refreshStyle = true;
                update(d.organizer, d.task, d.parent);
                Object controller = controllers.get(d.task.taskId);
                if (controller != null) XposedHelpers.callMethod(controller, "relayout", d.task, "ExtBridge settings");
            }
            int boot = boot();
            String nonce = Settings.Global.getString(context.getContentResolver(), WindowDecorationContract.REQUEST);
            Settings.Global.putString(context.getContentResolver(), WindowDecorationContract.STATUS,
                    WindowDecorationContract.report(boot, square, caption, nonce == null ? "initial" : nonce));
            publishTasks();
        } catch (Throwable error) {
            log("window settings failed: " + error);
            Settings.Global.putString(context.getContentResolver(), WindowDecorationContract.STATUS, "error");
        }
    }

    private int boot() { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }

    private boolean eligibleTask(Object task) {
        if (!active || !(task instanceof ActivityManager.RunningTaskInfo)) return false;
        ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) task;
        try {
            int displayId = XposedHelpers.getIntField(info, "displayId");
            Display display = context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(displayId);
            String pkg = info.baseActivity == null ? null : info.baseActivity.getPackageName();
            int mode = ((Number) XposedHelpers.callMethod(info, "getWindowingMode")).intValue();
            return display != null && displayId > 0
                    && WindowDecorationContract.isDesktopTask(((Number) XposedHelpers.callMethod(display, "getType")).intValue(),
                            display.getName(), mode, pkg);
        } catch (Throwable ignored) { return false; }
    }

    private void appeared(Object organizer, Object appeared) {
        if (executor == null) return;
        ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) XposedHelpers.callMethod(appeared, "getTaskInfo");
        SurfaceControl leash = (SurfaceControl) XposedHelpers.callMethod(appeared, "getLeash");
        executor.execute(() -> update(organizer, info, leash));
    }

    private void update(Object organizer, ActivityManager.RunningTaskInfo task, Object leash) {
        Decoration d = decorations.get(task.taskId);
        try {
            if (!eligibleTask(task)) {
                if (d != null) {
                    d.task = task;
                    if (d.parent != null) d.applyCorners();
                    d.close(); decorations.remove(task.taskId); publishTasks();
                }
                return;
            }
            if (d == null) {
                d = new Decoration(organizer);
                decorations.put(task.taskId, d);
            }
            d.task = task;
            if (d.parent != null && !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isSameSurface", d.parent))) d.close();
            d.parent = leash;
            if (!square && !caption && !d.styleApplied && d.host == null) return;
            d.update(task);
            if (square) {
                Decoration current = d;
                int revision = ++d.styleRevision;
                // HyperOS commits a final task-leash radius after the task-info callback.
                handler.postDelayed(() -> executor.execute(() -> {
                    if (decorations.get(task.taskId) != current || current.styleRevision != revision
                            || !square || !eligibleTask(current.task)) return;
                    try { current.applyCorners(); } catch (Throwable error) { log("late corner update failed: " + error); }
                }), 1200);
            }
        } catch (Throwable error) {
            if (d != null) d.close();
            publishTasks();
            log("window decoration unavailable; keep ordinary controls: " + error);
        }
    }

    private void publishTasks() {
        if (!active) return;
        ArrayList<Integer> ids = new ArrayList<>();
        for (Decoration d : decorations.values()) if (d.host != null) ids.add(d.task.taskId);
        Collections.sort(ids);
        StringBuilder report = new StringBuilder("1:").append(boot()).append(':');
        for (int id : ids) { if (report.charAt(report.length() - 1) != ':') report.append(','); report.append(id); }
        if (!report.toString().equals(lastTasks)) {
            Settings.Global.putString(context.getContentResolver(), WindowDecorationContract.TASKS, report.toString());
            lastTasks = report.toString();
        }
    }

    private Object make(String name, Object... args) {
        return XposedHelpers.newInstance(XposedHelpers.findClass(name, loader), args);
    }

    private final class Decoration {
        final Object organizer;
        final Binder owner = new Binder();
        volatile ActivityManager.RunningTaskInfo task;
        Object container;
        Object host;
        volatile Object parent;
        DesktopCaptionView view;
        boolean inset;
        boolean refreshStyle = true;
        boolean styleApplied;
        volatile float[] originalRadii;
        int styleRevision;
        int width;
        int density;
        int displayId = -1;
        final Rect bounds = new Rect();

        Decoration(Object value) { organizer = value; }

        void update(ActivityManager.RunningTaskInfo info) {
            task = info;
            Configuration config = (Configuration) XposedHelpers.getObjectField(task, "configuration");
            int nextDisplayId = XposedHelpers.getIntField(task, "displayId");
            Rect next = (Rect) XposedHelpers.callMethod(XposedHelpers.getObjectField(config, "windowConfiguration"), "getBounds");
            Object leash = parent;
            boolean visible = XposedHelpers.getBooleanField(task, "isVisible");
            if (next.isEmpty() || !(leash instanceof SurfaceControl) || !((SurfaceControl) leash).isValid()) { close(); return; }
            if (host != null && (displayId != nextDisplayId || density != config.densityDpi
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isSameSurface", parent)))) close();
            parent = leash;
            if (refreshStyle || square) { applyCorners(); refreshStyle = false; }
            bounds.set(next);
            if (!caption || !visible) { closeCaption(); publishTasks(); return; }
            Display display = context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(nextDisplayId);
            if (display == null) return;
            int height = Math.round(32 * config.densityDpi / 160f);
            if (host == null) {
                Context ui = context.createDisplayContext(display).createConfigurationContext(config);
                Object builder = make("android.view.SurfaceControl$Builder");
                XposedHelpers.callMethod(builder, "setName", "ExtBridgeCaption task=" + task.taskId);
                XposedHelpers.callMethod(builder, "setContainerLayer");
                XposedHelpers.callMethod(builder, "setParent", leash);
                container = XposedHelpers.callMethod(builder, "build");
                Object wwm = make("android.view.WindowlessWindowManager", new Configuration(config), container, null);
                host = make("android.view.SurfaceControlViewHost", ui, display, wwm, "ExtBridgeCaption");
                view = new DesktopCaptionView(ui, (action, rect) -> command(task.taskId, action, rect),
                        () -> new Rect(bounds), display);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(next.width(), height, 2,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT);
                lp.setTitle("ExtBridgeCaption task=" + task.taskId);
                XposedHelpers.callMethod(lp, "setTrustedOverlay");
                XposedHelpers.callMethod(host, "setView", view, lp);
                density = config.densityDpi;
                displayId = nextDisplayId;
                width = -1;
                log("caption created task=" + task.taskId + " display=" + displayId);
            }
            if (width != next.width()) {
                XposedHelpers.callMethod(host, "relayout", next.width(), height);
                Object wct = make("android.window.WindowContainerTransaction");
                // Attached-container coordinates follow task movement and virtual-display migration.
                XposedHelpers.callMethod(wct, "addInsetsSource", XposedHelpers.getObjectField(task, "token"), owner, 0,
                        WindowInsets.Type.captionBar(), Insets.of(0, height, 0, 0), new Rect[0],
                        XposedHelpers.getStaticIntField(XposedHelpers.findClass("android.view.InsetsSource", loader),
                                "FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR"));
                apply(wct);
                inset = true;
                width = next.width();
            }
            view.update(task.baseActivity.getPackageName(), XposedHelpers.getBooleanField(task, "isFocused"),
                    next.width() >= display.getWidth() - 12);
            Object tx = make("android.view.SurfaceControl$Transaction");
            try {
                XposedHelpers.callMethod(tx, "setPosition", container, 0f, 0f);
                XposedHelpers.callMethod(tx, "setLayer", container, 10000);
                XposedHelpers.callMethod(tx, "show", container);
                hideOem(tx);
                XposedHelpers.callMethod(tx, "apply");
            } finally { XposedHelpers.callMethod(tx, "close"); }
            publishTasks();
        }

        void hideOem(Object tx) {
            Object controller = controllers.get(task.taskId);
            if (controller == null) return;
            for (String field : new String[]{"mTopDecoration", "mBottomDecoration"}) {
                Object decor = XposedHelpers.getObjectField(controller, field);
                if (decor == null) continue;
                Object surface = XposedHelpers.getObjectField(decor, "mCaptionContainerSurface");
                if (surface instanceof SurfaceControl && ((SurfaceControl) surface).isValid())
                    XposedHelpers.callMethod(tx, "hide", surface);
            }
        }

        void applyCorners() {
            float radius = 0;
            if (!square || !eligibleTask(task)) {
                Object freeform = repository == null ? null : XposedHelpers.callMethod(repository, "getMiuiFreeformTaskInfo", task.taskId);
                if (freeform != null) {
                    try {
                        radius = ((Number) XposedBridge.invokeOriginalMethod(
                                XposedHelpers.findMethodExact(freeform.getClass(), "getCornerRadius"), freeform, new Object[0])).floatValue();
                    } catch (Throwable error) { throw new IllegalStateException("Cannot restore OEM corners", error); }
                }
            }
            float[] radii = new float[8];
            java.util.Arrays.fill(radii, radius);
            if ((!square || !eligibleTask(task)) && originalRadii != null) radii = originalRadii.clone();
            Object tx = make("android.view.SurfaceControl$Transaction");
            try {
                XposedHelpers.callMethod(tx, "setCornerRadius", parent, radius);
                XposedHelpers.callMethod(tx, "setMiCornerRadii", parent, radii);
                XposedHelpers.callMethod(tx, "apply");
            } finally { XposedHelpers.callMethod(tx, "close"); }
            Object wct = make("android.window.WindowContainerTransaction");
            XposedHelpers.callMethod(wct, "setMiWindowCornerRadii", XposedHelpers.getObjectField(task, "token"), radii);
            apply(wct);
            styleApplied = square && eligibleTask(task);
        }

        void apply(Object wct) {
            XposedHelpers.callMethod(organizer, "applyTransaction", wct);
        }

        void closeCaption() {
            if (host != null) {
                try { XposedHelpers.callMethod(host, "release"); } catch (Throwable ignored) {}
                host = null; view = null;
            }
            if (container != null) {
                Object tx = make("android.view.SurfaceControl$Transaction");
                try {
                    XposedHelpers.callMethod(tx, "remove", container);
                    XposedHelpers.callMethod(tx, "apply");
                } catch (Throwable ignored) {}
                finally { XposedHelpers.callMethod(tx, "close"); ((SurfaceControl) container).release(); container = null; }
            }
            if (inset && task != null) {
                try {
                    Object wct = make("android.window.WindowContainerTransaction");
                    XposedHelpers.callMethod(wct, "removeInsetsSource", XposedHelpers.getObjectField(task, "token"), owner, 0, WindowInsets.Type.captionBar());
                    apply(wct);
                } catch (Throwable ignored) {}
                inset = false;
            }
            width = 0;
        }

        void close() { closeCaption(); }

        void command(int taskId, int action, Rect rect) {
            background.execute(() -> {
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    Bundle bundle = context.getContentResolver().call(
                            Uri.parse("content://com.extbridge.helper.provider"), "getBinder", null, null);
                    IBinder binder = bundle == null ? null : bundle.getBinder("cb");
                    if (binder == null) return;
                    Rect r = rect == null ? new Rect() : rect;
                    data.writeInt(taskId); data.writeInt(action);
                    data.writeInt(r.left); data.writeInt(r.top); data.writeInt(r.right); data.writeInt(r.bottom);
                    if (!binder.transact(42, data, reply, 0) || reply.readInt() != 1)
                        log("window command rejected task=" + taskId + " action=" + action);
                } catch (Throwable error) { log("window command failed: " + error); }
                finally { data.recycle(); reply.recycle(); }
            });
        }
    }

    private static void log(String message) { XposedBridge.log("ExtBridge-Windows: " + message); }
}
