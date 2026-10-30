package com.MrBRos.akhu;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.ColorResourcesOverride;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Applies the saved light/dark choice and app colors before any screen opens. */
public class App extends Application {

    private static final String PREFS = "app_settings";
    private static final String KEY_NIGHT_MODE = "night_mode";
    private static final String KEY_APP_COLOR = "app_color";
    private static final String KEY_APP_COLOR_2 = "app_color_2";
    private static final String KEY_APP_COLOR_MID = "app_color_mid";

    /** The app colors each open screen was themed with (null = the built-in classic blue). */
    private final Map<Activity, int[]> themedWith = new WeakHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(nightMode(this));
        // Each screen is themed from the chosen colors as it opens; screens opened before a change
        // rebuild themselves when they come back to the front.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle state) {
                int[] colors = appColors(activity);
                themedWith.put(activity, colors);
                if (colors != null && canChangeAppColor()) applyAppColors(activity, colors);
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (themedWith.containsKey(activity) && !Arrays.equals(appColors(activity), themedWith.get(activity))) {
                    activity.recreate();
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
                // Phone screens stay upright; tablets and an unfolded Fold may turn freely
                // (a locked app would be letterboxed there).
                if (activity.getResources().getConfiguration().smallestScreenWidthDp < 600) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
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

    /**
     * The main color builds the screen's whole M3 scheme; the middle and second colors
     * replace the banner gradient and accent colors (the same resource override Material uses for
     * its own content-based colors).
     */
    @SuppressLint("RestrictedApi")
    private static void applyAppColors(Activity activity, int[] colors) {
        DynamicColors.applyToActivityIfAvailable(activity,
                new DynamicColorsOptions.Builder().setContentBasedSource(colors[0]).build());
        ColorResourcesOverride override = ColorResourcesOverride.getInstance();
        if (override != null) {
            Map<Integer, Integer> map = new HashMap<>();
            map.put(R.color.app_color_1, colors[0]);
            map.put(R.color.app_color_mid, colors[1]);
            map.put(R.color.app_color_2, colors[2]);
            override.applyIfPossible(activity, map);
        }
        // Point the app's own colors (banners, text, chips) at the new roles too.
        activity.getTheme().applyStyle(R.style.ThemeOverlay_Akhu_AppColor, true);
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

    /** True when this phone can re-theme the app from custom colors (Android 12+). */
    public static boolean canChangeAppColor() {
        return DynamicColors.isDynamicColorAvailable();
    }

    /**
     * The {main, middle, second} colors the app is themed from, or null for the built-in classic blue.
     * A missing color falls back to the classic one (main), a hue step on from the main (second),
     * or an even blend of those two (middle).
     */
    @Nullable
    public static int[] appColors(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.contains(KEY_APP_COLOR) && !p.contains(KEY_APP_COLOR_2)) return null;
        int main = p.getInt(KEY_APP_COLOR, CLASSIC_MAIN);
        int second = p.contains(KEY_APP_COLOR_2) ? p.getInt(KEY_APP_COLOR_2, 0) : nextHue(main);
        int mid = p.contains(KEY_APP_COLOR_MID) ? p.getInt(KEY_APP_COLOR_MID, 0) : ColorUtils.blendARGB(main, second, 0.5f);
        return new int[]{main, mid, second};
    }

    /**
     * Saves the app colors (all null go back to classic blue; a null middle blends the other two). The current screen re-themes on its
     * next resume, so call recreate() on it; other open screens follow when they come back.
     */
    public static void setAppColors(Context context, @Nullable Integer main, @Nullable Integer mid,
                                    @Nullable Integer second) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (main == null) e.remove(KEY_APP_COLOR);
        else e.putInt(KEY_APP_COLOR, main);
        if (mid == null) e.remove(KEY_APP_COLOR_MID);
        else e.putInt(KEY_APP_COLOR_MID, mid);
        if (second == null) e.remove(KEY_APP_COLOR_2);
        else e.putInt(KEY_APP_COLOR_2, second);
        e.apply();
    }

    /** The classic theme's three colors, shown in the pickers before anything is chosen. */
    public static final int CLASSIC_MAIN = 0xFF2F6BFF;
    public static final int CLASSIC_MID = 0xFF8E44FF;
    public static final int CLASSIC_SECOND = 0xFFFF5E8A;
    /** The gold the classic gradient (and the app icon) ends on. */
    public static final int CLASSIC_TAIL = 0xFFF5B83D;

    /** Roughly classic blue's step to purple, for a main color picked without a second one. */
    private static int nextHue(int color) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[0] = (hsl[0] + 45) % 360;
        return ColorUtils.HSLToColor(hsl);
    }
}
