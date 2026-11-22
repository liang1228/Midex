package com.extbridge.helper;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class ConnectionPanel extends LinearLayout {
    private static final int INK = 0xFFF3F6FC;
    private static final int MUTED = 0xFFC4D1E4;
    private static final int ACCENT = 0xFFBBD9FF;

    interface PairingAnswer {
        void submit(String address, boolean accept, String pin);
    }

    private final TextView title;
    private final Switch radio;
    private final ScrollView scroll;
    private final LinearLayout rows;
    private final TextView settings;
    private final TextView refresh;
    private final Runnable openSettings;
    private final BiConsumer<Bundle, String> changeConnection;
    private final Consumer<Boolean> textInput;
    private final TextView message;
    private final LinearLayout pairingForm;
    private final PairingAnswer pairingAnswer;
    private boolean wifi;
    private boolean updatingSwitch;
    private boolean available;
    private boolean radioEnabled;
    private List<Bundle> entries;
    private String selected;
    private EditText password;
    private boolean discovering;
    private String pending;
    private boolean pendingConnect;
    private boolean pendingPairing;
    private String pendingName;
    private long pendingUntil;

    ConnectionPanel(Context context, int listHeight, Runnable back, Consumer<Boolean> toggle,
                    Runnable openSettings, Runnable refreshList, BiConsumer<Bundle, String> changeConnection,
                    Consumer<Boolean> textInput, PairingAnswer pairingAnswer) {
        super(context);
        this.openSettings = openSettings;
        this.changeConnection = changeConnection;
        this.textInput = textInput;
        this.pairingAnswer = pairingAnswer;
        setOrientation(VERTICAL);

        LinearLayout header = horizontal();
        header.setPadding(dp(8), 0, dp(16), 0);
        ImageView backButton = new ImageView(context);
        backButton.setImageResource(R.drawable.ic_chevron);
        backButton.setColorFilter(INK);
        backButton.setScaleType(ImageView.ScaleType.CENTER);
        backButton.setRotation(180f);
        backButton.setContentDescription("返回快捷设置");
        styleAction(backButton);
        backButton.setOnClickListener(view -> back.run());
        header.addView(backButton, new LayoutParams(dp(40), dp(46)));
        title = text("", 15, INK);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        radio = new Switch(context);
        radio.setMinHeight(dp(44));
        radio.setTrackDrawable(switchShape(36, 18));
        radio.setThumbDrawable(switchShape(16, 16));
        radio.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
                new int[]{ACCENT, 0xFF6D7990}));
        radio.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
                new int[]{0xFF162B49, INK}));
        radio.setOnCheckedChangeListener((button, enabled) -> {
            if (!updatingSwitch) {
                radio.setEnabled(false);
                toggle.accept(enabled);
            }
        });
        header.addView(radio);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, dp(52)));
        addDivider();

        message = text("", 13, ACCENT);
        message.setPadding(dp(16), dp(6), dp(16), dp(6));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setVisibility(View.GONE);
        addView(message);

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        rows.setPadding(dp(8), dp(6), dp(8), dp(6));
        scroll.addView(rows);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, listHeight));
        pairingForm = new LinearLayout(context);
        pairingForm.setOrientation(VERTICAL);
        pairingForm.setPadding(dp(16), dp(12), dp(16), dp(10));
        pairingForm.setVisibility(View.GONE);
        addView(pairingForm, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addDivider();

        LinearLayout footer = horizontal();
        footer.setPadding(dp(8), 0, dp(8), 0);
        footer.setBackgroundColor(0x24070E1B);
        settings = text("", 13, INK);
        settings.setGravity(Gravity.CENTER_VERTICAL);
        settings.setPadding(dp(8), 0, dp(8), 0);
        styleAction(settings);
        settings.setOnClickListener(view -> openSettings.run());
        footer.addView(settings, new LayoutParams(0, dp(44), 1f));
        refresh = text("刷新", 13, ACCENT);
        refresh.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        refresh.setPadding(0, 0, dp(8), 0);
        refresh.setContentDescription("刷新连接列表");
        styleAction(refresh);
        refresh.setOnClickListener(view -> refreshList.run());
        footer.addView(refresh, new LayoutParams(dp(52), dp(44)));
        addView(footer, new LayoutParams(LayoutParams.MATCH_PARENT, dp(46)));
    }

    boolean isWifi() {
        return wifi;
    }

    void open(boolean wifi, boolean available, boolean enabled) {
        close();
        this.wifi = wifi;
        title.setText(wifi ? "WLAN" : "蓝牙");
        radio.setContentDescription(wifi ? "Wi-Fi 开关" : "蓝牙开关");
        settings.setText(wifi ? "更多 Wi-Fi 设置" : "更多蓝牙设置");
        selected = null;
        entries = null;
        updateSwitch(available, enabled);
        scroll.scrollTo(0, 0);
        render(enabled ? "正在读取…" : "");
    }

    boolean updateSwitch(boolean available, boolean enabled) {
        boolean changed = available != this.available || enabled != radioEnabled;
        this.available = available;
        radioEnabled = enabled;
        updatingSwitch = true;
        radio.setChecked(enabled);
        radio.setEnabled(available);
        refresh.setEnabled(available && enabled);
        refresh.setAlpha(available && enabled ? 1f : 0.4f);
        updatingSwitch = false;
        if (changed && !enabled) close();
        return changed;
    }

    void showRows(List<Bundle> result) {
        entries = result;
        if (pending != null && result != null) {
            for (Bundle entry : result) {
                if (!pending.equals(entry.getString(wifi ? "name" : "id"))) continue;
                boolean reached = entry.getBoolean("connected") == pendingConnect;
                if (pendingPairing) reached = entry.getBoolean("bonded", true) && !entry.getBoolean("nearby");
                if (reached) {
                    pending = null;
                    clearPairingForm();
                    showMessage(pendingPairing ? "已配对" : pendingConnect ? "已连接" : "已断开");
                    break;
                }
            }
        }
        if (pending != null && SystemClock.elapsedRealtime() >= pendingUntil) {
            if (isPairing()) pairingAnswer.submit(pending, false, null);
            failAction(wifi ? "连接状态未更新，请检查密码或信号后重试"
                    : pendingPairing ? "未配对，请确认设备仍处于配对模式" : "设备连接状态未更新");
        }
        if (password == null) render(result == null ? "暂时无法读取连接列表" : "");
    }

    void setDiscovering(boolean scanning) {
        if (discovering == scanning) return;
        discovering = scanning;
        render("");
    }

    void close() {
        if (isPairing()) pairingAnswer.submit(pending, false, null);
        clearPairingForm();
        clearPassword();
        selected = null;
        pending = null;
        discovering = false;
        showMessage("");
    }

    boolean isWaiting() {
        return pending != null;
    }

    boolean isPairing() {
        return !wifi && pendingPairing && pending != null;
    }

    String pairingAddress() {
        return isPairing() ? pending : null;
    }

    boolean showPairingPrompt(String address, int variant, int key) {
        if (!address.equals(pairingAddress())) return false;
        if (pairingForm.getVisibility() == View.VISIBLE) return true;
        boolean numeric = variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION
                && key >= 0 && key <= 999999;
        boolean needsPin = variant == BluetoothDevice.PAIRING_VARIANT_PIN;
        if (!numeric && !needsPin && variant != BluetoothDiscovery.CONSENT) {
            pairingAnswer.submit(address, false, null);
            failAction("此设备的配对方式暂不支持");
            return true;
        }
        showMessage("");
        pairingForm.addView(text("与「" + pendingName + "」配对", 15, INK));
        if (numeric) {
            pairingForm.addView(text("确认两台设备显示的配对码一致", 13, MUTED));
            pairingForm.addView(text(String.format(Locale.ROOT, "%06d", key), 26, ACCENT));
        }
        EditText editor = needsPin ? addPassword(pairingForm, "输入蓝牙 PIN") : null;
        LinearLayout actions = horizontal();
        actions.setGravity(Gravity.END);
        addPairingAction(actions, "取消", () -> {
            pairingAnswer.submit(address, false, null);
            failAction("已取消配对");
        });
        addPairingAction(actions, numeric ? "确认匹配" : "配对", () -> {
            String pin = editor == null ? null : editor.getText().toString();
            if (pin != null && (pin.isEmpty() || pin.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16)) {
                editor.setError("请输入 1–16 字节的 PIN");
                return;
            }
            clearPairingForm();
            showMessage("正在完成配对…");
            pairingAnswer.submit(address, true, pin);
        });
        pairingForm.addView(actions);
        scroll.setVisibility(View.GONE);
        pairingForm.setVisibility(View.VISIBLE);
        return true;
    }

    private void addPairingAction(LinearLayout actions, String label, Runnable onClick) {
        TextView button = text(label, 14, ACCENT);
        button.setGravity(Gravity.CENTER);
        styleAction(button);
        button.setOnClickListener(view -> onClick.run());
        actions.addView(button, new LayoutParams(dp(100), dp(44)));
    }

    private void clearPairingForm() {
        if (pairingForm.getVisibility() != View.VISIBLE) return;
        clearPassword();
        pairingForm.removeAllViews();
        pairingForm.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
    }

    void onWifiNetworkChanged() {
        if (!isWaiting()) showMessage("");
    }

    void onPairingFailed(String address) {
        if (isPairing() && pending.equals(address)) {
            failAction("未配对，请确认设备仍处于配对模式");
        }
    }

    void failAction(String reason) {
        pending = null;
        clearPairingForm();
        showMessage(reason);
    }

    private void showMessage(String value) {
        message.setText(value);
        message.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void render(String message) {
        int scrollY = scroll.getScrollY();
        rows.removeAllViews();
        if (!available) {
            section("连接状态暂不可用");
        } else if (!radioEnabled) {
            section(wifi ? "Wi-Fi 已关闭" : "蓝牙已关闭");
        } else {
            if (!wifi) section("你的设备");
            if (entries == null) {
                if (!message.isEmpty()) section(message);
            } else if (wifi && entries.isEmpty()) {
                section("未发现网络，点击刷新重试");
            } else if (!wifi && entries.stream().noneMatch(entry -> !entry.getBoolean("nearby"))) {
                section("暂无已配对设备");
            }
            if (entries != null) {
                for (Bundle entry : entries) {
                    if (!entry.getBoolean("nearby")) addEntry(entry);
                }
            }
            if (!wifi) {
                section(discovering ? "新设备 · 正在搜索…" : "新设备");
                boolean found = false;
                if (entries != null) for (Bundle entry : entries) {
                    if (entry.getBoolean("nearby")) {
                        addEntry(entry);
                        found = true;
                    }
                }
                if (!found && !discovering) section("打开设备配对模式，再点击刷新");
            }
        }
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void addEntry(Bundle entry) {
        String name = entry.getString("name", "");
        String id = entry.getString("id", "");
        String key = id.isEmpty() ? name : id;
        boolean connected = entry.getBoolean("connected");
        boolean expanded = key.equals(selected);
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(VERTICAL);
        if (connected || expanded) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(0x1C9DBDFF);
            background.setCornerRadius(dp(8));
            group.setBackground(background);
        }
        LinearLayout row = horizontal();
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        row.setMinimumHeight(dp(48));
        BridgeService.MiniIconView icon = new BridgeService.MiniIconView(getContext(),
                wifi ? BridgeService.MiniIconView.KIND_WIFI : BridgeService.MiniIconView.KIND_BT, INK);
        LayoutParams iconParams = new LayoutParams(dp(18), dp(18));
        iconParams.rightMargin = dp(12);
        row.addView(icon, iconParams);
        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(VERTICAL);
        TextView primary = text(name, 14, INK);
        primary.setSingleLine();
        primary.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(primary);
        boolean nearby = entry.getBoolean("nearby");
        String status = wifi ? (connected ? "已连接" : "") : nearby ? "" : (connected ? "已连接" : "未连接");
        if (wifi && entry.getBoolean("secured")) status = connected ? "已连接 · 安全" : "安全";
        if (!status.isEmpty()) labels.addView(text(status, 12, MUTED));
        row.addView(labels, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        int battery = entry.getInt("battery", -1);
        if (!wifi && connected && battery >= 0 && battery <= 100) {
            row.addView(text(battery + "%", 12, MUTED));
        }
        styleAction(row);
        row.setOnClickListener(view -> {
            clearPassword();
            selected = expanded ? null : key;
            render("");
            if (!expanded) group.post(() -> group.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, group.getWidth(), group.getHeight())));
        });
        group.addView(row);
        if (expanded) {
            String security = entry.getString("security", "unsupported");
            boolean newWifi = wifi && !connected && id.isEmpty();
            boolean needsPassword = newWifi && ("sae".equals(security) || "psk".equals(security));
            if (newWifi && "unsupported".equals(security)) {
                TextView unsupported = text("该网络需要专用认证", 13, MUTED);
                unsupported.setPadding(dp(12), dp(6), dp(12), dp(12));
                group.addView(unsupported);
                rows.addView(group, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                return;
            }
            EditText editor = needsPassword ? addPassword(group, "输入 Wi-Fi 密码") : null;
            String actionLabel = nearby ? "配对" : connected ? "断开连接" : "连接";
            TextView action = text(actionLabel, 13, ACCENT);
            action.setGravity(Gravity.CENTER);
            styleAction(action);
            action.setOnClickListener(view -> {
                submit(entry, editor);
            });
            if (editor != null) editor.setOnEditorActionListener((view, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_GO) return false;
                submit(entry, editor);
                return true;
            });
            LayoutParams actionParams = new LayoutParams(dp(146), dp(40));
            actionParams.gravity = Gravity.RIGHT;
            actionParams.rightMargin = dp(8);
            actionParams.bottomMargin = dp(4);
            group.addView(action, actionParams);
        }
        rows.addView(group, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private EditText addPassword(LinearLayout group, String hint) {
        EditText editor = new EditText(getContext());
        editor.setSingleLine(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editor.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        editor.setSaveEnabled(false);
        editor.setPadding(dp(12), 0, dp(12), 0);
        editor.setTextSize(14);
        editor.setTextColor(INK);
        editor.setHintTextColor(MUTED);
        editor.setHint(hint);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF192638);
        background.setCornerRadius(dp(6));
        editor.setBackground(background);
        LayoutParams layout = new LayoutParams(LayoutParams.MATCH_PARENT, dp(42));
        layout.setMargins(dp(8), 0, dp(8), 0);
        group.addView(editor, layout);
        editor.setOnTouchListener((view, event) -> {
            textInput.accept(true);
            return false;
        });
        password = editor;
        return editor;
    }

    private void clearPassword() {
        if (password == null) return;
        EditText editor = password;
        password = null;
        InputMethodManager input = getContext().getSystemService(InputMethodManager.class);
        if (input != null && editor.getWindowToken() != null && editor.hasFocus()) {
            input.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
        editor.clearFocus();
        editor.getText().clear();
        textInput.accept(false);
    }

    private void submit(Bundle entry, EditText editor) {
        if (isPairing()) return;
        String secret = editor == null ? "" : editor.getText().toString();
        if (editor != null && secret.isEmpty()) {
            editor.setError("请输入密码");
            return;
        }
        boolean nearby = entry.getBoolean("nearby");
        pendingPairing = nearby;
        pendingConnect = !entry.getBoolean("connected");
        pending = entry.getString(wifi ? "name" : "id");
        pendingName = entry.getString("name");
        pendingUntil = SystemClock.elapsedRealtime() + (nearby ? 45000L : 20000L);
        clearPassword();
        selected = null;
        showMessage(nearby ? "正在发起配对…"
                : pendingConnect ? "正在连接…" : "正在断开…");
        render("");
        changeConnection.accept(entry, secret);
    }

    private void section(String label) {
        TextView text = text(label, 13, MUTED);
        text.setPadding(dp(10), dp(10), dp(10), dp(5));
        rows.addView(text);
    }

    private void addDivider() {
        View divider = new View(getContext());
        divider.setBackgroundColor(0x24FFFFFF);
        addView(divider, new LayoutParams(LayoutParams.MATCH_PARENT, 1));
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, float size, int color) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private void styleAction(View view) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(8));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24BBD9FF), null, mask));
        view.setFocusable(true);
    }

    private GradientDrawable switchShape(int width, int height) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setSize(dp(width), dp(height));
        shape.setCornerRadius(dp(height) / 2f);
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
