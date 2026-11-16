package com.extbridge.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 深色液态背景：AMOLED 黑底 + 低饱和蓝/青/紫光晕。
 * 不依赖壁纸，也不会随壁纸变化而降低可读性；玻璃卡片叠在上面形成层次。
 */
public final class LiquidBackgroundView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader topGlow;
    private Shader bottomGlow;
    private Shader sideGlow;
    private Shader vignette;
    private int w;
    private int h;

    public LiquidBackgroundView(Context context) {
        super(context);
        init();
    }

    public LiquidBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        this.w = w;
        this.h = h;
        rebuild();
    }

    private void rebuild() {
        if (w <= 0 || h <= 0) return;
        topGlow = new RadialGradient(w * 0.82f, -h * 0.12f, Math.max(w, h) * 0.62f,
                new int[]{0x2E3B6BFF, 0x0D3B6BFF, 0x00000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        bottomGlow = new RadialGradient(w * 0.12f, h * 1.02f, Math.max(w, h) * 0.58f,
                new int[]{0x2218B6A4, 0x0A18B6A4, 0x00000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
        sideGlow = new RadialGradient(w * 0.48f, h * 0.45f, Math.max(w, h) * 0.45f,
                new int[]{0x12A855F7, 0x06A855F7, 0x00000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        vignette = new LinearGradient(0, 0, 0, h,
                new int[]{0x00000000, 0x22000000, 0x66000000},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF040507);
        if (w <= 0 || h <= 0) return;
        if (topGlow == null) rebuild();
        paint.setShader(topGlow);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(bottomGlow);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(sideGlow);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(vignette);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        // 极细的顶部高光，让背景和状态栏衔接更自然
        paint.setColor(0x0DFFFFFF);
        canvas.drawRect(0, 0, w, dp(1), paint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
