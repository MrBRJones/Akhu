package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Saved themes in files/themes/&lt;id&gt;/: theme.json, wallpaper.jpg and icon photos. */
public final class ThemeStore {

    private static final String TAG = "ThemeStore";
    private static final String WALLPAPER = "wallpaper.jpg";
    /** Long side of the stored wallpaper; plenty for a phone screen. */
    private static final int WALLPAPER_MAX = 2400;
    /** Icon photos are stored square at this size. */
    public static final int PHOTO_SIZE = 256;
    /** Ids of the built-in themes on the Themes tab; the user's own themes have plain ids. */
    public static final String BUILTIN_PREFIX = "b_";
    /** Ids of public themes downloaded from the cloud: c_&lt;Firestore doc id&gt;. */
    public static final String CLOUD_PREFIX = "c_";

    private ThemeStore() {
    }

    private static File root(Context context) {
        return new File(context.getFilesDir(), "themes");
    }

    public static File dir(Context context, String id) {
        return new File(root(context), id);
    }

    public static File wallpaper(Context context, CustomTheme theme) {
        return new File(dir(context, theme.id), WALLPAPER);
    }

    public static File photo(Context context, CustomTheme theme, String name) {
        return new File(dir(context, theme.id), name);
    }

    public static CustomTheme create(String name) {
        CustomTheme t = new CustomTheme();
        t.id = UUID.randomUUID().toString().substring(0, 8);
        t.name = name;
        return t;
    }

