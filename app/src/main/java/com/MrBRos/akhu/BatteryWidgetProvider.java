package com.MrBRos.akhu;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

/** Battery level as a ring with the percentage and whether it's charging. */
public class BatteryWidgetProvider extends ThemedWidgetProvider {

    private static final int RING_PX = 240;

    @Override
    RemoteViews build(Context context, int widgetId, @Nullable CustomTheme t) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_battery);
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = bm.isCharging();
        int color = textColor(t);
        v.setTextColor(R.id.battery_level, color);
        v.setTextColor(R.id.battery_state, color);
        v.setTextViewText(R.id.battery_level, context.getString(R.string.percent, level));
        v.setTextViewText(R.id.battery_state, context.getString(charging ? R.string.battery_charging : R.string.battery));
        v.setImageViewBitmap(R.id.battery_ring, ring(level, color));
        applyCard(context, v, t, widgetId);
        v.setOnClickPendingIntent(R.id.widget_root, activity(context, 2, new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
        return v;
    }

    /** A faint full ring with the level drawn over it from the top, clockwise. */
    private static Bitmap ring(int level, int color) {
        Bitmap b = Bitmap.createBitmap(RING_PX, RING_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float stroke = RING_PX * 0.08f;
        RectF r = new RectF(stroke, stroke, RING_PX - stroke, RING_PX - stroke);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor((color & 0x00FFFFFF) | 0x40000000);
        c.drawOval(r, p);
        p.setColor(color);
        c.drawArc(r, -90, 360f * Math.max(0, Math.min(100, level)) / 100f, false, p);
        return b;
    }

    @Override
    int[] defaultSizeDp() {
        return new int[]{150, 150};
    }
}
