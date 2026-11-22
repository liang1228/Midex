package com.extbridge.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Shared surfaces and vector controls for the external desktop. */
final class DesktopStyle {
    static final int TEXT = 0xFFF1F4FA;
    static final int MUTED = 0xFFB8C3D7;

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable surface(Context context, int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        drawable.setStroke(dp(context, 0.5f), 0x25FFFFFF);
        return drawable;
    }

    static Drawable feedback(Context context, float radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, surface(context, 0x35FFFFFF, radius));
        states.addState(new int[]{android.R.attr.state_focused}, surface(context, 0x35FFFFFF, radius));
        states.addState(new int[]{android.R.attr.state_hovered}, surface(context, 0x20FFFFFF, radius));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    static TextView button(Context context, String icon, String label) {
        TextView view = new TextView(context);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(label);
        view.setTooltipText(label);
        view.setFocusable(true);
        view.setBackground(feedback(context, 8));
        Glyph glyph = new Glyph(icon, TEXT);
        int size = dp(context, 20);
        glyph.setBounds(0, 0, size, size);
        view.setCompoundDrawables(null, glyph, null, null);
        view.setPadding(0, dp(context, 10), 0, 0);
        return view;
    }

    /** Small Lucide-style strokes, never Unicode stand-ins or emoji. */
    static final class Glyph extends Drawable {
        private final String kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(String kind, int color) {
            this.kind = kind;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            Path path = new Path();
            switch (kind) {
                case "minimize":
                    canvas.drawLine(6, 16, 18, 16, paint); break;
                case "maximize":
                    canvas.drawRect(6, 6, 18, 18, paint); break;
                case "resize":
                    canvas.drawLine(9, 18, 18, 9, paint);
                    canvas.drawLine(14, 18, 18, 14, paint); break;
                case "apps":
                    for (int x = 5; x <= 17; x += 6) for (int y = 5; y <= 17; y += 6) {
                        canvas.drawRoundRect(x - 1, y - 1, x + 1, y + 1, .6f, .6f, paint);
                    }
                    break;
                case "back":
                    path.moveTo(15, 5); path.lineTo(8, 12); path.lineTo(15, 19);
                    canvas.drawPath(path, paint); break;
                case "home":
                    canvas.drawRoundRect(5, 5, 19, 19, 4, 4, paint); break;
                case "recent":
                    canvas.drawLine(6, 6, 6, 18, paint);
                    canvas.drawLine(12, 6, 12, 18, paint);
                    canvas.drawLine(18, 6, 18, 18, paint); break;
                case "search":
                    canvas.drawCircle(10, 10, 6, paint);
                    canvas.drawLine(15, 15, 21, 21, paint); break;
                case "close":
                    canvas.drawLine(6, 6, 18, 18, paint);
                    canvas.drawLine(18, 6, 6, 18, paint); break;
                case "keyboard":
                    canvas.drawRoundRect(3, 6, 21, 18, 2, 2, paint);
                    for (int x = 7; x <= 17; x += 5) canvas.drawPoint(x, 10, paint);
                    canvas.drawLine(8, 14, 16, 14, paint); break;
                case "wifi":
                    canvas.drawArc(new RectF(1, 3, 23, 25), 225, 90, false, paint);
                    canvas.drawArc(new RectF(5, 7, 19, 21), 225, 90, false, paint);
                    canvas.drawArc(new RectF(9, 11, 15, 17), 225, 90, false, paint);
                    canvas.drawPoint(12, 19, paint); break;
                case "volume":
                    path.moveTo(4, 9); path.lineTo(8, 9); path.lineTo(13, 5);
                    path.lineTo(13, 19); path.lineTo(8, 15); path.lineTo(4, 15); path.close();
                    canvas.drawPath(path, paint);
                    canvas.drawArc(new RectF(12, 7, 20, 17), -70, 140, false, paint);
                    canvas.drawArc(new RectF(10, 3, 24, 21), -60, 120, false, paint); break;
                case "settings":
                    canvas.drawCircle(12, 12, 3, paint);
                    canvas.drawCircle(12, 12, 8, paint);
                    for (int i = 0; i < 8; i++) {
                        canvas.save(); canvas.rotate(i * 45, 12, 12);
                        canvas.drawLine(12, 2, 12, 5, paint); canvas.restore();
                    }
                    break;
                default:
                    canvas.drawRoundRect(3, 4, 21, 17, 2, 2, paint);
                    canvas.drawLine(9, 21, 15, 21, paint);
                    canvas.drawLine(12, 17, 12, 21, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class WifiSignal extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        WifiSignal() {
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(1.8f);
        }

        @Override protected boolean onLevelChange(int level) {
            invalidateSelf();
            return true;
        }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setStyle(Paint.Style.STROKE);
            int level = getLevel();
            for (int ring = 1; ring <= 3; ring++) {
                float r = 3 + ring * 3.5f;
                paint.setColor(level > ring ? TEXT : 0x48DFE9FB);
                canvas.drawArc(12 - r, 19 - r, 12 + r, 19 + r, 230, 80, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(level > 0 ? TEXT : 0x48DFE9FB);
            canvas.drawCircle(12, 19, 1.35f, paint);
            if (level == 0) {
                paint.setColor(MUTED);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(5, 5, 19, 19, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Original scalable folded-glass wallpaper; no Samsung image assets. */
    static final class Wallpaper extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 1600f, getBounds().height() / 900f);
            paint.setColor(Color.WHITE);
            paint.setShader(new LinearGradient(0, 0, 1500, 900,
                    new int[]{0xFF101D34, 0xFF163B60, 0xFF0C182C}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, 1600, 900, paint);
            paint.setShader(new RadialGradient(1160, 430, 900,
                    new int[]{0xFF426E95, 0x00173151}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, 1600, 900, paint);
            for (int i = 0; i < 6; i++) {
                float offset = i * 95;
                Path fold = new Path();
                fold.moveTo(480 + offset, -150);
                fold.cubicTo(410 + offset, 260, 1220 + offset, 210, 1110 + offset, 550);
                fold.cubicTo(1020 + offset, 820, 640 + offset, 740, 720 + offset, 1100);
                fold.lineTo(1700, 1100); fold.lineTo(1700, -150); fold.close();
                paint.setColor(Color.WHITE);
                paint.setShader(new LinearGradient(500 + offset, 150, 1160 + offset, 760,
                        new int[]{0xFF6C9EBC - i * 0x00070806, 0xFF315579, 0xFF142D49},
                        new float[]{0, .48f, 1}, Shader.TileMode.CLAMP));
                canvas.drawPath(fold, paint);
                paint.setShader(null); paint.setColor(0x125AC8EF);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1);
                canvas.drawPath(fold, paint); paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(Color.WHITE);
            paint.setShader(new LinearGradient(0, 500, 0, 900,
                    new int[]{0x00101A2C, 0x99101A2C}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, 1600, 900, paint);
            paint.setShader(null);
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