    /** The user's own themes (Profile), newest first. Built-in and downloaded themes aren't included. */
    public static List<CustomTheme> list(Context context) {
        List<CustomTheme> out = new ArrayList<>();
        File[] dirs = root(context).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            CustomTheme t = load(context, d.getName());
            if (t != null && !isReadOnly(t)) out.add(t);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updated, a.updated));
        return out;
    }

    @Nullable
    public static CustomTheme load(Context context, String id) {
        File f = new File(dir(context, id), "theme.json");
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            return CustomTheme.fromJson(new JSONObject(new String(data, 0, off, StandardCharsets.UTF_8)));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Bad theme " + id, e);
            return null;
        }
    }

    public static void save(Context context, CustomTheme theme) throws IOException {
        theme.updated = System.currentTimeMillis();
        write(context, theme);
    }

    /** Writes theme.json as is (keeping {@code updated}) and drops unused icon photos. */
    private static void write(Context context, CustomTheme theme) throws IOException {
        File d = dir(context, theme.id);
        if (!d.exists() && !d.mkdirs()) throw new IOException("Cannot create " + d);
        try (OutputStream os = new FileOutputStream(new File(d, "theme.json"))) {
            os.write(theme.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        prunePhotos(context, theme);
    }

    /** Deletes icon photos in the theme's folder that none of its apps use. */
    public static void prunePhotos(Context context, CustomTheme theme) {
        File[] files = dir(context, theme.id).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.getName().startsWith("icon_")) continue;
            boolean used = false;
            for (CustomTheme.App a : theme.apps) used |= f.getName().equals(a.photo);
            if (!used) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    public static void delete(Context context, CustomTheme theme) {
        File d = dir(context, theme.id);
        File[] files = d.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

    /** Stores {@code source} (a picked file) as the theme's wallpaper, downscaled to phone size. */
    public static void setWallpaper(Context context, CustomTheme theme, File source) throws IOException {
        Bitmap bmp = ImagePickerHelper.decodeSampled(source, WALLPAPER_MAX);
        if (bmp == null) throw new IOException("Cannot decode " + source);
        try {
            writeJpeg(bmp, wallpaper(context, theme));
        } finally {
            bmp.recycle();
        }
    }

    /** Stores a square icon photo for one app and returns its file name. */
    public static String savePhoto(Context context, CustomTheme theme, Bitmap square) throws IOException {
        File d = dir(context, theme.id);
        if (!d.exists() && !d.mkdirs()) throw new IOException("Cannot create " + d);
        String name = "icon_" + System.currentTimeMillis() + ".png";
        try (OutputStream os = new FileOutputStream(new File(d, name))) {
            square.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        return name;
    }

    private static void writeJpeg(Bitmap bmp, File out) throws IOException {
        File d = out.getParentFile();
        if (d != null && !d.exists() && !d.mkdirs()) throw new IOException("Cannot create " + d);
        try (OutputStream os = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, os);
        }
    }

    /** Decodes the theme wallpaper with its long side at most {@code maxDim}; null if missing. */
    @Nullable
    public static Bitmap decodeWallpaper(Context context, CustomTheme theme, int maxDim) {
        return ImagePickerHelper.decodeSampled(wallpaper(context, theme), maxDim);
    }

    /**
     * Picks theme colours from a wallpaper: up to {@code max} distinct, fairly saturated colours,
     * most common first.
     */
    public static List<Integer> palette(Bitmap source, int max) {
        Bitmap small = Bitmap.createScaledBitmap(source, 48, 48, true);
        int[] px = new int[48 * 48];
        small.getPixels(px, 0, 48, 0, 0, 48, 48);
        if (small != source) small.recycle();
        // 4 bits per channel histogram, keeping each bucket's mean colour.
        int[] count = new int[4096];
        long[] r = new long[4096], g = new long[4096], b = new long[4096];
        for (int c : px) {
            int k = ((c >> 20) & 0xF) << 8 | ((c >> 12) & 0xF) << 4 | ((c >> 4) & 0xF);
            count[k]++;
            r[k] += Color.red(c);
            g[k] += Color.green(c);
            b[k] += Color.blue(c);
        }
        List<Integer> out = new ArrayList<>();
        float[] hsv = new float[3];
        while (out.size() < max) {
            int best = -1;
            float bestScore = 0f;
            for (int k = 0; k < 4096; k++) {
                if (count[k] == 0) continue;
                int c = Color.rgb((int) (r[k] / count[k]), (int) (g[k] / count[k]), (int) (b[k] / count[k]));
                Color.colorToHSV(c, hsv);
                // Favour colourful, mid-bright buckets; greys still count a little.
                float score = count[k] * (0.25f + hsv[1]) * (hsv[2] < 0.15f ? 0.2f : 1f);
                if (score > bestScore) {
                    bestScore = score;
                    best = k;
                }
            }
            if (best < 0) break;
            int c = Color.rgb((int) (r[best] / count[best]), (int) (g[best] / count[best]), (int) (b[best] / count[best]));
            count[best] = 0;
            boolean distinct = true;
            for (int o : out) distinct &= distance(o, c) > 60;
            if (distinct) out.add(c);
        }
        return out;
    }

    private static int distance(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    /** White or near-black, whichever reads better on {@code bg}. */
    public static int contrastOn(int bg) {
        double lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg);
        return lum > 160 ? 0xFF16181D : 0xFFFFFFFF;
    }

    /** True for built-in and downloaded themes, which can be copied into the user's own but not changed. */
    public static boolean isReadOnly(CustomTheme theme) {
        return theme.id.startsWith(BUILTIN_PREFIX) || theme.id.startsWith(CLOUD_PREFIX);
    }

    /**
     * The nine built-in themes, in Themes-tab order, one per featured wallpaper. Each is written the
     * first time it is needed (or again if missing), and kept apart from the user's own themes, so
     * deleting a theme in Profile never removes one here.
     * Synchronized because the Themes tab and the preview both list them on their own threads.
     */
    public static synchronized List<CustomTheme> builtins(Context context) {
        List<CustomTheme> out = new ArrayList<>();
        List<CustomTheme.App> apps = null;
        for (Starter s : STARTERS) {
            String id = BUILTIN_PREFIX + s.name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
            CustomTheme t = load(context, id);
            if (t == null || !new File(dir(context, id), WALLPAPER).exists()) {
                List<String> assets = FeaturedWallpapers.list(context, s.category);
                if (assets.size() <= s.index) continue;
                if (apps == null) apps = AppCatalog.defaults(context, 12);
                try {
                    t = new CustomTheme();
                    t.id = id;
                    t.name = s.name;
                    t.shape = s.shape;
                    t.style = s.style;
                    t.bgColor = s.bg;
                    t.glyphColor = s.glyph != 0 ? s.glyph : ThemeIcons.defaultGlyph(s.style, s.bg);
                    for (CustomTheme.App a : apps) t.apps.add(new CustomTheme.App(a.pkg, a.activity, a.label));
                    setWallpaper(context, t, FeaturedWallpapers.copyToCache(context, assets.get(s.index)));
                    write(context, t);
                } catch (IOException e) {
                    Log.w(TAG, "Writing built-in " + s.name + " failed", e);
                    continue;
                }
            }
            out.add(t);
        }
        return out;
    }

    /**
     * A public theme from the cloud, kept as c_&lt;docId&gt; so it previews like any other. The
     * wallpaper is downloaded once; blocking, so call off the main thread. Null if it can't be read.
     */
    @Nullable
    public static CustomTheme cloud(Context context, String docId, String json, String wallpaperUrl) {
        String id = CLOUD_PREFIX + docId;
        File wp = new File(dir(context, id), WALLPAPER);
        try {
            CustomTheme t = CustomTheme.fromJson(new JSONObject(json));
            t.id = id;
            for (CustomTheme.App a : t.apps) a.photo = null;
            if (!wp.exists()) {
                Bitmap bmp = FirebaseHelper.downloadBitmap(context, wallpaperUrl);
                writeJpeg(bmp, wp);
            }
            write(context, t);
            return t;
        } catch (Exception e) {
            Log.w(TAG, "Cloud theme " + docId + " failed", e);
            return null;
        }
    }

    /**
     * Copies a theme's wallpaper and icon photos to a new id, for editing a built-in or downloaded
     * theme into one of the user's own. The copy isn't listed until it is saved.
     */
    public static CustomTheme duplicate(Context context, CustomTheme source) throws IOException {
        CustomTheme t = source.copy();
        t.id = create(source.name).id;
        File from = dir(context, source.id), to = dir(context, t.id);
        if (!to.exists() && !to.mkdirs()) throw new IOException("Cannot create " + to);
        File[] files = from.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().equals("theme.json")) continue;
                try (InputStream in = new FileInputStream(f); OutputStream os = new FileOutputStream(new File(to, f.getName()))) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
        return t;
    }

    /** A starter theme: one bundled featured wallpaper with its own icon style, shape and colours. */
    private static final class Starter {
        final String name, category;
        final int index, style, shape, bg, glyph;

        /** {@code glyph} 0 = the style's default symbol colour on {@code bg}. */
        Starter(String name, String category, int index, int style, int shape, int bg, int glyph) {
            this.name = name;
            this.category = category;
            this.index = index;
            this.style = style;
            this.shape = shape;
            this.bg = bg;
            this.glyph = glyph;
        }
    }

    /** One per featured wallpaper, each showing off a different icon style and shape. */
    private static final Starter[] STARTERS = {
            new Starter("Hyrule", "zelda", 0, CustomTheme.STYLE_GRADIENT, CustomTheme.SHAPE_TEARDROP, 0xFF2BB3A3, 0),
            new Starter("Grand Line", "onepiece", 0, CustomTheme.STYLE_RAISED, CustomTheme.SHAPE_COOKIE, 0xFFFF8A3D, 0xFFFFFFFF),
            new Starter("Alpine Glow", "mountains", 0, CustomTheme.STYLE_GLASS, CustomTheme.SHAPE_SQUIRCLE, 0xFF6E5A7E, 0xFFFFFFFF),
            new Starter("Lost Woods", "zelda", 1, CustomTheme.STYLE_THEMED, CustomTheme.SHAPE_SHIELD, 0xFF1F3F8F, 0xFFFFFFFF),
            new Starter("Three Swords", "onepiece", 1, CustomTheme.STYLE_NEON, CustomTheme.SHAPE_HEXAGON, 0xFF39FF88, 0),
            new Starter("Moonrise", "mountains", 1, CustomTheme.STYLE_OUTLINE, CustomTheme.SHAPE_CIRCLE, 0xFFF2C46B, 0),
            new Starter("Royal Light", "zelda", 2, CustomTheme.STYLE_PASTEL, CustomTheme.SHAPE_LEAF, 0xFFD9B45A, 0),
            new Starter("Straw Hat", "onepiece", 2, CustomTheme.STYLE_STICKER, CustomTheme.SHAPE_FLOWER, 0xFFF2B632, 0),
            new Starter("Midnight Pines", "mountains", 2, CustomTheme.STYLE_MINIMAL, CustomTheme.SHAPE_ROUNDED, 0xFF9C8BC9, 0xFFF3E9FF),
    };

    /** Decodes a picked image and centre-crops it to a PHOTO_SIZE square. */
    @Nullable
    public static Bitmap squarePhoto(File file) {
        Bitmap bmp = ImagePickerHelper.decodeSampled(file, PHOTO_SIZE * 2);
        if (bmp == null) return null;
        int side = Math.min(bmp.getWidth(), bmp.getHeight());
        Bitmap crop = Bitmap.createBitmap(bmp, (bmp.getWidth() - side) / 2, (bmp.getHeight() - side) / 2, side, side);
        Bitmap out = Bitmap.createScaledBitmap(crop, PHOTO_SIZE, PHOTO_SIZE, true);
        if (crop != bmp) crop.recycle();
        if (out != bmp) bmp.recycle();
        return out;
    }

    /** Decodes a stored icon photo. */
    @Nullable
    public static Bitmap loadPhoto(Context context, CustomTheme theme, String name) {
        File f = photo(context, theme, name);
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }
}
