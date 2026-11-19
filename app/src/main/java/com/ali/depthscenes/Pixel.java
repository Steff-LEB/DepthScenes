package com.ali.depthscenes;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/** Material You styling built from the phone's dynamic wallpaper colours, without extra libraries. */
final class Pixel {
    final boolean dark;
    final int bg, card, onSurface, variant, primary, onPrimary, tonal, onTonal, outline;
    final float density;
    /** Material 3 "emphasized decelerate" curve used by Pixel transitions. */
    static final PathInterpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    Pixel(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        density = c.getResources().getDisplayMetrics().density;
        if (dark) {
            bg = c.getColor(android.R.color.system_neutral1_900);
            card = c.getColor(android.R.color.system_neutral1_800);
            onSurface = c.getColor(android.R.color.system_neutral1_100);
            variant = c.getColor(android.R.color.system_neutral2_200);
            primary = c.getColor(android.R.color.system_accent1_200);
            onPrimary = c.getColor(android.R.color.system_accent1_800);
            tonal = c.getColor(android.R.color.system_accent2_700);
            onTonal = c.getColor(android.R.color.system_accent2_100);
            outline = c.getColor(android.R.color.system_neutral2_400);
        } else {
            bg = c.getColor(android.R.color.system_neutral1_10);
            card = c.getColor(android.R.color.system_neutral2_50);
            onSurface = c.getColor(android.R.color.system_neutral1_900);
            variant = c.getColor(android.R.color.system_neutral2_700);
            primary = c.getColor(android.R.color.system_accent1_600);
            onPrimary = c.getColor(android.R.color.system_accent1_0);
            tonal = c.getColor(android.R.color.system_accent2_100);
            onTonal = c.getColor(android.R.color.system_accent2_900);
            outline = c.getColor(android.R.color.system_neutral2_500);
        }
    }
    int dp(float n) { return Math.round(density * n); }

    /** Google Sans on Pixel phones, falling back to the system sans-serif elsewhere. */
    static Typeface font(boolean medium) {
        Typeface t = Typeface.create(medium ? "google-sans-medium" : "google-sans", Typeface.NORMAL);
        return t != null ? t : Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
    }

    TextView text(Context c, String s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(font(medium));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    GradientDrawable surface(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /**
     * A pill button that squishes on press: the corners morph from a full pill to a rounded square and
     * the button shrinks slightly, then springs back with a small overshoot when released.
     */
    static final class Morph extends TextView {
        private final GradientDrawable shape = new GradientDrawable();
        private final float rest, pressed;
        private ValueAnimator anim;

        Morph(Context c, Pixel p, String label, int fill, int ink, float heightDp) {
            super(c);
            rest = p.dp(heightDp / 2f); pressed = p.dp(Math.min(14, heightDp / 4f));
            setText(label); setTextColor(ink); setTextSize(16); setTypeface(font(true));
            setGravity(Gravity.CENTER); setMinHeight(p.dp(heightDp)); setMinimumHeight(p.dp(heightDp));
            setPadding(p.dp(24), 0, p.dp(24), 0); setSingleLine(true);
            shape.setColor(fill); shape.setCornerRadius(rest);
            setBackground(new RippleDrawable(ColorStateList.valueOf((ink & 0x00ffffff) | 0x29000000), shape, null));
            setClickable(true); setFocusable(true);
            setStateListAnimator(null);
        }
        void setFill(int fill, int ink) { shape.setColor(fill); setTextColor(ink); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (isEnabled()) switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: morph(true); break;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: morph(false); break;
            }
            return super.onTouchEvent(e);
        }
        private void morph(boolean down) {
            if (anim != null) anim.cancel();
            final float r0 = shape.getCornerRadius(), r1 = down ? pressed : rest;
            final float s0 = getScaleX(), s1 = down ? .94f : 1f;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(down ? 140 : 520);
            anim.setInterpolator(down ? new DecelerateInterpolator() : new OvershootInterpolator(2.2f));
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                shape.setCornerRadius(Math.max(0, r0 + (r1 - r0) * f));
                float s = s0 + (s1 - s0) * f; setScaleX(s); setScaleY(s);
            });
            anim.start();
            if (down) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        @Override public void setEnabled(boolean on) {
            super.setEnabled(on);
            animate().alpha(on ? 1f : .5f).setDuration(200).start();
        }
    }

    /** A wallpaper thumbnail whose shape morphs when selected: rounder and smaller when idle. */
    static final class Tile extends ImageView {
        private float radius, ringAlpha;
        private final float idleRadius, activeRadius;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float stroke;
        private ValueAnimator anim;

        Tile(Context c, Pixel p) {
            super(c);
            idleRadius = p.dp(30); activeRadius = p.dp(16); radius = idleRadius;
            stroke = p.dp(3);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(stroke); ring.setColor(p.primary);
            setScaleType(ScaleType.CENTER_CROP);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
            });
            setClipToOutline(true);
            setScaleX(.9f); setScaleY(.9f);
        }
        void choose(boolean on, boolean animate) {
            if (anim != null) anim.cancel();
            final float r0 = radius, r1 = on ? activeRadius : idleRadius;
            final float a0 = ringAlpha, a1 = on ? 1f : 0f;
            final float s0 = getScaleX(), s1 = on ? 1f : .9f;
            if (!animate) { apply(r1, a1, s1); return; }
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(420);
            anim.setInterpolator(on ? new OvershootInterpolator(1.6f) : EMPHASIZED);
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                apply(r0 + (r1 - r0) * f, Math.max(0, Math.min(1, a0 + (a1 - a0) * f)), s0 + (s1 - s0) * f);
            });
            anim.start();
        }
        private void apply(float r, float a, float s) {
            radius = Math.max(0, r); ringAlpha = a; setScaleX(s); setScaleY(s);
            invalidateOutline(); invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (ringAlpha <= 0) return;
            ring.setAlpha(Math.round(255 * ringAlpha));
            float h = stroke / 2f;
            c.drawRoundRect(h, h, getWidth() - h, getHeight() - h, Math.max(0, radius - h), Math.max(0, radius - h), ring);
        }
    }
}
