package com.MrBRos.akhu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;

/** Sets the latest saved image as the static wallpaper (widget tap). */
public class WidgetSetWallpaperReceiver extends BroadcastReceiver {

    public static final String ACTION_SET_WALLPAPER = "com.MrBRos.akhu.SET_WALLPAPER";
    private static final int MAX_DIM = 4096;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SET_WALLPAPER.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        new Thread(() -> {
            int msg;
            try {
                msg = setLatest(app) ? R.string.wallpaper_set : R.string.no_saved_wallpapers;
            } catch (Exception e) {
                msg = R.string.wallpaper_set_failed;
            }
            int finalMsg = msg;
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(app, finalMsg, Toast.LENGTH_SHORT).show();
                result.finish();
            });
        }, "WidgetSetWallpaper").start();
    }

    /** Returns false if there is nothing saved yet. Blocking; call off the main thread. */
    static boolean setLatest(Context context) throws Exception {
        String latest = ImageExporter.getLatestPath(context);
        if (latest == null) return false;
        Bitmap bmp = ImagePickerHelper.decodeSampled(new File(latest), MAX_DIM);
        if (bmp == null) return false;
        try {
            WallpaperHelper.setStaticWallpaper(context, bmp);
        } finally {
            bmp.recycle();
        }
        return true;
    }
}
