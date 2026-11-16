package com.extbridge.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int[][] RES_PRESETS = {
            {3840, 2160},
            {2560, 1600},
            {2560, 1440},
            {1920, 1200},
            {1920, 1080},
            {1600, 900},
            {1280, 720}
    };
    private static final String[] RES_LABELS = {
            "3840×2160\n4K 16:9",
            "2560×1600\n2.5K 16:10",
            "2560×1440\n2K 原生★",
            "1920×1200\n16:10",
            "1920×1080\n1080p 16:9",
            "1600×900\n900p 16:9",
            "1280×720\n720p 省流"
    };

    private static final int[] DPI_PRESETS = {160, 200, 240, 280, 320};
    private static final String[] DPI_LABELS = {"160\n超高密", "200\n紧凑桌面", "240\n标准★", "280\n大字", "320\n超大"};
    private static final float[] FPS_PRESETS = {165f, 144f, 120f, 90f, 75f, 60f};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mSelW = 2560;
    private int mSelH = 1440;
    private int mSelDpi = 240;
    private float mSelFps = 60f;
    private int mSelPhysModeId = -1;
    private boolean mInitedFromDaemon = false;

    private TextView mTvStatusTitle;
    private TextView mTvStatusDetail;
    private TextView mBtnToggleInput;
    private TextView mBtnToggleMirror;
    private TextView mBtnFullscreenPolicy;
    private TextView mBtnAppModes;
    private TextView mBtnActivate;
    private TextView mTvSwInfo;
    private TextView mTvGestureStatus;
    private TextView mTvRuntime;
    private TextView mTvDeviceBrief;
    private TextView mTvPageTitle;
    private TextView mTvMetricRes;
    private TextView mTvMetricFps;
    private TextView mTvMetricInput;
    private TextView mTvDriverSummary;
    private TextView mTvSideStatus;
    private TextView mTvAppModesSummary;
    private TextView mTvPermOverlay;
    private TextView mTvPermNotif;
    private TextView mTvPermA11y;
    private View mStatusDot;
    private LinearLayout mDock;
    private final List<DockItem> mDockItems = new ArrayList<>();
    private LinearLayout mHeroCard;
    private TextView mHeroTag;
    private TextView mBtnHeroOpen;
    private TextView mBtnHeroRebuild;
    private TextView mBtnHeroAct;
    private FrameLayout mPageHost;
    private ScrollView mPageOverview;
    private ScrollView mPageDisplay;
    private ScrollView mPageApps;
    private ScrollView mPageDriver;
    private ScrollView mPageSettings;
    private int mCurrentPage = -1;
    private TextView mBtnRootActivate;
    private TextView mBtnDriverInfo;
    private TextView mBtnAutostart;
    private TextView mBtnLsposed;
    private volatile String mLastDriverJson;
    private EditText mEdW;
    private EditText mEdH;
    private LinearLayout mPhysModesWrap;
    private String mLastModesRaw = "";

    private AlertDialog mActivationDialog;
    private boolean mUserSkippedDialog = false;
    private boolean mActWorking = false;
    private boolean mHostEdited = false;
    private boolean mPairPortEdited = false;
    private boolean mConnPortEdited = false;
    private EditText mEdPairHost;
    private EditText mEdPairPort;
    private EditText mEdPairCode;
    private EditText mEdConnPort;
    private TextView mTvMdnsHint;
    private TextView mTvActStatus;

    private final List<TextView> mResChips = new ArrayList<>();
    private final List<TextView> mDpiChips = new ArrayList<>();
    private final List<TextView> mFpsChips = new ArrayList<>();

    private final Runnable mRefreshLoop = new Runnable() {
        @Override
        public void run() {
            BridgeClient.Status st = BridgeClient.getStatus();
            if (!st.connected) {
                BridgeClient.requestBinder(MainActivity.this);
            }
            refreshStatus(false);
            mHandler.postDelayed(this, st.connected ? 2000 : 1200);
        }
    };

    private final Runnable mAutoPromptCheck = () -> {
        if (isFinishing()) return;
        BridgeClient.Status st = BridgeClient.getStatus();
        if (!st.connected && !mUserSkippedDialog && (mActivationDialog == null || !mActivationDialog.isShowing())) {
            showActivationDialog();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ensureOnDisplay0()) return;
        BridgeService.ensureStarted(this);
        buildUi();
        refreshStatus(true);
    }

    private boolean ensureOnDisplay0() {
        try {
            if (getDisplay() != null && getDisplay().getDisplayId() != 0) {
                Intent i = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                android.app.ActivityOptions o0 = android.app.ActivityOptions.makeBasic();
                o0.setLaunchDisplayId(0);
                startActivity(i, o0.toBundle());
                finishAndRemoveTask();
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeClient.setOnBinderReady(() -> mHandler.post(() -> refreshStatus(true)));
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this);
        BridgeService.ensureStarted(this);
        refreshStatus(false);
        mHandler.post(mRefreshLoop);
        mHandler.removeCallbacks(mAutoPromptCheck);
        mHandler.postDelayed(mAutoPromptCheck, 450);
    }

    @Override
    protected void onPause() {
        super.onPause();
        BridgeClient.setOnBinderReady(null);
        mHandler.removeCallbacks(mRefreshLoop);
        mHandler.removeCallbacks(mAutoPromptCheck);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mActivationDialog != null && mActivationDialog.isShowing()) {
            mActivationDialog.dismiss();
        }
        AdbBootstrap.stopDiscovery();
    }

    private static final String[] PAGE_TITLES = {"概览", "显示", "应用", "驱动", "设置"};
    // ==================== 主界面构建 ====================

    private void buildUi() {
        getWindow().setStatusBarColor(0xFF040507);
        getWindow().setNavigationBarColor(0xFF040507);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        FrameLayout base = new FrameLayout(this);
        base.addView(new LiquidBackgroundView(this),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        base.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(0, Ui.dpi(this, 2), 0, 0);
        shell.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        main.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mPageHost = new FrameLayout(this);
        main.addView(mPageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mPageOverview = makePage();
        mPageDisplay = makePage();
        mPageApps = makePage();
        mPageDriver = makePage();
        mPageSettings = makePage();
        addPage(mPageOverview);
        addPage(mPageDisplay);
        addPage(mPageApps);
        addPage(mPageDriver);
        addPage(mPageSettings);

        buildOverviewPage(pageContent(mPageOverview));
        buildDisplayPage(pageContent(mPageDisplay));
        buildAppsPage(pageContent(mPageApps));
        buildDriverPage(pageContent(mPageDriver));
        buildSettingsPage(pageContent(mPageSettings));

        mDock = buildDock();
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                Math.min(Ui.dpi(this, 620), getResources().getDisplayMetrics().widthPixels - Ui.dpi(this, 24)),
                Ui.dpi(this, 68));
        dockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dockLp.bottomMargin = Ui.dpi(this, 16);
        base.addView(mDock, dockLp);

        final LinearLayout fshell = shell;
        base.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            fshell.setPadding(0, bars.top, 0, 0);
            if (mDock != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mDock.getLayoutParams();
                lp.bottomMargin = bars.bottom + Ui.dpi(this, 14);
                mDock.setLayoutParams(lp);
            }
            return insets;
        });
        base.requestApplyInsets();

        setContentView(base);
        switchPage(0);
        updateSelectionStyles();
    }

    private LinearLayout buildDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(Ui.dpi(this, 6), Ui.dpi(this, 6), Ui.dpi(this, 6), Ui.dpi(this, 6));
        GlassDrawable bg = new GlassDrawable(34f, getResources().getDisplayMetrics().density,
                0xE61C1C20, 0xD90E0E10, 0x38FFFFFF);
        dock.setBackground(bg);
        dock.setElevation(Ui.dp(this, 24));
        dock.setOutlineProvider(GlassDrawable.outlineProvider());
        dock.setClipToOutline(false);
        mDockItems.clear();
        addDockItem(dock, R.drawable.ic_home, "概览", 0);
        addDockItem(dock, R.drawable.ic_display, "显示", 1);
        addDockItem(dock, R.drawable.ic_apps, "应用", 2);
        addDockItem(dock, R.drawable.ic_chip, "驱动", 3);
        addDockItem(dock, R.drawable.ic_settings, "设置", 4);
        return dock;
    }

    private void addDockItem(LinearLayout dock, int icon, String label, int page) {
        DockItem item = new DockItem(this, icon, label, Ui.ACCENT);
        item.setOnClickListener(v -> switchPage(page));
        mDockItems.add(item);
        dock.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }


    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int hSide = contentSide();
        header.setPadding(hSide, Ui.dpi(this, 6), hSide, Ui.dpi(this, 8));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        mTvPageTitle = Ui.title(this, PAGE_TITLES[0], 22f);
        mTvPageTitle.setLetterSpacing(-0.01f);
        titles.addView(mTvPageTitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (mTvSideStatus == null) {
            mTvSideStatus = Ui.pill(this, "未连接", Ui.TEXT3);
            mTvSideStatus.setTextSize(10.5f);
        }
        if (mTvSideStatus != null) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.rightMargin = Ui.dpi(this, 8);
            header.addView(mTvSideStatus, slp);
        }

        View refresh = iconButton(R.drawable.ic_refresh, () -> {
            BridgeClient.rebuild();
            refreshStatus(true);
            Toast.makeText(this, "已请求重建扩展桌面", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Ui.dpi(this, 38), Ui.dpi(this, 38));
        rlp.rightMargin = Ui.dpi(this, 8);
        header.addView(refresh, rlp);

        mBtnActivate = Ui.primaryButton(this, "激活");
        mBtnActivate.setTextSize(13f);
        mBtnActivate.setPadding(Ui.dpi(this, 16), Ui.dpi(this, 8), Ui.dpi(this, 16), Ui.dpi(this, 8));
        mBtnActivate.setMinHeight(Ui.dpi(this, 38));
        mBtnActivate.setOnClickListener(v -> {
            mUserSkippedDialog = false;
            showActivationDialog();
        });
        header.addView(mBtnActivate, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return header;
    }

    private View iconButton(int res, Runnable action) {
        FrameLayout f = new FrameLayout(this);
        int s = Ui.dpi(this, 38);
        f.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(Ui.dp(this, 19));
        f.setBackground(bg);
        Ui.setRippleKeepBackground(f, 19);
        ImageView iv = Ui.icon(this, res, Ui.TEXT2, 18);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dpi(this, 18), Ui.dpi(this, 18));
        ilp.gravity = Gravity.CENTER;
        f.addView(iv, ilp);
        f.setClickable(true);
        f.setFocusable(true);
        f.setOnClickListener(v -> action.run());
        return f;
    }

    private ScrollView makePage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(false);
        sv.setClipToPadding(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int side = contentSide();
        box.setPadding(side, Ui.dpi(this, 8), side, Ui.dpi(this, 112));
        sv.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private LinearLayout pageContent(ScrollView sv) {
        return (LinearLayout) sv.getChildAt(0);
    }

    private void addPage(ScrollView page) {
        mPageHost.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildOverviewPage(LinearLayout box) {
        box.addView(Ui.section(this, "运行状态"));
        LinearLayout hero = Ui.card(this);
        mHeroCard = hero;
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        mStatusDot = Ui.dot(this, Ui.RED, 9);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dpi(this, 9), Ui.dpi(this, 9));
        dlp.rightMargin = Ui.dpi(this, 10);
        statusRow.addView(mStatusDot, dlp);
        mTvStatusTitle = Ui.title(this, "服务未运行", 22f);
        statusRow.addView(mTvStatusTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mHeroTag = Ui.pill(this, "未连接", Ui.TEXT3);
        statusRow.addView(mHeroTag);
        hero.addView(statusRow);

        mTvStatusDetail = Ui.text(this,
                "通过 Root 或无线调试启动后台守护进程，再把外接显示器变成独立扩展桌面。",
                12.5f, Ui.TEXT2);
        mTvStatusDetail.setPadding(0, Ui.dpi(this, 9), 0, Ui.dpi(this, 16));
        hero.addView(mTvStatusDetail);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        mTvMetricRes = addMetric(metrics, "—", "输出分辨率", true);
        mTvMetricFps = addMetric(metrics, "—", "刷新率", true);
        mTvMetricInput = addMetric(metrics, "—", "键鼠方向", false);
        hero.addView(metrics);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dpi(this, 16), 0, 0);

        mBtnRootActivate = Ui.primaryButton(this, "Root 一键激活");
        mBtnRootActivate.setOnClickListener(v -> runRootActivation());
        actions.addView(mBtnRootActivate, actionLp(8));

        mBtnHeroOpen = Ui.primaryButton(this, "打开触摸板");
        mBtnHeroOpen.setOnClickListener(v -> startActivity(new Intent(this, TouchpadActivity.class)));
        mBtnHeroOpen.setVisibility(View.GONE);
        actions.addView(mBtnHeroOpen, actionLp(8));

        mBtnHeroAct = Ui.tonalButton(this, "激活方式");
        mBtnHeroAct.setOnClickListener(v -> {
            mUserSkippedDialog = false;
            showActivationDialog();
        });
        actions.addView(mBtnHeroAct, actionLp(8));

        mBtnHeroRebuild = Ui.tonalButton(this, "重建桌面");
        mBtnHeroRebuild.setOnClickListener(v -> {
            BridgeClient.rebuild();
            refreshStatus(true);
            Toast.makeText(this, "已请求重建扩展桌面", Toast.LENGTH_SHORT).show();
        });
        mBtnHeroRebuild.setVisibility(View.GONE);
        actions.addView(mBtnHeroRebuild, actionLp(0));
        hero.addView(actions);
        box.addView(hero);

        LinearLayout quick = Ui.card(this);
        TextView quickTitle = Ui.medium(this, "快捷操作", 13f, Ui.TEXT2);
        quickTitle.setPadding(Ui.dpi(this, 4), 0, 0, Ui.dpi(this, 6));
        quick.addView(quickTitle, 0);

        mBtnToggleInput = Ui.value(this, "扩展屏", Ui.ACCENT);
        mBtnToggleInput.setOnClickListener(v -> {
            int vv = BridgeClient.toggleInput();
            Toast.makeText(this, vv == 1 ? "键鼠已切到扩展屏" : "键鼠已切回平板", Toast.LENGTH_SHORT).show();
            BridgeService.refreshFromReceiver(this);
            refreshStatus(false);
        });
        addSetting(quick, R.drawable.ic_touchpad, "键鼠方向", "在扩展屏与平板之间切换", mBtnToggleInput, null);

        mBtnToggleMirror = Ui.value(this, "DeX", Ui.PURPLE);
        mBtnToggleMirror.setOnClickListener(v -> {
            int mm = BridgeClient.toggleMirrorMode();
            Toast.makeText(this, mm == 1 ? "已切换为原生镜像" : "已切回独立桌面", Toast.LENGTH_SHORT).show();
            BridgeService.refreshFromReceiver(this);
            refreshStatus(false);
        });
        addSetting(quick, R.drawable.ic_layers, "显示模式", "DeX 独立桌面 / 原生镜像", mBtnToggleMirror, null);

        mBtnFullscreenPolicy = Ui.value(this, "开", Ui.TEAL);
        mBtnFullscreenPolicy.setOnClickListener(v -> {
            int fp = BridgeClient.toggleFullscreenPolicy();
            Toast.makeText(this, fp == 1 ? "已开启强制横屏" : "已关闭强制横屏", Toast.LENGTH_SHORT).show();
            BridgeService.refreshFromReceiver(this);
            refreshStatus(false);
        });
        addSetting(quick, R.drawable.ic_fullscreen, "强制横屏", "扩展桌面锁定横屏大屏", mBtnFullscreenPolicy, null);

        mBtnAppModes = Ui.value(this, "管理", Ui.TEXT2);
        mBtnAppModes.setOnClickListener(v -> showAppModesDialog());
        addSetting(quick, R.drawable.ic_apps, "分应用显示模式", "按包名单独设置兼容策略", mBtnAppModes, null);

        addSetting(quick, R.drawable.ic_touchpad, "打开触摸板", "手机 / 平板变成大面积触控板", Ui.chevron(this, Ui.TEXT3),
                () -> startActivity(new Intent(this, TouchpadActivity.class)));
        addSetting(quick, R.drawable.ic_monitor, "扩展桌面", "把桌面启动器投到外接屏", Ui.chevron(this, Ui.TEXT3),
                () -> BridgeClient.relaunchLauncher());
        LinearLayout runtime = Ui.card(this);
        TextView runtimeTitle = Ui.medium(this, "运行模式", 13f, Ui.TEXT2);
        runtimeTitle.setPadding(Ui.dpi(this, 4), 0, 0, Ui.dpi(this, 6));
        runtime.addView(runtimeTitle);
        mTvRuntime = Ui.text(this, "运行模式：检测中…", 12.5f, Ui.TEXT2);
        mTvRuntime.setPadding(Ui.dpi(this, 4), 0, Ui.dpi(this, 4), 0);
        runtime.addView(mTvRuntime);

        LinearLayout dev = Ui.card(this);
        TextView devTitle = Ui.medium(this, "本机", 13f, Ui.TEXT2);
        devTitle.setPadding(Ui.dpi(this, 4), 0, 0, Ui.dpi(this, 6));
        dev.addView(devTitle);
        mTvDeviceBrief = Ui.text(this, deviceBriefText(), 12.5f, Ui.TEXT2);
        mTvDeviceBrief.setPadding(Ui.dpi(this, 4), 0, Ui.dpi(this, 4), 0);
        dev.addView(mTvDeviceBrief);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.addView(quick);
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.addView(runtime);
        rightCol.addView(dev);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.55f);
        llp.rightMargin = Ui.dpi(this, 10);
        grid.addView(leftCol, llp);
        grid.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(grid);
    }

    private int contentSide() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        return Math.max(Ui.dpi(this, 16), (screenW - Ui.dpi(this, 1180)) / 2);
    }

    private LinearLayout.LayoutParams actionLp(int rightMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dpi(this, rightMarginDp);
        return lp;
    }

    private void buildDisplayPage(LinearLayout box) {
        LinearLayout resCard = Ui.card(this);
        cardTitle(resCard, "输出分辨率");
        List<View> resViews = new ArrayList<>();
        mResChips.clear();
        for (int i = 0; i < RES_PRESETS.length; i++) {
            final int w = RES_PRESETS[i][0], h = RES_PRESETS[i][1];
            TextView chip = makeChip(RES_LABELS[i]);
            chip.setOnClickListener(v -> {
                mSelW = w;
                mSelH = h;
                mEdW.setText(String.valueOf(w));
                mEdH.setText(String.valueOf(h));
                updateSelectionStyles();
            });
            mResChips.add(chip);
            resViews.add(chip);
        }
        addChipWrap(resCard, resViews);

        LinearLayout custom = new LinearLayout(this);
        custom.setOrientation(LinearLayout.HORIZONTAL);
        custom.setGravity(Gravity.CENTER_VERTICAL);
        custom.setPadding(0, Ui.dpi(this, 12), 0, 0);
        TextView lab = Ui.text(this, "自定义", 12.5f, Ui.TEXT3);
        lab.setPadding(0, 0, Ui.dpi(this, 12), 0);
        custom.addView(lab);
        mEdW = makeNumBox(String.valueOf(mSelW));
        mEdH = makeNumBox(String.valueOf(mSelH));
        custom.addView(mEdW);
        TextView x = Ui.text(this, "  ×  ", 14f, Ui.TEXT2);
        custom.addView(x);
        custom.addView(mEdH);
        resCard.addView(custom);

        LinearLayout dpiCard = Ui.card(this);
        cardTitle(dpiCard, "界面缩放");
        List<View> dpiViews = new ArrayList<>();
        mDpiChips.clear();
        for (int i = 0; i < DPI_PRESETS.length; i++) {
            final int dpi = DPI_PRESETS[i];
            TextView chip = makeChip(DPI_LABELS[i]);
            chip.setOnClickListener(v -> {
                mSelDpi = dpi;
                updateSelectionStyles();
            });
            mDpiChips.add(chip);
            dpiViews.add(chip);
        }
        addChipWrap(dpiCard, dpiViews);

        LinearLayout fpsCard = Ui.card(this);
        cardTitle(fpsCard, "刷新率");
        List<View> fpsViews = new ArrayList<>();
        mFpsChips.clear();
        for (final float fps : FPS_PRESETS) {
            TextView chip = makeChip(Math.round(fps) + " Hz");
            chip.setOnClickListener(v -> {
                mSelFps = fps;
                updateSelectionStyles();
            });
            mFpsChips.add(chip);
            fpsViews.add(chip);
        }
        addChipWrap(fpsCard, fpsViews);

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.addView(dpiCard);
        rightCol.addView(fpsCard);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.45f);
        llp.rightMargin = Ui.dpi(this, 10);
        grid.addView(resCard, llp);
        grid.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(grid);

        box.addView(Ui.section(this, "显示器硬件模式"));
        LinearLayout modeCard = Ui.card(this);
        mPhysModesWrap = new LinearLayout(this);
        mPhysModesWrap.setOrientation(LinearLayout.VERTICAL);
        modeCard.addView(mPhysModesWrap);
        mTvSwInfo = Ui.text(this, "", 11.5f, Ui.TEXT3);
        mTvSwInfo.setPadding(0, Ui.dpi(this, 10), 0, 0);
        modeCard.addView(mTvSwInfo);
        box.addView(modeCard);

        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        TextView apply = Ui.primaryButton(this, "应用显示设置");
        apply.setOnClickListener(v -> applyChosenConfig());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f);
        alp.rightMargin = Ui.dpi(this, 8);
        ops.addView(apply, alp);
        TextView reset = Ui.tonalButton(this, "恢复推荐");
        reset.setOnClickListener(v -> {
            mSelW = 1920;
            mSelH = 1080;
            mSelDpi = 240;
            mSelFps = 60f;
            mEdW.setText("1920");
            mEdH.setText("1080");
            updateSelectionStyles();
            applyChosenConfig();
        });
        ops.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(ops);
    }

    private void cardTitle(LinearLayout card, String title) {
        TextView tv = Ui.medium(this, title, 13f, Ui.TEXT2);
        tv.setPadding(Ui.dpi(this, 4), 0, 0, Ui.dpi(this, 6));
        card.addView(tv, 0);
    }

    private void buildAppsPage(LinearLayout box) {
        box.addView(Ui.section(this, "兼容策略"));
        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.text(this,
                "每个应用可以独立选择显示策略，不修改全局 Settings.Global，避免影响微信、夸克、拼多多等应用的原生布局。",
                12.5f, Ui.TEXT2));
        box.addView(intro);

        LinearLayout modes = Ui.card(this);
        addModeInfo(modes, Ui.TEXT2, "跟随系统默认", "不注入兼容参数，推荐大多数应用");
        addModeInfo(modes, Ui.ACCENT, "强制横屏全屏", "适合红果短剧等锁定竖屏的视频应用");
        addModeInfo(modes, Ui.AMBER, "竖屏居中兼容", "保持竖屏比例，不拉伸变形");
        addModeInfo(modes, Ui.PURPLE, "自由小窗模式", "以 Freeform 小窗启动，可自由缩放");
        box.addView(modes);

        box.addView(Ui.section(this, "已配置应用"));
        LinearLayout appsCard = Ui.card(this);
        mTvAppModesSummary = Ui.text(this, "正在读取…", 12.5f, Ui.TEXT2);
        appsCard.addView(mTvAppModesSummary);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dpi(this, 14), 0, 0);
        TextView manage = Ui.primaryButton(this, "管理应用");
        manage.setOnClickListener(v -> showAppModesDialog());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.rightMargin = Ui.dpi(this, 8);
        actions.addView(manage, mlp);
        TextView reset = Ui.tonalButton(this, "全部重置");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("重置所有应用")
                .setMessage("将清除所有分应用兼容覆盖，并恢复到跟随系统默认。")
                .setPositiveButton("重置", (d, w) -> {
                    BridgeClient.resetAllAppModes();
                    Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show();
                    refreshAppModesSummary();
                })
                .setNegativeButton("取消", null)
                .show());
        actions.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        appsCard.addView(actions);
        box.addView(appsCard);
    }

    private void addModeInfo(LinearLayout card, int color, String title, String sub) {
        if (card.getChildCount() > 0) Ui.divider(this, card);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dpi(this, 6), Ui.dpi(this, 12), Ui.dpi(this, 6), Ui.dpi(this, 12));
        View dot = Ui.dot(this, color, 8);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dpi(this, 8), Ui.dpi(this, 8));
        dlp.rightMargin = Ui.dpi(this, 14);
        row.addView(dot, dlp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.medium(this, title, 14f, Ui.TEXT));
        TextView s = Ui.text(this, sub, 11.5f, Ui.TEXT3);
        s.setPadding(0, Ui.dpi(this, 2), 0, 0);
        texts.addView(s);
        row.addView(texts);
        card.addView(row);
    }

    private void buildDriverPage(LinearLayout box) {
        box.addView(Ui.section(this, "Root 与系统框架"));
        LinearLayout env = Ui.card(this);
        TextView note = Ui.text(this,
                "守护进程可以以 root（app_process）或 LSPosed（注入 system_server）运行。两种模式下都能读取 DRM / EDID、GPU、面板、Type-C 与 dmesg 等驱动信息。",
                12.5f, Ui.TEXT2);
        env.addView(note);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, Ui.dpi(this, 14), 0, 0);
        TextView root = Ui.primaryButton(this, "Root 一键激活");
        root.setOnClickListener(v -> runRootActivation());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.rightMargin = Ui.dpi(this, 8);
        row1.addView(root, rlp);
        mBtnLsposed = Ui.tonalButton(this, "LSPosed 状态");
        mBtnLsposed.setOnClickListener(v -> showLsposedDialog());
        row1.addView(mBtnLsposed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        env.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, Ui.dpi(this, 8), 0, 0);
        mBtnAutostart = Ui.tonalButton(this, "开机自启");
        mBtnAutostart.setOnClickListener(v -> toggleRootAutostart());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        alp.rightMargin = Ui.dpi(this, 8);
        row2.addView(mBtnAutostart, alp);
        mBtnDriverInfo = Ui.primaryButton(this, "采集驱动信息");
        mBtnDriverInfo.setOnClickListener(v -> collectDriverInfo());
        row2.addView(mBtnDriverInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        env.addView(row2);
        box.addView(env);

        box.addView(Ui.section(this, "最近一次采集"));
        LinearLayout result = Ui.card(this);
        mTvDriverSummary = Ui.text(this,
                "尚未采集。点击「采集驱动信息」读取本机显示、GPU、输入与 Type-C 状态。",
                12f, Ui.TEXT2);
        result.addView(mTvDriverSummary);
        LinearLayout outRow = new LinearLayout(this);
        outRow.setOrientation(LinearLayout.HORIZONTAL);
        outRow.setPadding(0, Ui.dpi(this, 14), 0, 0);
        TextView export = Ui.tonalButton(this, "保存 JSON");
        export.setOnClickListener(v -> exportDriverJson());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        elp.rightMargin = Ui.dpi(this, 8);
        outRow.addView(export, elp);
        TextView share = Ui.ghostButton(this, "分享");
        share.setOnClickListener(v -> shareDriverJson());
        outRow.addView(share, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        result.addView(outRow);
        box.addView(result);
    }

    private void buildSettingsPage(LinearLayout box) {
        LinearLayout perm = Ui.card(this);
        cardTitle(perm, "权限与授权");
        mTvGestureStatus = Ui.text(this, "", 12f, Ui.TEXT2);
        mTvGestureStatus.setPadding(Ui.dpi(this, 6), 0, Ui.dpi(this, 6), Ui.dpi(this, 10));
        perm.addView(mTvGestureStatus);

        mTvPermOverlay = Ui.value(this, "检查中", Ui.TEXT3);
        addSetting(perm, R.drawable.ic_display, "悬浮窗权限", "显示鼠标光标与浮动控制条", mTvPermOverlay,
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        mTvPermNotif = Ui.value(this, "检查中", Ui.TEXT3);
        addSetting(perm, R.drawable.ic_info, "通知权限", "快捷控制与无线调试配对通知", mTvPermNotif,
                () -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        mTvPermA11y = Ui.value(this, "检查中", Ui.TEXT3);
        addSetting(perm, R.drawable.ic_touchpad, "辅助手势", "可选：系统手势兜底", mTvPermA11y,
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        LinearLayout act = Ui.card(this);
        cardTitle(act, "激活与常驻");
        addSetting(act, R.drawable.ic_power, "激活方式", "Root / 无线调试 / 电脑 ADB", Ui.chevron(this, Ui.TEXT3),
                () -> {
                    mUserSkippedDialog = false;
                    showActivationDialog();
                });
        addSetting(act, R.drawable.ic_refresh, "Root 开机自启", "重启后自动拉起守护进程", Ui.chevron(this, Ui.TEXT3),
                this::toggleRootAutostart);
        addSetting(act, R.drawable.ic_shield, "LSPosed 插件模式", "注入 system_server，无需每次启动", Ui.chevron(this, Ui.TEXT3),
                this::showLsposedDialog);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f);
        plp.rightMargin = Ui.dpi(this, 10);
        grid.addView(perm, plp);
        grid.addView(act, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(grid);

        LinearLayout about = Ui.card(this);
        cardTitle(about, "关于");
        about.addView(Ui.text(this,
                "ExtBridge 4.0.0-root\nAndroid 17 · XRING O1 · 小米平板\n开源项目，MIT License",
                12f, Ui.TEXT2));
        box.addView(about);
    }

    private TextView addMetric(LinearLayout row, String value, String label, boolean divider) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tv = Ui.medium(this, value, 17f, Ui.TEXT);
        tv.setIncludeFontPadding(false);
        col.addView(tv);
        TextView lb = Ui.text(this, label, 10.5f, Ui.TEXT3);
        lb.setPadding(0, Ui.dpi(this, 4), 0, 0);
        col.addView(lb);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (divider) {
            View d = new View(this);
            d.setBackgroundColor(0x18FFFFFF);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Math.max(1, Ui.dpi(this, 0.8f)),
                    Ui.dpi(this, 30));
            dlp.rightMargin = Ui.dpi(this, 16);
            row.addView(d, dlp);
        }
        return tv;
    }

    private LinearLayout addSetting(LinearLayout card, int icon, String title, String subtitle,
                                    View trailing, Runnable action) {
        if (card.getChildCount() > 0) Ui.divider(this, card);
        LinearLayout row = Ui.settingRow(this, icon, title, subtitle, trailing);
        if (action != null) {
            row.setOnClickListener(v -> action.run());
        } else if (trailing != null) {
            row.setOnClickListener(v -> trailing.performClick());
        }
        card.addView(row);
        return row;
    }

    private void switchPage(int index) {
        if (index < 0 || index > 4) return;
        if (mCurrentPage == index) return;
        mCurrentPage = index;
        mPageOverview.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        mPageDisplay.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        mPageApps.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        mPageDriver.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        mPageSettings.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
        mTvPageTitle.setText(PAGE_TITLES[index]);
        for (int i = 0; i < mDockItems.size() && i < 5; i++) {
            mDockItems.get(i).setSelectedState(i == index);
        }
        if (index == 2) refreshAppModesSummary();
        if (index == 4) refreshStatus(false);
    }

    private void refreshAppModesSummary() {
        if (mTvAppModesSummary == null) return;
        new Thread(() -> {
            Map<String, Integer> modes = BridgeClient.getAllAppModes();
            int count = modes == null ? 0 : modes.size();
            StringBuilder names = new StringBuilder();
            if (modes != null) {
                int shown = 0;
                for (String pkg : modes.keySet()) {
                    if (shown++ >= 6) break;
                    if (names.length() > 0) names.append("、");
                    names.append(pkg);
                }
            }
            final String msg = count == 0
                    ? "当前没有应用使用自定义兼容模式，全部跟随系统默认。"
                    : ("已配置 " + count + " 个应用：" + names + (count > 6 ? " …" : "")
                    + "\n点击「管理应用」可按应用调整。");
            runOnUiThread(() -> mTvAppModesSummary.setText(msg));
        }).start();
    }

    private void exportDriverJson() {
        String json = mLastDriverJson;
        if (json == null || json.isEmpty()) {
            Toast.makeText(this, "请先采集驱动信息", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "extbridge-driver-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new java.util.Date()) + ".json";
        RootBootstrap.saveJsonAsync(this, json, name, new RootBootstrap.Listener() {
            @Override public void onStep(String message) {}
            @Override public void onResult(boolean success, String message) {
                Toast.makeText(MainActivity.this, success ? "已保存: " + message : "保存失败: " + message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void shareDriverJson() {
        String json = mLastDriverJson;
        if (json == null || json.isEmpty()) {
            Toast.makeText(this, "请先采集驱动信息", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "ExtBridge 驱动信息");
        i.putExtra(Intent.EXTRA_TEXT, json);
        startActivity(Intent.createChooser(i, "分享驱动信息"));
    }

    private void applyChosenConfig() {
        try {
            int cw = Integer.parseInt(mEdW.getText().toString().trim());
            int ch = Integer.parseInt(mEdH.getText().toString().trim());
            mSelW = Math.max(640, Math.min(7680, cw));
            mSelH = Math.max(480, Math.min(4320, ch));
        } catch (Throwable ignored) {}

        boolean ok = BridgeClient.applyConfig(mSelW, mSelH, mSelDpi, mSelFps, mSelPhysModeId);
        Toast.makeText(this,
                ok ? ("已应用：" + mSelW + "×" + mSelH + " · " + mSelDpi + "dpi · " + Math.round(mSelFps) + "Hz")
                   : "应用失败，请确认 ExtBridge 后台服务正在运行",
                Toast.LENGTH_SHORT).show();
        BridgeService.refreshFromReceiver(this);
        refreshStatus(false);
    }

    // ==================== 状态、权限与运行模式 ====================

    private void refreshStatus(boolean syncSelection) {
        BridgeClient.Status st = BridgeClient.getStatus();
        refreshRuntimeCard(st);
        if (!st.connected) {
            mTvStatusTitle.setText("服务未运行");
            mTvStatusTitle.setTextColor(Ui.TEXT);
            setDotColor(mStatusDot, Ui.RED);
            setHeroTint(0xFF241318, "未连接", Ui.RED);
            mTvStatusDetail.setText("后台守护进程尚未启动。使用 Root 或无线调试激活后，插入外接显示器即可自动进入扩展桌面。");
            if (mTvMetricRes != null) mTvMetricRes.setText("—");
            if (mTvMetricFps != null) mTvMetricFps.setText("—");
            if (mTvMetricInput != null) mTvMetricInput.setText("—");
            if (mBtnToggleInput != null) mBtnToggleInput.setText("扩展屏");
            if (mBtnToggleMirror != null) mBtnToggleMirror.setText("DeX");
            if (mBtnFullscreenPolicy != null) mBtnFullscreenPolicy.setText("开");
            if (mBtnActivate != null) mBtnActivate.setVisibility(View.VISIBLE);
            if (mBtnHeroOpen != null) mBtnHeroOpen.setVisibility(View.GONE);
            if (mBtnHeroRebuild != null) mBtnHeroRebuild.setVisibility(View.GONE);
            if (mBtnHeroAct != null) mBtnHeroAct.setVisibility(View.VISIBLE);
            if (mTvSideStatus != null) {
                mTvSideStatus.setText("未连接");
                mTvSideStatus.setTextColor(Ui.RED);
            }
        } else {
            mUserSkippedDialog = false;
            if (mBtnActivate != null) mBtnActivate.setVisibility(View.GONE);
            if (mBtnHeroOpen != null) mBtnHeroOpen.setVisibility(View.VISIBLE);
            if (mBtnHeroRebuild != null) mBtnHeroRebuild.setVisibility(View.VISIBLE);
            if (mBtnHeroAct != null) mBtnHeroAct.setVisibility(View.GONE);
            if (mActivationDialog != null && mActivationDialog.isShowing()) {
                mActivationDialog.dismiss();
                AdbBootstrap.stopDiscovery();
                AdbBootstrap.cancelPairingNotification(this);
                Toast.makeText(this, "ExtBridge 服务已就绪", Toast.LENGTH_SHORT).show();
            }
            if (syncSelection && !mInitedFromDaemon) {
                mInitedFromDaemon = true;
                mSelW = st.virtW;
                mSelH = st.virtH;
                mSelDpi = st.virtDpi;
                mSelFps = st.virtFps;
                mEdW.setText(String.valueOf(mSelW));
                mEdH.setText(String.valueOf(mSelH));
                updateSelectionStyles();
            }

            String modeLabel = BridgeClient.hostModeLabel(st.hostMode);
            if (st.mirrorMode == 1 && st.extId > 0) {
                mTvStatusTitle.setText("原生镜像模式");
                mTvStatusTitle.setTextColor(Ui.TEXT);
                setDotColor(mStatusDot, Ui.PURPLE);
                setHeroTint(0xFF1B1430, "镜像", Ui.PURPLE);
                mTvStatusDetail.setText("外接屏正在同步" + modeLabel + "的主屏画面，独立扩展桌面已暂停。\n"
                        + "物理信号 " + st.physW + "×" + st.physH + " · " + String.format(Locale.US, "%.1f", st.physFps) + " Hz");
                if (mTvSideStatus != null) {
                    mTvSideStatus.setText("镜像中");
                    mTvSideStatus.setTextColor(Ui.PURPLE);
                }
            } else if (st.vdId > 0) {
                mTvStatusTitle.setText("扩展桌面已连接");
                mTvStatusTitle.setTextColor(Ui.TEXT);
                setDotColor(mStatusDot, Ui.GREEN);
                setHeroTint(0xFF12251C, "已连接", Ui.GREEN);
                String target = st.inputToExt == 1 ? "扩展屏" : "平板";
                mTvStatusDetail.setText(modeLabel + " · 物理 Display #" + st.extId + " → 扩展 Display #" + st.vdId + "\n"
                        + "键鼠当前控制 " + target + " · 检测到 " + st.extInputCnt + " 个输入设备 · " + st.renderMode);
                if (mTvSideStatus != null) {
                    mTvSideStatus.setText("已连接");
                    mTvSideStatus.setTextColor(Ui.GREEN);
                }
            } else {
                mTvStatusTitle.setText("等待外接显示器");
                mTvStatusTitle.setTextColor(Ui.TEXT);
                setDotColor(mStatusDot, Ui.AMBER);
                setHeroTint(0xFF241E10, "待机", Ui.AMBER);
                mTvStatusDetail.setText(modeLabel + " 正在运行。插入支持 DP Alt Mode 的 Type-C 显示器后会自动创建独立桌面。\n"
                        + "目标预设 " + st.virtW + "×" + st.virtH + " · " + st.virtDpi + " dpi · " + Math.round(st.virtFps) + " Hz");
                if (mTvSideStatus != null) {
                    mTvSideStatus.setText("待机");
                    mTvSideStatus.setTextColor(Ui.AMBER);
                }
            }

            if (mTvMetricRes != null) mTvMetricRes.setText(st.virtW + "×" + st.virtH);
            if (mTvMetricFps != null) mTvMetricFps.setText(Math.round(st.virtFps) + " Hz");
            if (mTvMetricInput != null) mTvMetricInput.setText(st.inputToExt == 1 ? "扩展屏" : "平板");
            if (mBtnToggleInput != null) mBtnToggleInput.setText(st.inputToExt == 1 ? "扩展屏" : "平板");
            if (mBtnToggleMirror != null) mBtnToggleMirror.setText(st.mirrorMode == 1 ? "镜像" : "DeX");
            if (mBtnFullscreenPolicy != null) mBtnFullscreenPolicy.setText(st.fullscreenPolicy == 1 ? "开" : "关");
            if (!st.modesRaw.equals(mLastModesRaw)) {
                mLastModesRaw = st.modesRaw;
                rebuildPhysModes(st.modesRaw);
            }
        }

        boolean canOverlay = Settings.canDrawOverlays(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean canNotif = Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled();
        boolean a11yOn = NavAssistService.isRunning();
        if (mTvPermOverlay != null) {
            mTvPermOverlay.setText(canOverlay ? "已授权" : "未授权");
            mTvPermOverlay.setTextColor(canOverlay ? Ui.GREEN : Ui.AMBER);
        }
        if (mTvPermNotif != null) {
            mTvPermNotif.setText(canNotif ? "已授权" : "未授权");
            mTvPermNotif.setTextColor(canNotif ? Ui.GREEN : Ui.AMBER);
        }
        if (mTvPermA11y != null) {
            mTvPermA11y.setText(a11yOn ? "已开启" : "可选");
            mTvPermA11y.setTextColor(a11yOn ? Ui.GREEN : Ui.TEXT3);
        }
        if (mTvGestureStatus != null) {
            mTvGestureStatus.setText("原生手势保护已启用；悬浮窗用于光标与浮动控制条，通知权限用于快捷控制与无线配对。");
        }
    }

    private void setHeroTint(int color, String tag, int tagColor) {
        if (mHeroCard != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(Ui.dp(this, 24));
            mHeroCard.setBackground(bg);
        }
        if (mHeroTag != null) {
            mHeroTag.setText(tag);
            mHeroTag.setTextColor(tagColor);
            GradientDrawable pbg = new GradientDrawable();
            pbg.setColor(Ui.withAlpha(tagColor, 0x24));
            pbg.setCornerRadius(Ui.dp(this, 100));
            pbg.setStroke(Math.max(1, Ui.dpi(this, 0.8f)), Ui.withAlpha(tagColor, 0x4D));
            mHeroTag.setBackground(pbg);
        }
    }

    private void setDotColor(View dot, int color) {
        if (dot == null) return;
        if (dot.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) dot.getBackground()).setColor(color);
        }
    }

    private String deviceBriefText() {
        String soc = "";
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                soc = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim();
            }
        } catch (Throwable ignored) {}
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return "本机  " + Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.DEVICE + "\n"
                + "SoC  " + (soc.isEmpty() ? Build.HARDWARE : soc) + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "内屏  " + dm.widthPixels + "×" + dm.heightPixels + " @ " + dm.densityDpi + " dpi\n"
                + "内核  " + System.getProperty("os.version");
    }

    private void refreshRuntimeCard(BridgeClient.Status st) {
        if (mTvRuntime == null) return;
        if (!st.connected) {
            mTvRuntime.setText("未连接守护进程\n使用 Root 一键激活，或通过无线调试 / 电脑 ADB 启动。");
            mTvRuntime.setTextColor(Ui.TEXT2);
            if (mBtnRootActivate != null) {
                mBtnRootActivate.setVisibility(View.VISIBLE);
                mBtnRootActivate.setText("Root 一键激活");
            }
            return;
        }
        String mode = st.hostMode == null || st.hostMode.isEmpty() ? "shell" : st.hostMode;
        String label = BridgeClient.hostModeLabel(mode);
        String ver = (st.daemonVersion == null || st.daemonVersion.isEmpty()) ? "" : (" · v" + st.daemonVersion);
        String detail;
        if ("root".equals(mode)) {
            detail = "已获得 root 权限。驱动采集可读取 DRM EDID、debugfs、Type-C 与 dmesg。";
            mTvRuntime.setTextColor(Ui.GREEN);
            if (mBtnRootActivate != null) mBtnRootActivate.setVisibility(View.GONE);
        } else if ("system_server".equals(mode)) {
            detail = "LSPosed 已注入 system_server，驱动读取走系统框架权限，无需每次启动。";
            mTvRuntime.setTextColor(Ui.PURPLE);
            if (mBtnRootActivate != null) mBtnRootActivate.setVisibility(View.GONE);
        } else {
            detail = "ADB Shell 模式：显示接管可用，但部分 debugfs / EDID 节点不可读。建议切换到 Root 模式。";
            mTvRuntime.setTextColor(Ui.AMBER);
            if (mBtnRootActivate != null) {
                mBtnRootActivate.setVisibility(View.VISIBLE);
                mBtnRootActivate.setText("切换到 Root 模式");
            }
        }
        mTvRuntime.setText(label + ver + "\n" + detail);
    }

    // ==================== Root 激活 / 驱动采集 ====================

    private void runRootActivation() {
        BridgeClient.RuntimeInfo ri = BridgeClient.getRuntimeInfo();
        if (ri.valid && "system_server".equals(ri.hostModeName)) {
            showTextDialog("Root 激活", "当前已由 LSPosed 系统框架模式接管，无需再启动 root 守护进程。\n\n"
                    + "如需改用 Root 模式，请先在 LSPosed 管理器中停用 ExtBridge 模块并重启。");
            return;
        }
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("🐧 Root 一键激活")
                .setMessage("正在请求 root 权限…\n首次使用时请在 KernelSU / Magisk 弹窗中允许 ExtBridge。")
                .setCancelable(false)
                .create();
        progress.show();
        RootBootstrap.activateAsync(this, new RootBootstrap.Listener() {
            @Override
            public void onStep(String message) {
                if (progress.isShowing()) progress.setMessage(message);
            }

            @Override
            public void onResult(boolean success, String message) {
                if (progress.isShowing()) progress.dismiss();
                if (success) {
                    Toast.makeText(MainActivity.this, "✅ Root 激活成功", Toast.LENGTH_SHORT).show();
                    refreshStatus(true);
                } else {
                    showTextDialog("Root 激活失败", message);
                }
            }
        });
    }

    private void collectDriverInfo() {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("🔍 读取本机驱动信息")
                .setMessage("正在读取 DRM / EDID / GPU / 面板 / Type-C / 输入设备…")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(() -> {
            BridgeClient.RuntimeInfo ri = BridgeClient.getRuntimeInfo();
            String json = null;
            if (ri.valid) {
                try {
                    json = BridgeClient.getDriverJson();
                } catch (Throwable ignored) {}
            }
            if (json != null && json.trim().startsWith("{")) {
                final String okJson = json;
                runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    mLastDriverJson = okJson;
                    showDriverResult(okJson);
                });
                return;
            }
            boolean root = RootBootstrap.isRootAvailable();
            runOnUiThread(() -> {
                if (progress.isShowing()) progress.dismiss();
                if (!root) {
                    showTextDialog("驱动采集", "需要 Root 权限或已激活的守护进程。\n\n"
                            + "请先点「Root 一键激活」，或在 KernelSU/Magisk 中授予 ExtBridge root 权限。");
                    return;
                }
                final AlertDialog p2 = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("🔍 Root 驱动采集")
                        .setMessage("正在用 root 运行 app_process probe…")
                        .setCancelable(false)
                        .create();
                p2.show();
                RootBootstrap.probeAsync(MainActivity.this, (json2, error) -> {
                    if (p2.isShowing()) p2.dismiss();
                    if (json2 != null) {
                        mLastDriverJson = json2;
                        showDriverResult(json2);
                    } else {
                        showTextDialog("驱动采集失败", error == null ? "未知错误" : error);
                    }
                });
            });
        }).start();
    }

    private void showDriverResult(String json) {
        String pretty = json;
        String summary;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            pretty = obj.toString(2);
            summary = buildDriverSummary(obj);
        } catch (Throwable t) {
            summary = "JSON 解析失败: " + t;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(26, 14, 26, 6);

        TextView tvSummary = new TextView(this);
        tvSummary.setText(summary);
        tvSummary.setTextColor(0xFFE2E8F0);
        tvSummary.setTextSize(12f);
        tvSummary.setLineSpacing(3f, 1f);
        content.addView(tvSummary);

        TextView tvRaw = new TextView(this);
        tvRaw.setText(pretty);
        tvRaw.setTextColor(0xFF94A3B8);
        tvRaw.setTextSize(10.5f);
        tvRaw.setTypeface(Typeface.MONOSPACE);
        tvRaw.setTextIsSelectable(true);
        tvRaw.setPadding(0, 14, 0, 10);
        content.addView(tvRaw);

        ScrollView sv = new ScrollView(this);
        sv.addView(content);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(20, 8, 20, 4);
        TextView copy = makeActionBtn("📋 复制", 0xFF334155);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("extbridge_driver", json));
            Toast.makeText(this, "已复制驱动 JSON", Toast.LENGTH_SHORT).show();
        });
        TextView save = makeActionBtn("💾 保存", 0xFF0369A1);
        save.setOnClickListener(v -> {
            String name = "extbridge-driver-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new java.util.Date()) + ".json";
            RootBootstrap.saveJsonAsync(this, json, name, new RootBootstrap.Listener() {
                @Override public void onStep(String message) {}
                @Override public void onResult(boolean success, String message) {
                    Toast.makeText(MainActivity.this, success ? "✅ " + message : "❌ " + message,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
        TextView share = makeActionBtn("📤 分享", 0xFF7C3AED);
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "ExtBridge 本机驱动信息");
            i.putExtra(Intent.EXTRA_TEXT, json);
            startActivity(Intent.createChooser(i, "分享驱动信息"));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = 8;
        row.addView(copy, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.rightMargin = 8;
        row.addView(save, lp2);
        row.addView(share, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        outer.addView(row);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("本机驱动信息")
                .setView(outer)
                .setPositiveButton("关闭", null)
                .create();
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.86f));
        }
    }

    private String buildDriverSummary(org.json.JSONObject o) {
        StringBuilder sb = new StringBuilder();
        try {
            org.json.JSONObject dev = o.optJSONObject("device");
            if (dev != null) {
                sb.append("设备  ").append(dev.optString("manufacturer")).append(' ')
                        .append(dev.optString("model")).append(" (").append(dev.optString("device")).append(")\n");
                String soc = (dev.optString("socManufacturer") + " " + dev.optString("socModel")).trim();
                sb.append("SoC   ").append(soc.isEmpty() ? dev.optString("hardware") : soc).append('\n');
                sb.append("系统  Android ").append(dev.optString("androidRelease"))
                        .append(" (API ").append(dev.optInt("sdk")).append(") · ").append(dev.optString("buildId")).append('\n');
                sb.append("内核  ").append(dev.optString("kernelRelease")).append('\n');
            }
            String mode = o.optString("hostMode");
            sb.append("采集  ").append("root".equals(mode) ? "Root (uid 0)" : mode)
                    .append(" · SELinux ").append(o.optString("selinux")).append('\n');

            org.json.JSONObject drm = o.optJSONObject("drm");
            if (drm != null) {
                org.json.JSONArray cs = drm.optJSONArray("connectors");
                sb.append("\nDRM 连接器\n");
                if (cs != null) {
                    for (int i = 0; i < cs.length(); i++) {
                        org.json.JSONObject c = cs.optJSONObject(i);
                        if (c == null) continue;
                        sb.append("• ").append(c.optString("name")).append(": ")
                                .append(c.optString("status")).append(" / ").append(c.optString("enabled"));
                        org.json.JSONArray modes = c.optJSONArray("modes");
                        if (modes != null && modes.length() > 0) {
                            sb.append("  ").append(modes.optString(0));
                            if (modes.length() > 1) sb.append(" … +").append(modes.length() - 1);
                        }
                        org.json.JSONObject edid = c.optJSONObject("edid");
                        if (edid != null) {
                            sb.append("\n   EDID: ").append(edid.optString("manufacturerId"))
                                    .append(' ').append(edid.optString("productName"))
                                    .append(" · ").append(edid.optInt("widthCm")).append('×').append(edid.optInt("heightCm")).append("cm");
                        }
                        sb.append('\n');
                    }
                }
            }

            org.json.JSONArray displays = o.optJSONArray("displays");
            if (displays != null && displays.length() > 0) {
                sb.append("\nDisplayManager\n");
                for (int i = 0; i < displays.length(); i++) {
                    org.json.JSONObject d = displays.optJSONObject(i);
                    if (d == null) continue;
                    org.json.JSONObject dmode = d.optJSONObject("mode");
                    sb.append("• #").append(d.optInt("id")).append(' ').append(d.optString("name"))
                            .append(" type=").append(d.optInt("type"));
                    if (dmode != null) sb.append("  ").append(dmode.optInt("width")).append('×')
                            .append(dmode.optInt("height")).append('@').append(dmode.optDouble("refresh"));
                    sb.append('\n');
                }
            }

            org.json.JSONObject gpu = o.optJSONObject("gpu");
            if (gpu != null) {
                String g = gpu.optString("model");
                if (g.isEmpty()) g = gpu.optString("maliInfo");
                if (!g.isEmpty()) sb.append("\nGPU   ").append(g).append('\n');
                String cur = gpu.optString("curFreq");
                if (!cur.isEmpty()) sb.append("频率  ").append(cur).append('\n');
            }

            org.json.JSONObject panel = o.optJSONObject("panel");
            if (panel != null) {
                String dt = panel.optString("deviceTreeModel");
                if (!dt.isEmpty()) sb.append("\n面板  ").append(dt).append('\n');
                org.json.JSONArray bl = panel.optJSONArray("backlights");
                if (bl != null) for (int i = 0; i < bl.length(); i++) {
                    org.json.JSONObject b = bl.optJSONObject(i);
                    if (b != null) sb.append("背光  ").append(b.optString("name"))
                            .append(' ').append(b.optString("brightness")).append('/')
                            .append(b.optString("maxBrightness")).append('\n');
                }
            }

            org.json.JSONObject typec = o.optJSONObject("typec");
            if (typec != null) {
                org.json.JSONArray ports = typec.optJSONArray("ports");
                if (ports != null) for (int i = 0; i < ports.length(); i++) {
                    org.json.JSONObject p = ports.optJSONObject(i);
                    if (p == null) continue;
                    String dr = p.optString("data_role");
                    if (dr.isEmpty()) continue;
                    sb.append("\nType-C ").append(p.optString("name")).append('\n')
                            .append("• data_role=").append(dr).append('\n')
                            .append("• power_role=").append(p.optString("power_role")).append('\n')
                            .append("• port_type=").append(p.optString("port_type")).append('\n')
                            .append("• PD=").append(p.optString("usb_power_delivery_revision")).append('\n');
                    break;
                }
            }

            org.json.JSONArray inputs = o.optJSONArray("inputs");
            if (inputs != null) {
                sb.append("\n输入设备  ").append(inputs.length()).append(" 个\n");
                for (int i = 0; i < inputs.length() && i < 10; i++) {
                    org.json.JSONObject in = inputs.optJSONObject(i);
                    if (in != null) sb.append("• ").append(in.optString("name")).append('\n');
                }
            }
            org.json.JSONObject debug = o.optJSONObject("debug");
            if (debug != null) {
                org.json.JSONArray dm = debug.optJSONArray("dmesg");
                if (dm != null) sb.append("\ndmesg 相关行  ").append(dm.length()).append(" 条（见下方 JSON）\n");
            }
        } catch (Throwable t) {
            sb.append("摘要生成失败: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private void toggleRootAutostart() {
        new Thread(() -> {
            boolean on = RootBootstrap.isAutostartEnabled();
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("⏱ Root 开机自启")
                    .setMessage(on
                            ? "当前已安装开机自启脚本:\n" + RootBootstrap.AUTOSTART_PATH
                            + "\n\n重启后会自动以 root 启动守护进程。若启用 LSPosed 模块，脚本会检测并跳过。"
                            : "安装开机自启脚本到 /data/adb/service.d/，重启后自动以 root 启动 ExtBridge，"
                            + "无需每次手动激活。")
                    .setPositiveButton(on ? "关闭自启" : "开启自启", (d, w) -> {
                        final AlertDialog p = new AlertDialog.Builder(this)
                                .setMessage("正在处理…").setCancelable(false).create();
                        p.show();
                        RootBootstrap.setAutostartAsync(this, !on, new RootBootstrap.Listener() {
                            @Override public void onStep(String message) {}
                            @Override public void onResult(boolean success, String message) {
                                if (p.isShowing()) p.dismiss();
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            }
                        });
                    })
                    .setNegativeButton("取消", null)
                    .show());
        }).start();
    }

    private void showLsposedDialog() {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("🧩 LSPosed 插件模式")
                .setMessage("正在检测 LSPosed 安装状态…")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(() -> {
            String info = RootBootstrap.lsposedInfo(this);
            runOnUiThread(() -> {
                if (progress.isShowing()) progress.dismiss();
                AlertDialog dlg = new AlertDialog.Builder(this)
                        .setTitle("🧩 LSPosed 插件模式")
                        .setMessage(info)
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("打开 LSPosed", (d, w) -> {
                            try {
                                Intent i = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
                                if (i != null) startActivity(i);
                                else Toast.makeText(this, "未安装 LSPosed 管理器", Toast.LENGTH_SHORT).show();
                            } catch (Throwable t) {
                                Toast.makeText(this, "打开失败: " + t, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .create();
                dlg.show();
            });
        }).start();
    }

    private void showTextDialog(String title, String message) {
        TextView tv = new TextView(this);
        tv.setText(message == null ? "" : message);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(12.5f);
        tv.setTextIsSelectable(true);
        tv.setPadding(28, 16, 28, 8);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void rebuildPhysModes(String raw) {
        mPhysModesWrap.removeAllViews();
        if (raw == null || raw.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("暂无外接硬件模式数据 (插屏后自动刷新)");
            tv.setTextColor(0xFF64748B);
            tv.setTextSize(12.5f);
            mPhysModesWrap.addView(tv);
            return;
        }
        List<View> modeBtns = new ArrayList<>();
        for (String item : raw.split(";")) {
            String[] kv = item.split(":");
            if (kv.length != 2) continue;
            try {
                final int modeId = Integer.parseInt(kv[0]);
                final String desc = kv[1];
                TextView chip = makeChip("物理模式 #" + modeId + " · " + desc + "Hz");
                chip.setOnClickListener(v -> {
                    mSelPhysModeId = modeId;
                    try {
                        String[] rf = desc.split("@");
                        String[] wh = rf[0].split("x");
                        mSelW = Integer.parseInt(wh[0]);
                        mSelH = Integer.parseInt(wh[1]);
                        mSelFps = Float.parseFloat(rf[1]);
                        mEdW.setText(String.valueOf(mSelW));
                        mEdH.setText(String.valueOf(mSelH));
                        updateSelectionStyles();
                    } catch (Throwable ignored) {}
                    applyChosenConfig();
                });
                modeBtns.add(chip);
            } catch (Throwable ignored) {}
        }
        addChipWrap(mPhysModesWrap, modeBtns);
    }

    private void updateSelectionStyles() {
        for (int i = 0; i < RES_PRESETS.length && i < mResChips.size(); i++) {
            boolean sel = (RES_PRESETS[i][0] == mSelW && RES_PRESETS[i][1] == mSelH);
            styleChip(mResChips.get(i), sel);
        }
        for (int i = 0; i < DPI_PRESETS.length && i < mDpiChips.size(); i++) {
            styleChip(mDpiChips.get(i), DPI_PRESETS[i] == mSelDpi);
        }
        for (int i = 0; i < FPS_PRESETS.length && i < mFpsChips.size(); i++) {
            styleChip(mFpsChips.get(i), Math.abs(FPS_PRESETS[i] - mSelFps) < 1f);
        }
        updateSwHint();
    }

    private void updateSwHint() {
        if (mTvSwInfo == null) return;
        int cw = mSelW;
        int ch = mSelH;
        try {
            int pw = Integer.parseInt(mEdW.getText().toString().trim());
            int ph = Integer.parseInt(mEdH.getText().toString().trim());
            if (pw > 0 && ph > 0) { cw = pw; ch = ph; }
        } catch (Throwable ignored) {}
        int minPx = Math.min(cw, ch);
        int swDp = Math.round(minPx * 160f / Math.max(120, mSelDpi));
        boolean tabletLayout = swDp >= 600;
        mTvSwInfo.setText("等效最小宽度 sw" + swDp + "dp · "
                + (tabletLayout ? "已达到平板大屏阈值 (sw ≥ 600dp)" : "低于平板阈值，建议 1920×1080 @ 240dpi"));
        mTvSwInfo.setTextColor(tabletLayout ? Ui.TEAL : Ui.AMBER);
    }

    private void styleChip(TextView tv, boolean selected) {
        if (!(tv.getBackground() instanceof GradientDrawable)) return;
        GradientDrawable bg = (GradientDrawable) tv.getBackground();
        if (selected) {
            bg.setColor(0x333B82F6);
            bg.setStroke(Math.max(1, Ui.dpi(this, 0.9f)), 0x993B82F6);
            tv.setTextColor(Ui.TEXT);
        } else {
            bg.setColor(0x0DFFFFFF);
            bg.setStroke(Math.max(1, Ui.dpi(this, 0.7f)), 0x1FFFFFFF);
            tv.setTextColor(Ui.TEXT2);
        }
    }

    private TextView makeChip(String txt) {
        TextView tv = Ui.medium(this, txt, 12.5f, Ui.TEXT2);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dpi(this, 17), Ui.dpi(this, 9), Ui.dpi(this, 17), Ui.dpi(this, 9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x0DFFFFFF);
        bg.setCornerRadius(Ui.dp(this, 14));
        bg.setStroke(Math.max(1, Ui.dpi(this, 0.7f)), 0x1FFFFFFF);
        tv.setBackground(bg);
        return tv;
    }

    private TextView makeActionBtn(String txt, int color) {
        TextView tv = Ui.medium(this, txt, 13f, Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dpi(this, 20), Ui.dpi(this, 12), Ui.dpi(this, 20), Ui.dpi(this, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(Ui.dp(this, 100));
        bg.setStroke(Math.max(1, Ui.dpi(this, 0.7f)), 0x1FFFFFFF);
        tv.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), bg, null));
        return tv;
    }

    private EditText makeNumBox(String val) {
        EditText ed = new EditText(this);
        ed.setText(val);
        ed.setTextColor(Ui.TEXT);
        ed.setTextSize(14f);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER);
        ed.setGravity(Gravity.CENTER);
        ed.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 8), Ui.dpi(this, 14), Ui.dpi(this, 8));
        ed.setMinWidth(Ui.dpi(this, 96));
        ed.setIncludeFontPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x0DFFFFFF);
        bg.setCornerRadius(Ui.dp(this, 12));
        bg.setStroke(Math.max(1, Ui.dpi(this, 0.8f)), 0x33FFFFFF);
        ed.setBackground(bg);
        return ed;
    }

    private void addChipWrap(LinearLayout container, List<View> views) {
        ChipFlowLayout flow = new ChipFlowLayout(this, Ui.dpi(this, 8), Ui.dpi(this, 8));
        for (View v : views) {
            flow.addView(v);
        }
        container.addView(flow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ==================== 激活 / 应用模式 / 驱动对话框 ====================

    private void showActivationDialog() {
        if (isFinishing()) return;
        if (mActivationDialog != null && mActivationDialog.isShowing()) return;

        mHostEdited = false;
        mPairPortEdited = false;
        mConnPortEdited = false;

        ScrollView sv = new ScrollView(this);
        GradientDrawable dlgBg = new GradientDrawable();
        dlgBg.setColor(0xFF0F172A);
        dlgBg.setCornerRadius(24f);
        dlgBg.setStroke(2, 0xFF334155);
        sv.setBackground(dlgBg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 30, 34, 28);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("⚡ 激活 ExtBridge 核心服务");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19f);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("未检测到后台守护进程（新安装或重启后需激活一次）。请选择激活方式，检测连接成功后将自动进入应用：");
        sub.setTextColor(0xFF94A3B8);
        sub.setTextSize(12.5f);
        sub.setPadding(0, 8, 0, 18);
        root.addView(sub);

        // Root mode (recommended when su is present)
        boolean hasSu = new java.io.File("/system/bin/su").exists();
        TextView rootDesc = new TextView(this);
        rootDesc.setText(hasSu
                ? "检测到 su（KernelSU / Magisk）：推荐直接用 Root 启动，无需无线调试，且可读取完整驱动信息。"
                : "未检测到 /system/bin/su；如已 root 请确认 su 路径，否则请使用下面的无线调试或电脑 ADB。");
        rootDesc.setTextColor(0xFFCBD5E1);
        rootDesc.setTextSize(12f);
        rootDesc.setPadding(14, 12, 14, 12);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xFF052E16);
        rootBg.setCornerRadius(12f);
        rootBg.setStroke(1, 0xFF166534);
        rootDesc.setBackground(rootBg);
        root.addView(rootDesc);

        final TextView btnRootRun = makeActionBtn("🐧 Root 一键激活（推荐）", 0xFF16A34A);
        btnRootRun.setOnClickListener(v -> {
            if (mActWorking) return;
            mActWorking = true;
            setActStatus("⏳ 正在请求 root 权限并启动守护进程…", 0xFF38BDF8);
            RootBootstrap.activateAsync(this, new RootBootstrap.Listener() {
                @Override
                public void onStep(String message) {
                    setActStatus("⏳ " + message, 0xFF38BDF8);
                }

                @Override
                public void onResult(boolean success, String message) {
                    mActWorking = false;
                    setActStatus((success ? "✅ " : "❌ ") + message, success ? 0xFF4ADE80 : 0xFFF87171);
                    refreshStatus(true);
                }
            });
        });
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootLp.topMargin = 10;
        rootLp.bottomMargin = 6;
        root.addView(btnRootRun, rootLp);

        // Two mode selector buttons
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabLp1.rightMargin = 10;
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        final TextView btnModeWireless = makeActionBtn("📡 无线调试\n(本机免电脑)", 0xFF1E293B);
        final TextView btnModeAdb = makeActionBtn("💻 ADB 命令\n(电脑执行一句)", 0xFF1E293B);
        btnModeWireless.setTextSize(13f);
        btnModeAdb.setTextSize(13f);
        tabRow.addView(btnModeWireless, tabLp1);
        tabRow.addView(btnModeAdb, tabLp2);
        root.addView(tabRow);

        // Panel 1: Wireless Debugging
        final LinearLayout wirelessPane = new LinearLayout(this);
        wirelessPane.setOrientation(LinearLayout.VERTICAL);
        wirelessPane.setPadding(0, 18, 0, 0);
        wirelessPane.setVisibility(View.GONE);

        mTvMdnsHint = new TextView(this);
        mTvMdnsHint.setTextColor(0xFF38BDF8);
        mTvMdnsHint.setTextSize(12f);
        mTvMdnsHint.setPadding(14, 10, 14, 10);
        GradientDrawable mdnsBg = new GradientDrawable();
        mdnsBg.setColor(0xFF1E293B);
        mdnsBg.setCornerRadius(12f);
        mTvMdnsHint.setBackground(mdnsBg);
        wirelessPane.addView(mTvMdnsHint);

        // Step helper buttons
        LinearLayout guideRow = new LinearLayout(this);
        guideRow.setOrientation(LinearLayout.HORIZONTAL);
        guideRow.setPadding(0, 12, 0, 12);
        LinearLayout.LayoutParams gLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        gLp1.rightMargin = 8;
        LinearLayout.LayoutParams gLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView btnOpenDev = makeActionBtn("1. 去开启无线调试 + 配对通知", 0xFF0369A1);
        btnOpenDev.setTextSize(12f);
        btnOpenDev.setOnClickListener(v -> {
            ensureNotifPermAndShowPairNotif();
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Throwable ignored) {}
            }
        });
        guideRow.addView(btnOpenDev, gLp1);

        TextView btnPairNotif = makeActionBtn("🔔 发送/刷新通知快捷配对", 0xFF334155);
        btnPairNotif.setTextSize(12f);
        btnPairNotif.setOnClickListener(v -> {
            ensureNotifPermAndShowPairNotif();
            Toast.makeText(this, "已发送配对通知：在系统配对弹窗上方下拉通知即可直接填6位配对码", Toast.LENGTH_LONG).show();
        });
        guideRow.addView(btnPairNotif, gLp2);
        wirelessPane.addView(guideRow);

        // Input fields: IP + Pair Port + Pair Code + Connect Port
        mEdPairHost = makeDialogInput(AdbBootstrap.getLocalIpv4(), "本机 IP 地址 (如 192.168.1.5)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mEdPairPort = makeDialogInput("", "配对端口 (弹窗上的端口，如 36565)", InputType.TYPE_CLASS_NUMBER);
        mEdPairCode = makeDialogInput("", "6 位 WLAN 配对码 (如 551857)", InputType.TYPE_CLASS_NUMBER);
        mEdConnPort = makeDialogInput("", "无线调试主页连接端口 (若已配对直接填，如 37991)", InputType.TYPE_CLASS_NUMBER);

        attachEditFlag(mEdPairHost, () -> mHostEdited = true);
        attachEditFlag(mEdPairPort, () -> mPairPortEdited = true);
        attachEditFlag(mEdConnPort, () -> mConnPortEdited = true);

        addLabeledField(wirelessPane, "IP 地址", mEdPairHost);
        addLabeledField(wirelessPane, "配对端口 & 6位配对码 (新设备配对必填)", mEdPairPort);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = 6;
        wirelessPane.addView(mEdPairCode, codeLp);
        addLabeledField(wirelessPane, "连接端口 (已配对过可直接填此端口跳过配对)", mEdConnPort);

        LinearLayout runRow = new LinearLayout(this);
        runRow.setOrientation(LinearLayout.HORIZONTAL);
        runRow.setPadding(0, 14, 0, 8);
        LinearLayout.LayoutParams rLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rLp1.rightMargin = 8;
        LinearLayout.LayoutParams rLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView btnPairRun = makeActionBtn("🔗 配对并激活", 0xFF2563EB);
        TextView btnConnRun = makeActionBtn("⚡ 已配对·直接连接", 0xFF0F766E);
        runRow.addView(btnPairRun, rLp1);
        runRow.addView(btnConnRun, rLp2);
        wirelessPane.addView(runRow);

        mTvActStatus = new TextView(this);
        mTvActStatus.setText("提示：也可下拉通知栏在「ExtBridge 无线调试配对助手」里直接输入6位配对码，不会导致弹窗验证码失效。");
        mTvActStatus.setTextColor(0xFFCBD5E1);
        mTvActStatus.setTextSize(12f);
        mTvActStatus.setPadding(12, 10, 12, 10);
        GradientDrawable stBg = new GradientDrawable();
        stBg.setColor(0xFF020617);
        stBg.setCornerRadius(10f);
        mTvActStatus.setBackground(stBg);
        wirelessPane.addView(mTvActStatus);
        root.addView(wirelessPane);

        // Panel 2: PC ADB Command
        final LinearLayout adbPane = new LinearLayout(this);
        adbPane.setOrientation(LinearLayout.VERTICAL);
        adbPane.setPadding(0, 18, 0, 0);
        adbPane.setVisibility(View.GONE);

        TextView adbDesc = new TextView(this);
        adbDesc.setText("在电脑通过 USB 或无线 ADB 连接本机后，复制并执行下方命令（全自动释放内置 extbridge.jar、授权并启动服务）：");
        adbDesc.setTextColor(0xFFCBD5E1);
        adbDesc.setTextSize(12.5f);
        adbDesc.setPadding(0, 0, 0, 10);
        adbPane.addView(adbDesc);

        final String cmdText = AdbBootstrap.buildPcAdbCommand();
        TextView tvCmd = new TextView(this);
        tvCmd.setText(cmdText);
        tvCmd.setTextColor(0xFF86EFAC);
        tvCmd.setTextSize(11f);
        tvCmd.setTypeface(Typeface.MONOSPACE);
        tvCmd.setTextIsSelectable(true);
        tvCmd.setPadding(16, 14, 16, 14);
        GradientDrawable cmdBg = new GradientDrawable();
        cmdBg.setColor(0xFF020617);
        cmdBg.setCornerRadius(12f);
        cmdBg.setStroke(1, 0xFF334155);
        tvCmd.setBackground(cmdBg);
        adbPane.addView(tvCmd);

        TextView btnCopyCmd = makeActionBtn("📋 复制 ADB 激活命令", 0xFF2563EB);
        btnCopyCmd.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("extbridge_adb", cmdText));
            }
            Toast.makeText(this, "已复制命令到剪贴板，请在电脑终端粘贴运行", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.topMargin = 12;
        adbPane.addView(btnCopyCmd, copyLp);

        TextView tvWait = new TextView(this);
        tvWait.setText("⏳ 正在自动检测连接状态… 电脑命令执行完成后将自动关闭此窗口并进入应用。");
        tvWait.setTextColor(0xFFFBBF24);
        tvWait.setTextSize(12f);
        tvWait.setPadding(0, 12, 0, 0);
        adbPane.addView(tvWait);
        root.addView(adbPane);

        // Skip button at bottom
        TextView btnSkip = new TextView(this);
        btnSkip.setText("暂不激活，先浏览控制台界面 →");
        btnSkip.setTextColor(0xFF64748B);
        btnSkip.setTextSize(12.5f);
        btnSkip.setGravity(Gravity.CENTER);
        btnSkip.setPadding(12, 20, 12, 6);
        btnSkip.setOnClickListener(v -> {
            mUserSkippedDialog = true;
            if (mActivationDialog != null) mActivationDialog.dismiss();
        });
        root.addView(btnSkip);

        final Runnable selectWireless = () -> {
            wirelessPane.setVisibility(View.VISIBLE);
            adbPane.setVisibility(View.GONE);
            setTabSelected(btnModeWireless, true);
            setTabSelected(btnModeAdb, false);
            AdbBootstrap.startDiscovery(this, this::applyDiscoveryStateToUi);
        };
        final Runnable selectAdb = () -> {
            wirelessPane.setVisibility(View.GONE);
            adbPane.setVisibility(View.VISIBLE);
            setTabSelected(btnModeWireless, false);
            setTabSelected(btnModeAdb, true);
        };

        btnModeWireless.setOnClickListener(v -> selectWireless.run());
        btnModeAdb.setOnClickListener(v -> selectAdb.run());

        btnPairRun.setOnClickListener(v -> {
            if (mActWorking) return;
            String host = mEdPairHost.getText().toString().trim();
            int pairPort = parseIntStr(mEdPairPort.getText().toString(), 0);
            String code = mEdPairCode.getText().toString().trim();
            int connPort = parseIntStr(mEdConnPort.getText().toString(), 0);
            mActWorking = true;
            setActStatus("⏳ 正在执行配对并启动服务...", 0xFF38BDF8);
            AdbBootstrap.pairAndBootstrapAsync(this, host, pairPort, code, host, connPort,
                    new AdbBootstrap.ProgressListener() {
                        @Override
                        public void onStep(String message) {
                            setActStatus("⏳ " + message, 0xFF38BDF8);
                        }

                        @Override
                        public void onResult(boolean success, String message) {
                            mActWorking = false;
                            setActStatus((success ? "✅ " : "❌ ") + message, success ? 0xFF4ADE80 : 0xFFF87171);
                            refreshStatus(true);
                        }
                    });
        });

        btnConnRun.setOnClickListener(v -> {
            if (mActWorking) return;
            String host = mEdPairHost.getText().toString().trim();
            int connPort = parseIntStr(mEdConnPort.getText().toString(), 0);
            mActWorking = true;
            setActStatus("⏳ 正在连接无线调试并启动服务...", 0xFF38BDF8);
            AdbBootstrap.connectAndBootstrapAsync(this, host, connPort,
                    new AdbBootstrap.ProgressListener() {
                        @Override
                        public void onStep(String message) {
                            setActStatus("⏳ " + message, 0xFF38BDF8);
                        }

                        @Override
                        public void onResult(boolean success, String message) {
                            mActWorking = false;
                            setActStatus((success ? "✅ " : "❌ ") + message, success ? 0xFF4ADE80 : 0xFFF87171);
                            refreshStatus(true);
                        }
                    });
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(sv);
        builder.setCancelable(false);
        mActivationDialog = builder.create();
        if (mActivationDialog.getWindow() != null) {
            mActivationDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        mActivationDialog.show();
        if (mActivationDialog.getWindow() != null) {
            mActivationDialog.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            mActivationDialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void applyDiscoveryStateToUi(AdbBootstrap.DiscoveryState ds) {
        if (ds == null) return;
        if (mTvMdnsHint != null) {
            String pStr = ds.pairingPort > 0 ? (ds.pairingHost + ":" + ds.pairingPort) : "未开启配对弹窗";
            String cStr = ds.connectPort > 0 ? (ds.connectHost + ":" + ds.connectPort) : "待检测";
            mTvMdnsHint.setText("🔍 mDNS 自动发现：配对=[" + pStr + "] · 连接=[" + cStr + "]");
        }
        if (mEdPairHost != null && !mHostEdited) {
            String bestHost = !ds.pairingHost.isEmpty() ? ds.pairingHost : ds.connectHost;
            if (!bestHost.isEmpty() && !bestHost.equals(mEdPairHost.getText().toString())) {
                mEdPairHost.setText(bestHost);
                mHostEdited = false;
            }
        }
        if (mEdPairPort != null && !mPairPortEdited && ds.pairingPort > 0) {
            mEdPairPort.setText(String.valueOf(ds.pairingPort));
            mPairPortEdited = false;
        }
        if (mEdConnPort != null && !mConnPortEdited && ds.connectPort > 0) {
            mEdConnPort.setText(String.valueOf(ds.connectPort));
            mConnPortEdited = false;
        }
    }

    private void setActStatus(String msg, int color) {
        if (mTvActStatus != null) {
            mTvActStatus.setText(msg);
            mTvActStatus.setTextColor(color);
        }
    }

    private void ensureNotifPermAndShowPairNotif() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3902);
        }
        AdbBootstrap.showPairingNotification(this, null);
    }

    private void setTabSelected(TextView tv, boolean sel) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(sel ? 0xFF1D4ED8 : 0xFF1E293B);
        bg.setCornerRadius(14f);
        bg.setStroke(2, sel ? 0xFF60A5FA : 0x4464748B);
        tv.setBackground(bg);
    }

    private EditText makeDialogInput(String val, String hint, int inputType) {
        EditText ed = new EditText(this);
        ed.setText(val);
        ed.setHint(hint);
        ed.setHintTextColor(0xFF64748B);
        ed.setTextColor(Color.WHITE);
        ed.setTextSize(13.5f);
        ed.setInputType(inputType);
        ed.setSingleLine(true);
        ed.setPadding(18, 14, 18, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF020617);
        bg.setCornerRadius(10f);
        bg.setStroke(1, 0xFF475569);
        ed.setBackground(bg);
        return ed;
    }

    private void addLabeledField(LinearLayout parent, String label, View field) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF94A3B8);
        tv.setTextSize(11.5f);
        tv.setPadding(2, 10, 0, 4);
        parent.addView(tv);
        parent.addView(field);
    }

    private void attachEditFlag(EditText ed, final Runnable onUserEdit) {
        ed.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (ed.hasFocus()) onUserEdit.run();
            }
        });
    }

    private static final class AppModeRow {
        String label;
        String pkg;
        int mode;
    }

    private void showAppModesDialog() {
        if (BridgeClient.getService() == null) {
            Toast.makeText(this, "请先激活后台服务再配置分应用显示模式", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 28, 36, 24);
        root.setBackgroundColor(0xFF0F172A);

        TextView title = new TextView(this);
        title.setText("🧩 分应用显示模式管理");
        title.setTextColor(0xFFF8FAFC);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("只有设为非「跟随系统默认」的应用才会注入兼容或全屏参数，微信、夸克、拼多多等建议保持「跟随系统默认」即可恢复正常。");
        desc.setTextColor(0xFF94A3B8);
        desc.setTextSize(12f);
        desc.setPadding(0, 6, 0, 12);
        root.addView(desc);

        EditText edFilter = makeDialogInput("", "搜索应用名或包名（如：微信 / 夸克 / 红果 / pinduoduo）", InputType.TYPE_CLASS_TEXT);
        root.addView(edFilter);

        LinearLayout listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.setPadding(0, 8, 0, 4);

        TextView loading = new TextView(this);
        loading.setText("正在读取已安装应用与当前兼容配置...");
        loading.setTextColor(0xFF94A3B8);
        loading.setTextSize(12.5f);
        loading.setPadding(8, 16, 8, 16);
        listWrap.addView(loading);

        ScrollView sv = new ScrollView(this);
        sv.addView(listWrap);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(420, (int) (dm.heightPixels * 0.48f)));
        svLp.topMargin = 10;
        root.addView(sv, svLp);

        b.setView(root);
        b.setNeutralButton("↺ 全部重置为默认", null);
        b.setNegativeButton("关闭", null);
        AlertDialog dlg = b.create();
        dlg.show();

        final List<AppModeRow> allRows = new ArrayList<>();
        final Runnable refreshList = () -> rebuildAppModeRows(listWrap, allRows, edFilter.getText().toString(), dlg);

        edFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                refreshList.run();
            }
        });

        View resetBtn = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (resetBtn != null) {
            resetBtn.setOnClickListener(v -> new Thread(() -> {
                BridgeClient.resetAllAppModes();
                Map<String, Integer> latest = BridgeClient.getAllAppModes();
                runOnUiThread(() -> {
                    for (AppModeRow r : allRows) {
                        Integer mv = latest.get(r.pkg);
                        r.mode = mv != null ? mv : 0;
                    }
                    refreshList.run();
                    BridgeService.refreshFromReceiver(this);
                    Toast.makeText(this, "已清空全部自定义兼容覆盖并恢复系统默认", Toast.LENGTH_SHORT).show();
                });
            }).start());
        }

        new Thread(() -> {
            Map<String, Integer> modes = BridgeClient.getAllAppModes();
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN, null);
            q.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            Map<String, AppModeRow> dedup = new HashMap<>();
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (getPackageName().equals(pkg) || dedup.containsKey(pkg)) continue;
                AppModeRow row = new AppModeRow();
                row.pkg = pkg;
                row.label = String.valueOf(ri.loadLabel(pm));
                Integer mv = modes.get(pkg);
                row.mode = mv != null ? mv : 0;
                dedup.put(pkg, row);
            }
            List<AppModeRow> loaded = new ArrayList<>(dedup.values());
            Collections.sort(loaded, (a, c) -> {
                int ca = a.mode > 0 ? 0 : 1;
                int cb = c.mode > 0 ? 0 : 1;
                if (ca != cb) return ca - cb;
                return a.label.compareToIgnoreCase(c.label);
            });
            runOnUiThread(() -> {
                if (!dlg.isShowing()) return;
                allRows.clear();
                allRows.addAll(loaded);
                refreshList.run();
            });
        }).start();
    }

    private void rebuildAppModeRows(LinearLayout container, List<AppModeRow> allRows, String query, AlertDialog parentDlg) {
        container.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (AppModeRow r : allRows) {
            if (!q.isEmpty()) {
                boolean match = r.label.toLowerCase(Locale.ROOT).contains(q)
                        || r.pkg.toLowerCase(Locale.ROOT).contains(q);
                if (!match) continue;
            }
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(20, 14, 20, 14);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(r.mode > 0 ? 0xFF172554 : 0xFF1E293B);
            bg.setCornerRadius(14f);
            bg.setStroke(1, r.mode > 0 ? 0xFF38BDF8 : 0xFF334155);
            card.setBackground(bg);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView tvName = new TextView(this);
            tvName.setText(r.label);
            tvName.setTextColor(0xFFF1F5F9);
            tvName.setTextSize(14f);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            left.addView(tvName);

            TextView tvPkg = new TextView(this);
            tvPkg.setText(r.pkg);
            tvPkg.setTextColor(0xFF94A3B8);
            tvPkg.setTextSize(11f);
            left.addView(tvPkg);
            card.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView pill = new TextView(this);
            pill.setText(BridgeClient.appModeLabel(r.mode));
            pill.setTextSize(11.5f);
            pill.setPadding(16, 8, 16, 8);
            GradientDrawable pBg = new GradientDrawable();
            int pColor = r.mode == BridgeClient.APP_MODE_LAND_FULL ? 0xFF0284C7
                    : (r.mode == BridgeClient.APP_MODE_PORT_COMPAT ? 0xFFD97706
                    : (r.mode == BridgeClient.APP_MODE_FREEFORM ? 0xFF7C3AED : 0xFF334155));
            pBg.setColor(pColor);
            pBg.setCornerRadius(12f);
            pill.setBackground(pBg);
            pill.setTextColor(Color.WHITE);
            card.addView(pill);

            card.setOnClickListener(v -> showSingleAppModePicker(r, () -> {
                if (parentDlg.isShowing()) {
                    rebuildAppModeRows(container, allRows, query, parentDlg);
                }
            }));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 10;
            container.addView(card, lp);
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("未找到匹配的应用");
            empty.setTextColor(0xFF94A3B8);
            empty.setPadding(10, 20, 10, 20);
            container.addView(empty);
        }
    }

    private void showSingleAppModePicker(AppModeRow row, Runnable onUpdated) {
        String[] choices = {
                (row.mode == 0 ? "● " : "") + "跟随系统默认（不注入兼容参数，推荐微信/夸克/拼多多）",
                (row.mode == 1 ? "● " : "") + "强制横屏全屏（适合红果短剧等无法横屏的视频 App）",
                (row.mode == 2 ? "● " : "") + "竖屏居中兼容（保持手机竖屏比例不拉伸变形）",
                (row.mode == 3 ? "● " : "") + "自由小窗模式（以 PC 自由缩放小窗打开）"
        };
        new AlertDialog.Builder(this)
                .setTitle(row.label + "\n" + row.pkg)
                .setItems(choices, (d, which) -> {
                    Toast.makeText(this, "正在切换并重启「" + row.label + "」...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        BridgeClient.setAppMode(row.pkg, which, true);
                        row.mode = which;
                        runOnUiThread(() -> {
                            BridgeService.refreshFromReceiver(this);
                            onUpdated.run();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int parseIntStr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    /** 底部液态玻璃导航项。 */
    private static final class DockItem extends LinearLayout {
        private final ImageView icon;
        private final TextView label;
        private final int accent;

        DockItem(Context c, int iconRes, String text, int accentColor) {
            super(c);
            this.accent = accentColor;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setPadding(Ui.dpi(c, 4), Ui.dpi(c, 5), Ui.dpi(c, 4), Ui.dpi(c, 4));
            icon = Ui.icon(c, iconRes, Ui.TEXT2, 21);
            addView(icon);
            label = Ui.medium(c, text, 10f, Ui.TEXT3);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dpi(c, 2);
            addView(label, lp);
            setClickable(true);
            setFocusable(true);
            setSelectedState(false);
        }

        void setSelectedState(boolean selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(getContext(), 26));
            if (selected) {
                bg.setColor(Ui.withAlpha(accent, 0x28));
                bg.setStroke(Math.max(1, Ui.dpi(getContext(), 0.8f)), Ui.withAlpha(accent, 0x55));
                icon.setColorFilter(accent);
                label.setTextColor(accent);
            } else {
                bg.setColor(0x00FFFFFF);
                icon.setColorFilter(Ui.TEXT2);
                label.setTextColor(Ui.TEXT3);
            }
            setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x1FFFFFFF), bg, null));
        }
    }
}
