package com.MrBRos.akhu;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Looping video wallpapers bundled in assets/motion/ as N.mp4 with an N.jpg poster frame
 * (sources listed in CREDITS.txt there), plus helpers to apply one via {@link VideoWallpaperService}.
 */
public final class MotionWallpapers {

    public static final String KEY_VIDEO_PATH = "video_wallpaper_path";
    private static final String ROOT = "motion";

    private MotionWallpapers() {
    }

    /** Asset paths of the bundled videos, e.g. "motion/1.mp4". */
    public static List<String> list(Context context) {
        List<String> out = new ArrayList<>();
        try {
            String[] files = context.getAssets().list(ROOT);
            if (files == null) return out;
            Arrays.sort(files, (a, b) -> Integer.compare(number(a), number(b)));
            for (String f : files) {
                if (f.endsWith(".mp4")) out.add(ROOT + "/" + f);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static int number(String name) {
        try {
            return Integer.parseInt(name.substring(0, name.indexOf('.')));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    public static String posterFor(String videoAsset) {
        return videoAsset.substring(0, videoAsset.length() - 4) + ".jpg";
    }

    /** Low-res animated WebP loop shown in the grid so tiles move without running 9 video decoders. */
    public static String previewFor(String videoAsset) {
        return videoAsset.substring(0, videoAsset.length() - 4) + ".webp";
    }

    /** Copies an asset to {@code dest} (via a temp file so a playing copy is never half-written). */
    public static File copyAsset(Context context, String asset, File dest) throws IOException {
        File dir = dest.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(dir, dest.getName() + ".tmp");
        try (InputStream in = context.getAssets().open(asset); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) throw new IOException("rename failed");
        return dest;
    }

    /** Where the user's own downloaded motion videos are kept for the Profile tab. */
    private static File savedDir(Context context) {
        return new File(context.getFilesDir(), "saved_motion");
    }

    /** Keeps a private copy of a video the user made, named by time so newest sorts first. */
    public static void keepCopy(Context context, File video) throws IOException {
        File dir = savedDir(context);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(dir, "m.tmp");
        java.nio.file.Files.copy(video.toPath(), tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (!tmp.renameTo(new File(dir, "m" + System.currentTimeMillis() + ".mp4"))) throw new IOException("rename failed");
    }

    /** The user's kept motion videos (absolute paths), newest first. */
    public static List<String> saved(Context context) {
        List<String> out = new ArrayList<>();
        File[] files = savedDir(context).listFiles((d, f) -> f.matches("m\\d+\\.mp4"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (File f : files) out.add(f.getAbsolutePath());
        return out;
    }

    /** Writes the video to {@code dest}; runs off the main thread. */
    interface VideoSource {
        void writeTo(Context app, File dest) throws IOException;
    }

    /** Copies the video into private storage, points the engine at it and opens the system picker. */
    public static void apply(Activity activity, String videoAsset) {
        apply(activity, (app, dest) -> copyAsset(app, videoAsset, dest), null);
    }

    /** Same as {@link #apply(Activity, String)} for a cloud video; {@code onDone} runs on the UI thread. */
    public static void applyFromUrl(Activity activity, String url, @Nullable Runnable onDone) {
        apply(activity, (app, dest) -> download(url, dest), onDone);
    }

    /** Same as {@link #apply(Activity, String)} for a video file on the phone, e.g. one from the motion editor. */
    public static void applyFile(Activity activity, File video, @Nullable Runnable onDone) {
        apply(activity, (app, dest) -> {
            File dir = dest.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File tmp = new File(dir, dest.getName() + ".tmp");
            java.nio.file.Files.copy(video.toPath(), tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!tmp.renameTo(dest)) throw new IOException("rename failed");
        }, onDone);
    }

    private static void download(String url, File dest) throws IOException {
        File dir = dest.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(dir, dest.getName() + ".tmp");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(dest)) throw new IOException("rename failed");
    }

    private static void apply(Activity activity, VideoSource source, @Nullable Runnable onDone) {
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            try {
                // A new file name each time lets a running engine notice the change.
                File dir = new File(app.getFilesDir(), "motion");
                File dest = new File(dir, "current_" + System.currentTimeMillis() + ".mp4");
                source.writeTo(app, dest);
                String old = EffectConfig.prefs(app).getString(KEY_VIDEO_PATH, null);
                EffectConfig.prefs(app).edit().putString(KEY_VIDEO_PATH, dest.getAbsolutePath()).commit();
                if (old != null && !old.equals(dest.getAbsolutePath())) {
                    // Keep the previous file until the engine has switched; clean up older ones.
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (!f.getAbsolutePath().equals(old) && !f.equals(dest)) //noinspection ResultOfMethodCallIgnored
                                f.delete();
                        }
                    }
                }
                activity.runOnUiThread(() -> {
                    VideoWallpaperService.requestReload(app);
                    if (onDone != null) onDone.run();
                    if (activity.isFinishing()) return;
                    Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    new ComponentName(app, VideoWallpaperService.class));
                    try {
                        activity.startActivity(intent);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(app, R.string.live_picker_missing, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                activity.runOnUiThread(() -> {
                    if (onDone != null) onDone.run();
                    Toast.makeText(app, R.string.save_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }, "ApplyMotion").start();
    }
}
