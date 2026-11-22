package com.extbridge.helper;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Short, interaction-only property animations; no timers or retained frame callbacks. */
final class UiMotion {
    private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
    private static final long ENTER_MS = 180;
    private static final long EXIT_MS = 120;

    private UiMotion() {}

    static void drawer(View backdrop, View panel, boolean open, boolean animate, Runnable onClosed) {
        // Cancel the old completion before retargeting from the current visual position.
        backdrop.animate().withEndAction(null).cancel();
        panel.animate().cancel();
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || !backdrop.isAttachedToWindow()) {
            settleDrawer(backdrop, panel, open, onClosed);
            return;
        }
        if (open && backdrop.getVisibility() != View.VISIBLE) {
            backdrop.setAlpha(0f);
            panel.setTranslationY(DesktopStyle.dp(panel.getContext(), 12));
        }
        backdrop.setVisibility(View.VISIBLE);
        long duration = open ? ENTER_MS : EXIT_MS;
        backdrop.animate().alpha(open ? 1f : 0f).setStartDelay(0).setDuration(duration)
                .setInterpolator(EASE_OUT).withEndAction(() -> {
                    if (!open) settleDrawer(backdrop, panel, false, onClosed);
                }).start();
        panel.animate().translationY(open ? 0f : DesktopStyle.dp(panel.getContext(), 8))
                .setStartDelay(0).setDuration(duration).setInterpolator(EASE_OUT).start();
    }

    private static void settleDrawer(View backdrop, View panel, boolean open, Runnable onClosed) {
        panel.animate().cancel();
        backdrop.setVisibility(open ? View.VISIBLE : View.GONE);
        backdrop.setAlpha(1f);
        panel.setTranslationY(0f);
        if (!open) onClosed.run();
    }

    static void pressFeedback(View view) {
        view.setOnTouchListener((target, event) -> {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_CANCEL) return false;
            target.animate().cancel();
            if (!ValueAnimator.areAnimatorsEnabled()) {
                target.setScaleX(1f);
                target.setScaleY(1f);
            } else {
                boolean pressed = action == MotionEvent.ACTION_DOWN;
                float scale = pressed ? .97f : 1f;
                target.animate().scaleX(scale).scaleY(scale).setStartDelay(0)
                        .setDuration(pressed ? 100 : 160).setInterpolator(EASE_OUT).start();
            }
            // Keep normal clicks, ripples, long presses, and keyboard input owned by the View.
            return false;
        });
    }
}
