package com.MrBRos.akhu;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Gallery picking, copying the picked image into the app cache, and sampled decoding. */
public final class ImagePickerHelper {

    private ImagePickerHelper() {
    }

    /** ACTION_PICK needs no storage permission on API 29+. */
    public static Intent createPickIntent() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        return intent;
    }

    /** Copies a content URI into cache/picked/ so it can be decoded repeatedly and shared via FileProvider. */
    public static File copyToCache(Context context, Uri uri) throws IOException {
        File dir = new File(context.getCacheDir(), "picked");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        File out = new File(dir, "picked_" + System.currentTimeMillis() + ".jpg");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    /** Copies a picked video into cache/picked_video/ (kept apart so it never clears the picked image). */
    public static File copyVideoToCache(Context context, Uri uri) throws IOException {
        File dir = new File(context.getCacheDir(), "picked_video");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        File out = new File(dir, "video_" + System.currentTimeMillis() + ".mp4");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    /** Decodes a file so its longest side is at most {@code maxDim}, honoring EXIF rotation. */
    @Nullable
    public static Bitmap decodeSampled(File file, int maxDim) {
        if (file == null || !file.exists()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bmp == null) return null;
        bmp = applyExifRotation(file, bmp);
        int longest = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longest > maxDim) {
            float scale = (float) maxDim / longest;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                    Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        return bmp;
    }

    private static Bitmap applyExifRotation(File file, Bitmap bmp) {
        int degrees;
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            degrees = exif.getRotationDegrees();
        } catch (IOException e) {
            return bmp;
        }
        if (degrees == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (rotated != bmp) bmp.recycle();
        return rotated;
    }
}
