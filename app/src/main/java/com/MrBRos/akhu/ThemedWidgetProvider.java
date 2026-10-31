package com.MrBRos.akhu;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

/**
 * Base for Akhu's home screen widgets (clock, music, battery, next event, app shortcuts, weather,
 * note, countdown). Every one is styled by a theme's widget settings: the {@link WidgetCard}
 * behind it and the text colour. A widget placed by One-Tap Setup follows the theme it was added
 * with; one added from the launcher's widget list follows the theme set up most recently.
 *
 * <p>Each layout has a {@code widget_root} and a {@code widget_card} image behind the content.</p>
 */
public abstract class ThemedWidgetProvider extends AppWidgetProvider {

    /** Sent back by the launcher once a widget asked for by One-Tap Setup is placed. */
    static final String ACTION_PINNED = "com.MrBRos.akhu.WIDGET_PINNED";
    static final String EXTRA_THEME_ID = "theme_id";
    static final String EXTRA_SEQ = "seq";
    private static final String PREFS = "theme_widgets";
    private static final String KEY_LAST = "last";

    /** One of each kind, for redrawing them all. */
    static ThemedWidgetProvider[] all() {
        return new ThemedWidgetProvider[]{
                new ThemeClockWidgetProvider(), new MusicWidgetProvider(), new BatteryWidgetProvider(),
                new EventWidgetProvider(), new AppsWidgetProvider(), new WeatherWidgetProvider(),
                new NoteWidgetProvider(), new CountdownWidgetProvider(),
        };
    }

    /** The widget's views for [theme] (null: no theme yet, use plain white). */
    abstract RemoteViews build(Context context, int widgetId, @Nullable CustomTheme theme);

    /** Size used for the card before the launcher reports one, in dp. */
    int[] defaultSizeDp() {
        return new int[]{320, 160};
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_PINNED.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            String theme = intent.getStringExtra(EXTRA_THEME_ID);
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && theme != null) {
                prefs(context).edit().putString("w" + id, theme).apply();
                update(context, AppWidgetManager.getInstance(context), id);
            }
            // Lets One-Tap Setup move on to the next widget.
            context.sendBroadcast(new Intent(ThemePreviewActivity.ACTION_PINNED).setPackage(context.getPackageName())
                    .putExtra(EXTRA_SEQ, intent.getIntExtra(EXTRA_SEQ, -1)));
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) update(context, manager, id);
    }

    /** Resized: the card is redrawn to the new size. */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        update(context, manager, id);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences.Editor e = prefs(context).edit();
        for (int id : appWidgetIds) e.remove("w" + id);
        e.apply();
    }

    void update(Context context, AppWidgetManager manager, int id) {
        manager.updateAppWidget(id, build(context, id, themeFor(context, id)));
    }

    /** Redraws every placed widget of this kind. */
    void updateAllOfKind(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, getClass()))) update(context, manager, id);
    }

    /** Redraws every placed Akhu widget, e.g. after a theme is edited. */
    static void updateAll(Context context) {
        for (ThemedWidgetProvider p : all()) p.updateAllOfKind(context);
    }

    /** Remembers [themeId] as the one unassigned widgets follow, and redraws them all. */
    static void setLastTheme(Context context, String themeId) {
        prefs(context).edit().putString(KEY_LAST, themeId).apply();
        updateAll(context);
    }

    /**
     * Asks the launcher to place a [kind] widget styled by [theme]; [seq] comes back to One-Tap
     * Setup when it's placed. Returns false when the launcher can't pin widgets.
     */
    static boolean requestPin(Context context, Class<? extends ThemedWidgetProvider> kind, CustomTheme theme, int seq) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (!manager.isRequestPinAppWidgetSupported()) return false;
        Intent cb = new Intent(context, kind).setAction(ACTION_PINNED)
                .putExtra(EXTRA_THEME_ID, theme.id).putExtra(EXTRA_SEQ, seq);
        // Mutable so the launcher can add the new widget's id.
        PendingIntent pi = PendingIntent.getBroadcast(context, seq, cb,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        return manager.requestPinAppWidget(new ComponentName(context, kind), null, pi);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    static CustomTheme themeFor(Context context, int widgetId) {
        SharedPreferences p = prefs(context);
        String id = p.getString("w" + widgetId, p.getString(KEY_LAST, null));
        return id == null ? null : ThemeStore.load(context, id);
    }

    static int textColor(@Nullable CustomTheme t) {
        return t == null ? 0xFFFFFFFF : t.widgetColor;
    }

    /**
     * Draws the theme's card behind the widget at its current size (portrait: its min width by
     * max height), at most 900 px wide so it stays well under the widget size limit.
     */
    void applyCard(Context context, RemoteViews v, @Nullable CustomTheme t, int widgetId) {
        Bitmap card = null;
        if (t != null && WidgetCard.has(t)) {
            Bundle o = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
            int wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            if (wDp <= 0 || hDp <= 0) {
                int[] d = defaultSizeDp();
                wDp = d[0];
                hDp = d[1];
            }
            float scale = Math.min(context.getResources().getDisplayMetrics().density, 900f / wDp);
            card = WidgetCard.render(context, t, Math.round(wDp * scale), Math.round(hDp * scale));
        }
        v.setViewVisibility(R.id.widget_card, card != null ? View.VISIBLE : View.GONE);
        if (card != null) v.setImageViewBitmap(R.id.widget_card, card);
    }

    static PendingIntent activity(Context context, int request, Intent intent) {
        return PendingIntent.getActivity(context, request, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** A broadcast back to this widget kind, e.g. a music button. */
    PendingIntent self(Context context, int request, String action) {
        return PendingIntent.getBroadcast(context, request, new Intent(context, getClass()).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Tints an ImageView in the widget to the text colour. */
    static void tint(RemoteViews v, int id, int color) {
        v.setInt(id, "setColorFilter", color);
    }
}
