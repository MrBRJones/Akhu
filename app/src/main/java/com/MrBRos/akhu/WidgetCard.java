package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * The card behind a theme's clock widget, styled the way {@link ThemeIcons} styles icons: the same
 * Solid, Glass, Gradient, Outline, Neon, Pastel, Raised and Sticker looks in the widget colour, a
 * rounded, pill or square outline, or the theme's widget photo. Minimal means no card. Shared by
 * the preview ({@link HomeMockView}) and the real widget ({@link ThemeClockWidgetProvider}).
 */
final class WidgetCard {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private WidgetCard() {
    }

    /** Whether [theme]'s widget has a card at all. */
    static boolean has(CustomTheme theme) {
        return theme.widgetPhoto != null || theme.widgetStyle != CustomTheme.STYLE_MINIMAL;
    }

    /** The card at [w] x [h] px, or null for none. Reads the photo from disk; call off the main thread. */
    @Nullable
    static Bitmap render(Context context, CustomTheme theme, int w, int h) {
        if (!has(theme) || w <= 0 || h <= 0) return null;
        String key = theme.id + '|' + theme.widgetStyle + '|' + theme.widgetShape + '|' + theme.widgetBg + '|'
                + theme.widgetPhoto + '|' + w + 'x' + h;
        Bitmap hit = CACHE.get(key);
        if (hit != null && !hit.isRecycled()) return hit;
        Bitmap out = draw(context, theme, w, h);
        CACHE.put(key, out);
        return out;
    }

    private static Bitmap draw(Context context, CustomTheme theme, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float unit = Math.min(w, h);
        // Room around the card for shadows and glows.
        float margin = unit * 0.06f;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        int bg = theme.widgetBg;

        Bitmap photo = theme.widgetPhoto == null ? null
                : ImagePickerHelper.decodeSampled(ThemeStore.photo(context, theme, theme.widgetPhoto), Math.max(w, h));
        if (photo != null) {
            Path body = shape(theme, w, h, margin);
            c.save();
            c.clipPath(body);
            float s = Math.max((float) w / photo.getWidth(), (float) h / photo.getHeight());
            float pw = photo.getWidth() * s, ph = photo.getHeight() * s;
            c.drawBitmap(photo, null, new RectF((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            // Darkened a little so the time stays readable.
            c.drawColor(0x40000000);
            c.restore();
            photo.recycle();
            return out;
        }

        switch (theme.widgetStyle) {
            case CustomTheme.STYLE_GLASS: {
                fill.setColor(Color.argb(110, Color.red(bg), Color.green(bg), Color.blue(bg)));
                c.drawPath(shape(theme, w, h, margin), fill);
                Paint rim = stroke(Math.max(1.5f, unit * 0.015f), 0x8CFFFFFF);
                c.drawPath(shape(theme, w, h, margin + rim.getStrokeWidth() / 2f), rim);
                break;
            }
            case CustomTheme.STYLE_GRADIENT:
                fill.setShader(new LinearGradient(0, 0, w, h, ThemeIcons.shade(bg, 0.35f), ThemeIcons.shade(bg, -0.35f),
                        Shader.TileMode.CLAMP));
                c.drawPath(shape(theme, w, h, margin), fill);
                break;
            case CustomTheme.STYLE_OUTLINE: {
                Paint ring = stroke(unit * 0.035f, ThemeIcons.vivid(bg));
                c.drawPath(shape(theme, w, h, margin + ring.getStrokeWidth() / 2f), ring);
                break;
            }
            case CustomTheme.STYLE_NEON: {
                float sw = unit * 0.025f;
                Path rim = shape(theme, w, h, margin);
                fill.setColor(0xE60E0F14);
                c.drawPath(rim, fill);
                Paint glow = stroke(sw * 2.2f, ThemeIcons.vivid(bg));
                glow.setMaskFilter(new BlurMaskFilter(unit * 0.04f, BlurMaskFilter.Blur.NORMAL));
                c.drawPath(rim, glow);
                c.drawPath(rim, stroke(sw, ThemeIcons.shade(ThemeIcons.vivid(bg), 0.45f)));
                break;
            }
            case CustomTheme.STYLE_PASTEL:
                fill.setColor(ThemeIcons.shade(bg, 0.6f));
                c.drawPath(shape(theme, w, h, margin), fill);
                break;
            case CustomTheme.STYLE_RAISED: {
                Path body = shape(theme, w, h, margin);
                shadow(c, body, unit);
                fill.setShader(new LinearGradient(0, 0, 0, h, ThemeIcons.shade(bg, 0.25f), ThemeIcons.shade(bg, -0.25f),
                        Shader.TileMode.CLAMP));
                c.drawPath(body, fill);
                Paint shine = stroke(unit * 0.015f, 0);
                shine.setShader(new LinearGradient(0, 0, 0, h, 0x99FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawPath(shape(theme, w, h, margin + unit * 0.01f), shine);
                break;
            }
            case CustomTheme.STYLE_STICKER: {
                Path outer = shape(theme, w, h, margin);
                shadow(c, outer, unit);
                fill.setColor(0xFFFFFFFF);
                c.drawPath(outer, fill);
                fill.setColor(bg);
                c.drawPath(shape(theme, w, h, margin + unit * 0.05f), fill);
                break;
            }
            default:
                fill.setColor(bg);
                c.drawPath(shape(theme, w, h, margin), fill);
        }
        return out;
    }

    /** The card's outline inset by [inset] px on each side. */
    private static Path shape(CustomTheme theme, int w, int h, float inset) {
        RectF r = new RectF(inset, inset, w - inset, h - inset);
        float small = Math.min(r.width(), r.height());
        float radius;
        switch (theme.widgetShape) {
            case CustomTheme.WIDGET_SHAPE_PILL:
                radius = small / 2f;
                break;
            case CustomTheme.WIDGET_SHAPE_SQUARE:
                radius = small * 0.04f;
                break;
            default:
                radius = small * 0.2f;
        }
        Path p = new Path();
        p.addRoundRect(r, radius, radius, Path.Direction.CW);
        return p;
    }

    private static Paint stroke(float width, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(color);
        return p;
    }

    private static void shadow(Canvas c, Path body, float unit) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x66000000);
        shadow.setMaskFilter(new BlurMaskFilter(unit * 0.03f, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, unit * 0.02f);
        c.drawPath(body, shadow);
        c.restore();
    }

    /** Stores a picked picture as the widget photo (up to 1024 px), returning its file name. */
    static String savePhoto(Context context, CustomTheme theme, File picked) throws java.io.IOException {
        Bitmap bmp = ImagePickerHelper.decodeSampled(picked, 1024);
        if (bmp == null) throw new java.io.IOException("decode");
        File d = ThemeStore.dir(context, theme.id);
        if (!d.exists() && !d.mkdirs()) throw new java.io.IOException("Cannot create " + d);
        String name = "widget_" + System.currentTimeMillis() + ".jpg";
        try (java.io.OutputStream os = new java.io.FileOutputStream(new File(d, name))) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
        } finally {
            bmp.recycle();
        }
        return name;
    }
}
