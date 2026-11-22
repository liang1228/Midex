package com.extbridge.helper;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Controls the real focused task on the external display, without embedding its content. */
final class DesktopWindowChrome {
    interface Actions { void perform(int taskId, int action, Rect bounds); }
    private final Context context;
    private final WindowManager manager;
    private final Actions actions;
    private final LinearLayout caption;
    private final TextView title;
    private final TextView resize;
    private final WindowManager.LayoutParams captionLp;
    private final WindowManager.LayoutParams resizeLp;
    private final Rect bounds = new Rect();
    private final int screenWidth;
    private final int screenHeight;
    private int taskId = -1;
    private boolean dragging;
    private float downX, downY;
    private Rect start;
    private long lastMove;

    DesktopWindowChrome(Context context, WindowManager manager, int type, int width, int height, Actions actions) {
        this.context = context; this.manager = manager; this.actions = actions;
        screenWidth = width; screenHeight = height;
        caption = new LinearLayout(context);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setPadding(dp(10), 0, dp(2), 0);
        caption.setBackground(DesktopStyle.surface(context, 0xFF202D44, 6));
        title = new TextView(context);
        title.setTextColor(DesktopStyle.TEXT); title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setContentDescription("拖动窗口");
        title.setOnTouchListener((v, event) -> drag(event, false));
        caption.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        addButton("minimize", "最小化", () -> command(1));
        addButton("maximize", "最大化或还原", () -> command(bounds.width() >= screenWidth - dp(8) ? 3 : 2));
        addButton("close", "关闭窗口", () -> command(4));
        resize = DesktopStyle.button(context, "resize", "拖动调整窗口大小");
        resize.setBackground(DesktopStyle.surface(context, 0xFF202D44, 5));
        resize.setOnTouchListener((v, event) -> drag(event, true));
        captionLp = params(type, dp(400), dp(32));
        resizeLp = params(type, dp(24), dp(24));
        caption.setVisibility(View.GONE); resize.setVisibility(View.GONE);
        manager.addView(caption, captionLp); manager.addView(resize, resizeLp);
    }

    private int dp(float value) { return DesktopStyle.dp(context, value); }

    private WindowManager.LayoutParams params(int type, int width, int height) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        return lp;
    }

    private void addButton(String glyph, String label, Runnable action) {
        TextView button = DesktopStyle.button(context, glyph, label);
        button.setOnClickListener(v -> action.run());
        caption.addView(button, new LinearLayout.LayoutParams(dp(34), dp(30)));
    }

    void update(BridgeClient.DesktopWindow window) {
        if (dragging) return;
        boolean show = window != null && window.bounds != null && !window.bounds.isEmpty();
        boolean nativeCaption = false;
        try {
            int boot = android.provider.Settings.Global.getInt(context.getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, -1);
            String status = android.provider.Settings.Global.getString(context.getContentResolver(),
                    com.extbridge.refreshunlock.WindowDecorationContract.STATUS);
            String tasks = android.provider.Settings.Global.getString(context.getContentResolver(),
                    com.extbridge.refreshunlock.WindowDecorationContract.TASKS);
            nativeCaption = show && com.extbridge.refreshunlock.WindowDecorationContract.caption(status, boot)
                    && com.extbridge.refreshunlock.WindowDecorationContract.hasCaption(tasks, boot, window.taskId);
        } catch (Exception ignored) {}
        caption.setVisibility(show && !nativeCaption ? View.VISIBLE : View.GONE);
        resize.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) { taskId = -1; return; }
        taskId = window.taskId; bounds.set(window.bounds);
        try { title.setText(context.getPackageManager().getApplicationLabel(
                context.getPackageManager().getApplicationInfo(window.pkg, 0))); }
        catch (Exception ignored) { title.setText(window.pkg); }
        position();
    }

    private void position() {
        captionLp.x = Math.max(0, bounds.left);
        captionLp.y = Math.max(0, bounds.top - dp(32));
        captionLp.width = Math.max(dp(150), bounds.width());
        resizeLp.x = Math.min(screenWidth - dp(24), bounds.right - dp(24));
        resizeLp.y = Math.min(screenHeight - dp(52) - dp(24), bounds.bottom - dp(24));
        manager.updateViewLayout(caption, captionLp); manager.updateViewLayout(resize, resizeLp);
    }

    private void command(int action) {
        if (taskId > 0) actions.perform(taskId, action, null);
    }

    private boolean drag(MotionEvent event, boolean sizing) {
        if (taskId <= 0) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragging = true; downX = event.getRawX(); downY = event.getRawY();
            start = new Rect(bounds); lastMove = 0;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && dragging) {
            int x = Math.round(event.getRawX() - downX), y = Math.round(event.getRawY() - downY);
            bounds.set(start);
            if (sizing) {
                bounds.right = Math.min(screenWidth, Math.max(bounds.left + dp(240), start.right + x));
                bounds.bottom = Math.min(screenHeight - dp(52), Math.max(bounds.top + dp(160), start.bottom + y));
            } else {
                int left = Math.max(0, Math.min(screenWidth - start.width(), start.left + x));
                int top = Math.max(dp(34), Math.min(screenHeight - dp(52) - start.height(), start.top + y));
                bounds.offsetTo(left, top);
            }
            position();
            if (SystemClock.uptimeMillis() - lastMove >= 100) {
                lastMove = SystemClock.uptimeMillis();
                actions.perform(taskId, 0, new Rect(bounds));
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (dragging) actions.perform(taskId, 0, new Rect(bounds));
            dragging = false;
        }
        return true;
    }

    void destroy() {
        try { manager.removeViewImmediate(caption); } catch (Exception ignored) {}
        try { manager.removeViewImmediate(resize); } catch (Exception ignored) {}
    }
}
