package com.extbridge.helper;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.icu.text.Transliterator;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LauncherActivity extends Activity {
    private static class AppEntry {
        String label;
        String pkg;
        String cls;
        String searchBlob;
        Drawable icon;
    }

    private final List<AppEntry> mAllApps = new ArrayList<>();
    private final List<AppEntry> mApps = new ArrayList<>();
    private FrameLayout mRoot;
    private FrameLayout mOverlayHost;
    private ImageView mWallpaperView;
    private LinearLayout mContent;
    private EditText mSearchInput;
    private TextView mBtnClear;
    private TextView mTvEmpty;
    private GridView mGrid;
    private TextView mHeaderInfo;
    private AppAdapter mAdapter;
    private String mQuery = "";
    private Bitmap mLastWpBitmap;
    private int mAppliedDensityDpi = 0;
    private static String sPendingQuery = "";
    private Map<String, Integer> mAppModes = new HashMap<>();

    private final BroadcastReceiver mSyncRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshHeader();
            refreshAppModesAsync();
        }
    };

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float dpF(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAppliedDensityDpi = getResources().getConfiguration().densityDpi;
        extractBinder(getIntent());
        BridgeService.ensureStarted(this);
        buildUi();
        if (!sPendingQuery.isEmpty() && mSearchInput != null) {
            mQuery = sPendingQuery;
            sPendingQuery = "";
            mSearchInput.setText(mQuery);
        }
        try {
            registerReceiver(mSyncRx, new IntentFilter("com.extbridge.helper.STATE_SYNC"), RECEIVER_EXPORTED);
        } catch (Throwable ignored) {}
        loadAppsAsync();
        loadWallpaperAsync(false);
        BridgeClient.setOnBinderReady(() -> runOnUiThread(() -> {
            refreshHeader();
            loadWallpaperAsync(false);
        }));
    }

    @Override
    protected void onStart() {
        super.onStart();
        BridgeService.setDockHidden(false);
        BridgeService.setLauncherOverlayHost(mOverlayHost, getDisplay() != null ? getDisplay().getDisplayId() : -1);
    }

    @Override
    protected void onStop() {
        BridgeService.clearLauncherOverlayHost(mOverlayHost);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        BridgeService.clearLauncherOverlayHost(mOverlayHost);
        try { unregisterReceiver(mSyncRx); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev != null && ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            BridgeService.dismissPanelOnHostTouch(ev.getX(), ev.getY());
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        extractBinder(intent);
    }

    private void extractBinder(Intent i) {
        if (i != null && i.getExtras() != null) {
            android.os.IBinder cb = i.getExtras().getBinder("cb");
            if (cb != null) BridgeClient.setBinder(cb);
        }
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHeader();
        adjustColumns();
        loadWallpaperAsync(false);
        refreshAppModesAsync();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig != null && newConfig.densityDpi != 0 && newConfig.densityDpi != mAppliedDensityDpi) {
            sPendingQuery = mQuery;
            recreate();
            return;
        }
        adjustColumns();
        refreshHeader();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && !mQuery.isEmpty()) {
            clearSearch();
            return true;
        }
        if (mSearchInput != null && !mSearchInput.hasFocus()) {
            boolean isSlash = (keyCode == KeyEvent.KEYCODE_SLASH);
            boolean isCtrlF = (keyCode == KeyEvent.KEYCODE_F && event != null && event.isCtrlPressed());
            int ch = event != null ? event.getUnicodeChar() : 0;
            if (isSlash || isCtrlF) {
                mSearchInput.requestFocus();
                return true;
            }
            if (ch >= 0x20 && !Character.isISOControl(ch) && (ch < 0xE000)) {
                mSearchInput.requestFocus();
                mSearchInput.append(String.valueOf((char) ch));
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void buildUi() {
        mRoot = new FrameLayout(this);
        mRoot.setFocusable(true);
        mRoot.setFocusableInTouchMode(true);
        mRoot.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1120, 0xFF16213E, 0xFF0F172A}));

        // Layer 1: Original device wallpaper with GPU frosted-glass blur
        mWallpaperView = new ImageView(this);
        mWallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mWallpaperView.setScaleX(1.08f);
        mWallpaperView.setScaleY(1.08f);
        if (Build.VERSION.SDK_INT >= 31) {
            mWallpaperView.setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP));
        }
        mRoot.addView(mWallpaperView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Layer 2: Soft frosted dark scrim so icons & text remain high contrast on bright wallpapers
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x480A101D, 0x5E0B1120, 0x78070B14}));
        mRoot.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Layer 3: Foreground desktop layout
        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(26), dp(14), dp(26), dp(62));
        mContent.setFocusable(true);
        mContent.setFocusableInTouchMode(true);

        // Top Bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), dp(12));

        TextView title = new TextView(this);
        title.setText("ExtBridge 扩展桌面");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        title.getPaint().setFakeBoldText(true);
        title.setShadowLayer(dpF(4f), 0f, dpF(1.5f), 0xAA000000);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.rightMargin = dp(16);
        top.addView(title, tLp);

        // Frosted glass Search box container
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(11), dp(4), dp(9), dp(4));
        GradientDrawable sbBg = new GradientDrawable();
        sbBg.setColor(0x66121A2A);
        sbBg.setCornerRadius(dpF(16f));
        sbBg.setStroke(1, 0x48FFFFFF);
        searchBox.setBackground(sbBg);

        TextView searchIcon = new TextView(this);
        searchIcon.setText("🔍");
        searchIcon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        searchIcon.setPadding(0, 0, dp(6), 0);
        searchBox.addView(searchIcon);

        mSearchInput = new EditText(this);
        mSearchInput.setHint("搜索应用名 / 拼音首字母 / 包名 (回车打开)");
        mSearchInput.setHintTextColor(0xFFAAB8CC);
        mSearchInput.setTextColor(Color.WHITE);
        mSearchInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        mSearchInput.setSingleLine(true);
        mSearchInput.setBackgroundColor(Color.TRANSPARENT);
        mSearchInput.setPadding(dp(2), dp(3), dp(6), dp(3));
        mSearchInput.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                applyFilter(s == null ? "" : s.toString());
            }
        });
        mSearchInput.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_UP)) {
                if (!mApps.isEmpty()) {
                    launchApp(mApps.get(0));
                }
                return true;
            }
            return false;
        });
        searchBox.addView(mSearchInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mBtnClear = new TextView(this);
        mBtnClear.setText("✕");
        mBtnClear.setTextColor(0xFFCBD5E1);
        mBtnClear.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        mBtnClear.setPadding(dp(8), dp(2), dp(8), dp(2));
        mBtnClear.setVisibility(View.GONE);
        mBtnClear.setOnClickListener(v -> clearSearch());
        searchBox.addView(mBtnClear);

        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sbLp.rightMargin = dp(12);
        top.addView(searchBox, sbLp);

        TextView btnModeTip = new TextView(this);
        btnModeTip.setText("🧩 显示模式");
        btnModeTip.setTextColor(0xFFBAE6FD);
        btnModeTip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        btnModeTip.setPadding(dp(11), dp(5), dp(11), dp(5));
        GradientDrawable mtBg = new GradientDrawable();
        mtBg.setColor(0x550C4A6E);
        mtBg.setCornerRadius(dpF(15f));
        mtBg.setStroke(1, 0x5538BDF8);
        btnModeTip.setBackground(mtBg);
        btnModeTip.setOnClickListener(v -> showAllAppModesPicker());
        LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mtLp.rightMargin = dp(8);
        top.addView(btnModeTip, mtLp);

        TextView btnWpRefresh = new TextView(this);
        btnWpRefresh.setText("🖼 同步壁纸");
        btnWpRefresh.setTextColor(0xFFE2E8F0);
        btnWpRefresh.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        btnWpRefresh.setPadding(dp(11), dp(5), dp(11), dp(5));
        GradientDrawable wpBg = new GradientDrawable();
        wpBg.setColor(0x55121A2A);
        wpBg.setCornerRadius(dpF(15f));
        wpBg.setStroke(1, 0x3AFFFFFF);
        btnWpRefresh.setBackground(wpBg);
        btnWpRefresh.setOnClickListener(v -> {
            loadWallpaperAsync(true);
            Toast.makeText(this, "正在同步平板原机壁纸...", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams wpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wpLp.rightMargin = dp(10);
        top.addView(btnWpRefresh, wpLp);

        mHeaderInfo = new TextView(this);
        mHeaderInfo.setTextColor(0xFFE0F2FE);
        mHeaderInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        mHeaderInfo.setPadding(dp(11), dp(5), dp(11), dp(5));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0x55121A2A);
        chipBg.setCornerRadius(dpF(15f));
        chipBg.setStroke(1, 0x3AFFFFFF);
        mHeaderInfo.setBackground(chipBg);
        top.addView(mHeaderInfo);

        mContent.addView(top);

        mTvEmpty = new TextView(this);
        mTvEmpty.setText("未找到匹配的应用，可尝试输入中文、拼音首字母或英文包名");
        mTvEmpty.setTextColor(0xFFE2E8F0);
        mTvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        mTvEmpty.setShadowLayer(dpF(3f), 0f, dpF(1.2f), 0xCC000000);
        mTvEmpty.setGravity(Gravity.CENTER);
        mTvEmpty.setVisibility(View.GONE);
        mContent.addView(mTvEmpty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mGrid = new GridView(this);
        mGrid.setVerticalSpacing(dp(12));
        mGrid.setHorizontalSpacing(dp(8));
        mGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        mGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        mGrid.setClipToPadding(false);
        mAdapter = new AppAdapter();
        mGrid.setAdapter(mAdapter);
        mGrid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < mApps.size()) {
                launchApp(mApps.get(position));
            }
        });
        mGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < mApps.size()) {
                showAppModeDialog(mApps.get(position));
                return true;
            }
            return false;
        });
        mContent.addView(mGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mRoot.addView(mContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Layer 4: In-Activity host for Dock / Flyout / Cursor in Scheme A (immune to global Settings overlay hiding)
        mOverlayHost = new FrameLayout(this);
        mOverlayHost.setClipChildren(false);
        mOverlayHost.setClipToPadding(false);
        mRoot.addView(mOverlayHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(mRoot);
        mContent.requestFocus();
        adjustColumns();
        refreshHeader();
    }

    private void loadWallpaperAsync(boolean force) {
        new Thread(() -> {
            Bitmap bmp = BridgeClient.getWallpaperBitmap(force);
            if (bmp != null && (force || bmp != mLastWpBitmap)) {
                mLastWpBitmap = bmp;
                runOnUiThread(() -> {
                    if (mWallpaperView != null && !bmp.isRecycled()) {
                        mWallpaperView.setImageBitmap(bmp);
                    }
                });
            }
        }).start();
    }

    private void clearSearch() {
        if (mSearchInput != null) mSearchInput.setText("");
        if (mRoot != null) mRoot.requestFocus();
    }

    private void applyFilter(String raw) {
        mQuery = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (mBtnClear != null) {
            mBtnClear.setVisibility(mQuery.isEmpty() ? View.GONE : View.VISIBLE);
        }
        mApps.clear();
        if (mQuery.isEmpty()) {
            mApps.addAll(mAllApps);
        } else {
            for (AppEntry e : mAllApps) {
                if (e.searchBlob.contains(mQuery)) {
                    mApps.add(e);
                }
            }
        }
        boolean empty = !mQuery.isEmpty() && mApps.isEmpty();
        if (mTvEmpty != null) mTvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (mGrid != null) mGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
    }

    private void adjustColumns() {
        if (mGrid == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float dpW = dm.widthPixels / Math.max(0.5f, dm.density) - 52f;
        int cols = Math.max(5, Math.min(14, Math.round(dpW / 165f)));
        mGrid.setNumColumns(cols);
    }

    private void refreshHeader() {
        if (mHeaderInfo == null) return;
        BridgeClient.Status st = BridgeClient.getStatus();
        if (st.connected) {
            mHeaderInfo.setText(st.virtW + "×" + st.virtH + " · " + st.virtDpi + "dpi · "
                    + Math.round(st.virtFps) + "Hz · " + (st.inputToExt == 1 ? "扩展键鼠" : "平板键鼠"));
        } else {
            mHeaderInfo.setText("Display #" + getDisplay().getDisplayId());
        }
    }

    private void loadAppsAsync() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN, null);
            q.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            Collections.sort(ris, new ResolveInfo.DisplayNameComparator(pm));

            Transliterator hanLatin = null;
            try {
                hanLatin = Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC");
            } catch (Throwable ignored) {}

            List<AppEntry> loaded = new ArrayList<>(ris.size());
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                String cls = ri.activityInfo.name;
                if (getPackageName().equals(pkg) && LauncherActivity.class.getName().equals(cls)) continue;
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.cls = cls;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.searchBlob = buildSearchBlob(e.label, pkg, hanLatin);
                try { e.icon = ri.loadIcon(pm); } catch (Throwable ignored) {}
                loaded.add(e);
            }
            runOnUiThread(() -> {
                mAllApps.clear();
                mAllApps.addAll(loaded);
                applyFilter(mQuery);
            });
        }).start();
    }

    private static String buildSearchBlob(String label, String pkg, Transliterator hanLatin) {
        StringBuilder sb = new StringBuilder();
        String lbl = label == null ? "" : label.toLowerCase(Locale.ROOT);
        sb.append(lbl).append(' ').append(pkg == null ? "" : pkg.toLowerCase(Locale.ROOT));
        if (hanLatin != null && !lbl.isEmpty()) {
            try {
                String py = hanLatin.transliterate(lbl).toLowerCase(Locale.ROOT);
                sb.append(' ').append(py).append(' ').append(py.replace(" ", ""));
                StringBuilder initials = new StringBuilder();
                for (String tok : py.split("[\\s\\p{Punct}]+")) {
                    if (!tok.isEmpty()) initials.append(tok.charAt(0));
                }
                sb.append(' ').append(initials);
            } catch (Throwable ignored) {}
        }
        return sb.toString();
    }

    private void launchApp(AppEntry e) {
        try {
            int dispId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(e.pkg, e.cls));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ActivityOptions opts = ActivityOptions.makeBasic();
            boolean routeSettingsToTablet = !NavAssistService.isRunning()
                    && ("com.android.settings".equals(e.pkg) || "com.miui.securitycenter".equals(e.pkg));
            if (routeSettingsToTablet) {
                opts.setLaunchDisplayId(0);
                Toast.makeText(this, "未开启无障碍：已在平板打开设置", Toast.LENGTH_SHORT).show();
            } else if (dispId > 0) {
                int mode = BridgeClient.prepareAppLaunch(e.pkg);
                opts.setLaunchDisplayId(dispId);
                try {
                    if (mode == BridgeClient.APP_MODE_LAND_FULL) {
                        opts.getClass().getMethod("setLaunchWindowingMode", int.class).invoke(opts, 1);
                    } else if (mode == BridgeClient.APP_MODE_FREEFORM) {
                        opts.getClass().getMethod("setLaunchWindowingMode", int.class).invoke(opts, 5);
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int w = Math.max(420, (int) (dm.widthPixels * 0.44f));
                        int h = Math.max(540, (int) (dm.heightPixels * 0.84f));
                        int l = Math.max(0, (dm.widthPixels - w) / 2);
                        int t = Math.max(20, (dm.heightPixels - h) / 2 - dp(16));
                        opts.setLaunchBounds(new Rect(l, t, l + w, t + h));
                    }
                } catch (Throwable ignored) {}
            }
            startActivity(intent, opts.toBundle());
        } catch (Throwable t) {
            BridgeClient.launchComponent(e.pkg, e.cls);
        }
    }

    private void refreshAppModesAsync() {
        new Thread(() -> {
            Map<String, Integer> modes = BridgeClient.getAllAppModes();
            runOnUiThread(() -> {
                mAppModes = modes != null ? modes : new HashMap<>();
                if (mAdapter != null) mAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    private int getAppModeLocal(String pkg) {
        Integer v = mAppModes != null ? mAppModes.get(pkg) : null;
        return v != null ? v : BridgeClient.APP_MODE_DEFAULT;
    }

    private void showAllAppModesPicker() {
        if (mAllApps.isEmpty()) {
            Toast.makeText(this, "应用列表加载中...", Toast.LENGTH_SHORT).show();
            return;
        }
        List<AppEntry> sorted = new ArrayList<>(mAllApps);
        Collections.sort(sorted, (a, b) -> {
            int ma = getAppModeLocal(a.pkg) > 0 ? 0 : 1;
            int mb = getAppModeLocal(b.pkg) > 0 ? 0 : 1;
            if (ma != mb) return ma - mb;
            return String.valueOf(a.label).compareToIgnoreCase(String.valueOf(b.label));
        });
        int count = sorted.size();
        String[] items = new String[count + 1];
        items[0] = "↺ 全部恢复【跟随系统默认】（清除所有自定义兼容覆盖）";
        for (int i = 0; i < count; i++) {
            AppEntry ae = sorted.get(i);
            int m = getAppModeLocal(ae.pkg);
            items[i + 1] = ae.label + "  [" + BridgeClient.appModeLabel(m) + "]";
        }
        new AlertDialog.Builder(this)
                .setTitle("🧩 分应用显示模式（也可以在桌面图标上长按 / 鼠标右键设置）")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        new Thread(() -> {
                            BridgeClient.resetAllAppModes();
                            Map<String, Integer> latest = BridgeClient.getAllAppModes();
                            runOnUiThread(() -> {
                                mAppModes = latest;
                                if (mAdapter != null) mAdapter.notifyDataSetChanged();
                                Toast.makeText(this, "已全部恢复系统默认模式（重启对应应用生效）", Toast.LENGTH_SHORT).show();
                            });
                        }).start();
                    } else if (which - 1 < sorted.size()) {
                        showAppModeDialog(sorted.get(which - 1));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAppModeDialog(AppEntry e) {
        if (e == null) return;
        int cur = getAppModeLocal(e.pkg);
        String[] labels = {
                (cur == 0 ? "● " : "") + "跟随系统默认（推荐微信 / 夸克 / 正常原生适配应用）",
                (cur == 1 ? "● " : "") + "强制横屏全屏（适合红果短剧等竖屏视频类 App）",
                (cur == 2 ? "● " : "") + "竖屏居中兼容（适合拼多多等固定比例竖屏页面）",
                (cur == 3 ? "● " : "") + "自由小窗模式（以可缩放悬浮窗形式启动）"
        };
        new AlertDialog.Builder(this)
                .setTitle(e.label + "\n" + e.pkg)
                .setItems(labels, (dialog, which) -> {
                    final int chosen = which;
                    Toast.makeText(this, "正在应用【" + BridgeClient.appModeLabel(chosen) + "】并重启应用...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        BridgeClient.setAppMode(e.pkg, chosen, true);
                        Map<String, Integer> latest = BridgeClient.getAllAppModes();
                        runOnUiThread(() -> {
                            mAppModes = latest;
                            if (mAdapter != null) mAdapter.notifyDataSetChanged();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return mApps.size(); }
        @Override public Object getItem(int pos) { return mApps.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            LinearLayout card;
            ImageView iv;
            TextView tv;
            TextView badge;
            if (convertView instanceof LinearLayout) {
                card = (LinearLayout) convertView;
                iv = (ImageView) card.getChildAt(0);
                tv = (TextView) card.getChildAt(1);
                badge = (TextView) card.getChildAt(2);
            } else {
                card = new LinearLayout(LauncherActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);
                card.setPadding(dp(6), dp(8), dp(6), dp(8));

                GradientDrawable hoverBg = new GradientDrawable();
                hoverBg.setColor(0x36FFFFFF);
                hoverBg.setCornerRadius(dpF(14f));
                StateListDrawable sld = new StateListDrawable();
                sld.addState(new int[]{android.R.attr.state_pressed}, hoverBg);
                sld.addState(new int[]{android.R.attr.state_hovered}, hoverBg);
                sld.addState(new int[]{android.R.attr.state_focused}, hoverBg);
                sld.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
                card.setBackground(sld);

                iv = new ImageView(LauncherActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(54), dp(54));
                ivLp.bottomMargin = dp(6);
                card.addView(iv, ivLp);

                tv = new TextView(LauncherActivity.this);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
                tv.setShadowLayer(dpF(4f), 0f, dpF(1.5f), 0xE6000000);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(2);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(tv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                badge = new TextView(LauncherActivity.this);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f);
                badge.setPadding(dp(5), dp(1), dp(5), dp(1));
                badge.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bLp.topMargin = dp(3);
                card.addView(badge, bLp);
            }
            AppEntry e = mApps.get(pos);
            iv.setImageDrawable(e.icon);
            tv.setText(e.label);
            int mode = getAppModeLocal(e.pkg);
            if (mode > 0) {
                badge.setVisibility(View.VISIBLE);
                badge.setText(BridgeClient.appModeBadge(mode));
                int bgColor = mode == BridgeClient.APP_MODE_LAND_FULL ? 0xCC0284C7
                        : (mode == BridgeClient.APP_MODE_PORT_COMPAT ? 0xCCD97706 : 0xCC7C3AED);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(bgColor);
                bg.setCornerRadius(dpF(6f));
                badge.setBackground(bg);
                badge.setTextColor(Color.WHITE);
            } else {
                badge.setVisibility(View.GONE);
            }
            card.setOnGenericMotionListener((v, ev) -> {
                if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                        && ev.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                    showAppModeDialog(e);
                    return true;
                }
                return false;
            });
            return card;
        }
    }
}
