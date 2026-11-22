package com.extbridge.helper;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Native components following the Miuix visual hierarchy.
 */
public final class Ui {
    public static boolean dark;
    public static int BG, SURFACE, SURFACE_ALT, TEXT, TEXT2, TEXT3, OUTLINE;
    public static int ACCENT, ACCENT_STRONG, GREEN, AMBER, RED, PURPLE, TEAL;

    private Ui() {}

    public static void configure(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        BG = dark ? 0xFF101010 : 0xFFF7F7F7;
        SURFACE = dark ? 0xFF1F1F1F : 0xFFFFFFFF;
        SURFACE_ALT = dark ? 0xFF2B2B2B : 0xFFF0F0F0;
        TEXT = dark ? 0xFFF4F4F4 : 0xFF171717;
        TEXT2 = dark ? 0xFFB2B2B2 : 0xFF575757;
        TEXT3 = dark ? 0xFF9B9B9B : 0xFF6B6B6B;
        OUTLINE = dark ? 0x20FFFFFF : 0x14000000;
        ACCENT = dark ? 0xFF8AB4FF : 0xFF2864D7;
        ACCENT_STRONG = 0xFF3482FF;
        GREEN = dark ? 0xFF83DB9C : 0xFF226A37;
        AMBER = dark ? 0xFFECC071 : 0xFF805400;
        RED = dark ? 0xFFFFA6A6 : 0xFFB32632;
        PURPLE = dark ? 0xFFD1B4EE : 0xFF714199;
        TEAL = dark ? 0xFF87D6C1 : 0xFF216C5B;
    }

    public static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static int dpi(Context c, float v) {
        return Math.round(dp(c, v));
    }

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(c, 2.5f), 1f);
        return tv;
    }

    public static TextView title(Context c, CharSequence s, float sp) {
        TextView tv = text(c, s, sp, TEXT);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        tv.setLetterSpacing(-0.01f);
        return tv;
    }

    public static TextView medium(Context c, CharSequence s, float sp, int color) {
        TextView tv = text(c, s, sp, color);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    public static TextView section(Context c, String s) {
        TextView tv = medium(c, s, 13f, TEXT3);
        tv.setPadding(dpi(c, 16), dpi(c, 12), dpi(c, 16), dpi(c, 8));
        return tv;
    }

    public static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(color);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int s = dpi(c, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return iv;
    }

    /** Grouped surface; glass is reserved for the navigation bar. */
    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dpi(c, 20);
        card.setPadding(p, dpi(c, 16), p, dpi(c, 16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(dp(c, 20));
        card.setBackground(bg);
        card.setElevation(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpi(c, 12);
        card.setLayoutParams(lp);
        return card;
    }

    /** 卡片内的一行：图标 + 标题/副标题 + 尾部控件。 */
    public static LinearLayout settingRow(Context c, int iconRes, String title, String subtitle, View trailing) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int px = dpi(c, 4), py = dpi(c, 10);
        row.setPadding(px, py, px, py);
        row.setMinimumHeight(dpi(c, 58));
        row.setClickable(true);
        row.setFocusable(true);
        setRipple(row, 16);

        if (iconRes != 0) {
            ImageView iv = icon(c, iconRes, TEXT2, 21);
            LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) iv.getLayoutParams();
            ilp.rightMargin = dpi(c, 12);
            row.addView(iv, ilp);
        }

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = medium(c, title, 15.5f, TEXT);
        texts.addView(tv);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(c, subtitle, 12f, TEXT3);
            sub.setPadding(0, dpi(c, 3), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) {
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dpi(c, 10);
            row.addView(trailing, tlp);
        }
        return row;
    }

    public static TextView value(Context c, String s, int color) {
        TextView tv = medium(c, s, 13.5f, color);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        return tv;
    }

    public static ImageView chevron(Context c, int color) {
        return icon(c, R.drawable.ic_chevron, color, 18);
    }

    public static void divider(Context c, LinearLayout parent) {
        View v = new View(c);
        v.setBackgroundColor(OUTLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dpi(c, 0.8f)));
        lp.leftMargin = dpi(c, 40);
        parent.addView(v, lp);
    }

    public static TextView pill(Context c, String text, int color) {
        TextView tv = medium(c, text, 11.5f, color);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dpi(c, 10), dpi(c, 5), dpi(c, 10), dpi(c, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(withAlpha(color, dark ? 0x24 : 0x14));
        bg.setCornerRadius(dp(c, 100));
        tv.setBackground(bg);
        return tv;
    }

    public static TextView primaryButton(Context c, String text) {
        return button(c, text, ACCENT_STRONG, Color.WHITE, 0);
    }

    public static TextView tonalButton(Context c, String text) {
        return button(c, text, SURFACE_ALT, TEXT, 0);
    }

    public static TextView ghostButton(Context c, String text) {
        return button(c, text, Color.TRANSPARENT, TEXT2, 0);
    }

    public static TextView button(Context c, String s, int bgColor, int textColor, int strokeColor) {
        TextView tv = medium(c, s, 13.5f, textColor);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dpi(c, 18), dpi(c, 10), dpi(c, 18), dpi(c, 10));
        tv.setMinHeight(dpi(c, 48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(c, 14));
        if ((strokeColor >>> 24) != 0) bg.setStroke(Math.max(1, dpi(c, 0.8f)), strokeColor);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), bg, null);
        tv.setBackground(ripple);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    public static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        v.setBackground(bg);
        int s = dpi(c, sizeDp);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return v;
    }

    public static void setRipple(View v, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(v.getContext(), radiusDp));
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x1FFFFFFF), null, mask);
        v.setBackground(ripple);
    }

    public static void setRippleKeepBackground(View v, float radiusDp) {
        // 通过 foreground 叠加涟漪，不覆盖玻璃背景
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(v.getContext(), radiusDp));
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, mask));
    }

    public static int withAlpha(int color, int alpha) {
        return ((alpha & 0xff) << 24) | (color & 0x00ffffff);
    }

}
