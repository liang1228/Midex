package com.extbridge.refreshunlock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Supplier;

/** Caption occupies a real captionBar inset; app content is never covered by the controls. */
final class DesktopCaptionView extends LinearLayout {
    interface Commands { void run(int action, Rect bounds); }
    private final Commands commands;
    private final Supplier<Rect> bounds;
    private final Display display;
    private final TextView title;
    private final ImageView icon;
    private final Control maximize;
    private String pkg;
    private boolean maximized;
    private Rect start;
    private float downX, downY;
    private long lastMove;

    DesktopCaptionView(Context context, Commands commands, Supplier<Rect> bounds, Display display) {
        super(context);
        this.commands = commands; this.bounds = bounds; this.display = display;
        setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(10), 0, 0, 0);
        setContentDescription("ExtBridge 窗口栏");
        icon = new ImageView(context);
        addView(icon, new LayoutParams(dp(16), dp(16)));
        title = new TextView(context);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextSize(12); title.setTextColor(0xFFE9EDF4);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(8), 0, dp(8), 0); title.setGravity(Gravity.CENTER_VERTICAL);
        title.setContentDescription("拖动窗口");
        title.setOnTouchListener((v, e) -> drag(e));
        addView(title, new LayoutParams(0, -1, 1));
        addControl(1, "最小化", () -> commands.run(1, null));
        maximize = addControl(2, "最大化或还原", () -> commands.run(maximized ? 3 : 2, null));
        addControl(4, "关闭窗口", () -> commands.run(4, null));
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Control addControl(int action, String label, Runnable command) {
        Control button = new Control(getContext(), action);
        button.setContentDescription(label); button.setTooltipText(label); button.setFocusable(true);
        button.setOnClickListener(v -> command.run());
        addView(button, new LayoutParams(dp(38), -1));
        return button;
    }

    void update(String name, boolean focused, boolean max) {
        maximized = max; maximize.restore = max; maximize.invalidate();
        setBackgroundColor(focused ? 0xFF202631 : 0xFF30343B);
        if (name.equals(pkg)) return;
        pkg = name;
        try {
            android.content.pm.ApplicationInfo app = getContext().getPackageManager().getApplicationInfo(name, 0);
            title.setText(getContext().getPackageManager().getApplicationLabel(app));
            icon.setImageDrawable(getContext().getPackageManager().getApplicationIcon(app));
        } catch (Exception ignored) { title.setText(name); icon.setImageDrawable(null); }
    }

    private boolean drag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                start = bounds.get();
                downX = event.getX() + start.left; downY = event.getY() + start.top; lastMove = 0;
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (start == null) return true;
                Point size = new Point(); display.getRealSize(size);
                Rect current = bounds.get();
                Rect next = new Rect(start);
                next.offsetTo(Math.max(0, Math.min(size.x - start.width(),
                                start.left + Math.round(event.getX() + current.left - downX))),
                        Math.max(dp(34), Math.min(size.y - dp(52) - start.height(),
                                start.top + Math.round(event.getY() + current.top - downY))));
                if (event.getActionMasked() == MotionEvent.ACTION_UP || SystemClock.uptimeMillis() - lastMove >= 100) {
                    commands.run(0, next); lastMove = SystemClock.uptimeMillis();
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) start = null;
                return true;
            case MotionEvent.ACTION_CANCEL:
                start = null; return true;
            default: return true;
        }
    }

    /** Lucide minimize, square, copy and x strokes, drawn at the caption's actual density. */
    private final class Control extends View {
        final int action;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean hover;
        boolean restore;
        Control(Context context, int action) {
            super(context); this.action = action;
            setOnHoverListener((v, e) -> {
                hover = e.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT;
                invalidate(); return false;
            });
        }
        @Override public void setPressed(boolean value) { super.setPressed(value); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            if (hover || isPressed() || isFocused()) canvas.drawColor(action == 4 ? 0xFFD63C4C : 0x35FFFFFF);
            canvas.save();
            canvas.translate((getWidth() - dp(16)) / 2f, (getHeight() - dp(16)) / 2f);
            canvas.scale(dp(16) / 24f, dp(16) / 24f);
            paint.setColor(0xFFF1F4FA); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.7f);
            if (action == 1) canvas.drawLine(5, 16, 19, 16, paint);
            else if (action == 4) {
                canvas.drawLine(6, 6, 18, 18, paint); canvas.drawLine(18, 6, 6, 18, paint);
            } else if (restore) {
                canvas.drawRect(5, 9, 15, 19, paint);
                canvas.drawLine(9, 9, 9, 5, paint); canvas.drawLine(9, 5, 19, 5, paint);
                canvas.drawLine(19, 5, 19, 15, paint); canvas.drawLine(19, 15, 15, 15, paint);
            } else canvas.drawRect(6, 6, 18, 18, paint);
            canvas.restore();
        }
    }
}
