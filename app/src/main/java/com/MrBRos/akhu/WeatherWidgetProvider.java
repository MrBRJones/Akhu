package com.MrBRos.akhu;

import android.Manifest;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Current weather and today's high and low from open-meteo.com (free, no account). Only a rough
 * location goes out: the phone's coarse position rounded to two decimals (about 1 km). Refreshed
 * every 30 minutes by the launcher and when tapped; the last result is kept for drawing.
 */
public class WeatherWidgetProvider extends ThemedWidgetProvider {

    private static final String ACTION_REFRESH = "com.MrBRos.akhu.WEATHER_REFRESH";
    private static final String PREFS = "weather";
    private static final long FRESH = 25 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            fetchAsync(context, true);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        super.onUpdate(context, manager, appWidgetIds);
        fetchAsync(context, false);
    }

    /** Fetches in the background (unless the last result is fresh) and redraws every weather widget. */
    void fetchAsync(Context context, boolean force) {
        Context app = context.getApplicationContext();
        SharedPreferences p = prefs(app);
        if (!force && System.currentTimeMillis() - p.getLong("at", 0) < FRESH) return;
        if (app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        PendingResult result = goAsyncOrNull();
        new Thread(() -> {
            try {
                JSONObject w = fetch(app);
                if (w != null) p.edit().putString("json", w.toString()).putLong("at", System.currentTimeMillis()).apply();
            } catch (IOException | org.json.JSONException ignored) {
                // Offline or the service failed: keep showing the last result.
            }
            updateAllOfKind(app);
            if (result != null) result.finish();
        }, "Weather").start();
    }

    /** goAsync only works inside onReceive; outside it (from the permission screen) just run. */
    @Nullable
    private PendingResult goAsyncOrNull() {
        try {
            return goAsync();
        } catch (IllegalStateException | NullPointerException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Blocking: a coarse location, then the forecast for it. Null without a location. */
    @Nullable
    private static JSONObject fetch(Context context) throws IOException, org.json.JSONException {
        Location loc = location(context);
        if (loc == null) return null;
        boolean fahrenheit = usesFahrenheit();
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                        + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                        + "&forecast_days=1&timezone=auto%s",
                loc.getLatitude(), loc.getLongitude(), fahrenheit ? "&temperature_unit=fahrenheit" : "");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject o = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            o.put("fahrenheit", fahrenheit);
            return o;
        } finally {
            c.disconnect();
        }
    }

    /** The last known coarse location, or a fresh one (waiting up to 10 s). */
    @Nullable
    @SuppressWarnings("MissingPermission")
    private static Location location(Context context) {
        LocationManager lm = context.getSystemService(LocationManager.class);
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && System.currentTimeMillis() - l.getTime() < 6 * 60 * 60 * 1000L) return l;
            } catch (IllegalArgumentException | SecurityException ignored) {
            }
        }
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return null;
        AtomicReference<Location> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, new CancellationSignal(),
                    Executors.newSingleThreadExecutor(), l -> {
                        got.set(l);
                        done.countDown();
                    });
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException | SecurityException | IllegalArgumentException ignored) {
        }
        return got.get();
    }

    private static boolean usesFahrenheit() {
        String country = Locale.getDefault().getCountry();
        return "US".equals(country) || "LR".equals(country) || "MM".equals(country);
    }

    @Override
    RemoteViews build(Context context, int widgetId, @Nullable CustomTheme t) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
        int color = textColor(t);
        v.setTextColor(R.id.weather_temp, color);
        v.setTextColor(R.id.weather_detail, color);
        applyCard(context, v, t, widgetId);
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            v.setTextViewText(R.id.weather_icon, "🌤️");
            v.setTextViewText(R.id.weather_temp, context.getString(R.string.widget_weather));
            v.setTextViewText(R.id.weather_detail, context.getString(R.string.weather_allow));
            v.setOnClickPendingIntent(R.id.widget_root, activity(context, 5,
                    WidgetPermissionActivity.intent(context, Manifest.permission.ACCESS_COARSE_LOCATION)));
            return v;
        }
        v.setOnClickPendingIntent(R.id.widget_root, self(context, 6, ACTION_REFRESH));
        String json = prefs(context).getString("json", null);
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            JSONObject now = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            int code = now.getInt("weather_code");
            boolean day = now.optInt("is_day", 1) == 1;
            v.setTextViewText(R.id.weather_icon, emoji(code, day));
            v.setTextViewText(R.id.weather_temp, Math.round(now.getDouble("temperature_2m")) + "°");
            long hi = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
            long lo = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
            v.setTextViewText(R.id.weather_detail,
                    context.getString(R.string.weather_detail, context.getString(sky(code)), hi, lo));
        } catch (org.json.JSONException e) {
            v.setTextViewText(R.id.weather_icon, "🌤️");
            v.setTextViewText(R.id.weather_temp, context.getString(R.string.widget_weather));
            v.setTextViewText(R.id.weather_detail, context.getString(R.string.weather_loading));
        }
        return v;
    }

    /** WMO weather codes, as open-meteo reports them. */
    private static String emoji(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "🌤️" : "☁️";
        if (code == 3) return "☁️";
        if (code <= 48) return "🌫️";
        if (code <= 57) return "🌦️";
        if (code <= 67) return "🌧️";
        if (code <= 77) return "❄️";
        if (code <= 82) return "🌧️";
        if (code <= 86) return "🌨️";
        return "⛈️";
    }

    private static int sky(int code) {
        if (code == 0) return R.string.sky_clear;
        if (code <= 2) return R.string.sky_partly;
        if (code == 3) return R.string.sky_cloudy;
        if (code <= 48) return R.string.sky_fog;
        if (code <= 57) return R.string.sky_drizzle;
        if (code <= 67) return R.string.sky_rain;
        if (code <= 77) return R.string.sky_snow;
        if (code <= 82) return R.string.sky_showers;
        if (code <= 86) return R.string.sky_snow;
        return R.string.sky_storm;
    }
}
