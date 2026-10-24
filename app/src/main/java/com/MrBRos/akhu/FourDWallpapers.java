package com.MrBRos.akhu;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 4D (layered parallax) wallpapers bundled in assets/fourd/: N.jpg is the flat image (thumbnail,
 * save, share) and N_l0.jpg / N_l1.webp / N_l2.webp are its far, mid and near layers, cut from an
 * AI depth map with the hidden areas inpainted. Sources are listed in CREDITS.txt there.
 *
 * <p>4D images the user makes with {@link FourDMaker} use the same naming in files/fourd_user/
 * ("uN.jpg") and are referred to by absolute path instead of asset path.
 */
public final class FourDWallpapers {

    public static final String KEY_CURRENT = "fourd_current_asset";
    private static final String ROOT = "fourd";
    static final String USER_DIR = "fourd_user";
    /** Per-image motion settings file next to a 4D image, see {@link #motion}. */
    static final String MOTION_SUFFIX = "_motion.txt";
    /** {strength multiplier, mid layer factor}: the look of the bundled images. */
    public static final float[] DEFAULT_MOTION = {1f, FourDRenderer.FACTOR_MID};

    private FourDWallpapers() {
    }

    /**
     * The user's own 4D images (absolute paths, newest first), then the bundled image asset
     * paths, e.g. "fourd/1.jpg", in numeric order.
     */
    public static List<String> list(Context context) {
        List<String> out = new ArrayList<>();
        File[] mine = userDir(context).listFiles((d, f) -> f.matches("u\\d+\\.jpg"));
        if (mine != null) {
            Arrays.sort(mine, (a, b) -> b.getName().compareTo(a.getName()));
            // A half-written set (no near layer yet) isn't listed.
            for (File f : mine) {
                if (new File(layersFor(f.getAbsolutePath())[2]).exists()) out.add(f.getAbsolutePath());
            }
        }
        List<String> bundled = new ArrayList<>();
        try {
            String[] files = context.getAssets().list(ROOT);
            if (files != null) {
                for (String f : files) {
                    // Only "N.jpg"; the far layer "N_l0.jpg" sits in the same folder.
                    if (f.matches("\\d+\\.jpg")) bundled.add(ROOT + "/" + f);
                }
            }
        } catch (IOException ignored) {
        }
        bundled.sort((a, b) -> Integer.compare(number(a), number(b)));
        out.addAll(bundled);
        return out;
    }

    /** Where {@link FourDMaker} keeps the user's 4D images. */
    static File userDir(Context context) {
        return new File(context.getFilesDir(), USER_DIR);
    }

    /** True for a user-made 4D image (a file path) rather than a bundled asset. */
    public static boolean isUser(String imagePath) {
        return imagePath.startsWith("/");
    }

    /** True for any 4D image path, bundled or user-made. */
    public static boolean isFourD(@Nullable String path) {
        return path != null && (path.startsWith(ROOT + "/") || path.contains("/" + USER_DIR + "/"));
    }

    /** Image model (Glide / ImageView) for a 4D image path. */
    public static Uri uri(String imagePath) {
        return isUser(imagePath) ? Uri.fromFile(new File(imagePath))
                : Uri.parse("file:///android_asset/" + imagePath);
    }

    /** Opens a 4D image or layer, from files or assets. */
    public static InputStream open(Context context, String path) throws IOException {
        return isUser(path) ? new FileInputStream(path) : context.getAssets().open(path);
    }

    /** Deletes a user-made 4D image and its layers; bundled ones are left alone. */
    public static void delete(Context context, String imagePath) {
        if (!isUser(imagePath)) return;
        new File(imagePath).delete();
        for (String l : layersFor(imagePath)) new File(l).delete();
        new File(base(imagePath) + "_l3.webp").delete();
        new File(base(imagePath) + "_l3.txt").delete();
        new File(base(imagePath) + "_mask.png").delete();
        new File(base(imagePath) + "_subj.png").delete();
        new File(base(imagePath) + MOTION_SUFFIX).delete();
        android.content.SharedPreferences prefs = EffectConfig.prefs(context);
        if (imagePath.equals(prefs.getString(KEY_CURRENT, null))) prefs.edit().remove(KEY_CURRENT).apply();
    }

