package com.extbridge.helper;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Liquid-glass 卡片背景：
 *  - 半透明垂直渐变 + 1px 高光描边
 *  - 左上角斜向反光（specular highlight）
 *  - 可选强调色辉光，模拟 iOS 26 / visionOS 的玻璃层次
 */
public final class GlassDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final int topColor;
    private final int bottomColor;
    private final int strokeColor;
    private int alpha = 255;
    private LinearGradient fillShader;
    private LinearGradient sheenShader;
    private Shader strokeShader;

    public GlassDrawable(float radiusDp, float density) {
        this(radiusDp, density, 0x1FFFFFFF, 0x0BFFFFFF, 0x26FFFFFF);
    }

    public GlassDrawable(float radiusDp, float density, int topColor, int bottomColor, int strokeColor) {
        this.radius = radiusDp * density;
        this.topColor = topColor;
        this.bottomColor = bottomColor;
        this.strokeColor = strokeColor;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, density));
        setAlpha(255);
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect bounds) {
        rect.set(bounds);
        rect.inset(stroke.getStrokeWidth() / 2f, stroke.getStrokeWidth() / 2f);
        float w = Math.max(1f, rect.width());
        float h = Math.max(1f, rect.height());
        fillShader = new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                applyAlpha(topColor), applyAlpha(bottomColor), Shader.TileMode.CLAMP);
        // 左上到右下的细窄反光
        sheenShader = new LinearGradient(rect.left, rect.top, rect.left + w * 0.9f, rect.top + h * 0.9f,
                new int[]{0x1AFFFFFF, 0x08FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 0.75f}, Shader.TileMode.CLAMP);
        strokeShader = new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                0x40FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP);
    }

    private int applyAlpha(int color) {
        int a = (int) (((color >>> 24) & 0xff) * (alpha / 255f));
        return (a << 24) | (color & 0x00ffffff);
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.isEmpty()) return;
        fill.setShader(fillShader);
        canvas.drawRoundRect(rect, radius, radius, fill);
        sheen.setShader(sheenShader);
        canvas.drawRoundRect(rect, radius, radius, sheen);
        stroke.setShader(strokeShader);
        canvas.drawRoundRect(rect, radius, radius, stroke);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        sheen.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(new android.graphics.Rect((int) rect.left, (int) rect.top,
                (int) rect.right, (int) rect.bottom), radius);
    }

    public static ViewOutlineProvider outlineProvider() {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                Drawable bg = view.getBackground();
                if (bg != null) bg.getOutline(outline);
            }
        };
    }
}
