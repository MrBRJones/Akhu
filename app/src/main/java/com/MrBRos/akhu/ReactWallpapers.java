package com.MrBRos.akhu;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reactive wallpapers: a cut-out character floating on water (drawn by {@link ReactRenderer}) that
 * drifts as the phone tilts, bounces off the screen edges and ripples the water when touched.
 *
 * <p>Bundled ones live in assets/react/ as N.png (the character, transparent) and N.json (its
 * settings); sources are listed in CREDITS.txt there. The user's own are in files/react_user/ as
 * uN.png, uN.json and, when they chose a photo for the background, uN_bg.jpg. Both are referred to
 * by the character's path (asset path or absolute path).</p>
 */
public final class ReactWallpapers {

    public static final String KEY_CURRENT = "react_current";
    private static final String ROOT = "react";
    private static final String USER_DIR = "react_user";
    /** Longest side the character is kept at. */
    static final int CHARACTER_DIM = 1024;

    /**
     * Backgrounds, by the number saved in a wallpaper's settings (so new ones are added at the
     * end); {@link #STYLE_ORDER} is the order the editor offers them in.
     */
    public static final int STYLE_POOL = 0, STYLE_LAGOON = 1, STYLE_DEEP = 2, STYLE_NIGHT = 3,
            STYLE_POND = 4, STYLE_SUNSET = 5, STYLE_MINT = 6, STYLE_PHOTO = 7, STYLE_SPACE = 8,
            STYLE_LEAVES = 9, STYLE_SNOW = 10, STYLE_SAKURA = 11, STYLE_SKY = 12;
    public static final int[] STYLE_NAMES = {
            R.string.react_style_pool, R.string.react_style_lagoon, R.string.react_style_deep,
            R.string.react_style_night, R.string.react_style_pond, R.string.react_style_sunset,
            R.string.react_style_mint, R.string.react_style_photo, R.string.react_style_space,
            R.string.react_style_leaves, R.string.react_style_snow, R.string.react_style_sakura,
            R.string.react_style_sky,
    };
    /** The editor's order: the waters, the other scenes, then the user's own photo. */
    public static final int[] STYLE_ORDER = {
            STYLE_POOL, STYLE_LAGOON, STYLE_DEEP, STYLE_NIGHT, STYLE_POND, STYLE_SUNSET, STYLE_MINT,
            STYLE_SPACE, STYLE_LEAVES, STYLE_SNOW, STYLE_SAKURA, STYLE_SKY, STYLE_PHOTO,
    };

    /** Scenes the background shader draws; see react_water_fragment.glsl. */
    static final int SCENE_WATER = 0, SCENE_SPACE = 1, SCENE_LEAVES = 2, SCENE_SNOW = 3, SCENE_SAKURA = 4, SCENE_SKY = 5;

    /**
     * Per style: first colour (water: shallow; others: top), second colour (deep / bottom), light
     * colour (caustics, stars, glints) as 0xRRGGBB, whether the floor has pool tiles, and the
     * scene. The photo style uses its light colour for the caustics over the photo.
     */
    static final int[][] STYLES = {
            {0x6FD3F2, 0x2A9FD8, 0xFFFFFF, 1, SCENE_WATER},
            {0x7FE6DA, 0x1FA7B0, 0xFFFDE8, 0, SCENE_WATER},
            {0x2C7FC4, 0x0B2E6B, 0xBFE4FF, 0, SCENE_WATER},
            {0x1D3B66, 0x060E24, 0x9FC3FF, 0, SCENE_WATER},
            {0x6FA35A, 0x23452A, 0xE8F7C8, 0, SCENE_WATER},
            {0xFFB38A, 0xC2457A, 0xFFF0D6, 0, SCENE_WATER},
            {0x9EF0CF, 0x37B59A, 0xFFFFFF, 1, SCENE_WATER},
            {0x000000, 0x000000, 0xFFFFFF, 0, SCENE_WATER},
            {0x3B2A8C, 0x02020A, 0xDDE6FF, 0, SCENE_SPACE},
            {0xF6D6A0, 0xB0643A, 0xFFE9C2, 0, SCENE_LEAVES},
            {0x4D6FA3, 0x0E1A33, 0xFFFFFF, 0, SCENE_SNOW},
            {0xFFE3EE, 0xE892B6, 0xFFFFFF, 0, SCENE_SAKURA},
            {0xD8F0FF, 0x3D8FE0, 0xFFFFFF, 0, SCENE_SKY},
    };

