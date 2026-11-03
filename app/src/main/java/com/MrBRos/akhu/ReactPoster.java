package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Still pictures of reactive wallpapers for the grids, drawn on the CPU to look like the live
 * water (a gradient, pool tiles, soft light and the character with its shadow) and cached in
 * cache/react_poster/.
 */
public final class ReactPoster {

    private static final int W = 360, H = 640;

    private ReactPoster() {
    }

    /** The poster file for a wallpaper, drawn now if it isn't cached; null if it can't be drawn. */
    @WorkerThread
    @Nullable
    public static synchronized File get(Context context, String character) {
        File f = fileFor(context, character);
        if (f.exists()) return f;
        Bitmap poster = render(context, character, W, H);
        if (poster == null) return null;
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return null;
        try (OutputStream os = new FileOutputStream(f)) {
            poster.compress(Bitmap.CompressFormat.JPEG, 88, os);
            return f;
        } catch (IOException e) {
            return null;
        } finally {
            poster.recycle();
        }
    }

    /** Drops the cached poster (after an edit or delete). */
    public static void forget(Context context, String character) {
        File[] old = new File(context.getCacheDir(), "react_poster").listFiles(
                (d, n) -> n.startsWith(stem(character) + "_"));
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static String stem(String character) {
        return Integer.toHexString(character.hashCode());
    }

    private static File fileFor(Context context, String character) {
        // A user's settings file changes when they edit it, so its time keys the cache.
        long version = 0;
        if (ReactWallpapers.isUser(character)) {
            File json = new File(character.substring(0, character.length() - 4) + ".json");
            version = json.lastModified();
        }
        return new File(new File(context.getCacheDir(), "react_poster"), stem(character) + "_" + version + ".jpg");
    }

    @Nullable
    static Bitmap render(Context context, String character, int w, int h) {
        Bitmap ch = ReactWallpapers.loadCharacter(context, character);
        if (ch == null) return null;
        ReactWallpapers.Spec spec = ReactWallpapers.spec(context, character);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        drawWater(c, spec, w, h);

        float drawW = spec.size * Math.min(w, h * 0.75f);
        float drawH = drawW * ch.getHeight() / ch.getWidth();
        float left = (w - drawW) / 2f, top = h * 0.45f - drawH / 2f;
        RectF dst = new RectF(left, top, left + drawW, top + drawH);

        // Soft shadow, down and to the right, like the live one.
        Paint blur = new Paint();
        blur.setMaskFilter(new BlurMaskFilter(Math.max(2f, drawW / 40f), BlurMaskFilter.Blur.NORMAL));
        int[] offset = new int[2];
        Bitmap alpha = ch.extractAlpha(blur, offset);
        Paint shadow = new Paint(Paint.FILTER_BITMAP_FLAG);
        shadow.setColor(0x52000510);
        float scale = drawW / ch.getWidth(), shift = Math.min(w, h) * 0.025f;
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(left + offset[0] * scale + shift, top + offset[1] * scale + shift * 1.4f);
        c.drawBitmap(alpha, m, shadow);
        alpha.recycle();

        c.drawBitmap(ch, new Rect(0, 0, ch.getWidth(), ch.getHeight()), dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        ch.recycle();
        return out;
    }

    private static void drawWater(Canvas c, ReactWallpapers.Spec spec, int w, int h) {
        int[] style = ReactWallpapers.STYLES[spec.style];
        if (spec.style == ReactWallpapers.STYLE_PHOTO && spec.photo != null) {
            Bitmap photo = ImagePickerHelper.decodeSampled(new File(spec.photo), Math.max(w, h));
            if (photo != null) {
                float s = Math.max((float) w / photo.getWidth(), (float) h / photo.getHeight());
                float pw = photo.getWidth() * s, ph = photo.getHeight() * s;
                c.drawBitmap(photo, null, new RectF((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                photo.recycle();
                return;
            }
        }
        int shallow = 0xFF000000 | style[0], deep = 0xFF000000 | style[1], light = style[2];
        int scene = style[4];
        Paint p = new Paint();
        if (scene == ReactWallpapers.SCENE_SKY) {
            p.setShader(new LinearGradient(0, 0, 0, h, deep, shallow, Shader.TileMode.CLAMP));
        } else if (scene == ReactWallpapers.SCENE_SPACE) {
            p.setShader(new LinearGradient(0, 0, 0, h, mix(0xFF000000, shallow, 0.35f), deep, Shader.TileMode.CLAMP));
        } else {
            p.setShader(new LinearGradient(0, 0, 0, h, new int[]{shallow, mix(shallow, deep, 0.45f), deep},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }
        c.drawRect(0, 0, w, h, p);
        if (scene != ReactWallpapers.SCENE_WATER) {
            drawScene(c, scene, shallow, light, w, h);
            return;
        }
        if (style[3] == 1) {
            Paint grout = new Paint(Paint.ANTI_ALIAS_FLAG);
            grout.setColor(0x55FFFFFF);
            grout.setStrokeWidth(Math.max(1f, w / 180f));
            float tile = Math.min(w, h) / 7f;
            for (float x = tile; x < w; x += tile) c.drawLine(x, 0, x, h, grout);
            for (float y = tile; y < h; y += tile) c.drawLine(0, y, w, y, grout);
        }
        // A few soft patches of light standing in for the moving caustics.
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 9; i++) {
            float x = r.nextFloat() * w, y = r.nextFloat() * h, rad = (0.12f + r.nextFloat() * 0.18f) * w;
            glow.setShader(new RadialGradient(x, y, rad, 0x30000000 | light, 0x00000000 | light, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, rad, glow);
        }
    }

    /** Stills of the other scenes: stars and nebula glow, falling leaves, snow, petals or clouds. */
    private static void drawScene(Canvas c, int scene, int first, int light, int w, int h) {
        java.util.Random r = new java.util.Random(11);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (scene == ReactWallpapers.SCENE_SPACE) {
            for (int i = 0; i < 5; i++) {
                float x = r.nextFloat() * w, y = r.nextFloat() * h, rad = (0.25f + r.nextFloat() * 0.3f) * w;
                int tint = i % 2 == 0 ? first : 0x8C40A6;
                paint.setShader(new RadialGradient(x, y, rad, 0x60000000 | (tint & 0xFFFFFF), tint & 0xFFFFFF, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, rad, paint);
            }
            paint.setShader(null);
            for (int i = 0; i < 140; i++) {
                paint.setColor(((40 + r.nextInt(215)) << 24) | (light & 0xFFFFFF));
                c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, 0.4f + r.nextFloat() * 1.4f, paint);
            }
        } else if (scene == ReactWallpapers.SCENE_SKY) {
            for (int i = 0; i < 7; i++) {
                float x = r.nextFloat() * w, y = r.nextFloat() * h, rad = (0.18f + r.nextFloat() * 0.2f) * w;
                for (int k = 0; k < 4; k++) {
                    float cx = x + (k - 1.5f) * rad * 0.55f, cy = y + (k % 2) * rad * 0.15f;
                    paint.setShader(new RadialGradient(cx, cy, rad * 0.7f, 0xCCFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    c.drawCircle(cx, cy, rad * 0.7f, paint);
                }
            }
        } else {
            int[] colors = scene == ReactWallpapers.SCENE_LEAVES ? new int[]{0xFFEE731F, 0xFFCC381A, 0xFFF5B833}
                    : scene == ReactWallpapers.SCENE_SAKURA ? new int[]{0xFFFFBFD6, 0xFFFFEDF4} : new int[]{0xE6FFFFFF};
            int count = scene == ReactWallpapers.SCENE_SNOW ? 70 : 26;
            for (int i = 0; i < count; i++) {
                float x = r.nextFloat() * w, y = r.nextFloat() * h;
                float s = (scene == ReactWallpapers.SCENE_SNOW ? 0.006f : 0.022f) * w * (0.6f + r.nextFloat());
                paint.setColor(colors[r.nextInt(colors.length)]);
                c.save();
                c.rotate(r.nextFloat() * 360f, x, y);
                if (scene == ReactWallpapers.SCENE_SNOW) c.drawCircle(x, y, s, paint);
                else c.drawOval(x - s, y - s * (scene == ReactWallpapers.SCENE_LEAVES ? 0.48f : 0.65f),
                        x + s, y + s * (scene == ReactWallpapers.SCENE_LEAVES ? 0.48f : 0.65f), paint);
                c.restore();
            }
        }
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
