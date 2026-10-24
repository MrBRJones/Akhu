package com.MrBRos.akhu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws {@link MotionOverlay}s onto a canvas the size of the video frame, at a point in time (so
 * animations play). The editor's preview and the exported video both draw through here.
 */
final class OverlayRenderer {

    static final String[] FONT_NAMES = {"Modern", "Modern Bold", "Classic", "Classic Light", "Signature",
            "Editor", "Typewriter", "Strong", "Condensed", "Casual", "Elegant", "Small Caps"};
    private static final Typeface[] FONTS = {
            Typeface.create("sans-serif", Typeface.NORMAL),
            Typeface.create("sans-serif", Typeface.BOLD),
            Typeface.create("serif", Typeface.BOLD),
            Typeface.create("sans-serif-light", Typeface.NORMAL),
            Typeface.create("cursive", Typeface.NORMAL),
            Typeface.create("monospace", Typeface.NORMAL),
            Typeface.create("serif-monospace", Typeface.NORMAL),
            Typeface.create("sans-serif-black", Typeface.BOLD),
            Typeface.create("sans-serif-condensed", Typeface.BOLD),
            Typeface.create("casual", Typeface.NORMAL),
            Typeface.create("serif", Typeface.ITALIC),
            Typeface.create("sans-serif-smallcaps", Typeface.NORMAL)};

    static final int EFFECT_NONE = 0, EFFECT_HARD_SHADOW = 1, EFFECT_BLOCK_OFFSET = 2, EFFECT_NEON = 3,
            EFFECT_HALO = 4, EFFECT_OUTLINE = 5, EFFECT_3D = 6, EFFECT_GLOW = 7;
    static final String[] EFFECT_NAMES = {"None", "Hard Shadow", "Block Offset", "Neon", "Halo", "Outline",
            "3D Offset", "Glow"};

    static final int ANIM_NONE = 0, ANIM_TYPEWRITER = 1, ANIM_POP = 2, ANIM_JUMP = 3, ANIM_FADE = 4,
            ANIM_SLIDE_UP = 5, ANIM_STEP = 6, ANIM_PULSE = 7;
    static final String[] ANIM_NAMES = {"None", "Typewriter", "Pop", "Jump", "Fade In", "Slide Up",
            "Step By Step", "Pulse"};

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Bitmap> photos = new HashMap<>();
    private final Rect tmp = new Rect();

    static Typeface font(int index) {
        return FONTS[Math.max(0, Math.min(FONTS.length - 1, index))];
    }

    /**
     * Draws every overlay showing at {@code timeMs}. {@code clipStartMs} is where the trimmed clip
     * begins, so an overlay that starts before it still animates in when the clip does.
     */
    void draw(Canvas c, int w, int h, List<MotionOverlay> overlays, long timeMs, long clipStartMs,
              @Nullable MotionOverlay skip) {
        for (MotionOverlay o : overlays) {
            if (o == skip || !o.visibleAt(timeMs)) continue;
            draw(c, w, h, o, timeMs - Math.max(o.startMs, clipStartMs));
        }
    }

    /** Draws one overlay {@code ageMs} after it appeared (animations are timed from then). */
    void draw(Canvas c, int w, int h, MotionOverlay o, long ageMs) {
        float[] anim = animate(o, Math.max(0, ageMs), w, h);
        if (anim[1] <= 0f) return;
        int save = c.save();
        c.translate(o.x * w, o.y * h + anim[2]);
        c.rotate(o.rotation);
        c.scale(anim[0], anim[0]);
        int alpha = Math.round(255 * anim[1]);
        if (alpha < 255) c.saveLayerAlpha(null, alpha);
        switch (o.type) {
            case MotionOverlay.TEXT:
                drawText(c, w, o, anim[3]);
                break;
            case MotionOverlay.EMOJI:
                drawEmoji(c, w, o);
                break;
            default:
                drawPhoto(c, w, o);
        }
        c.restoreToCount(save);
    }

