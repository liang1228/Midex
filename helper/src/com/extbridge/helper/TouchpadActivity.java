package com.extbridge.helper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class TouchpadActivity extends Activity {
    private static final float[] SPEEDS = {1.0f, 1.6f, 2.3f, 3.2f};
    private int mSpeedIdx = 1;
    private boolean mUseCtrlV = false;
    private boolean mOneHandPortrait = false;

    private TextView mTvCoord;
    private TextView mTvHint;
    private TextView mBtnInputMode;
    private TextView mBtnSpeed;
    private TextView mBtnReach;
    private TextView mBtnDragLock;
    private TextView mBtnPasteMode;
    private LinearLayout mReachSpacer;
    private LinearLayout mImeDock;
    private EditText mImeInput;
    private boolean mImeVisible = false;
    private boolean mDragLocked = false;

    private int dp(int v) {
        return Math.max(1, Math.round(v * getResources().getDisplayMetrics().density));
    }

    private float dpF(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private boolean isPortrait() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mVirtW = 2560;
    private int mVirtH = 1440;
    private int mCurX = 1280;
    private int mCurY = 720;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ensureOnDisplay0()) return;
        BridgeService.ensureStarted(this);
        buildUi();
        refreshStatus();
    }

    private boolean ensureOnDisplay0() {
        try {
            if (getDisplay() != null && getDisplay().getDisplayId() != 0) {
                android.content.Intent i = new android.content.Intent(this, TouchpadActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
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
        BridgeClient.setOnBinderReady(() -> mHandler.post(this::refreshStatus));
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        BridgeClient.setOnBinderReady(null);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateOrientationLayout();
    }

    private void updateOrientationLayout() {
        boolean port = isPortrait();
        if (mBtnReach != null) {
            mBtnReach.setVisibility(port ? View.VISIBLE : View.GONE);
        }
        if (mReachSpacer != null) {
            mReachSpacer.setVisibility((port && mOneHandPortrait) ? View.VISIBLE : View.GONE);
        }
        if (mTvHint != null) {
            mTvHint.setText(port
                    ? "全区域滑动 · 右边缘单指滚轮 · 轻触左键 · 双指右键"
                    : "单指移动 | 轻触左键 | 双指点右键 | 双指或右边缘滑滚轮");
        }
    }

    private void refreshStatus() {
        BridgeClient.Status st = BridgeClient.getStatus();
        if (st.connected) {
            mVirtW = st.virtW;
            mVirtH = st.virtH;
            mCurX = st.cursorX;
            mCurY = st.cursorY;
            updateCoordLabel();
            if (mBtnInputMode != null) {
                mBtnInputMode.setText(st.inputToExt == 1 ? "⇄ 扩展屏" : "⇄ 本机");
            }
        }
    }

    private void updateCoordLabel() {
        if (mTvCoord != null) {
            mTvCoord.setText("光标 (" + mCurX + ", " + mCurY + ") · " + mVirtW + "×" + mVirtH);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0F1A);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets sys = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout()
                                | android.view.WindowInsets.Type.ime());
                v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
                return insets;
            });
        }

        // 1. Top status header (two clean compact rows so portrait phones never crush coordinates/exit)
        LinearLayout topWrap = new LinearLayout(this);
        topWrap.setOrientation(LinearLayout.VERTICAL);
        topWrap.setPadding(dp(12), dp(8), dp(12), dp(6));
        topWrap.setBackgroundColor(0xFF111827);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ExtBridge 触摸板");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        title.setSingleLine(true);
        title.getPaint().setFakeBoldText(true);
        top.addView(title);

        mTvCoord = new TextView(this);
        mTvCoord.setTextColor(0xFF38BDF8);
        mTvCoord.setTextSize(12.5f);
        mTvCoord.setSingleLine(true);
        mTvCoord.setEllipsize(TextUtils.TruncateAt.END);
        mTvCoord.setPadding(dp(10), 0, dp(8), 0);
        top.addView(mTvCoord, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnExit = makeBtn("✕ 退出", 0xFF334155);
        btnExit.setOnClickListener(v -> finish());
        top.addView(btnExit);
        topWrap.addView(top);

        mTvHint = new TextView(this);
        mTvHint.setTextColor(0xFF94A3B8);
        mTvHint.setTextSize(11.5f);
        mTvHint.setSingleLine(true);
        mTvHint.setEllipsize(TextUtils.TruncateAt.END);
        mTvHint.setPadding(0, dp(3), 0, 0);
        topWrap.addView(mTvHint);
        root.addView(topWrap);

        // Optional ergonomic upper shelf for one-handed portrait thumb reach
        mReachSpacer = new LinearLayout(this);
        mReachSpacer.setOrientation(LinearLayout.VERTICAL);
        mReachSpacer.setGravity(Gravity.CENTER);
        mReachSpacer.setPadding(dp(14), dp(10), dp(14), dp(6));
        TextView reachInfo = new TextView(this);
        reachInfo.setText("单手握持模式：触控板与左右键已下移至拇指热区\n点击下方「☝ 单手」按钮可切回全屏大触控板");
        reachInfo.setTextColor(0xFF64748B);
        reachInfo.setTextSize(12f);
        reachInfo.setGravity(Gravity.CENTER);
        mReachSpacer.addView(reachInfo);
        root.addView(mReachSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.72f));

        // 2. Main Huge Touchpad Canvas
        TouchpadView pad = new TouchpadView(this);
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        padLp.setMargins(dp(10), dp(8), dp(10), dp(6));
        root.addView(pad, padLp);

        // 3. Physical Trackpad Left/Right Buttons Bar
        LinearLayout clickBar = new LinearLayout(this);
        clickBar.setOrientation(LinearLayout.HORIZONTAL);
        clickBar.setPadding(dp(10), 0, dp(10), dp(6));

        TextView btnLeft = makeBigPadBtn("左键点击", 0xFF1E293B);
        btnLeft.setOnClickListener(v -> sendMouseSync(1, 0f, 0f));

        mBtnDragLock = makeBigPadBtn("拖拽: 关", 0xFF1E293B);
        mBtnDragLock.setOnClickListener(v -> toggleDragLock());

        TextView btnRight = makeBigPadBtn("右键 / 返回", 0xFF1E293B);
        btnRight.setOnClickListener(v -> sendMouseSync(2, 0f, 0f));

        int clickH = dp(44);
        clickBar.addView(btnLeft, new LinearLayout.LayoutParams(0, clickH, 2f));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, clickH, 1.25f);
        dLp.setMargins(dp(6), 0, dp(6), 0);
        clickBar.addView(mBtnDragLock, dLp);
        clickBar.addView(btnRight, new LinearLayout.LayoutParams(0, clickH, 2f));
        root.addView(clickBar);

        // 4. Bottom Action Strip (horizontally scrollable on narrow portrait phones)
        HorizontalScrollView barScroll = new HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setFillViewport(true);
        barScroll.setBackgroundColor(0xFF111827);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(8));

        TextView btnExtBack = makeBtn("← 返回", 0xFF1E293B);
        btnExtBack.setOnClickListener(v -> BridgeClient.sendBack());

        TextView btnExtHome = makeBtn("⊞ 桌面", 0xFF1E293B);
        btnExtHome.setOnClickListener(v -> BridgeClient.relaunchLauncher());

        mBtnInputMode = makeBtn("⇄ 扩展屏", 0xFF1E40AF);
        mBtnInputMode.setOnClickListener(v -> {
            int m = BridgeClient.toggleInput();
            mBtnInputMode.setText(m == 1 ? "⇄ 扩展屏" : "⇄ 本机");
            BridgeService.refreshFromReceiver(this);
        });

        mBtnSpeed = makeBtn("速度 " + SPEEDS[mSpeedIdx] + "x", 0xFF334155);
        mBtnSpeed.setOnClickListener(v -> {
            mSpeedIdx = (mSpeedIdx + 1) % SPEEDS.length;
            mBtnSpeed.setText("速度 " + SPEEDS[mSpeedIdx] + "x");
        });

        mBtnReach = makeBtn("☝ 全屏", 0xFF334155);
        mBtnReach.setOnClickListener(v -> {
            mOneHandPortrait = !mOneHandPortrait;
            mBtnReach.setText(mOneHandPortrait ? "☝ 单手" : "☝ 全屏");
            ((GradientDrawable) mBtnReach.getBackground()).setColor(mOneHandPortrait ? 0xFF0369A1 : 0xFF334155);
            updateOrientationLayout();
        });

        TextView btnIme = makeBtn("⌨ 键盘", 0xFF0284C7);
        btnIme.setOnClickListener(v -> toggleImeDock(!mImeVisible));

        bar.addView(btnExtBack);
        bar.addView(btnExtHome);
        bar.addView(mBtnInputMode);
        bar.addView(mBtnSpeed);
        bar.addView(mBtnReach);
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(btnIme);
        barScroll.addView(bar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(barScroll);

        // 5. Collapsible IME & Special Keys Dock
        mImeDock = new LinearLayout(this);
        mImeDock.setOrientation(LinearLayout.VERTICAL);
        mImeDock.setBackgroundColor(0xFF172033);
        mImeDock.setPadding(16, 10, 16, 14);
        mImeDock.setVisibility(View.GONE);

        HorizontalScrollView keysScroll = new HorizontalScrollView(this);
        LinearLayout keysRow = new LinearLayout(this);
        keysRow.setOrientation(LinearLayout.HORIZONTAL);
        addKeyChip(keysRow, "回车 Enter", KeyEvent.KEYCODE_ENTER, 0);
        addKeyChip(keysRow, "退格 ⌫", KeyEvent.KEYCODE_DEL, 0);
        addKeyChip(keysRow, "空格 Space", KeyEvent.KEYCODE_SPACE, 0);
        addKeyChip(keysRow, "Esc", KeyEvent.KEYCODE_ESCAPE, 0);
        addKeyChip(keysRow, "Tab", KeyEvent.KEYCODE_TAB, 0);
        addKeyChip(keysRow, "全选 Ctrl+A", KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
        addKeyChip(keysRow, "复制 Ctrl+C", KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
        addKeyChip(keysRow, "粘贴 Ctrl+V", KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
        addKeyChip(keysRow, "↑", KeyEvent.KEYCODE_DPAD_UP, 0);
        addKeyChip(keysRow, "↓", KeyEvent.KEYCODE_DPAD_DOWN, 0);
        addKeyChip(keysRow, "←", KeyEvent.KEYCODE_DPAD_LEFT, 0);
        addKeyChip(keysRow, "→", KeyEvent.KEYCODE_DPAD_RIGHT, 0);
        addKeyChip(keysRow, "主页", KeyEvent.KEYCODE_HOME, 0);
        keysScroll.addView(keysRow);
        mImeDock.addView(keysScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, 10, 0, 0);

        mImeInput = new EditText(this);
        mImeInput.setHint("在此调用平板输入法输入中文/英文，按发送直达扩展屏输入框...");
        mImeInput.setHintTextColor(0xFF64748B);
        mImeInput.setTextColor(Color.WHITE);
        mImeInput.setTextSize(15f);
        mImeInput.setSingleLine(true);
        mImeInput.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mImeInput.setPadding(18, 12, 18, 12);
        GradientDrawable edBg = new GradientDrawable();
        edBg.setColor(0xFF0F172A);
        edBg.setCornerRadius(12f);
        edBg.setStroke(1, 0xFF38BDF8);
        mImeInput.setBackground(edBg);
        mImeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                commitImeText();
                return true;
            }
            return false;
        });

        TextView btnSend = makeBtn("发送上屏", 0xFF2563EB);
        btnSend.setOnClickListener(v -> commitImeText());

        mBtnPasteMode = makeBtn("中文方式:PASTE", 0xFF334155);
        mBtnPasteMode.setOnClickListener(v -> {
            mUseCtrlV = !mUseCtrlV;
            mBtnPasteMode.setText(mUseCtrlV ? "中文方式:Ctrl+V" : "中文方式:PASTE");
        });

        TextView btnHideIme = makeBtn("收起", 0xFF475569);
        btnHideIme.setOnClickListener(v -> toggleImeDock(false));

        inputRow.addView(mImeInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        inputRow.addView(btnSend);
        inputRow.addView(mBtnPasteMode);
        inputRow.addView(btnHideIme);
        mImeDock.addView(inputRow);
        root.addView(mImeDock);

        setContentView(root);
        updateOrientationLayout();
    }

    private void addKeyChip(LinearLayout parent, String label, int keyCode, int meta) {
        TextView tv = makeBtn(label, 0xFF26334A);
        tv.setOnClickListener(v -> BridgeClient.sendKey(keyCode, meta));
        parent.addView(tv);
    }

    private void toggleDragLock() {
        mDragLocked = !mDragLocked;
        if (mDragLocked) {
            sendMouseSync(3, 0f, 0f);
            mBtnDragLock.setText("拖拽锁定: 开");
            ((GradientDrawable) mBtnDragLock.getBackground()).setColor(0xFFEA580C);
        } else {
            sendMouseSync(4, 0f, 0f);
            mBtnDragLock.setText("拖拽锁定: 关");
            ((GradientDrawable) mBtnDragLock.getBackground()).setColor(0xFF1E293B);
        }
    }

    private void toggleImeDock(boolean show) {
        mImeVisible = show;
        mImeDock.setVisibility(show ? View.VISIBLE : View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (show) {
            mImeInput.requestFocus();
            mHandler.postDelayed(() -> {
                if (imm != null) imm.showSoftInput(mImeInput, InputMethodManager.SHOW_IMPLICIT);
            }, 80);
        } else if (imm != null) {
            imm.hideSoftInputFromWindow(mImeInput.getWindowToken(), 0);
        }
    }

    private void commitImeText() {
        String txt = mImeInput.getText().toString();
        if (txt.isEmpty()) {
            BridgeClient.sendKey(KeyEvent.KEYCODE_ENTER, 0);
            return;
        }
        boolean asciiOnly = true;
        for (int i = 0; i < txt.length(); i++) {
            char c = txt.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                asciiOnly = false;
                break;
            }
        }
        if (asciiOnly) {
            BridgeClient.sendAscii(txt);
        } else {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("extbridge", txt));
            }
            mHandler.postDelayed(() -> BridgeClient.sendPaste(mUseCtrlV), 35);
        }
        mImeInput.setText("");
    }

    private void sendMouseSync(int type, float a1, float a2) {
        int[] xy = BridgeClient.sendMouse(type, a1, a2);
        if (xy != null && xy.length >= 2) {
            mCurX = xy[0];
            mCurY = xy[1];
            BridgeService.moveCursor(mCurX, mCurY, mDragLocked || type == 3);
            updateCoordLabel();
        }
    }

    private TextView makeBtn(String txt, int color) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setSingleLine(true);
        tv.setPadding(dp(9), dp(6), dp(9), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dpF(9));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView makeBigPadBtn(String txt, int color) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(13.5f);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dpF(10));
        bg.setStroke(1, 0x3394A3B8);
        tv.setBackground(bg);
        return tv;
    }

    private class TouchpadView extends View {
        private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRailTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int mMode = 0; // 0=idle, 1=1finger, 2=2finger, 3=drag1finger, 4=rightEdgeScroll
        private long mDownTime = 0L;
        private long mLastTapUpTime = 0L;
        private float mDownX, mDownY;
        private float mLastX, mLastY;
        private float mLast2Y, mLast2X;
        private float mTotalMove = 0f;
        private float mScrollAccumV = 0f;
        private float mScrollAccumH = 0f;
        private float mTouchVisX = -1f, mTouchVisY = -1f;

        TouchpadView(Context ctx) {
            super(ctx);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF0F172A);
            bg.setCornerRadius(dpF(14));
            bg.setStroke(Math.max(1, dp(1)), 0x3338BDF8);
            setBackground(bg);

            mDotPaint.setColor(0x2838BDF8);
            mRingPaint.setColor(0xAA38BDF8);
            mRingPaint.setStyle(Paint.Style.STROKE);
            mRingPaint.setStrokeWidth(dpF(1.6f));

            mRailPaint.setColor(0x1438BDF8);
            mRailTickPaint.setColor(0x4538BDF8);
            mRailTickPaint.setStrokeWidth(dpF(1.2f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int railW = dp(26);
            int step = Math.max(24, dp(30));
            for (int y = step; y < h; y += step) {
                for (int x = step; x < w - railW; x += step) {
                    canvas.drawCircle(x, y, dpF(1.2f), mDotPaint);
                }
            }
            // Subtle right-edge single-finger scroll strip
            int railLeft = Math.max(0, w - railW);
            canvas.drawRect(railLeft, dpF(10), w - dpF(2), h - dpF(10), mRailPaint);
            for (int y = dp(24); y < h - dp(24); y += dp(16)) {
                canvas.drawLine(railLeft + dpF(7), y, w - dpF(7), y, mRailTickPaint);
            }
            if (mTouchVisX >= 0 && mTouchVisY >= 0) {
                canvas.drawCircle(mTouchVisX, mTouchVisY, dpF(20), mRingPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            int cnt = ev.getPointerCount();
            long now = ev.getEventTime();
            float tapSlop = dpF(8f);
            float twoFingerTapSlop = dpF(12f);
            float scrollStepV = dpF(16f);
            float scrollStepH = dpF(20f);
            float accelCap = dpF(18f);
            float distFloor = dpF(0.2f);
            float railW = dpF(26f);

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mDownTime = now;
                    mDownX = ev.getX();
                    mDownY = ev.getY();
                    mLastX = mDownX;
                    mLastY = mDownY;
                    mTotalMove = 0f;
                    mScrollAccumV = 0f;
                    mScrollAccumH = 0f;
                    mTouchVisX = mDownX;
                    mTouchVisY = mDownY;
                    if (!mDragLocked && (now - mLastTapUpTime) < 240) {
                        mMode = 3; // double tap drag
                        sendMouseSync(3, 0f, 0f);
                    } else if (mDownX >= getWidth() - railW && getWidth() > railW * 3f) {
                        mMode = 4; // right-edge single-finger scroll strip
                    } else {
                        mMode = 1;
                    }
                    invalidate();
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (cnt == 2) {
                        if (mMode == 3) sendMouseSync(4, 0f, 0f);
                        mMode = 2;
                        mLast2X = (ev.getX(0) + ev.getX(1)) * 0.5f;
                        mLast2Y = (ev.getY(0) + ev.getY(1)) * 0.5f;
                        mScrollAccumV = 0f;
                        mScrollAccumH = 0f;
                        mTotalMove = 0f;
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    mTouchVisX = ev.getX(0);
                    mTouchVisY = ev.getY(0);
                    invalidate();
                    if (mMode == 1 || mMode == 3) {
                        float dx = ev.getX(0) - mLastX;
                        float dy = ev.getY(0) - mLastY;
                        mLastX = ev.getX(0);
                        mLastY = ev.getY(0);
                        float dist = (float) Math.hypot(dx, dy);
                        mTotalMove += dist;
                        if (dist > distFloor) {
                            // Normalize sensitivity across high-DPI portrait phones and wide tablets
                            float padW = Math.max(320f, getWidth() - railW);
                            float portraitGain = Math.max(1.0f, Math.min(2.1f, (mVirtW / padW) * 0.65f));
                            float dpiNorm = 2.0f / Math.max(1.0f, getResources().getDisplayMetrics().density);
                            float accel = SPEEDS[mSpeedIdx] * dpiNorm * portraitGain
                                    * (1.0f + Math.min(dist, accelCap) * (0.045f / Math.max(1f, getResources().getDisplayMetrics().density)));
                            sendMouseSync(0, dx * accel, dy * accel);
                        }
                    } else if (mMode == 4) {
                        float dx = ev.getX(0) - mLastX;
                        float dy = ev.getY(0) - mLastY;
                        mLastX = ev.getX(0);
                        mLastY = ev.getY(0);
                        mTotalMove += (float) Math.hypot(dx, dy);
                        mScrollAccumV += dy / scrollStepV;
                        if (Math.abs(mScrollAccumV) >= 0.25f) {
                            sendMouseSync(5, 0f, mScrollAccumV);
                            mScrollAccumV = 0f;
                        }
                    } else if (mMode == 2 && cnt >= 2) {
                        float cx = (ev.getX(0) + ev.getX(1)) * 0.5f;
                        float cy = (ev.getY(0) + ev.getY(1)) * 0.5f;
                        float dx = cx - mLast2X;
                        float dy = cy - mLast2Y;
                        mLast2X = cx;
                        mLast2Y = cy;
                        mTotalMove += (float) Math.hypot(dx, dy);
                        mScrollAccumV += dy / scrollStepV;
                        mScrollAccumH += dx / scrollStepH;
                        if (Math.abs(mScrollAccumV) >= 0.25f || Math.abs(mScrollAccumH) >= 0.25f) {
                            sendMouseSync(5, -mScrollAccumH, mScrollAccumV);
                            mScrollAccumV = 0f;
                            mScrollAccumH = 0f;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    if (mMode == 2 && (now - mDownTime) < 260 && mTotalMove < twoFingerTapSlop) {
                        sendMouseSync(2, 0f, 0f); // two-finger tap -> right click
                        mMode = 0;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    mTouchVisX = -1f;
                    mTouchVisY = -1f;
                    invalidate();
                    if (mMode == 3) {
                        sendMouseSync(4, 0f, 0f);
                    } else if ((mMode == 1 || mMode == 4) && (now - mDownTime) < 220 && mTotalMove < tapSlop) {
                        sendMouseSync(1, 0f, 0f);
                        mLastTapUpTime = now;
                    }
                    mMode = 0;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    mTouchVisX = -1f;
                    mTouchVisY = -1f;
                    if (mMode == 3) sendMouseSync(4, 0f, 0f);
                    mMode = 0;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(ev);
        }
    }
}