    private static int number(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        try {
            return Integer.parseInt(name.substring(0, name.indexOf('.')));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Layer assets for an image: far (opaque jpg), mid and near (RGBA webp cut-outs). */
    public static String[] layersFor(String imageAsset) {
        String base = imageAsset.substring(0, imageAsset.length() - 4);
        return new String[]{base + "_l0.jpg", base + "_l1.webp", base + "_l2.webp"};
    }

    /**
     * Decodes the far/mid/near layers plus the optional extra cut-out N_l3.webp (index 3, null if
     * the image has none), drawn between mid and near. Blocking; call off the main thread.
     */
    @Nullable
    public static Bitmap[] load(Context context, String imageAsset) {
        return load(context, imageAsset, 1);
    }

    /** As {@link #load(Context, String)}, downsampled by {@code sampleSize} (power of two). */
    @Nullable
    public static Bitmap[] load(Context context, String imageAsset, int sampleSize) {
        String[] paths = layersFor(imageAsset);
        Bitmap[] out = new Bitmap[4];
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = sampleSize;
        for (int i = 0; i < 3; i++) {
            try (InputStream in = open(context, paths[i])) {
                out[i] = BitmapFactory.decodeStream(in, null, opts);
            } catch (IOException e) {
                out[i] = null;
            }
            if (out[i] == null) {
                for (Bitmap b : out) if (b != null) b.recycle();
                return null;
            }
        }
        try (InputStream in = open(context, base(imageAsset) + "_l3.webp")) {
            out[3] = BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException ignored) {
            // No extra layer.
        }
        return out;
    }

    /**
     * Motion settings from N_motion.txt: {strength multiplier, mid layer factor}, set in
     * {@link FourDEditorActivity}. {@link #DEFAULT_MOTION} (a copy) when there's none.
     */
    public static float[] motion(Context context, String imagePath) {
        try (InputStream in = open(context, base(imagePath) + MOTION_SUFFIX)) {
            java.util.Scanner sc = new java.util.Scanner(in, "UTF-8").useLocale(java.util.Locale.US);
            return new float[]{sc.nextFloat(), sc.nextFloat()};
        } catch (IOException | RuntimeException e) {
            return DEFAULT_MOTION.clone();
        }
    }

    /**
     * How the extra layer moves, from N_l3.txt: {factor, angle in degrees}. Its slide is the tilt
     * times {@code factor} (like the other layers), turned by {@code angle}. Null if none.
     */
    @Nullable
    public static float[] extraMotion(Context context, String imageAsset) {
        try (InputStream in = open(context, base(imageAsset) + "_l3.txt")) {
            java.util.Scanner sc = new java.util.Scanner(in, "UTF-8").useLocale(java.util.Locale.US);
            return new float[]{sc.nextFloat(), sc.nextFloat()};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The extra layer's offset for a base tilt offset ({@code ox, oy} in uv units). The turn is
     * done in image pixels, since a uv unit spans a different number of pixels on each axis.
     */
    public static void extraOffset(float[] motion, float ox, float oy, int imageW, int imageH, float[] out) {
        double a = Math.toRadians(motion[1]);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        float px = ox * imageW, py = oy * imageH;
        out[0] = motion[0] * (c * px + s * py) / imageW;
        out[1] = motion[0] * (-s * px + c * py) / imageH;
    }

    private static String base(String imageAsset) {
        return imageAsset.substring(0, imageAsset.length() - 4);
    }

    /** Points the 4D engine at an asset and opens the system wallpaper preview. */
    public static void apply(Activity activity, String imageAsset) {
        Context app = activity.getApplicationContext();
        EffectConfig.prefs(app).edit().putString(KEY_CURRENT, imageAsset).apply();
        FourDWallpaperService.requestReload(app);
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(app, FourDWallpaperService.class));
        try {
            activity.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(app, R.string.live_picker_missing, Toast.LENGTH_SHORT).show();
        }
    }
}
