package com.MrBRos.akhu;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import java.io.IOException;

/** Static and live wallpaper setters. */
public final class WallpaperHelper {

    private WallpaperHelper() {
    }

    /**
     * Opens the phone's own screen for setting {@code picture}, where it is previewed and the
     * person picks home, lock or both, as live wallpapers are set. A phone without one gets it
     * on both screens at once instead. The picture is copied to share it, off the main thread.
     */
    public static void openSystemSetter(android.app.Activity activity, java.io.File picture) {
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            java.io.File shared;
            try {
                java.io.File dir = new java.io.File(app.getCacheDir(), "wallpaper_set");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
                String name = picture.getName();
                shared = new java.io.File(dir, "wallpaper" + (name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".jpg"));
                java.nio.file.Files.copy(picture.toPath(), shared.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                activity.runOnUiThread(() -> toast(app, R.string.wallpaper_set_failed));
                return;
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", shared);
                // Samsung's own Set as wallpaper screen (preview, then home / lock / both) when
                // there is one; otherwise Android's crop-and-set screen.
                Intent samsung = new Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, "image/*")
                        .setPackage(SAMSUNG_WALLPAPERS).putExtra("mimeType", "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    if (samsung.resolveActivity(activity.getPackageManager()) != null) {
                        activity.startActivity(samsung);
                        return;
                    }
                } catch (android.content.ActivityNotFoundException | SecurityException ignored) {
                }
                try {
                    Intent intent = WallpaperManager.getInstance(app).getCropAndSetWallpaperIntent(uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(intent);
                } catch (IllegalArgumentException | android.content.ActivityNotFoundException e) {
                    setBothNow(app, shared);
                }
            });
        }, "SystemSetter").start();
    }

    /** Samsung's Wallpaper and style app, whose Set as wallpaper screen takes a picture. */
    private static final String SAMSUNG_WALLPAPERS = "com.samsung.android.app.dressroom";

    private static void setBothNow(Context app, java.io.File picture) {
        new Thread(() -> {
            int msg = R.string.wallpaper_set_failed;
            Bitmap bmp = ImagePickerHelper.decodeSampled(picture, 4096);
            if (bmp != null) {
                try {
                    setStaticWallpaper(app, bmp);
                    msg = R.string.wallpaper_set;
                } catch (IOException ignored) {
                } finally {
                    bmp.recycle();
                }
            }
            int finalMsg = msg;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> toast(app, finalMsg));
        }, "SetWallpaperNow").start();
    }

    private static void toast(Context app, int msg) {
        android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Sets home + lock screen. Call off the main thread; large bitmaps take a moment. */
    public static void setStaticWallpaper(Context context, Bitmap bitmap) throws IOException {
        setStaticWallpaper(context, bitmap, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }

    /** {@code which} is a combination of WallpaperManager.FLAG_SYSTEM / FLAG_LOCK. */
    public static void setStaticWallpaper(Context context, Bitmap bitmap, int which) throws IOException {
        setStaticWallpaper(context, bitmap, which, null);
    }

    /**
     * Sets the wallpaper with a crop hint centred on {@code focal} (0..1, image space) for the
     * current screen. When the system adapts the wallpaper to another screen (e.g. a foldable's
     * cover vs inner display) it adjusts around that hint, so the subject stays in frame on both.
     * A null focal keeps the system's default centre crop.
     */
    public static void setStaticWallpaper(Context context, Bitmap bitmap, int which,
                                          @androidx.annotation.Nullable float[] focal) throws IOException {
        WallpaperManager wm = WallpaperManager.getInstance(context);
        if (focal == null) {
            wm.setBitmap(bitmap, null, true, which);
            return;
        }
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = Math.min(dm.widthPixels, dm.heightPixels), h = Math.max(dm.widthPixels, dm.heightPixels);
        wm.setBitmap(bitmap, Focal.cropRect(bitmap.getWidth(), bitmap.getHeight(), w, h, focal[0], focal[1]),
                true, which);
    }

    /** Opens the system live wallpaper preview for this app's service. */
    public static Intent createLiveWallpaperIntent(Context context) {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(context, LiveWallpaperService.class));
        return intent;
    }
}