    /** {scale, alpha, y shift in px, share of the text shown} for an overlay's animation. */
    private static float[] animate(MotionOverlay o, long age, int w, int h) {
        float scale = 1f, alpha = 1f, dy = 0f, reveal = 1f;
        switch (o.animation) {
            case ANIM_TYPEWRITER:
                reveal = Math.min(1f, age / (60f * Math.max(1, o.text.length())));
                break;
            case ANIM_POP: {
                float p = Math.min(1f, age / 350f);
                scale = p < 0.7f ? 1.15f * p / 0.7f : 1.15f - 0.15f * (p - 0.7f) / 0.3f;
                alpha = Math.min(1f, p * 3f);
                break;
            }
            case ANIM_JUMP:
                dy = -(float) Math.abs(Math.sin(age / 700.0 * Math.PI)) * o.size * w * 0.6f;
                break;
            case ANIM_FADE:
                alpha = Math.min(1f, age / 600f);
                break;
            case ANIM_SLIDE_UP: {
                float p = Math.min(1f, age / 450f);
                float ease = 1f - (1f - p) * (1f - p) * (1f - p);
                dy = (1f - ease) * h * 0.08f;
                alpha = ease;
                break;
            }
            case ANIM_STEP: {
                int words = o.text.trim().isEmpty() ? 1 : o.text.trim().split("\\s+").length;
                reveal = Math.min(1f, (1 + age / 300) / (float) words);
                break;
            }
            case ANIM_PULSE:
                scale = 1f + 0.07f * (float) Math.sin(age / 500.0 * 2 * Math.PI);
                break;
            default:
        }
        return new float[]{scale, alpha, dy, reveal};
    }

