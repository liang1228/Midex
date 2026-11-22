package com.extbridge.helper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** The same taskbar is hosted by the desktop Activity and the overlay service. */
final class DesktopTaskbar extends LinearLayout {
    interface Actions {
        void apps();
        void search();
        void back();
        void home();
        void recents();
        void touchpad();
        void quickSettings();
        void launch(String pkg, String cls);
    }

    final LinearLayout tasks;
    final LinearLayout tray;
    private final TextView battery;
    private final WifiManager wifiManager;
    private final DesktopStyle.WifiSignal wifiSignal = new DesktopStyle.WifiSignal();
    private static final String TASKBAR_PINS = "taskbarPins";
    private final LinearLayout pinnedArea;
    private final Actions actions;
    private final SharedPreferences desktopPrefs;
    private final Map<String, View> runningIndicators = new HashMap<>();
    private List<BridgeClient.TaskEntry> running = Collections.emptyList();
    private final SharedPreferences.OnSharedPreferenceChangeListener pinsChanged = (prefs, key) -> {
        if (TASKBAR_PINS.equals(key)) refreshPins();
    };

    DesktopTaskbar(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        desktopPrefs = context.getSharedPreferences("desktop", Context.MODE_PRIVATE);
        wifiManager = context.getApplicationContext().getSystemService(WifiManager.class);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        setPadding(dp(14), dp(4), dp(14), dp(4));
        setBackground(DesktopStyle.surface(context, 0xE81A2539, 0));
        setElevation(dp(8));
        addControl("apps", "应用", actions::apps);
        addControl("search", "搜索应用", actions::search);
        addDivider();
        addControl("back", "返回", actions::back);
        addControl("home", "显示桌面", actions::home);
        addControl("recent", "最近任务", actions::recents);
        addDivider();

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout appArea = new LinearLayout(context);
        appArea.setGravity(Gravity.CENTER_VERTICAL);
        pinnedArea = new LinearLayout(context);
        appArea.addView(pinnedArea);
        tasks = new LinearLayout(context);
        tasks.setGravity(Gravity.CENTER_VERTICAL);
        appArea.addView(tasks);
        scroll.addView(appArea, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        addView(scroll, new LayoutParams(0, dp(44), 1));
        addControl("keyboard", "平板触摸板", actions::touchpad);

        tray = new LinearLayout(context);
        tray.setGravity(Gravity.CENTER_VERTICAL);
        tray.setPadding(dp(4), 0, dp(4), 0);
        tray.setBackground(DesktopStyle.feedback(context, 8));
        tray.setContentDescription("快捷设置，Wi-Fi 未连接");
        tray.setTooltipText("快捷设置");
        tray.setFocusable(true);
        tray.setOnClickListener(v -> actions.quickSettings());
        UiMotion.pressFeedback(tray);
        ImageView wifi = new ImageView(context);
        wifi.setImageDrawable(wifiSignal);
        tray.addView(wifi, new LayoutParams(dp(20), dp(20)));
        ImageView volume = new ImageView(context);
        volume.setImageDrawable(new DesktopStyle.Glyph("volume", DesktopStyle.TEXT));
        LayoutParams volumeLp = new LayoutParams(dp(20), dp(20));
        // Account for the different insets of the Wi-Fi, volume, and battery glyphs.
        volumeLp.setMargins(dp(13), 0, dp(17), 0);
        tray.addView(volume, volumeLp);
        battery = new TextView(context);
        battery.setTextColor(DesktopStyle.TEXT);
        battery.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        battery.setFontFeatureSettings("'tnum'");
        battery.setIncludeFontPadding(false);
        battery.setText("—");
        tray.addView(battery);
        addView(tray, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));

        LinearLayout clock = new LinearLayout(context);
        clock.setOrientation(VERTICAL);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        clock.setPadding(dp(12), 0, 0, 0);
        for (String format : new String[]{"HH:mm:ss", "M月d日"}) {
            TextClock text = new TextClock(context);
            text.setFormat24Hour(format);
            text.setFormat12Hour(format);
            text.setTextColor(DesktopStyle.TEXT);
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
            text.setGravity(Gravity.RIGHT);
            text.setFontFeatureSettings("'tnum'");
            text.setIncludeFontPadding(false);
            clock.addView(text);
        }
        clock.setContentDescription("日期和时间");
        clock.setOnClickListener(v -> actions.quickSettings());
        addView(clock);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        desktopPrefs.registerOnSharedPreferenceChangeListener(pinsChanged);
        refreshPins();
    }

    @Override protected void onDetachedFromWindow() {
        desktopPrefs.unregisterOnSharedPreferenceChangeListener(pinsChanged);
        super.onDetachedFromWindow();
    }

