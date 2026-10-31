package com.MrBRos.akhu;

import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import java.util.Calendar;

/** Days until the theme's countdown date ("12 / days until Christmas"); "Today!" on the day. */
public class CountdownWidgetProvider extends ThemedWidgetProvider {

    @Override
    RemoteViews build(Context context, int widgetId, @Nullable CustomTheme t) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_countdown);
        int color = textColor(t);
        v.setTextColor(R.id.countdown_days, color);
        v.setTextColor(R.id.countdown_label, color);
        applyCard(context, v, t, widgetId);
        if (t == null || t.countdownDate == 0) {
            v.setTextViewText(R.id.countdown_days, "⏳");
            v.setTextViewText(R.id.countdown_label, context.getString(R.string.countdown_empty));
        } else {
            long days = daysUntil(t.countdownDate);
            String what = t.countdownTitle.trim().isEmpty() ? context.getString(R.string.countdown_default) : t.countdownTitle.trim();
            if (days == 0) {
                v.setTextViewText(R.id.countdown_days, context.getString(R.string.countdown_today));
                v.setTextViewText(R.id.countdown_label, what);
            } else if (days > 0) {
                v.setTextViewText(R.id.countdown_days, String.valueOf(days));
                v.setTextViewText(R.id.countdown_label, context.getResources()
                        .getQuantityString(R.plurals.countdown_until, (int) Math.min(days, Integer.MAX_VALUE), what));
            } else {
                v.setTextViewText(R.id.countdown_days, String.valueOf(-days));
                v.setTextViewText(R.id.countdown_label, context.getResources()
                        .getQuantityString(R.plurals.countdown_since, (int) Math.min(-days, Integer.MAX_VALUE), what));
            }
        }
        Intent edit = t == null || ThemeStore.isReadOnly(t) ? new Intent(context, MainActivity.class)
                : new Intent(context, ThemeEditorActivity.class).putExtra(ThemePreviewActivity.EXTRA_ID, t.id);
        v.setOnClickPendingIntent(R.id.widget_root, activity(context, 8, edit));
        return v;
    }

    /** Whole days from today to [day] (a local midnight), negative once it's passed. */
    static long daysUntil(long day) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return Math.round((day - today.getTimeInMillis()) / (double) DateUtils.DAY_IN_MILLIS);
    }

    @Override
    int[] defaultSizeDp() {
        return new int[]{150, 150};
    }
}
