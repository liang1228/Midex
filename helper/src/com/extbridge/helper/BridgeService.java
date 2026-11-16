package com.extbridge.helper;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BridgeService extends Service {
    private static final String CH_ID = "extbridge_ctrl_v3";
    private static final int NOTIF_ID = 301;
    private static volatile BridgeService sInstance;
    private static ViewGroup sLauncherHost;
    private static int sLauncherHostDisplayId = -1;

    private Handler mHandler;
    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private NotificationManager mNm;
    private DisplayManager mDm;
    private AudioManager mAm;

    private int mAttachedVdId = -1;
    private int mOverlayW = 0;
    private int mOverlayH = 0;
    private int mOverlayDpi = 0;
    private Context mExtCtx;
    private WindowManager mExtWm;
    private boolean mUsingA11yOverlay = false;
    private boolean mInLauncherHost = false;
    private boolean mLastObservedA11y = false;
    private int mWinType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    // Dock window (macOS style centered frosted glass)
    private LinearLayout mDockRoot;
    private WindowManager.LayoutParams mDockLp;
    private boolean mDockHidden = false;
    private TextView mRestorePill;
    private WindowManager.LayoutParams mRestoreLp;
    private TextView mBtnToggle;
    private LinearLayout mTaskStrip;
    private View mTaskDivider;
    private LinearLayout mTrayBtn;
    private MiniIconView mTrayWifiIcon;
    private MiniIconView mTrayVolIcon;
    private MiniBatteryView mTrayBattView;
    private TextView mTrayBattPct;

    // Win11 Quick Settings Flyout window (no brightness slider)
    private FrameLayout mPanelRoot;
    private LinearLayout mPanelCard;
    private WindowManager.LayoutParams mPanelLp;
    private boolean mPanelOpen = false;
    private boolean mTrayPressed = false;
    private long mLastTrayUpMs = 0L;
    private long mLastTrayToggleMs = 0L;
    private final Runnable mOutsideDismissTask = () -> {
        long now = SystemClock.uptimeMillis();
        if (mPanelOpen && !mTrayPressed
                && (now - mLastTrayUpMs) > 220L
                && (now - mLastTrayToggleMs) > 220L) {
            setPanelOpen(false);
        }
    };
    private QuickTile mTileWifi;
    private QuickTile mTileBt;
    private QuickTile mTileInput;
    private QuickTile mTileAirplane;
    private QuickTile mTileTouchpad;
    private QuickTile mTilePowerSave;
    private MiniIconView mVolSpeakerIcon;
    private SeekBar mVolSeekBar;
    private boolean mSeekingVol = false;
    private MiniBatteryView mPanelBattView;
    private TextView mPanelBattText;
    private TextView mBtnPanelFullscreen;
    private TextView mBtnPanelMirror;
    private TextView mBtnPanelHideDock;

    // Software mouse cursor window (always topmost)
    private CursorView mCursorView;
    private WindowManager.LayoutParams mCursorLp;
    private boolean mCursorVisible = false;

    private final HashMap<String, Drawable> mIconCache = new HashMap<>();
    private final HashMap<String, String> mLabelCache = new HashMap<>();
    private String mLastTaskSig = "";
    private BridgeClient.Status mLastStatus = new BridgeClient.Status();
    private BridgeClient.SysStatus mLastSys = new BridgeClient.SysStatus();
    private int mBattPct = 100;
    private boolean mBattCharging = false;

    private final BroadcastReceiver mSysRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String act = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                readBatteryIntent(intent);
            }
            if (mHandler != null) {
                mHandler.post(() -> {
                    refreshLocalHardwareViews();
                    if (!Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                        pollRemoteStateAsync();
                    }
                });
            }
        }
    };

    private final Runnable mHideCursorTask = () -> setCursorVisible(false);
    private final Runnable mPollTask = new Runnable() {
        @Override
        public void run() {
            pollRemoteStateAsync();
            mHandler.postDelayed(this, 2200);
        }
    };

    public static void ensureStarted(Context ctx) {
        try {
            Intent i = new Intent(ctx, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {}
    }

    public static void refreshFromReceiver(Context ctx) {
        if (sInstance != null) {
            sInstance.mHandler.post(() -> sInstance.pollRemoteStateAsync());
        } else {
            ensureStarted(ctx);
        }
    }

    public static void moveCursor(float x, float y, boolean pressing) {
        BridgeService svc = sInstance;
        if (svc == null) return;
        if (Looper.myLooper() == svc.mHandler.getLooper()) {
            svc.updateCursorOnExt(x, y, pressing);
        } else {
            svc.mHandler.post(() -> svc.updateCursorOnExt(x, y, pressing));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mHandler = new Handler(Looper.getMainLooper());
        mNm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mDm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        mAm = (AudioManager) getSystemService(AUDIO_SERVICE);

        createNotificationChannel();
        Notification n = buildNotification(BridgeClient.getStatus());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        IntentFilter flt = new IntentFilter();
        flt.addAction(Intent.ACTION_BATTERY_CHANGED);
        flt.addAction("android.media.VOLUME_CHANGED_ACTION");
        flt.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        flt.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        Intent sticky = registerReceiver(mSysRx, flt, RECEIVER_EXPORTED);
        if (sticky != null) readBatteryIntent(sticky);

        NavAssistService.setOnStateChanged(() -> {
            if (mHandler != null) {
                mHandler.post(() -> {
                    detachExtOverlays();
                    pollRemoteStateAsync();
                });
            }
        });

        mHandler.post(mPollTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getExtras() != null) {
            IBinder cb = intent.getExtras().getBinder("cb");
            if (cb != null) BridgeClient.setBinder(cb);
        }
        if (BridgeClient.getService() == null) {
            BridgeClient.requestBinder(this);
        }
        pollRemoteStateAsync();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        NavAssistService.setOnStateChanged(null);
        try { unregisterReceiver(mSysRx); } catch (Throwable ignored) {}
        mHandler.removeCallbacksAndMessages(null);
        detachExtOverlays();
        mBg.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void readBatteryIntent(Intent bi) {
        int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (lvl >= 0 && scale > 0) {
            mBattPct = Math.max(0, Math.min(100, Math.round(lvl * 100f / scale)));
        }
        mBattCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "ExtBridge 外接屏控制", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("通知栏一键切换平板/扩展屏键鼠、进入大触摸板及分辨率控制台");
            ch.setShowBadge(false);
            mNm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(BridgeClient.Status st) {
        ActivityOptions opts0 = ActivityOptions.makeBasic();
        opts0.setLaunchDisplayId(0);
        Bundle optBundle0 = opts0.toBundle();

        Intent mainIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piMain = PendingIntent.getActivity(this, 1, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, optBundle0);

        Intent tpIntent = new Intent(this, TouchpadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piTp = PendingIntent.getActivity(this, 2, tpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, optBundle0);

        Intent toggleIntent = new Intent(this, ControlReceiver.class)
                .setAction("com.extbridge.helper.TOGGLE_INPUT");
        PendingIntent piToggle = PendingIntent.getBroadcast(this, 3, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent extHomeIntent = new Intent(this, ControlReceiver.class)
                .setAction("com.extbridge.helper.LAUNCH_EXT_HOME");
        PendingIntent piExtHome = PendingIntent.getBroadcast(this, 4, extHomeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent mirrorIntent = new Intent(this, ControlReceiver.class)
                .setAction("com.extbridge.helper.TOGGLE_MIRROR");
        PendingIntent piMirror = PendingIntent.getBroadcast(this, 5, mirrorIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean mirrorActive = st.connected && st.mirrorMode == 1 && st.extId > 0;
        String title;
        String body;
        String toggleLabel;
        if (!st.connected) {
            title = "ExtBridge 未连接后台服务";
            body = "点击打开控制台查看状态，或在电脑端启动 extbridge";
            toggleLabel = "⇄ 切换键鼠";
        } else if (mirrorActive) {
            title = "ExtBridge 原生镜像模式 · 物理 Display #" + st.extId;
            body = "外接屏正在同步手机/平板主屏 | 点击右侧「切回DeX」恢复独立大屏桌面";
            toggleLabel = st.inputToExt == 1 ? "⇄ 切到本机" : "⇄ 切回外接";
        } else if (st.vdId <= 0) {
            title = "ExtBridge 待机中 · 等待 USB-C 外接屏";
            body = "已配置预设 " + st.virtW + "×" + st.virtH + " (" + st.virtDpi + "dpi)";
            toggleLabel = st.inputToExt == 1 ? "⇄ 切到平板" : "⇄ 切回扩展屏";
        } else {
            String target = st.inputToExt == 1 ? "外接扩展屏" : "平板本机";
            String modeTag = mUsingA11yOverlay ? "无障碍B" : "兼容A";
            title = "ExtBridge 已挂载(" + modeTag + ") · " + st.virtW + "×" + st.virtH + " @" + Math.round(st.virtFps) + "Hz";
            body = "键鼠控制：【" + target + "】 | 点通知本体进控制台，右侧按钮一键回桌面";
            toggleLabel = st.inputToExt == 1 ? "⇄ 键鼠切回平板" : "⇄ 键鼠切回扩展屏";
        }

        String act1Label = mirrorActive ? "🖥 切回DeX" : toggleLabel;
        PendingIntent act1Pi = mirrorActive ? piMirror : piToggle;
        String act3Label = mirrorActive ? toggleLabel : "⊞ 回扩展桌面";
        PendingIntent act3Pi = mirrorActive ? piToggle : piExtHome;

        return new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_extbridge)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piMain)
                .addAction(new Notification.Action.Builder(null, act1Label, act1Pi).build())
                .addAction(new Notification.Action.Builder(null, "🖐 大触摸板", piTp).build())
                .addAction(new Notification.Action.Builder(null, act3Label, act3Pi).build())
                .build();
    }

    private void pollRemoteStateAsync() {
        mBg.execute(() -> {
            BridgeClient.Status st = BridgeClient.getStatus();
            List<BridgeClient.TaskEntry> tasks = (st.connected && st.vdId > 0) ? BridgeClient.getTasks() : null;
            BridgeClient.SysStatus sys = (st.connected && st.vdId > 0) ? BridgeClient.getSysStatus() : mLastSys;
            mHandler.post(() -> applyStateOnUi(st, tasks, sys));
        });
    }

    private void applyStateOnUi(BridgeClient.Status st, List<BridgeClient.TaskEntry> tasks, BridgeClient.SysStatus sys) {
        mLastStatus = st;
        if (sys != null && sys.valid) mLastSys = sys;
        mNm.notify(NOTIF_ID, buildNotification(st));

        if (!st.connected || st.vdId <= 0) {
            detachExtOverlays();
            return;
        }
        boolean a11yNow = NavAssistService.isRunning();
        if (!a11yNow && !Settings.canDrawOverlays(this)) return;

        int targetOverlayDpi = computeOverlayDpi(st);
        if (st.vdId != mAttachedVdId
                || a11yNow != mLastObservedA11y
                || targetOverlayDpi != mOverlayDpi) {
            attachExtOverlays(st);
        } else if (st.virtW != mOverlayW || st.virtH != mOverlayH) {
            mOverlayW = st.virtW;
            mOverlayH = st.virtH;
            mLastTaskSig = "";
            repositionDock();
            repositionPanel();
        }

        updateInputToggleUi(st.inputToExt);
        if (tasks != null) updateTaskStrip(tasks);
        refreshLocalHardwareViews();
        refreshSysViews();
        if (mBtnPanelFullscreen != null) {
            boolean fsOn = st.fullscreenPolicy == 1;
            mBtnPanelFullscreen.setText(fsOn ? "⛶ 桌面横屏:开" : "⛶ 桌面横屏:关");
            mBtnPanelFullscreen.setTextColor(fsOn ? 0xFF7DD3FC : 0xFFCBD5E1);
        }
    }

    private void attachExtOverlays(BridgeClient.Status st) {
        detachExtOverlays();
        Display target = null;
        for (Display d : mDm.getDisplays()) {
            if (d.getDisplayId() == st.vdId) { target = d; break; }
        }
        if (target == null) return;

        NavAssistService a11y = NavAssistService.getInstance();
        mLastObservedA11y = (a11y != null);
        if (a11y != null) {
            try {
                attachWithContext(a11y.createDisplayContext(target),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, true, st);
                mNm.notify(NOTIF_ID, buildNotification(st));
                return;
            } catch (Throwable t) {
                t.printStackTrace();
                detachExtOverlays();
                mLastObservedA11y = true;
            }
        }
        if (Settings.canDrawOverlays(this)) {
            try {
                attachWithContext(createDisplayContext(target),
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, false, st);
                mNm.notify(NOTIF_ID, buildNotification(st));
            } catch (Throwable t) {
                t.printStackTrace();
                detachExtOverlays();
            }
        }
    }

    private static int computeOverlayDpi(BridgeClient.Status st) {
        int reqDpi = (st != null && st.virtDpi > 0) ? st.virtDpi : 240;
        int virtH = (st != null && st.virtH > 0) ? st.virtH : 1440;
        int maxDpiForHeight = Math.max(120, Math.round(virtH * 160f / 720f));
        return Math.min(reqDpi, maxDpiForHeight);
    }

    private void attachWithContext(Context dispCtx, int winType, boolean isA11y, BridgeClient.Status st) {
        int overlayDpi = computeOverlayDpi(st);
        Context uiCtx = dispCtx;
        try {
            android.content.res.Configuration cfg = new android.content.res.Configuration(
                    dispCtx.getResources().getConfiguration());
            boolean needCfg = false;
            if (cfg.densityDpi != overlayDpi) {
                cfg.densityDpi = overlayDpi;
                needCfg = true;
            }
            if (cfg.fontScale != 1.0f) {
                cfg.fontScale = 1.0f;
                needCfg = true;
            }
            if (needCfg) {
                uiCtx = dispCtx.createConfigurationContext(cfg);
            }
        } catch (Throwable ignored) {}
        mExtCtx = uiCtx;
        mExtWm = (WindowManager) dispCtx.getSystemService(WINDOW_SERVICE);
        mWinType = winType;
        mUsingA11yOverlay = isA11y;
        mAttachedVdId = st.vdId;
        mOverlayW = st.virtW;
        mOverlayH = st.virtH;
        mOverlayDpi = overlayDpi;
        mLastTaskSig = "";

        buildDockOverlay(mExtCtx, st);
        buildWin11PanelOverlay(mExtCtx, st);
        buildRestorePillOverlay(mExtCtx);
        buildCursorOverlay(mExtCtx, st); // Added LAST so cursor stays above Dock & Flyout
        syncOverlayHostSurface(true);
    }

    public static void setDockHidden(boolean hidden) {
        BridgeService svc = sInstance;
        if (svc == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.applyDockHidden(hidden);
        } else if (svc.mHandler != null) {
            svc.mHandler.post(() -> svc.applyDockHidden(hidden));
        }
    }

    public static void setLauncherOverlayHost(ViewGroup host, int displayId) {
        sLauncherHost = host;
        sLauncherHostDisplayId = displayId;
        BridgeService svc = sInstance;
        if (svc == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.syncOverlayHostSurface(false);
        } else if (svc.mHandler != null) {
            svc.mHandler.post(() -> svc.syncOverlayHostSurface(false));
        }
    }

    public static void clearLauncherOverlayHost(ViewGroup host) {
        if (sLauncherHost == host) {
            sLauncherHost = null;
            sLauncherHostDisplayId = -1;
            BridgeService svc = sInstance;
            if (svc == null) return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                svc.syncOverlayHostSurface(false);
            } else if (svc.mHandler != null) {
                svc.mHandler.post(() -> svc.syncOverlayHostSurface(false));
            }
        }
    }

    public static void dismissPanelOnHostTouch(float winX, float winY) {
        BridgeService svc = sInstance;
        if (svc != null) {
            svc.handleHostTouch(winX, winY);
        }
    }

    private boolean shouldHostInLauncher() {
        return !mUsingA11yOverlay
                && mAttachedVdId > 0
                && sLauncherHost != null
                && sLauncherHostDisplayId == mAttachedVdId;
    }

    private void removeOverlayView(View v) {
        if (v == null) return;
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(v);
        } else if (p != null && mExtWm != null) {
            try { mExtWm.removeViewImmediate(v); } catch (Throwable ignored) {}
        }
    }

    private void syncOverlayHostSurface(boolean forceAttach) {
        if (mDockRoot == null || mPanelRoot == null || mCursorView == null || mExtCtx == null) return;
        boolean wantHost = shouldHostInLauncher();
        if (!forceAttach && wantHost == mInLauncherHost) {
            ViewParent p = mDockRoot.getParent();
            boolean rightParent = wantHost ? (p == sLauncherHost) : (p != null && !(p instanceof ViewGroup));
            if (rightParent) return;
        }
        removeOverlayView(mDockRoot);
        removeOverlayView(mPanelRoot);
        removeOverlayView(mRestorePill);
        removeOverlayView(mCursorView);
        mInLauncherHost = wantHost;
        if (wantHost) {
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                    mDockLp != null ? mDockLp.width : ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dlp.bottomMargin = dp(mExtCtx, 10);
            sLauncherHost.addView(mDockRoot, dlp);

            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    dp(mExtCtx, 354), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT);
            if (mPanelLp != null) {
                plp.leftMargin = mPanelLp.x;
                plp.topMargin = mPanelLp.y;
            }
            sLauncherHost.addView(mPanelRoot, plp);

            if (mRestorePill != null) {
                FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.RIGHT);
                rlp.rightMargin = dp(mExtCtx, 10);
                rlp.bottomMargin = dp(mExtCtx, 10);
                sLauncherHost.addView(mRestorePill, rlp);
            }

            mCursorView.setClickable(false);
            mCursorView.setFocusable(false);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    44, 44, Gravity.TOP | Gravity.LEFT);
            sLauncherHost.addView(mCursorView, clp);
            if (mCursorLp != null) {
                mCursorView.setTranslationX(mCursorLp.x);
                mCursorView.setTranslationY(mCursorLp.y);
            }
            sLauncherHost.post(() -> {
                if (mInLauncherHost) {
                    repositionDock();
                    if (mPanelOpen) repositionPanel();
                }
            });
        } else if (mExtWm != null) {
            mCursorView.setTranslationX(0f);
            mCursorView.setTranslationY(0f);
            try { mExtWm.addView(mDockRoot, mDockLp); } catch (Throwable ignored) {}
            try { mExtWm.addView(mPanelRoot, mPanelLp); } catch (Throwable ignored) {}
            if (mRestorePill != null && mRestoreLp != null) {
                try { mExtWm.addView(mRestorePill, mRestoreLp); } catch (Throwable ignored) {}
            }
            try { mExtWm.addView(mCursorView, mCursorLp); } catch (Throwable ignored) {}
        }
        mDockRoot.setVisibility(mDockHidden ? View.GONE : View.VISIBLE);
        if (mRestorePill != null) mRestorePill.setVisibility(mDockHidden ? View.VISIBLE : View.GONE);
        repositionDock();
        if (mPanelOpen) repositionPanel();
    }

    private void handleHostTouch(float winX, float winY) {
        if (!mInLauncherHost || !mPanelOpen || mPanelCard == null) return;
        if (isPointInView(mPanelCard, winX, winY) || isPointInView(mTrayBtn, winX, winY)) {
            return;
        }
        setPanelOpen(false);
    }

    private static boolean isPointInView(View v, float winX, float winY) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return winX >= loc[0] && winX <= (loc[0] + v.getWidth())
                && winY >= loc[1] && winY <= (loc[1] + v.getHeight());
    }

    private void buildDockOverlay(Context ctx, BridgeClient.Status st) {
        mDockRoot = new LinearLayout(ctx);
        mDockRoot.setOrientation(LinearLayout.HORIZONTAL);
        mDockRoot.setGravity(Gravity.CENTER_VERTICAL);
        mDockRoot.setBaselineAligned(false);
        mDockRoot.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        mDockRoot.setElevation(dp(ctx, 10));
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(0xD2121926);
        dockBg.setCornerRadius(dp(ctx, 22));
        dockBg.setStroke(1, 0x42FFFFFF);
        mDockRoot.setBackground(dockBg);

        // Left: App Launcher/Search, Back, Input Switch
        TextView btnHome = makeDockBtn(ctx, "⊞ 桌面", 0x33FFFFFF, Color.WHITE);
        btnHome.setOnClickListener(v -> {
            setPanelOpen(false);
            BridgeClient.relaunchLauncher();
        });
        mDockRoot.addView(btnHome);

        TextView btnBack = makeDockBtn(ctx, "←", 0x26FFFFFF, Color.WHITE);
        btnBack.setOnClickListener(v -> BridgeClient.sendBack());
        mDockRoot.addView(btnBack);

        mBtnToggle = makeDockBtn(ctx, st.inputToExt == 1 ? "⇄ 外接" : "⇄ 平板",
                st.inputToExt == 1 ? 0xCC2563EB : 0xCCEA580C, Color.WHITE);
        mBtnToggle.setOnClickListener(v -> mBg.execute(() -> {
            int m = BridgeClient.toggleInput();
            mHandler.post(() -> {
                updateInputToggleUi(m);
                Toast.makeText(BridgeService.this, m == 1 ? "键鼠 -> 扩展屏" : "键鼠 -> 平板", Toast.LENGTH_SHORT).show();
            });
        }));
        mDockRoot.addView(mBtnToggle);

        mTaskDivider = makeVerticalDivider(ctx);
        mTaskDivider.setVisibility(View.GONE);
        mDockRoot.addView(mTaskDivider);

        // Center: Multitasking running/recent apps strip
        mTaskStrip = new LinearLayout(ctx);
        mTaskStrip.setOrientation(LinearLayout.HORIZONTAL);
        mTaskStrip.setGravity(Gravity.CENTER_VERTICAL);
        mDockRoot.addView(mTaskStrip);

        mDockRoot.addView(makeVerticalDivider(ctx));

        // Right: Win11 System Tray Pill (Wi-Fi, Vol, Battery %, Clock + Date)
        mTrayBtn = new LinearLayout(ctx);
        mTrayBtn.setOrientation(LinearLayout.HORIZONTAL);
        mTrayBtn.setGravity(Gravity.CENTER_VERTICAL);
        mTrayBtn.setPadding(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4));
        updateTrayPillBackground(false);
        mTrayBtn.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                mTrayPressed = true;
                mHandler.removeCallbacks(mOutsideDismissTask);
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                mTrayPressed = false;
                mLastTrayUpMs = SystemClock.uptimeMillis();
            }
            return false;
        });
        mTrayBtn.setOnClickListener(v -> {
            mHandler.removeCallbacks(mOutsideDismissTask);
            mLastTrayToggleMs = SystemClock.uptimeMillis();
            setPanelOpen(!mPanelOpen);
        });

        mTrayWifiIcon = new MiniIconView(ctx, MiniIconView.KIND_WIFI, Color.WHITE);
        LinearLayout.LayoutParams wiLp = new LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16));
        wiLp.rightMargin = dp(ctx, 6);
        mTrayBtn.addView(mTrayWifiIcon, wiLp);

        mTrayVolIcon = new MiniIconView(ctx, MiniIconView.KIND_VOLUME, Color.WHITE);
        LinearLayout.LayoutParams voLp = new LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16));
        voLp.rightMargin = dp(ctx, 6);
        mTrayBtn.addView(mTrayVolIcon, voLp);

        mTrayBattView = new MiniBatteryView(ctx);
        LinearLayout.LayoutParams bvLp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 12));
        bvLp.rightMargin = dp(ctx, 4);
        mTrayBtn.addView(mTrayBattView, bvLp);

        mTrayBattPct = new TextView(ctx);
        mTrayBattPct.setTextColor(Color.WHITE);
        mTrayBattPct.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
        mTrayBattPct.getPaint().setFakeBoldText(true);
        enforceSingleLine(mTrayBattPct);
        LinearLayout.LayoutParams bpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bpLp.rightMargin = dp(ctx, 9);
        mTrayBtn.addView(mTrayBattPct, bpLp);

        LinearLayout clockCol = new LinearLayout(ctx);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        TextClock tcTime = new TextClock(ctx);
        tcTime.setFormat24Hour("HH:mm");
        tcTime.setFormat12Hour("HH:mm");
        tcTime.setTextColor(Color.WHITE);
        tcTime.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        tcTime.getPaint().setFakeBoldText(true);
        enforceSingleLine(tcTime);
        clockCol.addView(tcTime);

        TextClock tcDate = new TextClock(ctx);
        tcDate.setFormat24Hour("yyyy/M/d");
        tcDate.setFormat12Hour("yyyy/M/d");
        tcDate.setTextColor(0xFFCBD5E1);
        tcDate.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f);
        enforceSingleLine(tcDate);
        clockCol.addView(tcDate);

        mTrayBtn.addView(clockCol);
        mDockRoot.addView(mTrayBtn);

        mDockLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mDockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mDockLp.x = 0;
        mDockLp.y = dp(ctx, 10);

        mDockRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                repositionDock();
                if (mPanelOpen) repositionPanel();
            }
        });
    }

    private void buildWin11PanelOverlay(Context ctx, BridgeClient.Status st) {
        mPanelRoot = new FrameLayout(ctx);
        mPanelRoot.setClipToPadding(false);
        mPanelRoot.setClipChildren(false);
        mPanelRoot.setPadding(dp(ctx, 8), dp(ctx, 14), dp(ctx, 8), dp(ctx, 8));

        mPanelCard = new LinearLayout(ctx);
        mPanelCard.setOrientation(LinearLayout.VERTICAL);
        mPanelCard.setElevation(dp(ctx, 16));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xEE18202E);
        panelBg.setCornerRadius(dp(ctx, 16));
        panelBg.setStroke(1, 0x3FFFFFFF);
        mPanelCard.setBackground(panelBg);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14));

        // Row 1 of 3x2 grid: Wi-Fi, Bluetooth, Input Target
        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(ctx, 10);

        mTileWifi = new QuickTile(ctx, MiniIconView.KIND_WIFI, "Wi-Fi", true,
                () -> execSysAndRefresh(BridgeClient.SYS_WIFI, -1, null),
                () -> openExtSettings(Settings.ACTION_WIFI_SETTINGS));
        mTileBt = new QuickTile(ctx, MiniIconView.KIND_BT, "蓝牙", true,
                () -> execSysAndRefresh(BridgeClient.SYS_BT, -1, null),
                () -> openExtSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        mTileInput = new QuickTile(ctx, MiniIconView.KIND_DISPLAY_SWITCH, "外接控制", false,
                () -> mBg.execute(() -> {
                    int m = BridgeClient.toggleInput();
                    mHandler.post(() -> {
                        updateInputToggleUi(m);
                        pollRemoteStateAsync();
                    });
                }), null);
        addGridTile(row1, mTileWifi.root, true);
        addGridTile(row1, mTileBt.root, true);
        addGridTile(row1, mTileInput.root, false);
        body.addView(row1, rowLp);

        // Row 2 of 3x2 grid: Airplane, Touchpad, Battery Saver
        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.bottomMargin = dp(ctx, 16);

        mTileAirplane = new QuickTile(ctx, MiniIconView.KIND_AIRPLANE, "飞行模式", false,
                () -> execSysAndRefresh(BridgeClient.SYS_AIRPLANE, -1, null), null);
        mTileTouchpad = new QuickTile(ctx, MiniIconView.KIND_TOUCHPAD, "大触摸板", true,
                this::openTabletTouchpad, this::openTabletTouchpad);
        mTilePowerSave = new QuickTile(ctx, MiniIconView.KIND_BATTERY_SAVER, "节能模式", false,
                () -> execSysAndRefresh(BridgeClient.SYS_POWER_SAVE, -1, null), null);
        addGridTile(row2, mTileAirplane.root, true);
        addGridTile(row2, mTileTouchpad.root, true);
        addGridTile(row2, mTilePowerSave.root, false);
        body.addView(row2, row2Lp);

        // Volume Slider row (no brightness slider, per user preference)
        LinearLayout volRow = new LinearLayout(ctx);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 2));

        mVolSpeakerIcon = new MiniIconView(ctx, MiniIconView.KIND_VOLUME, Color.WHITE);
        mVolSpeakerIcon.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        mVolSpeakerIcon.setOnClickListener(v -> {
            if (mAm != null) {
                try {
                    mAm.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                } catch (Throwable ignored) {}
                refreshLocalHardwareViews();
            }
        });
        volRow.addView(mVolSpeakerIcon, new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));

        mVolSeekBar = new SeekBar(ctx);
        int accent = 0xFFA6D8E8;
        if (mVolSeekBar.getProgressDrawable() != null) {
            mVolSeekBar.getProgressDrawable().setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        }
        if (mVolSeekBar.getThumb() != null) {
            mVolSeekBar.getThumb().setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        }
        if (mAm != null) {
            mVolSeekBar.setMax(Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        }
        mVolSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && mAm != null) {
                    try {
                        mAm.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    } catch (Throwable ignored) {}
                    refreshTrayIconsOnly();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { mSeekingVol = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { mSeekingVol = false; refreshLocalHardwareViews(); }
        });
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        skLp.leftMargin = dp(ctx, 4);
        skLp.rightMargin = dp(ctx, 4);
        volRow.addView(mVolSeekBar, skLp);

        TextView btnAudioMore = new TextView(ctx);
        btnAudioMore.setText("🎚 ›");
        btnAudioMore.setTextColor(0xFFCBD5E1);
        btnAudioMore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnAudioMore.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4));
        btnAudioMore.setOnClickListener(v -> openExtSettings(Settings.ACTION_SOUND_SETTINGS));
        volRow.addView(btnAudioMore);

        body.addView(volRow);

        // DeX policy & mode action strip
        LinearLayout dexRow = new LinearLayout(ctx);
        dexRow.setOrientation(LinearLayout.HORIZONTAL);
        dexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        drLp.topMargin = dp(ctx, 10);
        dexRow.setLayoutParams(drLp);

        mBtnPanelFullscreen = makeFooterChip(ctx, st.fullscreenPolicy == 1 ? "⛶ 桌面横屏:开" : "⛶ 桌面横屏:关");
        mBtnPanelFullscreen.setSingleLine(true);
        mBtnPanelFullscreen.setGravity(Gravity.CENTER);
        mBtnPanelFullscreen.setTextColor(st.fullscreenPolicy == 1 ? 0xFF7DD3FC : 0xFFCBD5E1);
        mBtnPanelFullscreen.setOnClickListener(v -> mBg.execute(() -> {
            int next = BridgeClient.toggleFullscreenPolicy();
            mHandler.post(() -> {
                Toast.makeText(BridgeService.this,
                        next == 1 ? "已开启：DeX 默认横屏大屏桌面（单应用可在桌面长按改模式）" : "已关闭：DeX 桌面默认横屏锁定",
                        Toast.LENGTH_SHORT).show();
                pollRemoteStateAsync();
            });
        }));
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dp1.rightMargin = dp(ctx, 6);
        dexRow.addView(mBtnPanelFullscreen, dp1);

        mBtnPanelMirror = makeFooterChip(ctx, "📺 原生镜像");
        mBtnPanelMirror.setSingleLine(true);
        mBtnPanelMirror.setGravity(Gravity.CENTER);
        mBtnPanelMirror.setOnClickListener(v -> {
            setPanelOpen(false);
            Toast.makeText(BridgeService.this, "正在切换为原生镜像模式...", Toast.LENGTH_SHORT).show();
            mBg.execute(() -> {
                BridgeClient.setMirrorMode(1);
                mHandler.post(() -> pollRemoteStateAsync());
            });
        });
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dp2.rightMargin = dp(ctx, 6);
        dexRow.addView(mBtnPanelMirror, dp2);

        mBtnPanelHideDock = makeFooterChip(ctx, "👁 隐藏Dock");
        mBtnPanelHideDock.setSingleLine(true);
        mBtnPanelHideDock.setGravity(Gravity.CENTER);
        mBtnPanelHideDock.setOnClickListener(v -> {
            applyDockHidden(true);
            Toast.makeText(BridgeService.this, "Dock 已隐藏，点右下角 ⊞ 可恢复", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams dp3 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dexRow.addView(mBtnPanelHideDock, dp3);

        body.addView(dexRow);
        mPanelCard.addView(body);

        // Footer Bar (Battery % left, Control Center + Settings right)
        LinearLayout footer = new LinearLayout(ctx);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        GradientDrawable footBg = new GradientDrawable();
        footBg.setColor(0x440B111C);
        float r = dp(ctx, 16);
        footBg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        footer.setBackground(footBg);

        mPanelBattView = new MiniBatteryView(ctx);
        LinearLayout.LayoutParams pbvLp = new LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 13));
        pbvLp.rightMargin = dp(ctx, 7);
        footer.addView(mPanelBattView, pbvLp);

        mPanelBattText = new TextView(ctx);
        mPanelBattText.setTextColor(0xFFE2E8F0);
        mPanelBattText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.addView(mPanelBattText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnCtrl = makeFooterChip(ctx, "🎛 控制台");
        btnCtrl.setOnClickListener(v -> openTabletMain());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = dp(ctx, 8);
        footer.addView(btnCtrl, cLp);

        TextView btnSysSet = makeFooterChip(ctx, "⚙ 设置");
        btnSysSet.setOnClickListener(v -> openExtSettings(Settings.ACTION_SETTINGS));
        footer.addView(btnSysSet);

        mPanelCard.addView(footer);
        mPanelRoot.addView(mPanelCard, new FrameLayout.LayoutParams(
                dp(ctx, 338), FrameLayout.LayoutParams.WRAP_CONTENT));
        mPanelRoot.setVisibility(View.GONE);
        mPanelOpen = false;

        mPanelLp = new WindowManager.LayoutParams(
                dp(ctx, 354),
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mPanelLp.gravity = Gravity.TOP | Gravity.LEFT;
        mPanelLp.x = Math.max(16, st.virtW - dp(ctx, 366));
        mPanelLp.y = Math.max(16, st.virtH - dp(ctx, 410));

        mPanelRoot.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE && mPanelOpen) {
                long now = SystemClock.uptimeMillis();
                if (!mTrayPressed && (now - mLastTrayUpMs) > 220L && (now - mLastTrayToggleMs) > 220L) {
                    mHandler.removeCallbacks(mOutsideDismissTask);
                    mHandler.postDelayed(mOutsideDismissTask, 70L);
                }
                return false;
            }
            return false;
        });
        mPanelRoot.addOnLayoutChangeListener((v, l, t, rt, b, ol, ot, or, ob) -> {
            if ((rt - l) != (or - ol) || (b - t) != (ob - ot)) {
                repositionPanel();
            }
        });
    }

    private void buildRestorePillOverlay(Context ctx) {
        mRestorePill = new TextView(ctx);
        mRestorePill.setText("⊞");
        mRestorePill.setTextColor(0xE6FFFFFF);
        mRestorePill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mRestorePill.setSingleLine(true);
        mRestorePill.setGravity(Gravity.CENTER);
        mRestorePill.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        mRestorePill.setAlpha(0.38f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xC8121926);
        bg.setCornerRadius(dp(ctx, 14));
        bg.setStroke(1, 0x45FFFFFF);
        mRestorePill.setBackground(bg);
        mRestorePill.setOnClickListener(v -> applyDockHidden(false));

        mRestoreLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mRestoreLp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        mRestoreLp.x = dp(ctx, 10);
        mRestoreLp.y = dp(ctx, 10);
        mRestorePill.setVisibility(mDockHidden ? View.VISIBLE : View.GONE);
    }

    private void applyDockHidden(boolean hidden) {
        mDockHidden = hidden;
        if (hidden) setPanelOpen(false);
        if (mDockRoot != null) {
            mDockRoot.setVisibility(hidden ? View.GONE : View.VISIBLE);
            if (!hidden) repositionDock();
        }
        if (mRestorePill != null) {
            mRestorePill.setVisibility(hidden ? View.VISIBLE : View.GONE);
        }
    }

    private void buildCursorOverlay(Context ctx, BridgeClient.Status st) {
        mCursorView = new CursorView(ctx);
        mCursorLp = new WindowManager.LayoutParams(
                44, 44,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mCursorLp.gravity = Gravity.TOP | Gravity.LEFT;
        mCursorLp.x = Math.round(st.cursorX) - 6;
        mCursorLp.y = Math.round(st.cursorY) - 4;
        mCursorView.setVisibility(View.GONE);
    }

    private void addGridTile(LinearLayout row, View tileRoot, boolean marginRight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (marginRight && mExtCtx != null) lp.rightMargin = dp(mExtCtx, 10);
        row.addView(tileRoot, lp);
    }

    private TextView makeFooterChip(Context ctx, String txt) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x28FFFFFF);
        bg.setCornerRadius(dp(ctx, 10));
        tv.setBackground(bg);
        return tv;
    }

    private void setPanelOpen(boolean open) {
        mHandler.removeCallbacks(mOutsideDismissTask);
        if (mPanelOpen == open && (mPanelRoot == null || (open && mPanelRoot.getVisibility() == View.VISIBLE))) {
            return;
        }
        mPanelOpen = open;
        updateTrayPillBackground(open);
        if (mPanelRoot == null || mPanelCard == null || mExtCtx == null) return;

        mPanelCard.animate().cancel();
        if (open) {
            mPanelRoot.setVisibility(View.VISIBLE);
            repositionPanel();
            refreshLocalHardwareViews();
            pollRemoteStateAsync();
            mPanelCard.setAlpha(0f);
            mPanelCard.setTranslationY(dp(mExtCtx, 18));
            mPanelCard.setScaleX(0.94f);
            mPanelCard.setScaleY(0.94f);
            mPanelCard.post(() -> {
                if (!mPanelOpen || mPanelCard == null) return;
                int w = mPanelCard.getWidth();
                int h = mPanelCard.getHeight();
                if (w > 0 && h > 0) {
                    mPanelCard.setPivotX(w * 0.85f);
                    mPanelCard.setPivotY(h);
                }
                mPanelCard.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200L)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .withEndAction(null)
                        .start();
            });
        } else {
            int w = mPanelCard.getWidth();
            int h = mPanelCard.getHeight();
            if (w > 0 && h > 0) {
                mPanelCard.setPivotX(w * 0.85f);
                mPanelCard.setPivotY(h);
            }
            mPanelCard.animate()
                    .alpha(0f)
                    .translationY(dp(mExtCtx, 14))
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(150L)
                    .setInterpolator(new AccelerateInterpolator(1.3f))
                    .withEndAction(() -> {
                        if (!mPanelOpen && mPanelRoot != null) {
                            mPanelRoot.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private void updateTrayPillBackground(boolean active) {
        if (mTrayBtn == null || mExtCtx == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? 0x45A6D8E8 : 0x1EFFFFFF);
        bg.setCornerRadius(dp(mExtCtx, 14));
        if (active) bg.setStroke(1, 0x80A6D8E8);
        mTrayBtn.setBackground(bg);
    }

    private void repositionDock() {
        if (mDockRoot == null || mDockLp == null || mExtCtx == null) return;
        try {
            int surfW = mOverlayW;
            if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0) {
                surfW = sLauncherHost.getWidth();
            }
            int dw = mDockRoot.getWidth();
            int maxW = Math.max(200, surfW - dp(mExtCtx, 20));
            int targetW = dw > maxW ? maxW : WindowManager.LayoutParams.WRAP_CONTENT;
            int bottomMargin = dp(mExtCtx, 10);
            mDockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mDockLp.width = targetW;
            mDockLp.x = 0;
            mDockLp.y = bottomMargin;
            if (mInLauncherHost) {
                ViewGroup.LayoutParams raw = mDockRoot.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                    int wantGravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    if (flp.width != targetW || flp.gravity != wantGravity
                            || flp.leftMargin != 0 || flp.topMargin != 0 || flp.bottomMargin != bottomMargin) {
                        flp.gravity = wantGravity;
                        flp.width = targetW;
                        flp.leftMargin = 0;
                        flp.topMargin = 0;
                        flp.bottomMargin = bottomMargin;
                        mDockRoot.setLayoutParams(flp);
                    }
                }
            } else if (mExtWm != null) {
                mExtWm.updateViewLayout(mDockRoot, mDockLp);
            }
        } catch (Throwable ignored) {}
    }

    private void repositionPanel() {
        if (mPanelRoot == null || mPanelLp == null || mExtCtx == null) return;
        try {
            int surfW = mOverlayW;
            int surfH = mOverlayH;
            if (mInLauncherHost && sLauncherHost != null) {
                if (sLauncherHost.getWidth() > 0) surfW = sLauncherHost.getWidth();
                if (sLauncherHost.getHeight() > 0) surfH = sLauncherHost.getHeight();
            }
            int pw = mPanelRoot.getWidth();
            int ph = mPanelRoot.getHeight();
            if (pw <= 0) pw = dp(mExtCtx, 338);
            if (ph <= 0) ph = dp(mExtCtx, 320);

            int dockW = (mDockRoot != null && mDockRoot.getWidth() > 0) ? mDockRoot.getWidth() : dp(mExtCtx, 520);
            int dockH = (mDockRoot != null && mDockRoot.getHeight() > 0) ? mDockRoot.getHeight() : dp(mExtCtx, 46);
            int dockBottomMargin = dp(mExtCtx, 10);
            int anchorRight = (mInLauncherHost && mDockRoot != null && mDockRoot.getWidth() > 0)
                    ? mDockRoot.getRight()
                    : ((surfW + dockW) / 2);
            int anchorTop = (mInLauncherHost && mDockRoot != null && mDockRoot.getHeight() > 0)
                    ? mDockRoot.getTop()
                    : (surfH - dockBottomMargin - dockH);

            int x = Math.min(surfW - pw - dp(mExtCtx, 14), Math.max(dp(mExtCtx, 14), anchorRight - pw));
            int y = Math.max(dp(mExtCtx, 14), anchorTop - ph - dp(mExtCtx, 10));
            mPanelLp.x = x;
            mPanelLp.y = y;
            if (mInLauncherHost) {
                ViewGroup.LayoutParams raw = mPanelRoot.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                    if (flp.leftMargin != x || flp.topMargin != y) {
                        flp.gravity = Gravity.TOP | Gravity.LEFT;
                        flp.leftMargin = x;
                        flp.topMargin = y;
                        mPanelRoot.setLayoutParams(flp);
                    }
                }
            } else if (mExtWm != null) {
                mExtWm.updateViewLayout(mPanelRoot, mPanelLp);
            }
        } catch (Throwable ignored) {}
    }

    private void updateInputToggleUi(int inputToExt) {
        if (mBtnToggle != null) {
            mBtnToggle.setText(inputToExt == 1 ? "⇄ 外接" : "⇄ 平板");
            GradientDrawable gd = (GradientDrawable) mBtnToggle.getBackground();
            gd.setColor(inputToExt == 1 ? 0xCC2563EB : 0xCCEA580C);
        }
        if (mTileInput != null) {
            mTileInput.setActive(inputToExt == 1, inputToExt == 1 ? "外接扩展屏" : "平板本机");
        }
    }

    private void updateTaskStrip(List<BridgeClient.TaskEntry> tasks) {
        if (mTaskStrip == null || mExtCtx == null) return;
        int surfW = mOverlayW;
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0) {
            surfW = sLauncherHost.getWidth();
        }
        int fixedW = dp(mExtCtx, 390);
        int slotW = Math.max(1, dp(mExtCtx, 42));
        int availW = Math.max(0, surfW - dp(mExtCtx, 28) - fixedW);
        int maxShown = Math.max(2, Math.min(9, availW / slotW));
        int limit = Math.min(maxShown, tasks.size());

        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            BridgeClient.TaskEntry t = tasks.get(i);
            sig.append(t.taskId).append(':').append(t.pkg).append(':').append(t.flags).append(';');
        }
        String s = sig.toString();
        if (s.equals(mLastTaskSig)) return;
        mLastTaskSig = s;

        mTaskStrip.removeAllViews();
        if (mTaskDivider != null) {
            mTaskDivider.setVisibility(limit > 0 ? View.VISIBLE : View.GONE);
        }

        PackageManager pm = getPackageManager();
        for (int i = 0; i < limit; i++) {
            BridgeClient.TaskEntry t = tasks.get(i);
            Drawable icon = getCachedIcon(pm, t.pkg);
            String label = getCachedLabel(pm, t.pkg);

            LinearLayout slot = new LinearLayout(mExtCtx);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setGravity(Gravity.CENTER);
            slot.setPadding(dp(mExtCtx, 6), dp(mExtCtx, 3), dp(mExtCtx, 6), dp(mExtCtx, 3));
            GradientDrawable slotBg = new GradientDrawable();
            slotBg.setCornerRadius(dp(mExtCtx, 10));
            if (t.isTopExt()) {
                slotBg.setColor(0x36FFFFFF);
            } else if (t.onExt()) {
                slotBg.setColor(0x18FFFFFF);
            } else {
                slotBg.setColor(Color.TRANSPARENT);
            }
            slot.setBackground(slotBg);

            ImageView iv = new ImageView(mExtCtx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (icon != null) iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(mExtCtx, 28), dp(mExtCtx, 28));
            slot.addView(iv, ivLp);

            View dot = new View(mExtCtx);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setCornerRadius(dp(mExtCtx, 2));
            if (t.isTopExt()) {
                dotBg.setColor(0xFF38BDF8);
            } else if (t.onExt()) {
                dotBg.setColor(0xFF94A3B8);
            } else if (t.isRunning()) {
                dotBg.setColor(0x7794A3B8);
            } else {
                dotBg.setColor(Color.TRANSPARENT);
            }
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    t.isTopExt() ? dp(mExtCtx, 14) : dp(mExtCtx, 6), dp(mExtCtx, 3));
            dotLp.topMargin = dp(mExtCtx, 2);
            slot.addView(dot, dotLp);

            slot.setOnClickListener(v -> {
                setPanelOpen(false);
                mBg.execute(() -> {
                    BridgeClient.switchToTask(t.taskId, t.pkg);
                    mHandler.postDelayed(this::pollRemoteStateAsync, 250);
                });
            });
            slot.setOnLongClickListener(v -> {
                if (t.taskId > 0) {
                    mBg.execute(() -> {
                        boolean ok = BridgeClient.removeTask(t.taskId);
                        mHandler.post(() -> {
                            Toast.makeText(BridgeService.this,
                                    ok ? ("已关闭后台任务：" + label) : ("尝试切换：" + label), Toast.LENGTH_SHORT).show();
                            pollRemoteStateAsync();
                        });
                    });
                    return true;
                }
                return false;
            });

            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sLp.leftMargin = dp(mExtCtx, 2);
            sLp.rightMargin = dp(mExtCtx, 2);
            mTaskStrip.addView(slot, sLp);
        }
    }

    private void refreshLocalHardwareViews() {
        refreshTrayIconsOnly();
        if (mAm != null && mVolSeekBar != null && !mSeekingVol) {
            int max = Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = mAm.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (mVolSeekBar.getMax() != max) mVolSeekBar.setMax(max);
            mVolSeekBar.setProgress(cur);
        }
        if (mTrayBattView != null) mTrayBattView.setBattery(mBattPct, mBattCharging);
        if (mTrayBattPct != null) mTrayBattPct.setText(mBattPct + "%");
        if (mPanelBattView != null) mPanelBattView.setBattery(mBattPct, mBattCharging);
        if (mPanelBattText != null) {
            mPanelBattText.setText(mBattPct + "%" + (mBattCharging ? " · 充电中" : ""));
        }
    }

    private void refreshTrayIconsOnly() {
        boolean muted = false;
        int volPct = 50;
        if (mAm != null) {
            int max = Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = mAm.getStreamVolume(AudioManager.STREAM_MUSIC);
            try { muted = mAm.isStreamMute(AudioManager.STREAM_MUSIC) || cur == 0; } catch (Throwable ignored) {}
            volPct = Math.round(cur * 100f / max);
        }
        if (mTrayVolIcon != null) mTrayVolIcon.setVolumeLevel(muted ? 0 : (volPct > 50 ? 2 : 1));
        if (mVolSpeakerIcon != null) mVolSpeakerIcon.setVolumeLevel(muted ? 0 : (volPct > 50 ? 2 : 1));
    }

    private void refreshSysViews() {
        if (!mLastSys.valid) return;
        if (mTrayWifiIcon != null) {
            mTrayWifiIcon.setActiveState(mLastSys.wifiOn);
        }
        if (mTileWifi != null) {
            String sub = mLastSys.wifiOn
                    ? (mLastSys.wifiSsid.isEmpty() ? "已开启" : mLastSys.wifiSsid)
                    : "Wi-Fi";
            mTileWifi.setActive(mLastSys.wifiOn, sub);
        }
        if (mTileBt != null) {
            String sub = mLastSys.btOn
                    ? (mLastSys.btName.isEmpty() ? "未连接" : mLastSys.btName)
                    : "蓝牙";
            mTileBt.setActive(mLastSys.btOn, sub);
        }
        if (mTileAirplane != null) {
            mTileAirplane.setActive(mLastSys.airplaneOn, "飞行模式");
        }
        if (mTilePowerSave != null) {
            mTilePowerSave.setActive(mLastSys.powerSaveOn, "节能模式");
        }
    }

    private void execSysAndRefresh(int act, int arg, String str) {
        mBg.execute(() -> {
            BridgeClient.execSysAction(act, arg, str);
            try { Thread.sleep(220); } catch (InterruptedException ignored) {}
            BridgeClient.SysStatus sys = BridgeClient.getSysStatus();
            mHandler.post(() -> {
                if (sys != null && sys.valid) mLastSys = sys;
                refreshSysViews();
            });
        });
    }

    private void openExtSettings(String action) {
        setPanelOpen(false);
        if (mUsingA11yOverlay) {
            // Scheme B: accessibility overlay remains visible inside Settings on external display
            mBg.execute(() -> BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, -1, action));
        } else {
            // Scheme A fallback: open Settings on tablet Display 0 so external screen isn't trapped
            Toast.makeText(this, "未开启无障碍：已在平板打开系统设置", Toast.LENGTH_SHORT).show();
            mBg.execute(() -> BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, 0, action));
        }
    }

    private void openTabletMain() {
        setPanelOpen(false);
        try {
            Intent mi = new Intent(BridgeService.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o0 = ActivityOptions.makeBasic();
            o0.setLaunchDisplayId(0);
            startActivity(mi, o0.toBundle());
        } catch (Throwable ignored) {}
    }

    private void openTabletTouchpad() {
        setPanelOpen(false);
        try {
            Intent ti = new Intent(BridgeService.this, TouchpadActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o0 = ActivityOptions.makeBasic();
            o0.setLaunchDisplayId(0);
            startActivity(ti, o0.toBundle());
        } catch (Throwable ignored) {}
    }

    private Drawable getCachedIcon(PackageManager pm, String pkg) {
        Drawable d = mIconCache.get(pkg);
        if (d != null) return d;
        try {
            d = pm.getApplicationIcon(pkg);
            mIconCache.put(pkg, d);
            return d;
        } catch (Throwable ignored) {}
        return null;
    }

    private String getCachedLabel(PackageManager pm, String pkg) {
        String lbl = mLabelCache.get(pkg);
        if (lbl != null) return lbl;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null) {
                lbl = cs.toString();
                mLabelCache.put(pkg, lbl);
                return lbl;
            }
        } catch (Throwable ignored) {}
        return pkg;
    }

    private void updateCursorOnExt(float x, float y, boolean pressing) {
        if (mCursorView == null || mCursorLp == null) return;
        try {
            if (!mCursorVisible) {
                mCursorVisible = true;
                mCursorView.setVisibility(View.VISIBLE);
            }
            mCursorView.setPressing(pressing);
            mCursorLp.x = Math.round(x) - 6;
            mCursorLp.y = Math.round(y) - 4;
            if (mInLauncherHost) {
                mCursorView.setTranslationX(mCursorLp.x);
                mCursorView.setTranslationY(mCursorLp.y);
            } else if (mExtWm != null) {
                mExtWm.updateViewLayout(mCursorView, mCursorLp);
            }
            mHandler.removeCallbacks(mHideCursorTask);
            mHandler.postDelayed(mHideCursorTask, 7000);
        } catch (Throwable ignored) {}
    }

    private void setCursorVisible(boolean vis) {
        mCursorVisible = vis;
        if (mCursorView != null) {
            mCursorView.setVisibility(vis ? View.VISIBLE : View.GONE);
        }
    }

    private void detachExtOverlays() {
        removeOverlayView(mDockRoot);
        removeOverlayView(mPanelRoot);
        removeOverlayView(mRestorePill);
        removeOverlayView(mCursorView);
        mInLauncherHost = false;
        mExtWm = null;
        mExtCtx = null;
        mUsingA11yOverlay = false;
        mWinType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        mDockRoot = null;
        mDockHidden = false;
        mRestorePill = null;
        mRestoreLp = null;
        mPanelRoot = null;
        mPanelCard = null;
        mPanelOpen = false;
        mTrayPressed = false;
        mHandler.removeCallbacks(mOutsideDismissTask);
        mBtnToggle = null;
        mBtnPanelFullscreen = null;
        mBtnPanelMirror = null;
        mBtnPanelHideDock = null;
        mTaskStrip = null;
        mTaskDivider = null;
        mTrayBtn = null;
        mTrayWifiIcon = null;
        mTrayVolIcon = null;
        mTrayBattView = null;
        mTrayBattPct = null;
        mTileWifi = null;
        mTileBt = null;
        mTileInput = null;
        mTileAirplane = null;
        mTileTouchpad = null;
        mTilePowerSave = null;
        mVolSpeakerIcon = null;
        mVolSeekBar = null;
        mPanelBattView = null;
        mPanelBattText = null;
        mCursorView = null;
        mCursorVisible = false;
        mAttachedVdId = -1;
        mOverlayW = 0;
        mOverlayH = 0;
        mOverlayDpi = 0;
        mLastTaskSig = "";
    }

    private static int dp(Context ctx, float dpVal) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dpVal, ctx.getResources().getDisplayMetrics()));
    }

    private static View makeVerticalDivider(Context ctx) {
        View div = new View(ctx);
        div.setBackgroundColor(0x38FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1, dp(ctx, 22));
        lp.leftMargin = dp(ctx, 7);
        lp.rightMargin = dp(ctx, 7);
        div.setLayoutParams(lp);
        return div;
    }

    private static void enforceSingleLine(TextView tv) {
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setIncludeFontPadding(false);
        tv.setEllipsize(TextUtils.TruncateAt.END);
    }

    private static TextView makeDockBtn(Context ctx, String txt, int color, int textColor) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        tv.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        enforceSingleLine(tv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(ctx, 13));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static class QuickTile {
        final LinearLayout root;
        final LinearLayout box;
        final View leftBtn;
        final View rightBtn;
        final View splitSep;
        final MiniIconView icon;
        final TextView arrow;
        final TextView label;
        final boolean hasArrow;
        final Context ctx;
        boolean active = false;

        QuickTile(Context ctx, int iconKind, String initialLabel, boolean hasArrow,
                  Runnable onToggle, Runnable onMore) {
            this.ctx = ctx;
            this.hasArrow = hasArrow;

            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);

            box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44));
            boxLp.bottomMargin = dp(ctx, 5);
            root.addView(box, boxLp);

            LinearLayout leftWrap = new LinearLayout(ctx);
            leftWrap.setGravity(Gravity.CENTER);
            leftWrap.setOnClickListener(v -> { if (onToggle != null) onToggle.run(); });
            icon = new MiniIconView(ctx, iconKind, Color.WHITE);
            leftWrap.addView(icon, new LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)));
            box.addView(leftWrap, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            leftBtn = leftWrap;

            if (hasArrow) {
                splitSep = new View(ctx);
                splitSep.setBackgroundColor(0x28FFFFFF);
                box.addView(splitSep, new LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT));

                TextView arr = new TextView(ctx);
                arr.setText("›");
                arr.setTextColor(Color.WHITE);
                arr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                arr.setGravity(Gravity.CENTER);
                arr.setOnClickListener(v -> { if (onMore != null) onMore.run(); });
                box.addView(arr, new LinearLayout.LayoutParams(dp(ctx, 32), LinearLayout.LayoutParams.MATCH_PARENT));
                arrow = arr;
                rightBtn = arr;
            } else {
                splitSep = null;
                arrow = null;
                rightBtn = null;
            }

            label = new TextView(ctx);
            label.setText(initialLabel);
            label.setTextColor(0xFFE2E8F0);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            setActive(false, initialLabel);
        }

        void setActive(boolean on, String caption) {
            active = on;
            if (caption != null) label.setText(caption);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? 0xFFA6D8E8 : 0xFF2B3444);
            bg.setCornerRadius(dp(ctx, 8));
            bg.setStroke(1, on ? 0x44FFFFFF : 0x1AFFFFFF);
            box.setBackground(bg);

            int fg = on ? 0xFF0F172A : Color.WHITE;
            icon.setTint(fg);
            if (arrow != null) arrow.setTextColor(fg);
            if (splitSep != null) splitSep.setBackgroundColor(on ? 0x330F172A : 0x22FFFFFF);
        }
    }

    private static class MiniIconView extends View {
        static final int KIND_WIFI = 1;
        static final int KIND_BT = 2;
        static final int KIND_VOLUME = 3;
        static final int KIND_AIRPLANE = 4;
        static final int KIND_BATTERY_SAVER = 5;
        static final int KIND_DISPLAY_SWITCH = 6;
        static final int KIND_TOUCHPAD = 7;

        private final int mKind;
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mColor;
        private int mVolLevel = 2; // 0=mute, 1=low, 2=high
        private boolean mActiveState = true;

        MiniIconView(Context ctx, int kind, int color) {
            super(ctx);
            mKind = kind;
            mColor = color;
        }

        void setTint(int c) {
            if (mColor != c) { mColor = c; invalidate(); }
        }

        void setVolumeLevel(int lvl) {
            if (mVolLevel != lvl) { mVolLevel = lvl; invalidate(); }
        }

        void setActiveState(boolean a) {
            if (mActiveState != a) { mActiveState = a; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();
            if (w <= 0 || h <= 0) return;
            c.save();
            c.translate(getPaddingLeft(), getPaddingTop());

            mP.setColor(mActiveState ? mColor : (mColor & 0x66FFFFFF));
            mP.setStrokeCap(Paint.Cap.ROUND);
            mP.setStrokeJoin(Paint.Join.ROUND);

            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float s = Math.min(w, h);

            if (mKind == KIND_WIFI) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.8f, s * 0.11f));
                float oy = h * 0.80f;
                c.drawArc(new RectF(cx - s * 0.44f, oy - s * 0.62f, cx + s * 0.44f, oy + s * 0.26f), -135, 90, false, mP);
                c.drawArc(new RectF(cx - s * 0.28f, oy - s * 0.40f, cx + s * 0.28f, oy + s * 0.16f), -135, 90, false, mP);
                mP.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, oy - s * 0.04f, Math.max(1.5f, s * 0.09f), mP);
                if (!mActiveState) {
                    mP.setColor(mColor);
                    mP.setStyle(Paint.Style.STROKE);
                    c.drawLine(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f, mP);
                }
            } else if (mKind == KIND_BT) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                Path p = new Path();
                p.moveTo(cx - s * 0.16f, cy - s * 0.20f);
                p.lineTo(cx + s * 0.18f, cy + s * 0.16f);
                p.lineTo(cx, cy + s * 0.36f);
                p.lineTo(cx, cy - s * 0.36f);
                p.lineTo(cx + s * 0.18f, cy - s * 0.16f);
                p.lineTo(cx - s * 0.16f, cy + s * 0.20f);
                c.drawPath(p, mP);
            } else if (mKind == KIND_VOLUME) {
                mP.setColor(mColor);
                mP.setStyle(Paint.Style.FILL);
                Path spk = new Path();
                spk.moveTo(w * 0.14f, h * 0.38f);
                spk.lineTo(w * 0.32f, h * 0.38f);
                spk.lineTo(w * 0.54f, h * 0.20f);
                spk.lineTo(w * 0.54f, h * 0.80f);
                spk.lineTo(w * 0.32f, h * 0.62f);
                spk.lineTo(w * 0.14f, h * 0.62f);
                spk.close();
                c.drawPath(spk, mP);

                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.6f, s * 0.10f));
                if (mVolLevel <= 0) {
                    c.drawLine(w * 0.64f, h * 0.36f, w * 0.86f, h * 0.64f, mP);
                    c.drawLine(w * 0.86f, h * 0.36f, w * 0.64f, h * 0.64f, mP);
                } else {
                    c.drawArc(new RectF(w * 0.50f, h * 0.32f, w * 0.72f, h * 0.68f), -50, 100, false, mP);
                    if (mVolLevel >= 2) {
                        c.drawArc(new RectF(w * 0.52f, h * 0.18f, w * 0.88f, h * 0.82f), -52, 104, false, mP);
                    }
                }
            } else if (mKind == KIND_AIRPLANE) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.8f, s * 0.10f));
                c.drawLine(w * 0.15f, cy, w * 0.85f, cy, mP);
                c.drawLine(w * 0.42f, h * 0.16f, w * 0.56f, cy, mP);
                c.drawLine(w * 0.42f, h * 0.84f, w * 0.56f, cy, mP);
                c.drawLine(w * 0.18f, h * 0.34f, w * 0.26f, cy, mP);
                c.drawLine(w * 0.18f, h * 0.66f, w * 0.26f, cy, mP);
            } else if (mKind == KIND_BATTERY_SAVER) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                c.drawRoundRect(new RectF(w * 0.16f, h * 0.26f, w * 0.82f, h * 0.74f), s * 0.1f, s * 0.1f, mP);
                c.drawLine(w * 0.85f, h * 0.40f, w * 0.85f, h * 0.60f, mP);
                c.drawLine(w * 0.38f, h * 0.50f, w * 0.60f, h * 0.50f, mP);
                c.drawLine(w * 0.49f, h * 0.38f, w * 0.49f, h * 0.62f, mP);
            } else if (mKind == KIND_DISPLAY_SWITCH) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                c.drawRoundRect(new RectF(w * 0.14f, h * 0.20f, w * 0.86f, h * 0.68f), s * 0.1f, s * 0.1f, mP);
                c.drawLine(w * 0.34f, h * 0.82f, w * 0.66f, h * 0.82f, mP);
                c.drawLine(cx, h * 0.68f, cx, h * 0.82f, mP);
            } else if (mKind == KIND_TOUCHPAD) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                c.drawRoundRect(new RectF(w * 0.16f, h * 0.18f, w * 0.84f, h * 0.82f), s * 0.12f, s * 0.12f, mP);
                c.drawLine(w * 0.16f, h * 0.64f, w * 0.84f, h * 0.64f, mP);
                c.drawLine(cx, h * 0.64f, cx, h * 0.82f, mP);
            }
            c.restore();
        }
    }

    private static class MiniBatteryView extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mPct = 100;
        private boolean mCharging = false;

        MiniBatteryView(Context ctx) {
            super(ctx);
        }

        void setBattery(int pct, boolean charging) {
            if (mPct != pct || mCharging != charging) {
                mPct = pct;
                mCharging = charging;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float bodyR = w * 0.86f;
            float stroke = Math.max(1.4f, h * 0.11f);
            mP.setStyle(Paint.Style.STROKE);
            mP.setStrokeWidth(stroke);
            mP.setColor(0xFFE2E8F0);
            c.drawRoundRect(new RectF(stroke, stroke, bodyR, h - stroke), h * 0.18f, h * 0.18f, mP);

            mP.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(bodyR + stroke * 0.5f, h * 0.30f, w - 1f, h * 0.70f), 1.5f, 1.5f, mP);

            int fillCol = mCharging ? 0xFF4ADE80 : (mPct <= 20 ? 0xFFF87171 : 0xFFE2E8F0);
            mP.setColor(fillCol);
            float pad = stroke * 1.6f;
            float avail = Math.max(0f, bodyR - pad * 2f);
            c.drawRoundRect(new RectF(pad, pad, pad + avail * (mPct / 100f), h - pad), h * 0.10f, h * 0.10f, mP);
        }
    }

    private static class CursorView extends View {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mArrow = new Path();
        private boolean mPressing = false;

        CursorView(Context ctx) {
            super(ctx);
            mFill.setColor(0xFF0F172A);
            mFill.setStyle(Paint.Style.FILL);
            mStroke.setColor(Color.WHITE);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(2.6f);
            mStroke.setStrokeJoin(Paint.Join.ROUND);

            mRing.setColor(0x9938BDF8);
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(2.5f);

            mArrow.moveTo(6f, 4f);
            mArrow.lineTo(6f, 34f);
            mArrow.lineTo(13.5f, 26.5f);
            mArrow.lineTo(19f, 38f);
            mArrow.lineTo(23.5f, 36f);
            mArrow.lineTo(18f, 24.5f);
            mArrow.lineTo(28f, 24.5f);
            mArrow.close();
        }

        void setPressing(boolean p) {
            if (mPressing != p) {
                mPressing = p;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mPressing) {
                canvas.drawCircle(6f, 6f, 10f, mRing);
            }
            canvas.drawPath(mArrow, mFill);
            canvas.drawPath(mArrow, mStroke);
        }
    }
}
