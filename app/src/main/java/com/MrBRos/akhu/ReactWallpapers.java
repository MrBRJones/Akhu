package com.MrBRos.akhu;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import org.json.JSONArray;
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
    private static final String CLOUD_DIR = "react_cloud";
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

    /** Effects layered over the scene, as bits in a wallpaper's settings. */
    public static final int EFFECT_BUBBLES = 1, EFFECT_SPARKLES = 2, EFFECT_FIREFLIES = 4, EFFECT_RAIN = 8,
            EFFECT_RAYS = 16, EFFECT_GLOW = 32;
    /** The editor's order, with names. */
    public static final int[] EFFECTS = {EFFECT_BUBBLES, EFFECT_SPARKLES, EFFECT_FIREFLIES, EFFECT_RAIN, EFFECT_RAYS, EFFECT_GLOW};
    public static final int[] EFFECT_NAMES = {
            R.string.react_effect_bubbles, R.string.react_effect_sparkles, R.string.react_effect_fireflies,
            R.string.react_effect_rain, R.string.react_effect_rays, R.string.react_effect_glow,
    };

    /**
     * The touch layer: how touches, bounces and the wake show on the scene. Water ripples are the
     * first; the others replace them. Indexes are stored in settings and posts, so only add.
     */
    public static final int[] LAYER_NAMES = {
            R.string.react_layer_water, R.string.react_layer_jelly, R.string.react_layer_glass,
            R.string.react_layer_heat, R.string.react_layer_shockwave, R.string.react_layer_neon,
            R.string.react_layer_ink, R.string.react_layer_sparks, R.string.react_layer_electric,
            R.string.react_layer_pixel,
    };

    /** One wallpaper's settings. */
    public static final class Spec {
        public int style = STYLE_POOL;
        /** Each character's width as a fraction of the screen's; one entry per character. */
        public float[] sizes = {0.5f};
        /** {@code EFFECT_*} bits. */
        public int effects;
        /** The touch layer, an index into {@link #LAYER_NAMES}; 0 is water. */
        public int layer;
        /** How much the characters squash and stretch when they hit something, 0 (none) to 1. */
        public float squish = DEFAULT_SQUISH;
        public static final float DEFAULT_SQUISH = 0.4f;
        /** The background photo (absolute path) for {@link #STYLE_PHOTO}, else null. */
        @Nullable
        public String photo;

        public int count() {
            return sizes.length;
        }

        JSONObject toJson() throws JSONException {
            JSONArray s = new JSONArray();
            for (float v : sizes) s.put((double) v);
            // "size" keeps older versions of the app reading the first character's size.
            return new JSONObject().put("style", style).put("size", (double) sizes[0]).put("sizes", s).put("effects", effects)
                    .put("layer", layer).put("squish", (double) squish);
        }

        static Spec fromJson(JSONObject j) {
            Spec s = new Spec();
            s.style = Math.max(0, Math.min(STYLES.length - 1, j.optInt("style", STYLE_POOL)));
            JSONArray a = j.optJSONArray("sizes");
            if (a != null && a.length() > 0) {
                s.sizes = new float[Math.min(ReactPhysics.MAX_BODIES, a.length())];
                for (int i = 0; i < s.sizes.length; i++) s.sizes[i] = clampSize(a.optDouble(i, 0.5));
            } else {
                s.sizes = new float[]{clampSize(j.optDouble("size", 0.5))};
            }
            s.effects = j.optInt("effects", 0);
            s.layer = clampLayer(j.optInt("layer", 0));
            s.squish = clampSquish(j.optDouble("squish", DEFAULT_SQUISH));
            return s;
        }

        static float clampSquish(double v) {
            return Double.isNaN(v) ? DEFAULT_SQUISH : (float) Math.max(0, Math.min(1, v));
        }

        static int clampLayer(int v) {
            return v >= 0 && v < LAYER_NAMES.length ? v : 0;
        }

        static float clampSize(double v) {
            return (float) Math.max(0.15, Math.min(0.9, v));
        }
    }

    /**
     * One character while it is being made: the picture it was cut from and how much of each pixel
     * is kept (0..255), so the cut can be brushed again later.
     */
    public static final class Piece {
        public final Bitmap source;
        public byte[] mask;

        public Piece(Bitmap source, byte[] mask) {
            this.source = source;
            this.mask = mask;
        }

        /** A picture that is already a cut-out: its own transparency is the mask. */
        public static Piece ofCutout(Bitmap b) {
            Bitmap src = b.getConfig() == Bitmap.Config.ARGB_8888 ? b : b.copy(Bitmap.Config.ARGB_8888, false);
            int w = src.getWidth(), h = src.getHeight();
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);
            byte[] m = new byte[w * h];
            for (int i = 0; i < px.length; i++) m[i] = (byte) (px[i] >>> 24);
            return new Piece(src, m);
        }

        /**
         * The character: the picture with the mask as its transparency, trimmed to what's kept.
         * Edge pixels take the colour of the solid pixels next to them, so a dark or bright
         * background doesn't leave a rim. Null when nothing is kept.
         */
        @Nullable
        public Bitmap cut() {
            int w = source.getWidth(), h = source.getHeight();
            int[] px = new int[w * h];
            source.getPixels(px, 0, w, 0, 0, w, h);
            int[] rgb = new int[w * h];
            boolean[] solid = new boolean[w * h];
            for (int i = 0; i < px.length; i++) {
                rgb[i] = px[i] & 0xFFFFFF;
                solid[i] = (mask[i] & 0xFF) >= 235 && (px[i] >>> 24) >= 235;
            }
            // Bleed solid colours a few pixels outward into the soft edge.
            for (int pass = 0; pass < 3; pass++) {
                boolean[] next = solid.clone();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = y * w + x;
                        if (solid[i] || (mask[i] & 0xFF) == 0) continue;
                        int r = 0, g = 0, b = 0, n = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            int yy = y + dy;
                            if (yy < 0 || yy >= h) continue;
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx;
                                if (xx < 0 || xx >= w || !solid[yy * w + xx]) continue;
                                int c = rgb[yy * w + xx];
                                r += (c >> 16) & 255;
                                g += (c >> 8) & 255;
                                b += c & 255;
                                n++;
                            }
                        }
                        if (n == 0) continue;
                        rgb[i] = ((r / n) << 16) | ((g / n) << 8) | (b / n);
                        next[i] = true;
                    }
                }
                solid = next;
            }
            int[] out = new int[w * h];
            for (int i = 0; i < out.length; i++) {
                // A picture that was already a cut-out never shows more than it had.
                int a = Math.min(mask[i] & 0xFF, px[i] >>> 24);
                if (a < 8) continue;
                out[i] = (a << 24) | rgb[i];
            }
            Bitmap full = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
            Bitmap trimmed = trim(full);
            if (trimmed == full && isEmpty(full)) {
                full.recycle();
                return null;
            }
            if (trimmed != full) full.recycle();
            return trimmed;
        }
    }

    private static boolean isEmpty(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int p : px) if ((p >>> 24) > 8) return false;
        return true;
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

    /** True for a video background (an .mp4), which plays behind the water instead of a photo. */
    public static boolean isVideo(@Nullable String path) {
        return path != null && path.toLowerCase(java.util.Locale.US).endsWith(".mp4");
    }

    /** The background as a picture: the photo, or a video's first frame. Blocking; null if unreadable. */
    @Nullable
    static Bitmap backgroundStill(String path, int maxDim) {
        if (!isVideo(path)) return ImagePickerHelper.decodeSampled(new File(path), maxDim);
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            return mmr.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxDim, maxDim);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                mmr.release();
            } catch (IOException ignored) {
            }
        }
    }

    /** Gives {@code renderer} the background of {@code s}: its video, its photo or neither. Blocking. */
    static void loadBackground(ReactRenderer renderer, Spec s) {
        String path = s.style == STYLE_PHOTO ? s.photo : null;
        if (isVideo(path)) {
            renderer.setPhoto(null);
            renderer.setVideo(path);
        } else {
            renderer.setVideo(null);
            renderer.setPhoto(path == null ? null : ImagePickerHelper.decodeSampled(new File(path), 2048));
        }
    }

    public static boolean isUser(@Nullable String path) {
        return path != null && path.startsWith("/");
    }

    /** True for any reactive wallpaper path, bundled or user-made. */
    public static boolean isReact(@Nullable String path) {
        return path != null && (path.startsWith(ROOT + "/") || path.contains("/" + USER_DIR + "/")
                || path.contains("/" + CLOUD_DIR + "/"));
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
            s = Spec.fromJson(new JSONObject(new String(readAll(in), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException ignored) {
            // Defaults.
        }
        // Only the characters whose files are there (a bundled one has just the one).
        int n = 1;
        while (n < s.sizes.length && isUser(character) && new File(piecePath(character, n)).exists()) n++;
        if (n < s.sizes.length) s.sizes = Arrays.copyOf(s.sizes, n);
        if (s.style == STYLE_PHOTO) {
            File bg = new File(base(character) + "_bg.mp4");
            if (!bg.exists()) bg = new File(base(character) + "_bg.jpg");
            if (isUser(character) && bg.exists()) s.photo = bg.getAbsolutePath();
            else s.style = STYLE_POOL;
        }
        return s;
    }

    /** Character {@code i}'s image path: the wallpaper's own path for the first, uN_c2.png and on for the rest. */
    static String piecePath(String character, int i) {
        return i == 0 ? character : base(character) + "_c" + (i + 1) + ".png";
    }

    /** All its characters (as many as its settings have), each at most {@link #CHARACTER_DIM}; unreadable ones are skipped. */
    public static List<Bitmap> loadCharacters(Context context, String character, Spec spec) {
        List<Bitmap> out = new ArrayList<>();
        for (int i = 0; i < spec.count(); i++) {
            Bitmap b = loadCharacter(context, piecePath(character, i));
            if (b != null) out.add(b);
        }
        return out;
    }

    /** An image, at most {@link #CHARACTER_DIM} on its longest side, as ARGB_8888; null if unreadable. */
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
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = open(context, character)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The pieces to edit a saved one again: each character's source picture and mask when they
     * were kept, otherwise the character itself as an already cut-out picture.
     */
    public static List<Piece> loadPieces(Context context, String character, Spec spec) {
        List<Piece> out = new ArrayList<>();
        for (int i = 0; i < spec.count(); i++) {
            String path = piecePath(character, i);
            String stem = base(path);
            Piece p = null;
            if (isUser(path)) {
                File src = new File(stem + "_src.png");
                if (!src.exists()) src = new File(stem + "_src.jpg");
                File mask = new File(stem + "_mask.png");
                if (src.exists() && mask.exists()) {
                    Bitmap s = loadCharacter(context, src.getAbsolutePath());
                    Bitmap m = loadCharacter(context, mask.getAbsolutePath());
                    if (s != null && m != null && m.getWidth() == s.getWidth() && m.getHeight() == s.getHeight()) {
                        int[] px = new int[s.getWidth() * s.getHeight()];
                        m.getPixels(px, 0, m.getWidth(), 0, 0, m.getWidth(), m.getHeight());
                        byte[] bytes = new byte[px.length];
                        for (int k = 0; k < px.length; k++) bytes[k] = (byte) ((px[k] >> 16) & 255);
                        p = new Piece(s, bytes);
                    } else if (s != null) {
                        s.recycle();
                    }
                    if (m != null) m.recycle();
                }
            }
            if (p == null) {
                Bitmap c = loadCharacter(context, path);
                if (c != null) p = Piece.ofCutout(c);
            }
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * Saves a reactive wallpaper the user made, new ({@code existing} null) or over one of theirs,
     * and returns its path. Each piece's character is written trimmed, with its source picture
     * and mask for later edits; the photo (if any) becomes the background.
     */
    public static String save(Context context, @Nullable String existing, List<Piece> pieces, Spec spec,
                              @Nullable File photo) throws IOException {
        if (pieces.isEmpty()) throw new IOException("No characters");
        String character;
        if (existing != null && isUser(existing) && existing.contains("/" + USER_DIR + "/")) {
            character = existing;
        } else {
            File dir = userDir(context);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            character = new File(dir, "u" + System.currentTimeMillis() + ".png").getAbsolutePath();
        }
        String stem = base(character);
        float[] sizes = new float[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            String path = piecePath(character, i);
            String ps = base(path);
            Bitmap cut = p.cut();
            if (cut == null) throw new IOException("Empty cut-out");
            try {
                write(cut, Bitmap.CompressFormat.PNG, 100, path);
            } finally {
                cut.recycle();
            }
            boolean alpha = p.source.hasAlpha();
            write(p.source, alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, ps + (alpha ? "_src.png" : "_src.jpg"));
            //noinspection ResultOfMethodCallIgnored
            new File(ps + (alpha ? "_src.jpg" : "_src.png")).delete();
            Bitmap mask = maskBitmap(p);
            try {
                write(mask, Bitmap.CompressFormat.PNG, 100, ps + "_mask.png");
            } finally {
                mask.recycle();
            }
            sizes[i] = i < spec.sizes.length ? spec.sizes[i] : 0.4f;
        }
        // Characters removed in this edit.
        for (int i = pieces.size(); i < ReactPhysics.MAX_BODIES; i++) deletePiece(piecePath(character, i));
        Spec out = new Spec();
        out.style = spec.style;
        out.sizes = sizes;
        out.effects = spec.effects;
        out.layer = spec.layer;
        out.squish = spec.squish;
        boolean video = isVideo(photo != null ? photo.getAbsolutePath() : null);
        String bgPath = stem + (video ? "_bg.mp4" : "_bg.jpg");
        if (spec.style == STYLE_PHOTO && photo != null && !photo.getAbsolutePath().equals(bgPath)) {
            if (video) {
                java.nio.file.Files.copy(photo.toPath(), new File(bgPath).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Bitmap bg = ImagePickerHelper.decodeSampled(photo, 2048);
                if (bg == null) throw new IOException("Cannot read " + photo);
                try {
                    write(bg, Bitmap.CompressFormat.JPEG, 92, bgPath);
                } finally {
                    bg.recycle();
                }
            }
            // Only one background: the other kind goes.
            //noinspection ResultOfMethodCallIgnored
            new File(stem + (video ? "_bg.jpg" : "_bg.mp4")).delete();
        }
        try (OutputStream os = new FileOutputStream(stem + ".json")) {
            os.write(out.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        ReactPoster.forget(context, character);
        return character;
    }

    private static Bitmap maskBitmap(Piece p) {
        int w = p.source.getWidth(), h = p.source.getHeight();
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int a = p.mask[i] & 0xFF;
            px[i] = 0xFF000000 | (a << 16) | (a << 8) | a;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void write(Bitmap b, Bitmap.CompressFormat format, int quality, String path) throws IOException {
        try (OutputStream os = new FileOutputStream(path)) {
            if (!b.compress(format, quality, os)) throw new IOException("Cannot write " + path);
        }
    }

    private static void deletePiece(String path) {
        String stem = base(path);
        for (String ext : new String[]{".png", "_src.png", "_src.jpg", "_mask.png"}) //noinspection ResultOfMethodCallIgnored
            new File(stem + ext).delete();
    }

    /** Deletes one of the user's own (bundled ones can't be). */
    public static void delete(Context context, String character) {
        if (!isUser(character)) return;
        for (int i = 0; i < ReactPhysics.MAX_BODIES; i++) deletePiece(piecePath(character, i));
        String stem = base(character);
        for (String ext : new String[]{".json", "_bg.jpg", "_bg.mp4"}) //noinspection ResultOfMethodCallIgnored
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

    /**
     * The outline the physics bumps with: the convex hull of the visible pixels, as x, y pairs in
     * fractions of the image, at most 32 points. Null when nothing is visible.
     */
    @Nullable
    static float[] hull(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int step = Math.max(1, Math.max(w, h) / 96);
        // Each sampled row's leftmost and rightmost visible pixel (both edges of the row band).
        List<float[]> pts = new ArrayList<>();
        int[] row = new int[w];
        for (int y = 0; y < h; y += step) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            int l = -1, r = -1;
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) < 48) continue;
                if (l < 0) l = x;
                r = x;
            }
            if (l < 0) continue;
            float top = (float) y / h, bottom = Math.min(1f, (float) (y + step) / h);
            pts.add(new float[]{(float) l / w, top});
            pts.add(new float[]{(float) l / w, bottom});
            pts.add(new float[]{(float) (r + 1) / w, top});
            pts.add(new float[]{(float) (r + 1) / w, bottom});
        }
        if (pts.size() < 3) return null;
        // Monotone chain.
        pts.sort((p, q) -> p[0] != q[0] ? Float.compare(p[0], q[0]) : Float.compare(p[1], q[1]));
        float[][] chain = new float[pts.size() * 2][];
        int k = 0;
        for (float[] p : pts) {
            while (k >= 2 && cross(chain[k - 2], chain[k - 1], p) <= 0) k--;
            chain[k++] = p;
        }
        for (int i = pts.size() - 2, t = k + 1; i >= 0; i--) {
            float[] p = pts.get(i);
            while (k >= t && cross(chain[k - 2], chain[k - 1], p) <= 0) k--;
            chain[k++] = p;
        }
        k--;
        if (k < 3) return null;
        // Thinning a convex outline only ever cuts corners a little; 32 points is plenty.
        int keep = Math.min(32, k);
        float[] out = new float[keep * 2];
        for (int i = 0; i < keep; i++) {
            float[] p = chain[(int) ((long) i * k / keep)];
            out[i * 2] = p[0];
            out[i * 2 + 1] = p[1];
        }
        return out;
    }

    private static float cross(float[] o, float[] a, float[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** A posted one's files kept on this phone, in files/react_cloud/{postId}/. */
    static File cloudDir(Context context, String postId) {
        return new File(new File(context.getFilesDir(), CLOUD_DIR), postId.replaceAll("[^A-Za-z0-9_-]", "_"));
    }

    /** True for a posted one downloaded to this phone (not the user's to edit). */
    public static boolean isCloud(@Nullable String path) {
        return path != null && path.contains("/" + CLOUD_DIR + "/");
    }

    /** True for a post whose background is a video (its URL names an .mp4 file, before the query). */
    public static boolean hasVideoBackground(CloudWallpaper wp) {
        return wp.reactBackground != null
                && wp.reactBackground.split("\\?")[0].toLowerCase(java.util.Locale.US).endsWith(".mp4");
    }

    /**
     * Downloads a posted reactive wallpaper (its characters, settings and background) to this
     * phone and returns its path, ready for {@link #apply}. Blocking; reuses an earlier download.
     */
    public static String downloadCloud(Context context, CloudWallpaper wp) throws IOException {
        return downloadCloud(cloudDir(context, wp.id), wp);
    }

    /** Posted ones kept for the grid at most; the least recently shown go first. */
    private static final int MAX_PREVIEWS = 30;

    /**
     * Downloads a posted reactive wallpaper just to show it in the grid. It goes to the app's
     * cache, not its files: the newest {@link #MAX_PREVIEWS} are kept, and Android may clear them
     * when the phone is low on space. One already downloaded to be set is used as it is. Blocking.
     */
    public static String downloadPreview(Context context, CloudWallpaper wp) throws IOException {
        File kept = cloudDir(context, wp.id);
        if (new File(kept, "u.json").exists()) return new File(kept, "u.png").getAbsolutePath();
        File root = new File(context.getCacheDir(), CLOUD_DIR);
        File dir = new File(root, kept.getName());
        String character = downloadCloud(dir, wp);
        //noinspection ResultOfMethodCallIgnored
        dir.setLastModified(System.currentTimeMillis());
        trimPreviews(root);
        return character;
    }

    private static synchronized void trimPreviews(File root) {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length <= MAX_PREVIEWS) return;
        Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = MAX_PREVIEWS; i < dirs.length; i++) {
            File[] files = dirs[i].listFiles();
            //noinspection ResultOfMethodCallIgnored
            if (files != null) for (File f : files) f.delete();
            //noinspection ResultOfMethodCallIgnored
            dirs[i].delete();
        }
    }

    private static String downloadCloud(File dir, CloudWallpaper wp) throws IOException {
        String character = new File(dir, "u.png").getAbsolutePath();
        File json = new File(dir, "u.json");
        if (json.exists()) return character;
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        Spec spec = wp.reactSpec();
        int n = Math.min(wp.layers.size(), ReactPhysics.MAX_BODIES);
        if (n == 0) throw new IOException("No characters");
        for (int i = 0; i < n; i++) download(wp.layers.get(i), new File(piecePath(character, i)));
        if (spec.count() > n) spec.sizes = Arrays.copyOf(spec.sizes, n);
        if (spec.style == STYLE_PHOTO) {
            // A video's URL names its .mp4 file (before the query); it may be up to 100 MB.
            if (wp.reactBackground != null) {
                boolean video = hasVideoBackground(wp);
                download(wp.reactBackground, new File(dir, video ? "u_bg.mp4" : "u_bg.jpg"),
                        (video ? 100L : 20L) * 1024 * 1024);
            }
            else spec.style = STYLE_POOL;
        }
        // The settings go last: their presence marks a finished download.
        try (OutputStream os = new FileOutputStream(json)) {
            os.write(spec.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return character;
    }

    private static void download(String url, File to) throws IOException {
        download(url, to, 20L * 1024 * 1024);
    }

    private static void download(String url, File to, long maxBytes) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int r;
            long total = 0;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > maxBytes) throw new IOException("Too big");
                os.write(buf, 0, r);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            throw e;
        } finally {
            c.disconnect();
        }
    }

    /**
     * Asks home / lock / both, makes it the reactive wallpaper there and opens the system screen
     * to set it. Premium only.
     */
    public static void apply(Activity activity, String character) {
        if (!PremiumGate.check(activity, R.string.react_premium_message)) return;
        Context app = activity.getApplicationContext();
        WallpaperTarget.choose(activity, which -> {
            WallpaperTarget.remember(app, KEY_CURRENT, which, character);
            ReactWallpaperService.requestReload(app);
            WallpaperTarget.openLivePicker(activity, ReactWallpaperService.class,
                    ReactWallpaperService.Lock.class, which);
        });
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
