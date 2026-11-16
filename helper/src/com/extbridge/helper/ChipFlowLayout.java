package com.extbridge.helper;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 按实际可用宽度自动换行的 chip 容器。
 * 旧的 addChipWrap 用屏幕宽度估算，放在双列卡片里会溢出/裁切。
 */
public final class ChipFlowLayout extends ViewGroup {
    private final int hGap;
    private final int vGap;

    public ChipFlowLayout(Context context, int hGap, int vGap) {
        super(context);
        this.hGap = hGap;
        this.vGap = vGap;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int available = Math.max(1, widthSize - getPaddingLeft() - getPaddingRight());
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        int maxWidth = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (x > 0 && x + hGap + cw > available) {
                x = 0;
                y += rowHeight + vGap;
                rowHeight = 0;
            }
            if (x > 0) x += hGap;
            x += cw;
            rowHeight = Math.max(rowHeight, ch);
            maxWidth = Math.max(maxWidth, x);
        }

        int height = y + rowHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(widthSize, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int available = Math.max(1, r - l - getPaddingLeft() - getPaddingRight());
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int rowHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (x > getPaddingLeft() && x + cw > getPaddingLeft() + available) {
                x = getPaddingLeft();
                y += rowHeight + vGap;
                rowHeight = 0;
            }
            child.layout(x, y, x + cw, y + ch);
            x += cw + hGap;
            rowHeight = Math.max(rowHeight, ch);
        }
    }
}