    /** The text cut to the share {@code reveal}: by letters for Typewriter, whole words for Step By Step. */
    private static String shown(MotionOverlay o, float reveal) {
        if (reveal >= 1f) return o.text;
        if (o.animation == ANIM_STEP) {
            String[] words = o.text.trim().split("\\s+");
            int n = Math.max(1, Math.round(reveal * words.length));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) b.append(i == 0 ? "" : " ").append(words[i]);
            return b.toString();
        }
        return o.text.substring(0, Math.round(reveal * o.text.length()));
    }

    private StaticLayout layout(String s, MotionOverlay o, int w) {
        text.reset();
        text.setAntiAlias(true);
        text.setTypeface(font(o.font));
        text.setTextSize(o.size * w);
        int width = 1;
        for (String line : o.text.split("\n", -1)) width = Math.max(width, (int) Math.ceil(text.measureText(line)));
        return StaticLayout.Builder.obtain(s, 0, s.length(), text, width)
                .setAlignment(o.align)
                .setIncludePad(false)
                .build();
    }

    private void drawText(Canvas c, int w, MotionOverlay o, float reveal) {
        if (o.text.isEmpty()) return;
        StaticLayout full = layout(o.text, o, w);
        float lw = full.getWidth(), lh = full.getHeight();
        StaticLayout l = reveal >= 1f ? full : layout(shown(o, reveal), o, w);
        float ts = o.size * w;
        c.translate(-lw / 2f, -lh / 2f);
        int color = o.color;
        if (o.background != MotionOverlay.BG_NONE) {
            float pad = ts * 0.3f;
            paint.setStyle(Paint.Style.FILL);
            paint.setMaskFilter(null);
            paint.setColor(o.background == MotionOverlay.BG_SOLID ? o.color : 0x99000000);
            c.drawRoundRect(new RectF(-pad, -pad * 0.6f, lw + pad, lh + pad * 0.6f), pad, pad, paint);
            if (o.background == MotionOverlay.BG_SOLID) color = ThemeStore.contrastOn(o.color);
        }
        switch (o.effect) {
            case EFFECT_HARD_SHADOW:
                offset(c, l, 0xCC000000, ts * 0.07f, 1, false);
                break;
            case EFFECT_BLOCK_OFFSET:
                offset(c, l, ThemeIcons.shade(color, -0.55f), ts * 0.1f, 1, true);
                break;
            case EFFECT_3D:
                offset(c, l, ThemeIcons.shade(color, -0.45f), ts * 0.022f, 6, false);
                break;
            case EFFECT_NEON:
                text.setMaskFilter(new BlurMaskFilter(ts * 0.3f, BlurMaskFilter.Blur.NORMAL));
                fill(c, l, color);
                fill(c, l, color);
                text.setMaskFilter(null);
                color = ThemeIcons.shade(color, 0.6f);
                break;
            case EFFECT_HALO:
                stroke(c, l, ThemeStore.contrastOn(color), ts * 0.22f);
                break;
            case EFFECT_OUTLINE:
                stroke(c, l, color, ts * 0.06f);
                return;
            case EFFECT_GLOW:
                text.setShadowLayer(ts * 0.4f, 0, 0, color);
                fill(c, l, color);
                text.clearShadowLayer();
                break;
            default:
        }
        fill(c, l, color);
    }

    private void fill(Canvas c, StaticLayout l, int color) {
        text.setStyle(Paint.Style.FILL);
        text.setColor(color);
        l.draw(c);
    }

    private void stroke(Canvas c, StaticLayout l, int color, float width) {
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeJoin(Paint.Join.ROUND);
        text.setStrokeWidth(width);
        text.setColor(color);
        l.draw(c);
        text.setStyle(Paint.Style.FILL);
    }

    /** Copies of the text behind it, {@code steps} of them stepping down-right by {@code step}. */
    private void offset(Canvas c, StaticLayout l, int color, float step, int steps, boolean solid) {
        for (int i = steps; i >= 1; i--) {
            int save = c.save();
            c.translate(step * i, step * i);
            if (solid) stroke(c, l, color, step * 0.6f);
            fill(c, l, color);
            c.restoreToCount(save);
        }
    }

    private void drawEmoji(Canvas c, int w, MotionOverlay o) {
        text.reset();
        text.setAntiAlias(true);
        text.setTextSize(o.size * w * 0.85f);
        text.getTextBounds(o.text, 0, o.text.length(), tmp);
        c.drawText(o.text, -tmp.exactCenterX(), -tmp.exactCenterY(), text);
    }

    private void drawPhoto(Canvas c, int w, MotionOverlay o) {
        Bitmap b = photo(o.photoPath);
        if (b == null) return;
        float dw = o.size * w, dh = dw * b.getHeight() / b.getWidth();
        paint.setMaskFilter(null);
        c.drawBitmap(b, null, new RectF(-dw / 2, -dh / 2, dw / 2, dh / 2), paint);
    }

    @Nullable
    private Bitmap photo(@Nullable String path) {
        if (path == null) return null;
        Bitmap b = photos.get(path);
        if (b == null) {
            b = BitmapFactory.decodeFile(path);
            if (b != null) photos.put(path, b);
        }
        return b;
    }

    /** Half the width and height of an overlay at rest (before rotation), for touch hit tests. */
    float[] halfSize(MotionOverlay o, int w) {
        switch (o.type) {
            case MotionOverlay.TEXT: {
                if (o.text.isEmpty()) return new float[]{o.size * w, o.size * w / 2};
                StaticLayout l = layout(o.text, o, w);
                float pad = o.background != MotionOverlay.BG_NONE ? o.size * w * 0.3f : 0f;
                return new float[]{l.getWidth() / 2f + pad, l.getHeight() / 2f + pad};
            }
            case MotionOverlay.EMOJI:
                return new float[]{o.size * w / 2, o.size * w / 2};
            default: {
                Bitmap b = photo(o.photoPath);
                float hw = o.size * w / 2;
                return new float[]{hw, b == null ? hw : hw * b.getHeight() / b.getWidth()};
            }
        }
    }

    /** True when the overlay at this time differs from a moment later (so the export must redraw). */
    static boolean moving(MotionOverlay o) {
        return o.animation != ANIM_NONE;
    }

    void release() {
        for (Bitmap b : photos.values()) b.recycle();
        photos.clear();
    }
}
