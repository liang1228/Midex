package com.extbridge.helper;

import androidx.activity.ComponentActivity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private WindowRefreshRate mRefreshRate;
    private volatile String mLastDriverJson;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRefreshRate = new WindowRefreshRate(this);
        setTheme(R.style.ConsoleTheme);
        Ui.configure(this);
        if (ensureOnDisplay0()) return;
        BridgeService.ensureStarted(this);
        ConsoleUi.show(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRefreshRate.start();
        BridgeClient.requestBinder(this);
        BridgeService.ensureStarted(this);
    }

    @Override
    protected void onPause() {
        mRefreshRate.stop();
        super.onPause();
    }

    public void setHighRefresh(boolean enabled) {
        getSharedPreferences("display_ui", 0).edit().putBoolean("highRefresh", enabled).apply();
        mRefreshRate.apply();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.configure(this);
        mRefreshRate.apply();
    }

    @Override
    protected void onDestroy() {
        AdbBootstrap.stopDiscovery();
        super.onDestroy();
    }

    public void openTouchpad() {
        startActivity(new Intent(this, TouchpadActivity.class));
    }

    public void openDesktop() {
        BridgeClient.relaunchLauncher();
    }

    public void rebuildDesktop() {
        BridgeClient.rebuild();
        BridgeService.refreshFromReceiver(this);
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

    public void exportDriverJson() {
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

    public void shareDriverJson() {
        String json = mLastDriverJson;
        if (json == null || json.isEmpty()) {
            Toast.makeText(this, "请先采集驱动信息", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Midex 驱动信息");
        i.putExtra(Intent.EXTRA_TEXT, json);
        startActivity(Intent.createChooser(i, "分享驱动信息"));
    }

    public void runRootActivation() {
        BridgeClient.RuntimeInfo ri = BridgeClient.getRuntimeInfo();
        if (ri.valid && "system_server".equals(ri.hostModeName)) {
            showTextDialog("Root 激活", "当前已由 LSPosed 系统框架模式接管，无需再启动 root 守护进程。\n\n"
                    + "如需改用 Root 模式，请先在 LSPosed 管理器中停用 Midex 模块并重启。");
            return;
        }
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Root 一键激活")
                .setMessage("正在请求 root 权限…\n首次使用时请在 KernelSU / Magisk 弹窗中允许 Midex。")
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
                    Toast.makeText(MainActivity.this, "Root 激活成功", Toast.LENGTH_SHORT).show();
                } else {
                    showTextDialog("Root 激活失败", message);
                }
            }
        });
    }

    public void collectDriverInfo(boolean allowRoot) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("读取本机驱动信息")
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
                    showDriverResult(okJson, allowRoot && RootBootstrap.hasVerifiedRoot());
                });
                return;
            }
            boolean root = allowRoot && RootBootstrap.hasVerifiedRoot();
            runOnUiThread(() -> {
                if (progress.isShowing()) progress.dismiss();
                if (!root) {
                    showTextDialog("驱动采集", allowRoot ? "请先在 KernelSU / Magisk 中授予 Midex Root 权限。"
                            : "请先通过无线调试或电脑 ADB 激活 Midex 服务。");
                    return;
                }
                final AlertDialog p2 = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Root 驱动采集")
                        .setMessage("正在用 root 运行 app_process probe…")
                        .setCancelable(false)
                        .create();
                p2.show();
                RootBootstrap.probeAsync(MainActivity.this, (json2, error) -> {
                    if (p2.isShowing()) p2.dismiss();
                    if (json2 != null) {
                        mLastDriverJson = json2;
                        showDriverResult(json2, true);
                    } else {
                        showTextDialog("驱动采集失败", error == null ? "未知错误" : error);
                    }
                });
            });
        }).start();
    }

    private void showDriverResult(String json, boolean allowRootSave) {
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
        tvSummary.setTextColor(Ui.TEXT);
        tvSummary.setTextSize(12f);
        tvSummary.setLineSpacing(3f, 1f);
        content.addView(tvSummary);

        TextView tvRaw = new TextView(this);
        tvRaw.setText(pretty);
        tvRaw.setTextColor(Ui.TEXT3);
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
        TextView copy = makeActionBtn("复制", Ui.SURFACE_ALT);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("extbridge_driver", json));
            Toast.makeText(this, "已复制驱动 JSON", Toast.LENGTH_SHORT).show();
        });
        TextView save = makeActionBtn("保存", 0xFF0369A1);
        save.setOnClickListener(v -> {
            String name = "extbridge-driver-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new java.util.Date()) + ".json";
            RootBootstrap.saveJsonAsync(this, json, name, new RootBootstrap.Listener() {
                @Override public void onStep(String message) {}
                @Override public void onResult(boolean success, String message) {
                    Toast.makeText(MainActivity.this, success ? message : message,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
        TextView share = makeActionBtn("分享", 0xFF7C3AED);
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "Midex 本机驱动信息");
            i.putExtra(Intent.EXTRA_TEXT, json);
            startActivity(Intent.createChooser(i, "分享驱动信息"));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = 8;
        row.addView(copy, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.rightMargin = 8;
        if (allowRootSave) row.addView(save, lp2);
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
                            if (!edid.optBoolean("checksumValid", true)) sb.append(" · 校验异常");
                            if (!edid.optBoolean("complete", true)) sb.append(" · 扩展数据不完整");
                            org.json.JSONArray timings = edid.optJSONArray("detailedTimings");
                            if (timings != null && timings.length() > 0) {
                                sb.append("\n   显示器声明的详细时序：");
                                for (int j = 0; j < timings.length(); j++) {
                                    org.json.JSONObject t = timings.optJSONObject(j);
                                    if (t == null) continue;
                                    sb.append("\n   ").append(t.optInt("width")).append('×').append(t.optInt("height"))
                                            .append(" @ ").append(t.optDouble("refresh")).append(" Hz")
                                            .append(t.optBoolean("interlaced") ? " (隔行)" : "")
                                            .append(" · ").append(t.optString("source", "base"));
                                }
                            }
                        }
                        sb.append('\n');
                    }
                }
            }

            org.json.JSONArray displays = o.optJSONArray("displays");
            if (displays != null && displays.length() > 0) {
                sb.append("\n系统显示模式（实际可选）\n");
                for (int i = 0; i < displays.length(); i++) {
                    org.json.JSONObject d = displays.optJSONObject(i);
                    if (d == null) continue;
                    org.json.JSONObject dmode = d.optJSONObject("mode");
                    sb.append("• #").append(d.optInt("id")).append(' ').append(d.optString("name"))
                            .append(" type=").append(d.optInt("type"));
                    if (dmode != null) sb.append("  ").append(dmode.optInt("width")).append('×')
                            .append(dmode.optInt("height")).append('@').append(dmode.optDouble("refresh"));
                    sb.append('\n');
                    org.json.JSONArray supported = d.optJSONArray("supportedModes");
                    if (supported != null) for (int j = 0; j < supported.length(); j++) {
                        org.json.JSONObject s = supported.optJSONObject(j);
                        if (s != null) sb.append("   ").append(s.optInt("width")).append('×').append(s.optInt("height"))
                                .append(" @ ").append(s.optDouble("refresh")).append(" Hz\n");
                    }
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

    public void toggleRootAutostart() {
        new Thread(() -> {
            boolean on = RootBootstrap.isAutostartEnabled();
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("模块即插即用")
                    .setMessage(on
                            ? "即插即用模块已开启。开机加载轻量插拔监听，无需保留控制台后台。拔线后释放桌面并停止前台服务和轮询；接屏后自动打开桌面。\n\n关闭仅取消下次开机自启动，当前桌面继续运行。"
                            : "安装 Root 即插即用模块，开机自动监听外屏连接，无需保留控制台后台。未接屏时不轮询、不持有唤醒锁；接屏后启动桌面。需要 KernelSU 或 Magisk 的模块支持。")
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

    private void showTextDialog(String title, String message) {
        TextView tv = new TextView(this);
        tv.setText(message == null ? "" : message);
        tv.setTextColor(Ui.TEXT);
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

    private TextView makeActionBtn(String txt, int color) {
        TextView tv = Ui.medium(this, txt, 13f, color == Ui.SURFACE_ALT ? Ui.TEXT : Color.WHITE);
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

    public void ensureNotifPermAndShowPairNotif() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3902);
        }
        AdbBootstrap.showPairingNotification(this, null);
    }

}
