package com.MrBRos.akhu;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * The next event in the phone's calendars over the coming two weeks: when, what and where.
 * Tapping it opens the event. Needs calendar permission, which the widget asks for when tapped.
 */
public class EventWidgetProvider extends ThemedWidgetProvider {

    private static final long WINDOW = 14 * DateUtils.DAY_IN_MILLIS;

    @Override
    RemoteViews build(Context context, int widgetId, @Nullable CustomTheme t) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_event);
        int color = textColor(t);
        for (int id : new int[]{R.id.event_when, R.id.event_title, R.id.event_where}) v.setTextColor(id, color);
        applyCard(context, v, t, widgetId);
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            v.setTextViewText(R.id.event_when, context.getString(R.string.widget_event));
            v.setTextViewText(R.id.event_title, context.getString(R.string.event_allow));
            v.setViewVisibility(R.id.event_where, View.GONE);
            v.setOnClickPendingIntent(R.id.widget_root, activity(context, 3,
                    WidgetPermissionActivity.intent(context, Manifest.permission.READ_CALENDAR)));
            return v;
        }
        long now = System.currentTimeMillis();
        Uri.Builder range = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(range, now);
        ContentUris.appendId(range, now + WINDOW);
        String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.EVENT_ID};
        try (Cursor c = context.getContentResolver().query(range.build(), cols,
                CalendarContract.Instances.END + " > ?", new String[]{String.valueOf(now)},
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (c != null && c.moveToFirst()) {
                String title = c.getString(0);
                long begin = c.getLong(1);
                boolean allDay = c.getInt(2) != 0;
                String where = c.getString(3);
                long eventId = c.getLong(4);
                v.setTextViewText(R.id.event_when, when(context, begin, allDay));
                v.setTextViewText(R.id.event_title, title == null || title.isEmpty()
                        ? context.getString(R.string.event_untitled) : title);
                boolean hasWhere = where != null && !where.isEmpty();
                v.setViewVisibility(R.id.event_where, hasWhere ? View.VISIBLE : View.GONE);
                if (hasWhere) v.setTextViewText(R.id.event_where, where);
                Uri event = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
                v.setOnClickPendingIntent(R.id.widget_root, activity(context, 4, new Intent(Intent.ACTION_VIEW, event)));
                return v;
            }
        } catch (RuntimeException ignored) {
            // A calendar provider that fails the query: show the empty state.
        }
        v.setTextViewText(R.id.event_when, context.getString(R.string.widget_event));
        v.setTextViewText(R.id.event_title, context.getString(R.string.event_none));
        v.setViewVisibility(R.id.event_where, View.GONE);
        Uri time = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(String.valueOf(now)).build();
        v.setOnClickPendingIntent(R.id.widget_root, activity(context, 4, new Intent(Intent.ACTION_VIEW, time)));
        return v;
    }

    /** "Today · 3:00 PM", "Tomorrow · All day", "Mon, Nov 3 · 3:00 PM". */
    private static String when(Context context, long begin, boolean allDay) {
        // All-day events are stored as UTC midnight; read their day in UTC.
        Calendar day = Calendar.getInstance(allDay ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
        day.setTimeInMillis(begin);
        Calendar today = Calendar.getInstance();
        int diff = dayIndex(day) - dayIndex(today);
        String d = diff == 0 ? context.getString(R.string.today)
                : diff == 1 ? context.getString(R.string.tomorrow)
                : DateFormat.format("EEE, MMM d", day).toString();
        String time = allDay ? context.getString(R.string.all_day)
                : DateFormat.getTimeFormat(context).format(new Date(begin));
        return d + " · " + time;
    }

    private static int dayIndex(Calendar c) {
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR);
    }
}
