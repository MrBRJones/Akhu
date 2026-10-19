package com.MrBRos.wallpapercreator;

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
