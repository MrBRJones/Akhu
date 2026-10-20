package com.MrBRos.wallpapercreator;

import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/** Applies the saved light/dark choice before any screen opens. */
public class App extends Application {

    private static final String PREFS = "app_settings";
    private static final String KEY_NIGHT_MODE = "night_mode";

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(nightMode(this));
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
}
