package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;

import androidx.annotation.Nullable;

/**
 * Draws a theme's version of an app icon: the theme's shape filled with a colour, frosted glass or
 * a user photo, with the app's glyph on top. The glyph is the app's monochrome icon when it ships
 * one (Android 13+), otherwise a silhouette of its adaptive foreground, otherwise the original icon
 * shrunk onto the background.
 */
public final class ThemeIcons {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ThemeIcons() {
    }

    /** The themed icon for {@code app} at {@code size} px square. Call off the main thread. */
    public static Bitmap render(Context context, CustomTheme theme, CustomTheme.App app, int size) {
        String key = theme.id + '|' + theme.shape + '|' + theme.style + '|' + theme.bgColor + '|'
                + theme.glyphColor + '|' + app.pkg + '/' + app.activity + '|' + app.photo + '|' + size;
        Bitmap hit = CACHE.get(key);
        if (hit != null && !hit.isRecycled()) return hit;
        Bitmap out = draw(context, theme, app, size);
        CACHE.put(key, out);
        return out;
    }

    private static Bitmap draw(Context context, CustomTheme theme, CustomTheme.App app, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path shape = shape(theme.shape, size);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bitmap photo = app.photo != null ? ThemeStore.loadPhoto(context, theme, app.photo) : null;
        if (photo != null) {
            c.drawPath(shape, fill);
            Paint in = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            in.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            c.drawBitmap(photo, null, new Rect(0, 0, size, size), in);
            photo.recycle();
            return out;
        }

        Drawable icon = loadIcon(context, app);
        if (theme.style == CustomTheme.STYLE_ORIGINAL) {
            c.drawPath(shape, fill);
            Bitmap full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            if (icon != null) drawFullBleed(new Canvas(full), icon, size);
            Paint in = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            in.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            c.drawBitmap(full, 0, 0, in);
            full.recycle();
            return out;
        }

        int bg = theme.bgColor;
        float glyphScale = 0.95f;
        switch (theme.style) {
            case CustomTheme.STYLE_GLASS: {
                fill.setColor(Color.argb(110, Color.red(bg), Color.green(bg), Color.blue(bg)));
                c.drawPath(shape, fill);
                Paint rim = stroke(Math.max(1.5f, size * 0.022f), 0x8CFFFFFF);
                c.drawPath(shape(theme.shape, size, rim.getStrokeWidth() / 2f), rim);
                break;
            }
            case CustomTheme.STYLE_GRADIENT:
                fill.setShader(new LinearGradient(0, 0, size, size, shade(bg, 0.35f), shade(bg, -0.35f),
                        Shader.TileMode.CLAMP));
                c.drawPath(shape, fill);
                break;
            case CustomTheme.STYLE_OUTLINE: {
                Paint ring = stroke(size * 0.05f, vivid(bg));
                c.drawPath(shape(theme.shape, size, ring.getStrokeWidth() / 2f), ring);
                glyphScale = 0.8f;
                break;
            }
            case CustomTheme.STYLE_NEON: {
                float w = size * 0.035f;
                Path rimPath = shape(theme.shape, size, size * 0.06f);
                fill.setColor(0xFF0E0F14);
                c.drawPath(rimPath, fill);
                Paint glow = stroke(w * 2.2f, vivid(bg));
                glow.setMaskFilter(new BlurMaskFilter(size * 0.05f, BlurMaskFilter.Blur.NORMAL));
                c.drawPath(rimPath, glow);
                c.drawPath(rimPath, stroke(w, shade(vivid(bg), 0.45f)));
                glyphScale = 0.8f;
                break;
            }
            case CustomTheme.STYLE_PASTEL:
                fill.setColor(shade(bg, 0.6f));
                c.drawPath(shape, fill);
                break;
            case CustomTheme.STYLE_MINIMAL:
                glyphScale = 1.2f;
                break;
            case CustomTheme.STYLE_RAISED: {
                Path body = shape(theme.shape, size, size * 0.05f);
                drawShadow(c, body, size);
                fill.setShader(new LinearGradient(0, 0, 0, size, shade(bg, 0.25f), shade(bg, -0.25f),
                        Shader.TileMode.CLAMP));
                c.drawPath(body, fill);
                Paint shine = stroke(size * 0.02f, 0);
                shine.setShader(new LinearGradient(0, 0, 0, size, 0x99FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawPath(shape(theme.shape, size, size * 0.06f), shine);
                glyphScale = 0.85f;
                break;
            }
            case CustomTheme.STYLE_STICKER: {
                Path outer = shape(theme.shape, size, size * 0.05f);
                drawShadow(c, outer, size);
                fill.setColor(0xFFFFFFFF);
                c.drawPath(outer, fill);
                fill.setColor(bg);
                c.drawPath(shape(theme.shape, size, size * 0.1f), fill);
                glyphScale = 0.8f;
                break;
            }
            default:
                fill.setColor(bg);
                c.drawPath(shape, fill);
        }
        if (icon == null) return out;
        if (theme.style == CustomTheme.STYLE_NEON) {
            // Glyph on its own layer so its blurred alpha can be drawn underneath as a glow.
            Bitmap g = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawGlyph(new Canvas(g), icon, size, theme.glyphColor, glyphScale);
            Paint blur = new Paint();
            blur.setMaskFilter(new BlurMaskFilter(size * 0.04f, BlurMaskFilter.Blur.NORMAL));
            int[] offset = new int[2];
            Bitmap halo = g.extractAlpha(blur, offset);
            Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            haloPaint.setColor(theme.glyphColor);
            c.drawBitmap(halo, offset[0], offset[1], haloPaint);
            c.drawBitmap(g, 0, 0, null);
            halo.recycle();
            g.recycle();
        } else {
            drawGlyph(c, icon, size, theme.glyphColor, glyphScale);
        }
        return out;
    }

    /** The symbol colour a style looks best with on {@code bg}, used when the style or colour changes. */
    public static int defaultGlyph(int style, int bg) {
        switch (style) {
            case CustomTheme.STYLE_OUTLINE:
            case CustomTheme.STYLE_NEON:
                return vivid(bg);
            case CustomTheme.STYLE_PASTEL:
                return shade(bg, -0.45f);
            case CustomTheme.STYLE_MINIMAL:
                return 0xFFFFFFFF;
            case CustomTheme.STYLE_GRADIENT:
                return ThemeStore.contrastOn(shade(bg, -0.2f));
            default:
                return ThemeStore.contrastOn(bg);
        }
    }

    /** {@code c} mixed toward white ({@code amount} &gt; 0) or black ({@code amount} &lt; 0). */
    static int shade(int c, float amount) {
        int to = amount > 0 ? 255 : 0;
        float t = Math.abs(amount);
        return Color.rgb(Math.round(Color.red(c) + (to - Color.red(c)) * t),
                Math.round(Color.green(c) + (to - Color.green(c)) * t),
                Math.round(Color.blue(c) + (to - Color.blue(c)) * t));
    }

    /** {@code c} at full brightness (and fairly saturated unless it's a grey), so lines and glows show up. */
    static int vivid(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.max(hsv[2], 0.95f);
        if (hsv[1] > 0.15f) hsv[1] = Math.max(hsv[1], 0.6f);
        return Color.HSVToColor(hsv);
    }

    private static Paint stroke(float width, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(color);
        return p;
    }

    private static void drawShadow(Canvas c, Path body, int size) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x66000000);
        shadow.setMaskFilter(new BlurMaskFilter(size * 0.04f, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, size * 0.03f);
        c.drawPath(body, shadow);
        c.restore();
    }

    @Nullable
    private static Drawable loadIcon(Context context, CustomTheme.App app) {
        try {
            return context.getPackageManager().getActivityIcon(app.component());
        } catch (PackageManager.NameNotFoundException e) {
            try {
                return context.getPackageManager().getApplicationIcon(app.pkg);
            } catch (PackageManager.NameNotFoundException e2) {
                return null;
            }
        }
    }

    /** Adaptive layers are 108dp with the visible icon in the middle 72dp, hence the 1.5x. */
    private static Rect adaptiveBounds(int size, float scale) {
        int ext = Math.round(size * 1.5f * scale);
        int off = (size - ext) / 2;
        return new Rect(off, off, off + ext, off + ext);
    }

    private static void drawFullBleed(Canvas c, Drawable icon, int size) {
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            Rect r = adaptiveBounds(size, 1f);
            for (Drawable layer : new Drawable[]{a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(r);
                layer.draw(c);
            }
        } else {
            icon.setBounds(0, 0, size, size);
            icon.draw(c);
        }
    }

    private static void drawGlyph(Canvas c, Drawable icon, int size, int color, float scale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && icon instanceof AdaptiveIconDrawable) {
            Drawable mono = ((AdaptiveIconDrawable) icon).getMonochrome();
            if (mono != null) {
                mono = mono.mutate();
                mono.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                mono.setBounds(adaptiveBounds(size, scale));
                mono.draw(c);
                return;
            }
        }
        if (icon instanceof AdaptiveIconDrawable) {
            Drawable fg = ((AdaptiveIconDrawable) icon).getForeground();
            if (fg != null) {
                Bitmap layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                fg.setBounds(adaptiveBounds(size, scale));
                fg.draw(new Canvas(layer));
                // A foreground that fills most of the icon (e.g. a full-colour logo) makes a blob
                // as a silhouette; those fall through to the shrunken original below.
                if (coverage(layer) < 0.42f) {
                    Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    tint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                    c.drawBitmap(layer, 0, 0, tint);
                    layer.recycle();
                    return;
                }
                layer.recycle();
            }
        }
        // No usable glyph: the original icon, shrunk onto the themed background.
        Bitmap orig = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(orig);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        oc.drawPath(shape(CustomTheme.SHAPE_SQUIRCLE, size), p);
        Bitmap full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawFullBleed(new Canvas(full), icon, size);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        oc.drawBitmap(full, 0, 0, p);
        full.recycle();
        float inset = size * (0.5f - 0.3f * scale / 0.95f);
        c.drawBitmap(orig, null, new RectF(inset, inset, size - inset, size - inset),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        orig.recycle();
    }

    /** Share of the icon's visible area (inner 2/3) that is mostly opaque. */
    private static float coverage(Bitmap layer) {
        int s = layer.getWidth();
        int from = s / 6, to = s - s / 6, step = Math.max(1, s / 48);
        int opaque = 0, total = 0;
        for (int y = from; y < to; y += step) {
            for (int x = from; x < to; x += step) {
                total++;
                if (Color.alpha(layer.getPixel(x, y)) > 160) opaque++;
            }
        }
        return total == 0 ? 0f : (float) opaque / total;
    }

    public static Path shape(int shape, int size) {
        return shape(shape, size, 0f);
    }

    /** The outline for {@code shape} in a {@code size} square, shrunk by {@code inset} px. */
    public static Path shape(int shape, int size, float inset) {
        float m = size * 0.02f + inset;
        RectF r = new RectF(m, m, size - m, size - m);
        float w = r.width();
        Path p = new Path();
        switch (shape) {
            case CustomTheme.SHAPE_CIRCLE:
                p.addOval(r, Path.Direction.CW);
                break;
            case CustomTheme.SHAPE_ROUNDED:
                p.addRoundRect(r, w * 0.2f, w * 0.2f, Path.Direction.CW);
                break;
            case CustomTheme.SHAPE_TEARDROP: {
                float big = w * 0.5f, small = w * 0.14f;
                p.addRoundRect(r, new float[]{big, big, big, big, small, small, big, big}, Path.Direction.CW);
                break;
            }
            default: {
                // Superellipse |x|^4 + |y|^4 = 1, the "squircle" phone launchers use.
                float cx = r.centerX(), cy = r.centerY(), rad = w / 2f;
                for (int i = 0; i <= 96; i++) {
                    double t = 2 * Math.PI * i / 96;
                    double cos = Math.cos(t), sin = Math.sin(t);
                    float x = cx + (float) (rad * Math.signum(cos) * Math.sqrt(Math.abs(cos)));
                    float y = cy + (float) (rad * Math.signum(sin) * Math.sqrt(Math.abs(sin)));
                    if (i == 0) p.moveTo(x, y);
                    else p.lineTo(x, y);
                }
                p.close();
            }
        }
        return p;
    }
}
