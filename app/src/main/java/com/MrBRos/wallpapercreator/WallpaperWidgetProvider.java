package com.MrBRos.wallpapercreator;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

import java.io.File;

/** 1x1 widget showing the latest saved wallpaper; tapping it sets that image as the wallpaper. */
public class WallpaperWidgetProvider extends AppWidgetProvider {

    private static final int THUMB_DIM = 256;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        RemoteViews views = buildViews(context);
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, views);
        }
    }

    /** Refreshes every placed widget, e.g. after the saved list changes. */
    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, WallpaperWidgetProvider.class));
        if (ids.length == 0) return;
        RemoteViews views = buildViews(context);
        for (int id : ids) {
            manager.updateAppWidget(id, views);
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_wallpaper);
        String latest = ImageExporter.getLatestPath(context);
        Bitmap thumb = latest == null ? null : ImagePickerHelper.decodeSampled(new File(latest), THUMB_DIM);
        if (thumb != null) {
            views.setImageViewBitmap(R.id.widget_image, thumb);
        } else {
            views.setImageViewResource(R.id.widget_image, R.drawable.ic_wallpaper);
        }
        Intent tap = new Intent(context, WidgetSetWallpaperReceiver.class)
                .setAction(WidgetSetWallpaperReceiver.ACTION_SET_WALLPAPER);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        return views;
    }
}
