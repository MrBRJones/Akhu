package com.MrBRos.akhu;

import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

/** A theme's clock widget: time and date, time alone, or day and date. Tapping it opens the phone's clock. */
public class ThemeClockWidgetProvider extends ThemedWidgetProvider {

    @Override
    RemoteViews build(Context context, int widgetId, @Nullable CustomTheme t) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_clock);
        // Without a theme (or with its clock turned off) it is a clock with the date.
        int style = t == null || t.widget == CustomTheme.WIDGET_NONE ? CustomTheme.WIDGET_CLOCK : t.widget;
        int color = textColor(t);
        boolean bold = t != null && t.widgetBold;
        int big = bold ? R.id.big_bold : R.id.big_light;
        int small = bold ? R.id.small_bold : R.id.small_light;
        v.setViewVisibility(R.id.big_light, bold ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.big_bold, bold ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.small_light, View.GONE);
        v.setViewVisibility(R.id.small_bold, View.GONE);
        if (style != CustomTheme.WIDGET_TIME) v.setViewVisibility(small, View.VISIBLE);
        v.setTextColor(big, color);
        v.setTextColor(small, color);
        if (style == CustomTheme.WIDGET_DATE) {
            v.setCharSequence(big, "setFormat12Hour", "EEEE");
            v.setCharSequence(big, "setFormat24Hour", "EEEE");
            v.setTextViewTextSize(big, TypedValue.COMPLEX_UNIT_SP, 40);
            v.setCharSequence(small, "setFormat12Hour", "MMMM d");
            v.setCharSequence(small, "setFormat24Hour", "MMMM d");
        } else {
            v.setCharSequence(big, "setFormat12Hour", "h:mm");
            v.setCharSequence(big, "setFormat24Hour", "H:mm");
            v.setTextViewTextSize(big, TypedValue.COMPLEX_UNIT_SP, 64);
        }
        applyCard(context, v, t, widgetId);
        v.setOnClickPendingIntent(R.id.widget_root, activity(context, 0, new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        return v;
    }
}
