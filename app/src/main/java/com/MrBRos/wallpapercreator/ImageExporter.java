package com.MrBRos.wallpapercreator;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Saves images to the MediaStore gallery, keeps the app's own "saved wallpapers"
 * list (private copies under files/saved, tracked in SharedPreferences) and shares files.
 */
public final class ImageExporter {

    private static final String KEY_SAVED_PATHS = "saved_paths";
    private static final int JPEG_QUALITY = 90;

    private ImageExporter() {
    }

    /** Inserts a JPEG into Pictures/WallpaperCreator via scoped storage. */
    public static Uri saveToGallery(Context context, Bitmap bitmap) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "wallpaper_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WallpaperCreator");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");
        try (OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)) {
                throw new IOException("JPEG compress failed");
            }
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    /** Writes a private copy and adds it to the saved-wallpapers strip. */
    public static File saveLocalCopy(Context context, Bitmap bitmap) throws IOException {
        File dir = new File(context.getFilesDir(), "saved");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File out = new File(dir, "wp_" + System.currentTimeMillis() + ".jpg");
        try (OutputStream os = new FileOutputStream(out)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)) {
                throw new IOException("JPEG compress failed");
            }
        }
        SharedPreferences sp = EffectConfig.prefs(context);
        Set<String> paths = new HashSet<>(sp.getStringSet(KEY_SAVED_PATHS, Collections.emptySet()));
        paths.add(out.getAbsolutePath());
        sp.edit().putStringSet(KEY_SAVED_PATHS, paths).apply();
        onSavedListChanged(context);
        return out;
    }

    /** Saved paths, newest first; files that no longer exist are skipped. */
    public static List<String> getSavedPaths(Context context) {
        Set<String> set = EffectConfig.prefs(context).getStringSet(KEY_SAVED_PATHS, Collections.emptySet());
        List<String> list = new ArrayList<>();
        for (String p : set) {
            if (new File(p).exists()) list.add(p);
        }
        // File names embed the save timestamp, so reverse name order is newest first.
        list.sort((a, b) -> new File(b).getName().compareTo(new File(a).getName()));
        return list;
    }

    public static String getLatestPath(Context context) {
        List<String> list = getSavedPaths(context);
        return list.isEmpty() ? null : list.get(0);
    }

    public static void removeSaved(Context context, String path) {
        SharedPreferences sp = EffectConfig.prefs(context);
        Set<String> paths = new HashSet<>(sp.getStringSet(KEY_SAVED_PATHS, Collections.emptySet()));
        paths.remove(path);
        SharedPreferences.Editor editor = sp.edit().putStringSet(KEY_SAVED_PATHS, paths);
        if (path.equals(sp.getString(EffectConfig.KEY_CURRENT_PATH, null))) {
            editor.remove(EffectConfig.KEY_CURRENT_PATH);
        }
        editor.apply();
        //noinspection ResultOfMethodCallIgnored
        new File(path).delete();
        onSavedListChanged(context);
    }

    private static void onSavedListChanged(Context context) {
        WallpaperWidgetProvider.updateAll(context);
        ShortcutHelper.updateShortcut(context);
    }

    public static Uri getShareUri(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }

    /** Inserts an MP4 into Movies/WallpaperCreator via scoped storage. */
    public static Uri saveVideoToGallery(Context context, File video) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "motion_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WallpaperCreator");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");
        try (OutputStream os = resolver.openOutputStream(uri);
             java.io.InputStream in = new java.io.FileInputStream(video)) {
            if (os == null) throw new IOException("Cannot open " + uri);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    public static Intent createVideoShareIntent(Context context, File file) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/mp4");
        send.putExtra(Intent.EXTRA_STREAM, getShareUri(context, file));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(send, context.getString(R.string.share_chooser));
    }

    public static Intent createShareIntent(Context context, File file) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, getShareUri(context, file));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(send, context.getString(R.string.share_chooser));
    }
}
