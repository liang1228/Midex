package com.extbridge.helper;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.icu.text.Transliterator;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
    private static final int REQUEST_NEARBY_DEVICES = 71;
    private static LauncherActivity sPermissionHost;

    static boolean requestNearbyDevices() {
        LauncherActivity host = sPermissionHost;
        if (host == null || host.isFinishing()) return false;
        host.requestPermissions(BluetoothDiscovery.PERMISSIONS, REQUEST_NEARBY_DEVICES);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NEARBY_DEVICES) BridgeService.onNearbyDevicesPermissionResult();
    }
    private static class AppEntry {
        String label;
        String pkg;
        String cls;
        String searchBlob;
        Drawable icon;
        boolean portrait;
    }

    private final List<AppEntry> mAllApps = new ArrayList<>();
    private final List<AppEntry> mApps = new ArrayList<>();
    private FrameLayout mRoot;
    private FrameLayout mOverlayHost;
    private FrameLayout mDrawer;
    private LinearLayout mShortcuts;
    private DesktopTaskbar mLocalTaskbar;
    private boolean mDrawerOpen;
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
        if (getIntent().getBooleanExtra("showApps", false)) setDrawerOpen(true, getIntent().getBooleanExtra("search", false));
        loadWallpaperAsync(false);
        BridgeClient.setOnBinderReady(() -> runOnUiThread(() -> {
            refreshHeader();
            loadWallpaperAsync(false);
        }));
    }

    @Override
    protected void onStart() {
        super.onStart();
        sPermissionHost = this;
        BridgeService.setDockHidden(false);
        BridgeService.setLauncherOverlayHost(mOverlayHost);
    }

    @Override
    protected void onStop() {
        if (sPermissionHost == this) sPermissionHost = null;
        BridgeService.clearLauncherOverlayHost(mOverlayHost);
        UiMotion.drawer(mDrawer, mContent, mDrawerOpen, false, this::clearSearch);
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
        BridgeService.setLauncherOverlayHost(mOverlayHost);
        BridgeService.refreshFromReceiver(this);
        if (intent.getBooleanExtra("showApps", false)) setDrawerOpen(true, intent.getBooleanExtra("search", false));
        else setDrawerOpen(false, false);
        if (intent.getBooleanExtra("showRecents", false)) showRecentTasks();
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
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && mDrawerOpen) {
            setDrawerOpen(false, false, false);
            return true;
        }
        if (mSearchInput != null && !mSearchInput.hasFocus()) {
            boolean isSlash = (keyCode == KeyEvent.KEYCODE_SLASH);
            boolean isCtrlF = (keyCode == KeyEvent.KEYCODE_F && event != null && event.isCtrlPressed());
            int ch = event != null ? event.getUnicodeChar() : 0;
            if (isSlash || isCtrlF) {
                setDrawerOpen(true, true, false);
                return true;
            }
            if (ch >= 0x20 && !Character.isISOControl(ch) && (ch < 0xE000)) {
                if (event != null && (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed())) return super.onKeyDown(keyCode, event);
                setDrawerOpen(true, true, false);
                mSearchInput.append(String.valueOf((char) ch));
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void buildUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mRoot = new FrameLayout(this);
        mRoot.setBackground(new DesktopStyle.Wallpaper());
        mRoot.setFocusableInTouchMode(true);
        mWallpaperView = new ImageView(this);
        mWallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mRoot.addView(mWallpaperView, new FrameLayout.LayoutParams(-1, -1));

        mShortcuts = new LinearLayout(this);
        mShortcuts.setOrientation(LinearLayout.VERTICAL);
        mShortcuts.setPadding(dp(18), dp(20), 0, 0);
        android.widget.ScrollView shortcutsScroll = new android.widget.ScrollView(this);
        shortcutsScroll.setVerticalScrollBarEnabled(false);
        shortcutsScroll.addView(mShortcuts);
        FrameLayout.LayoutParams shortcutsLp = new FrameLayout.LayoutParams(dp(124), -1, Gravity.TOP | Gravity.LEFT);
        shortcutsLp.bottomMargin = dp(64);
        mRoot.addView(shortcutsScroll, shortcutsLp);
        mRoot.setOnClickListener(v -> setDrawerOpen(false, false));
        mRoot.setOnLongClickListener(v -> { showDesktopMenu(v); return true; });

        mDrawer = new FrameLayout(this);
        mDrawer.setBackgroundColor(0x35030A17);
        mDrawer.setVisibility(View.GONE);
        mDrawer.setOnClickListener(v -> setDrawerOpen(false, false));
        mRoot.addView(mDrawer, new FrameLayout.LayoutParams(-1, -1));
        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(30), dp(23), dp(30), dp(22));
        mContent.setBackground(DesktopStyle.surface(this, 0xF024344D, 22));
        mContent.setElevation(dp(22));
        mContent.setOnClickListener(v -> {});
        FrameLayout.LayoutParams drawerLp = new FrameLayout.LayoutParams(dp(860), dp(580), Gravity.CENTER);
        drawerLp.bottomMargin = dp(44);
        mDrawer.addView(mContent, drawerLp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = desktopText("应用", 21, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView close = DesktopStyle.button(this, "close", "关闭应用抽屉");
        close.setOnClickListener(v -> setDrawerOpen(false, false));
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));
        mContent.addView(titleRow);
        mHeaderInfo = desktopText("", 11, DesktopStyle.MUTED);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, dp(24));
        infoLp.bottomMargin = dp(14);
        mContent.addView(mHeaderInfo, infoLp);

        LinearLayout search = new LinearLayout(this);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(13), 0, dp(8), 0);
        search.setBackground(DesktopStyle.surface(this, 0x40202A3E, 12));
        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageDrawable(new DesktopStyle.Glyph("search", DesktopStyle.MUTED));
        search.addView(searchIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        mSearchInput = new EditText(this);
        mSearchInput.setSingleLine(true);
        mSearchInput.setTextColor(Color.WHITE);
        mSearchInput.setHintTextColor(DesktopStyle.MUTED);
        mSearchInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        mSearchInput.setHint("搜索应用");
        mSearchInput.setBackgroundColor(Color.TRANSPARENT);
        mSearchInput.setPadding(dp(12), 0, dp(6), 0);
        mSearchInput.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) { applyFilter(value.toString()); }
        });
        mSearchInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP)) {
                if (!mApps.isEmpty()) launchApp(mApps.get(0));
                return true;
            }
            return false;
        });
        search.addView(mSearchInput, new LinearLayout.LayoutParams(0, dp(44), 1));
        mBtnClear = DesktopStyle.button(this, "close", "清除搜索");
        mBtnClear.setOnClickListener(v -> clearSearch());
        mBtnClear.setVisibility(View.GONE);
        search.addView(mBtnClear, new LinearLayout.LayoutParams(dp(36), dp(40)));
        mContent.addView(search, new LinearLayout.LayoutParams(-1, dp(44)));

        mTvEmpty = desktopText("没有找到应用", 14, DesktopStyle.MUTED);
        mTvEmpty.setGravity(Gravity.CENTER);
        mTvEmpty.setVisibility(View.GONE);
        mContent.addView(mTvEmpty, new LinearLayout.LayoutParams(-1, 0, 1));
        mGrid = new GridView(this);
        mGrid.setPadding(0, dp(24), 0, 0);
        mGrid.setVerticalSpacing(dp(12));
        mGrid.setHorizontalSpacing(dp(12));
        mGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        mGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        mAdapter = new AppAdapter();
        mGrid.setAdapter(mAdapter);
        mGrid.setOnItemClickListener((parent, view, position, id) -> launchApp(mApps.get(position)));
        mGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            showAppContext(mApps.get(position), view); return true;
        });
        mContent.addView(mGrid, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = desktopText("右键应用查看更多选项", 11, DesktopStyle.MUTED);
        hint.setGravity(Gravity.CENTER);
        mContent.addView(hint, new LinearLayout.LayoutParams(-1, dp(25)));

        mLocalTaskbar = new DesktopTaskbar(this, new DesktopTaskbar.Actions() {
            public void apps() { setDrawerOpen(!mDrawerOpen, false); }
            public void search() { setDrawerOpen(true, true); }
            public void back() { if (mDrawerOpen) setDrawerOpen(false, false); else BridgeClient.sendBack(); }
            public void home() { setDrawerOpen(false, false); }
            public void recents() { showRecentTasks(); }
            public void touchpad() { startActivity(new Intent(LauncherActivity.this, TouchpadActivity.class),
                    ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()); }
            public void quickSettings() {
                if (!BridgeService.openLauncherQuickSettings(mOverlayHost)) {
                    BridgeService.refreshFromReceiver(LauncherActivity.this);
                    Toast.makeText(LauncherActivity.this, "快捷设置正在准备，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            }
            public void launch(String pkg, String cls) { launchComponent(pkg, cls); }
        });
        mRoot.addView(mLocalTaskbar, new FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM));
        mOverlayHost = new FrameLayout(this);
        mOverlayHost.setClipChildren(false);
        mRoot.addView(mOverlayHost, new FrameLayout.LayoutParams(-1, -1));
        setContentView(mRoot);
        mRoot.post(this::adjustColumns);
        mRoot.requestFocus();
        refreshHeader();
    }

    private TextView desktopText(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void setDrawerOpen(boolean open, boolean search) {
        setDrawerOpen(open, search, true);
    }

    private void setDrawerOpen(boolean open, boolean search, boolean animate) {
        if (mDrawerOpen == open) {
            if (open && search) mSearchInput.requestFocus();
            return;
        }
        mDrawerOpen = open;
        UiMotion.drawer(mDrawer, mContent, open, animate, this::clearSearch);
        if (open) {
            if (search) mSearchInput.requestFocus(); else mRoot.requestFocus();
        } else {
            mRoot.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(mSearchInput.getWindowToken(), 0);
        }
    }

    private void refreshShortcuts() {
        mShortcuts.removeAllViews();
        java.util.Set<String> pinned = getSharedPreferences("desktop", 0).getStringSet("shortcuts", null);
        List<AppEntry> entries = new ArrayList<>();
        for (ResolveInfo favorite : DesktopTaskbar.favorites(this)) {
            String pkg = favorite.activityInfo.packageName;
            if (pinned != null && !pinned.contains(pkg)) continue;
            for (AppEntry entry : mAllApps) {
                if (entry.pkg.equals(pkg)) { entries.add(entry); break; }
            }
        }
        if (pinned != null) for (AppEntry entry : mAllApps) {
            if (pinned.contains(entry.pkg) && !entries.contains(entry)) entries.add(entry);
        }
        for (AppEntry entry : entries) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
            item.setBackground(DesktopStyle.feedback(this, 8)); item.setFocusable(true);
            ImageView icon = new ImageView(this); icon.setImageDrawable(entry.icon);
            item.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView label = desktopText(entry.label, 11, Color.WHITE);
            label.setGravity(Gravity.CENTER); label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(dp(92), dp(29));
            item.addView(label, labelLp);
            item.setOnClickListener(v -> launchApp(entry));
            item.setOnLongClickListener(v -> { showAppContext(entry, v); return true; });
            item.setOnGenericMotionListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                        && event.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                    showAppContext(entry, v); return true;
                }
                return false;
            });
            mShortcuts.addView(item, new LinearLayout.LayoutParams(dp(100), dp(94)));
        }
    }

    private void showAppContext(AppEntry entry, View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add("打开").setOnMenuItemClickListener(item -> { launchApp(entry); return true; });
        java.util.Set<String> saved = getSharedPreferences("desktop", 0).getStringSet("shortcuts", null);
        java.util.Set<String> pins = new java.util.HashSet<>();
        if (saved == null) for (ResolveInfo favorite : DesktopTaskbar.favorites(this)) {
            pins.add(favorite.activityInfo.packageName);
        } else pins.addAll(saved);
        boolean pinned = pins.contains(entry.pkg);
        menu.getMenu().add(pinned ? "从桌面移除" : "添加到桌面").setOnMenuItemClickListener(item -> {
            if (pinned) pins.remove(entry.pkg); else pins.add(entry.pkg);
            getSharedPreferences("desktop", 0).edit().putStringSet("shortcuts", pins).apply();
            refreshShortcuts(); return true;
        });
        boolean taskbarPinned = DesktopTaskbar.pinPackages(this).contains(entry.pkg);
        menu.getMenu().add(taskbarPinned ? "从底栏取消固定" : "固定到底栏").setOnMenuItemClickListener(item -> {
            DesktopTaskbar.setPinned(this, entry.pkg, !taskbarPinned);
            return true;
        });
        menu.getMenu().add("窗口显示模式").setOnMenuItemClickListener(item -> {
            showAppModeDialog(entry); return true;
        });
        menu.show();
    }

    private void showDesktopMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add("Midex 设置").setOnMenuItemClickListener(item -> {
            startActivity(new Intent(this, MainActivity.class), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
            return true;
        });
        menu.getMenu().add("应用显示模式").setOnMenuItemClickListener(item -> { showAllAppModesPicker(); return true; });
        menu.getMenu().add("使用平板壁纸").setOnMenuItemClickListener(item -> { loadWallpaperAsync(true); return true; });
        menu.getMenu().add("使用默认壁纸").setOnMenuItemClickListener(item -> {
            getSharedPreferences("desktop", 0).edit().putBoolean("deviceWallpaper", false).apply();
            mWallpaperView.setImageDrawable(null); return true;
        });
        menu.show();
    }

    private void launchComponent(String pkg, String cls) {
        for (AppEntry entry : mAllApps) if (entry.pkg.equals(pkg) && entry.cls.equals(cls)) { launchApp(entry); return; }
    }

    private void showRecentTasks() {
        new Thread(() -> {
            List<BridgeClient.TaskEntry> tasks = BridgeClient.getTasks();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                android.widget.PopupMenu menu = new android.widget.PopupMenu(this, mLocalTaskbar);
                for (BridgeClient.TaskEntry task : tasks) if (task.onExt()) {
                    menu.getMenu().add(task.pkg)
                            .setOnMenuItemClickListener(item -> { BridgeClient.switchToTask(task.taskId, task.pkg); return true; });
                }
                if (menu.getMenu().size() == 0) menu.getMenu().add("没有打开的外接屏任务").setEnabled(false);
                menu.show();
            });
        }, "DesktopRecents").start();
    }

    private void loadWallpaperAsync(boolean force) {
        if (force) getSharedPreferences("desktop", 0).edit().putBoolean("deviceWallpaper", true).apply();
        if (!getSharedPreferences("desktop", 0).getBoolean("deviceWallpaper", false)) return;
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
        if (mGrid == null || mRoot.getWidth() == 0) return;
        int width = Math.min(dp(860), mRoot.getWidth() - dp(56));
        int height = Math.min(dp(580), mRoot.getHeight() - dp(112));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mContent.getLayoutParams();
        lp.width = Math.max(dp(240), width); lp.height = Math.max(dp(240), height);
        mContent.setLayoutParams(lp);
        mGrid.setNumColumns(Math.max(3, Math.min(8, (width - dp(60)) / dp(100))));
    }

    private void refreshHeader() {
        if (mHeaderInfo == null) return;
        if (mLocalTaskbar != null) {
            mLocalTaskbar.setVisibility(BridgeService.hasDesktopTaskbar(getDisplay().getDisplayId()) ? View.GONE : View.VISIBLE);
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = battery == null ? -1 : battery.getIntExtra("level", -1);
            int scale = battery == null ? 100 : battery.getIntExtra("scale", 100);
            mLocalTaskbar.status(level < 0 ? -1 : level * 100 / Math.max(1, scale));
        }
        mHeaderInfo.setText(mAllApps.size() + " 个应用");
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
                int orientation = ri.activityInfo.screenOrientation;
                e.portrait = orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        || orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.searchBlob = buildSearchBlob(e.label, pkg, hanLatin);
                try { e.icon = ri.loadIcon(pm); } catch (Throwable ignored) {}
                loaded.add(e);
            }
            runOnUiThread(() -> {
                mAllApps.clear();
                mAllApps.addAll(loaded);
                applyFilter(mQuery);
                refreshShortcuts();
                refreshHeader();
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
        setDrawerOpen(false, false);
        try {
            int dispId = getDisplay() != null ? getDisplay().getDisplayId() : 0;
            boolean systemSettings = "com.android.settings".equals(e.pkg);
            if (dispId > 0 && systemSettings && BridgeClient.getStatus().hasPrivilegedBackend()) {
                new Thread(() -> {
                    if (!BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, dispId, Settings.ACTION_SETTINGS)) {
                        runOnUiThread(() -> Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show());
                    }
                }).start();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(e.pkg, e.cls));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ActivityOptions opts = ActivityOptions.makeBasic();
            boolean routeSettingsToTablet = dispId > 0 && !NavAssistService.isRunning()
                    && (systemSettings || "com.miui.securitycenter".equals(e.pkg));
            if (dispId > 0 && !routeSettingsToTablet && BridgeClient.getService() != null) {
                new Thread(() -> BridgeClient.launchComponent(e.pkg, e.cls)).start();
                return;
            }
            if (routeSettingsToTablet) {
                opts.setLaunchDisplayId(0);
            } else if (dispId > 0) {
                int mode = BridgeClient.prepareAppLaunch(e.pkg);
                opts.setLaunchDisplayId(dispId);
                try {
                    if (mode == BridgeClient.APP_MODE_LAND_FULL) {
                        opts.getClass().getMethod("setLaunchWindowingMode", int.class).invoke(opts, 1);
                    } else if (mode == BridgeClient.APP_MODE_FREEFORM || mode == BridgeClient.APP_MODE_DEFAULT) {
                        opts.getClass().getMethod("setLaunchWindowingMode", int.class).invoke(opts, 5);
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int h = Math.min(dm.heightPixels - dp(100), Math.max(dp(260), (int) (dm.heightPixels * 0.72f)));
                        int w = Math.min(dm.widthPixels - dp(48), mode == BridgeClient.APP_MODE_DEFAULT && e.portrait
                                ? Math.round(h * 9f / 16f) : Math.max(dp(360), (int) (dm.widthPixels * 0.64f)));
                        int l = Math.max(0, (dm.widthPixels - w) / 2);
                        int t = Math.max(20, (dm.heightPixels - h) / 2 - dp(16));
                        opts.setLaunchBounds(new Rect(l, t, l + w, t + h));
                    }
                } catch (Throwable ignored) {}
            }
            startActivity(intent, opts.toBundle());
            if (routeSettingsToTablet) {
                Toast.makeText(this, e.label + "已在平板打开", Toast.LENGTH_SHORT).show();
            }
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
        items[0] = "全部恢复桌面默认窗口（清除自定义兼容覆盖）";
        for (int i = 0; i < count; i++) {
            AppEntry ae = sorted.get(i);
            int m = getAppModeLocal(ae.pkg);
            items[i + 1] = ae.label + "  [" + BridgeClient.appModeLabel(m) + "]";
        }
        new AlertDialog.Builder(this)
                .setTitle("分应用显示模式（也可以在桌面图标上长按 / 鼠标右键设置）")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        new Thread(() -> {
                            BridgeClient.resetAllAppModes();
                            Map<String, Integer> latest = BridgeClient.getAllAppModes();
                            runOnUiThread(() -> {
                                mAppModes = latest;
                                if (mAdapter != null) mAdapter.notifyDataSetChanged();
                                Toast.makeText(this, "已恢复桌面默认窗口（重启对应应用生效）", Toast.LENGTH_SHORT).show();
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
                (cur == 0 ? "● " : "") + "桌面默认窗口（由系统决定应用能否调整大小）",
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
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(48), dp(48));
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
                    showAppContext(e, v);
                    return true;
                }
                return false;
            });
            return card;
        }
    }
}
