package com.extbridge.helper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.activity.ComponentActivity;

public class TouchpadActivity extends ComponentActivity {
    private WindowRefreshRate mRefreshRate;
    private final TouchpadUi mUi = new TouchpadUi();

    private int dp(int v) {
        return Math.max(1, Math.round(v * getResources().getDisplayMetrics().density));
    }

    private float dpF(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRefreshRate = new WindowRefreshRate(this);
        if (ensureOnDisplay0()) return;
        BridgeService.ensureStarted(this);
        mUi.show(this);
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
        mRefreshRate.start();
        BridgeClient.setOnBinderReady(() -> mHandler.post(this::refreshStatus));
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        if (mUi.getDragLocked()) toggleDragLock();
        super.onPause();
        mRefreshRate.stop();
        BridgeClient.setOnBinderReady(null);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mRefreshRate.apply();
    }

    private void refreshStatus() {
        mUi.updateStatus(BridgeClient.getStatus());
    }

    void toggleDragLock() {
        boolean lock = !mUi.getDragLocked();
        if (sendMouseSync(lock ? 3 : 4, 0f, 0f)) mUi.setDragLocked(lock);
    }

    void commitImeText(String txt, boolean useCtrlV) {
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
            mHandler.postDelayed(() -> BridgeClient.sendPaste(useCtrlV), 35);
        }
    }

    boolean sendMouseSync(int type, float a1, float a2) {
        int[] xy = BridgeClient.sendMouse(type, a1, a2);
        if (xy == null || xy.length < 2) return false;
        BridgeService.moveCursor(xy[0], xy[1], type == 3 || (mUi.getDragLocked() && type != 4));
        mUi.updateCursor(xy[0], xy[1]);
        return true;
    }

    class TouchpadView extends View {
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
            setContentDescription("手势区");
            mRingPaint.setStyle(Paint.Style.STROKE);
            mRingPaint.setStrokeWidth(dpF(1.6f));
            mRailTickPaint.setStrokeWidth(dpF(1.2f));
        }

        void setPalette(int dot, int ring, int rail, int tick) {
            mDotPaint.setColor(dot);
            mRingPaint.setColor(ring);
            mRailPaint.setColor(rail);
            mRailTickPaint.setColor(tick);
            invalidate();
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
            canvas.drawRoundRect(railLeft, dpF(10), w - dpF(4), h - dpF(10),
                    dpF(12), dpF(12), mRailPaint);
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
                    if (!mUi.getDragLocked() && (now - mLastTapUpTime) < 240) {
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
                            float portraitGain = Math.max(1.0f, Math.min(2.1f, (mUi.getDesktopWidth() / padW) * 0.65f));
                            float dpiNorm = 2.0f / Math.max(1.0f, getResources().getDisplayMetrics().density);
                            float accel = mUi.getSpeed() * dpiNorm * portraitGain
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