    /** Space is weightless: the character keeps drifting much longer there. */
    static float dragFor(int style) {
        return STYLES[style][4] == SCENE_SPACE ? 0.35f : 1.6f;
    }

    /** One wallpaper's settings. */
    public static final class Spec {
        public int style = STYLE_POOL;
        /** The character's width as a fraction of the screen's. */
        public float size = 0.5f;
        /** The background photo (absolute path) for {@link #STYLE_PHOTO}, else null. */
        @Nullable
        public String photo;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("style", style).put("size", size);
        }
    }

    private ReactWallpapers() {
    }

    /** Bundled ones first (in number order), then the user's own, newest first. */
    public static List<String> list(Context context) {
        List<String> out = new ArrayList<>(bundled(context));
        out.addAll(user(context));
        return out;
    }

    public static List<String> bundled(Context context) {
        List<String> out = new ArrayList<>();
        try {
            String[] files = context.getAssets().list(ROOT);
            if (files == null) return out;
            Arrays.sort(files, (a, b) -> Integer.compare(number(a), number(b)));
            for (String f : files) if (f.matches("\\d+\\.png")) out.add(ROOT + "/" + f);
        } catch (IOException ignored) {
        }
        return out;
    }

    public static List<String> user(Context context) {
        List<String> out = new ArrayList<>();
        File[] files = userDir(context).listFiles((d, f) -> f.matches("u\\d+\\.png"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> Integer.compare(number(b.getName().substring(1)), number(a.getName().substring(1))));
        for (File f : files) out.add(f.getAbsolutePath());
        return out;
    }

    static File userDir(Context context) {
        return new File(context.getFilesDir(), USER_DIR);
    }

    public static boolean isUser(@Nullable String path) {
        return path != null && path.startsWith("/");
    }

    /** True for any reactive wallpaper path, bundled or user-made. */
    public static boolean isReact(@Nullable String path) {
        return path != null && (path.startsWith(ROOT + "/") || path.contains("/" + USER_DIR + "/"));
    }

    private static int number(String name) {
        try {
            return Integer.parseInt(name.substring(0, name.indexOf('.')));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String base(String character) {
        return character.substring(0, character.length() - 4);
    }

    static InputStream open(Context context, String path) throws IOException {
        return isUser(path) ? new java.io.FileInputStream(path) : context.getAssets().open(path);
    }

    /** Glide model for the character image. */
    public static Object model(String character) {
        return isUser(character) ? new File(character)
                : android.net.Uri.parse("file:///android_asset/" + character);
    }

    public static Spec spec(Context context, String character) {
        Spec s = new Spec();
        try (InputStream in = open(context, base(character) + ".json")) {
            JSONObject j = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            s.style = Math.max(0, Math.min(STYLES.length - 1, j.optInt("style", STYLE_POOL)));
            s.size = (float) Math.max(0.2, Math.min(0.9, j.optDouble("size", 0.5)));
        } catch (IOException | JSONException ignored) {
            // Defaults.
        }
        if (s.style == STYLE_PHOTO) {
            File bg = new File(base(character) + "_bg.jpg");
            if (isUser(character) && bg.exists()) s.photo = bg.getAbsolutePath();
            else s.style = STYLE_POOL;
        }
        return s;
    }

    /** The character, at most {@link #CHARACTER_DIM} on its longest side; null if unreadable. */
    @Nullable
    public static Bitmap loadCharacter(Context context, String character) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = open(context, character)) {
            BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= CHARACTER_DIM) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        try (InputStream in = open(context, character)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Saves a new reactive wallpaper the user made and returns its character path. The character
     * is trimmed to its visible pixels; the photo (if any) becomes the background.
     */
    public static String save(Context context, Bitmap character, Spec spec, @Nullable File photo) throws IOException {
        File dir = userDir(context);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        String stem = "u" + System.currentTimeMillis();
        File png = new File(dir, stem + ".png");
        Bitmap trimmed = trim(character);
        try (OutputStream os = new FileOutputStream(png)) {
            trimmed.compress(Bitmap.CompressFormat.PNG, 100, os);
        } finally {
            if (trimmed != character) trimmed.recycle();
        }
        if (spec.style == STYLE_PHOTO && photo != null) {
            Bitmap bg = ImagePickerHelper.decodeSampled(photo, 2048);
            if (bg == null) throw new IOException("Cannot read " + photo);
            try (OutputStream os = new FileOutputStream(new File(dir, stem + "_bg.jpg"))) {
                bg.compress(Bitmap.CompressFormat.JPEG, 92, os);
            } finally {
                bg.recycle();
            }
        }
        try (OutputStream os = new FileOutputStream(new File(dir, stem + ".json"))) {
            os.write(spec.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return png.getAbsolutePath();
    }

    /** Changes a saved one's water and size (the character stays). */
    public static void update(String character, Spec spec, @Nullable File photo) throws IOException {
        String stem = base(character);
        if (spec.style == STYLE_PHOTO && photo != null && !photo.getAbsolutePath().equals(stem + "_bg.jpg")) {
            Bitmap bg = ImagePickerHelper.decodeSampled(photo, 2048);
            if (bg == null) throw new IOException("Cannot read " + photo);
            try (OutputStream os = new FileOutputStream(stem + "_bg.jpg")) {
                bg.compress(Bitmap.CompressFormat.JPEG, 92, os);
            } finally {
                bg.recycle();
            }
        }
        try (OutputStream os = new FileOutputStream(stem + ".json")) {
            os.write(spec.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Deletes one of the user's own (bundled ones can't be). */
    public static void delete(Context context, String character) {
        if (!isUser(character)) return;
        String stem = base(character);
        for (String ext : new String[]{".png", ".json", "_bg.jpg"}) //noinspection ResultOfMethodCallIgnored
            new File(stem + ext).delete();
        ReactPoster.forget(context, character);
    }

    /** Crops away fully transparent rows and columns. */
    static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 8) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return b;
        int pad = Math.round(Math.max(w, h) * 0.01f);
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad);
        maxY = Math.min(h - 1, maxY + pad);
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return b;
        return Bitmap.createBitmap(b, minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * Where the character turns and floats from, as a fraction of its width and height: the
     * centre of its visible pixels (for a float ring, roughly the ring's centre), not of the image.
     */
    static float[] pivot(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int step = Math.max(1, Math.max(w, h) / 128);
        double sx = 0, sy = 0, sum = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int a = b.getPixel(x, y) >>> 24;
                if (a < 32) continue;
                sx += x * (double) a;
                sy += y * (double) a;
                sum += a;
            }
        }
        if (sum == 0) return new float[]{0.5f, 0.5f};
        return new float[]{(float) (sx / sum / w), (float) (sy / sum / h)};
    }

    /** Makes it the reactive wallpaper and opens the system screen to set it. Premium only. */
    public static void apply(Activity activity, String character) {
        if (!PremiumGate.check(activity, R.string.react_premium_message)) return;
        Context app = activity.getApplicationContext();
        EffectConfig.prefs(app).edit().putString(KEY_CURRENT, character).apply();
        ReactWallpaperService.requestReload(app);
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(app, ReactWallpaperService.class));
        try {
            activity.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(app, R.string.live_picker_missing, Toast.LENGTH_SHORT).show();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