    void status(int percent) {
        battery.setText(percent >= 0 ? percent + "%" : "—");
        if (wifiSignal.setLevel(wifiLevel())) {
            int level = wifiSignal.getLevel();
            tray.setContentDescription("快捷设置，" + (level == 0 ? "Wi-Fi 未连接" : "Wi-Fi 信号 " + level + " 格"));
        }
    }

    @SuppressWarnings("deprecation")
    private int wifiLevel() {
        if (wifiManager == null || !wifiManager.isWifiEnabled()) return 0;
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null || info.getSupplicantState() != SupplicantState.COMPLETED) return 0;
        return 1 + WifiManager.calculateSignalLevel(info.getRssi(), 4);
    }

    void runningTasks(List<BridgeClient.TaskEntry> tasks) {
        running = tasks;
        for (Map.Entry<String, View> pin : runningIndicators.entrySet()) {
            int color = 0;
            for (BridgeClient.TaskEntry task : tasks) {
                if (!pin.getKey().equals(task.pkg)) continue;
                color = task.isTopExt() ? 0xFFC4DDFF : 0xFF94A3B8;
                if (task.isTopExt()) break;
            }
            pin.getValue().setBackgroundColor(color);
        }
    }

    boolean hasPin(String pkg) { return runningIndicators.containsKey(pkg); }

    private void refreshPins() {
        Context context = getContext();
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, ResolveInfo> launchers = new HashMap<>();
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            if (info.activityInfo != null) launchers.putIfAbsent(info.activityInfo.packageName, info);
        }
        pinnedArea.removeAllViews();
        runningIndicators.clear();
        for (String pkg : pinPackages(context)) {
            ResolveInfo info = launchers.get(pkg);
            if (info == null) continue;
            FrameLayout slot = new FrameLayout(context);
            slot.setBackground(DesktopStyle.feedback(context, 8));
            String label = String.valueOf(info.loadLabel(pm));
            slot.setContentDescription(label);
            slot.setTooltipText(label);
            slot.setFocusable(true);
            slot.setOnClickListener(v -> actions.launch(pkg, info.activityInfo.name));
            UiMotion.pressFeedback(slot);

            ImageView icon = new ImageView(context);
            icon.setImageDrawable(info.loadIcon(pm));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            slot.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
            View indicator = new View(context);
            FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                    dp(8), dp(2), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            indicatorParams.bottomMargin = dp(3);
            slot.addView(indicator, indicatorParams);
            pinnedArea.addView(slot, new LayoutParams(dp(42), dp(42)));
            runningIndicators.put(pkg, indicator);
        }
        runningTasks(running);
    }

    static LinkedHashSet<String> pinPackages(Context context) {
        String stored = context.getSharedPreferences("desktop", Context.MODE_PRIVATE).getString(TASKBAR_PINS, null);
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        if (stored == null) {
            for (ResolveInfo info : favorites(context)) packages.add(info.activityInfo.packageName);
        } else if (!stored.isEmpty()) {
            Collections.addAll(packages, stored.split(","));
        }
        return packages;
    }

    static void setPinned(Context context, String pkg, boolean pinned) {
        LinkedHashSet<String> packages = pinPackages(context);
        if (pinned) packages.add(pkg); else packages.remove(pkg);
        context.getSharedPreferences("desktop", Context.MODE_PRIVATE).edit()
                .putString(TASKBAR_PINS, String.join(",", packages)).apply();
    }

    private int dp(float value) { return DesktopStyle.dp(getContext(), value); }

    private void addControl(String glyph, String label, Runnable action) {
        ImageView button = new ImageView(getContext());
        button.setImageDrawable(new DesktopStyle.Glyph(glyph, DesktopStyle.TEXT));
        button.setBackground(DesktopStyle.feedback(getContext(), 8));
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        UiMotion.pressFeedback(button);
        addView(button, new LayoutParams(dp(40), dp(40)));
    }

    private void addDivider() {
        View divider = new View(getContext());
        divider.setBackgroundColor(0x24FFFFFF);
        LayoutParams lp = new LayoutParams(dp(.5f), dp(20));
        lp.setMargins(dp(7), 0, dp(7), 0);
        addView(divider, lp);
    }

    static List<ResolveInfo> favorites(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(query, 0);
        List<ResolveInfo> result = new ArrayList<>();
        String[][] preferred = {
                {"com.android.chrome", "com.sec.android.app.sbrowser", "com.android.browser"},
                {"com.google.android.documentsui", "com.android.documentsui", "com.sec.android.app.myfiles"},
                {"com.google.android.apps.photos", "com.miui.gallery", "com.sec.android.gallery3d"},
                {"com.android.settings"}
        };
        for (String[] group : preferred) {
            boolean found = false;
            for (String pkg : group) {
                for (ResolveInfo item : all) {
                    if (item.activityInfo != null && pkg.equals(item.activityInfo.packageName)) {
                        result.add(item); found = true; break;
                    }
                }
                if (found) break;
            }
        }
        return result;
    }
}
