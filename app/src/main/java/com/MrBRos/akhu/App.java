package com.MrBRos.akhu;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Applies the saved light/dark choice and app color before any screen opens. */
public class App extends Application {

    private static final String PREFS = "app_settings";
    private static final String KEY_NIGHT_MODE = "night_mode";
    private static final String KEY_APP_COLOR = "app_color";

    /** The app color each open screen was themed with (null = the built-in teal). */
    private final Map<Activity, Integer> themedWith = new WeakHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(nightMode(this));
        // Each screen is themed from the chosen color as it opens; screens opened before a change
        // rebuild themselves when they come back to the front.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle state) {
                Integer color = appColor(activity);
                themedWith.put(activity, color);
                if (color != null) {
                    DynamicColors.applyToActivityIfAvailable(activity,
                            new DynamicColorsOptions.Builder().setContentBasedSource(color).build());
                }
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (themedWith.containsKey(activity) && !Objects.equals(appColor(activity), themedWith.get(activity))) {
                    activity.recreate();
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                themedWith.remove(activity);
            }
        });
    }

    /** One of AppCompatDelegate's MODE_NIGHT_* values; follows the phone's setting by default. */
    public static int nightMode(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /** Saves and applies the choice; open screens recreate themselves in the new mode. */
    public static void setNightMode(Context context, int mode) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_NIGHT_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    /** True when this phone can re-theme the app from a custom color (Android 12+). */
    public static boolean canChangeAppColor() {
        return DynamicColors.isDynamicColorAvailable();
    }

    /** The color the app's theme is built from, or null for the built-in teal. */
    @Nullable
    public static Integer appColor(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.contains(KEY_APP_COLOR) ? p.getInt(KEY_APP_COLOR, 0) : null;
    }

    /**
     * Saves the app color (null goes back to teal). The current screen re-themes on its next
     * resume, so call recreate() on it; other open screens follow when they come back.
     */
    public static void setAppColor(Context context, @Nullable Integer color) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (color == null) e.remove(KEY_APP_COLOR);
        else e.putInt(KEY_APP_COLOR, color);
        e.apply();
    }
}
