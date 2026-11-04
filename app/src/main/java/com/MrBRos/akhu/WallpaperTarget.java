package com.MrBRos.akhu;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.util.function.IntConsumer;

/**
 * Home / lock / both choice for setting a wallpaper.
 *
 * <p>Each live wallpaper type has a second service for the lock screen (a subclass that reads its
 * own "current" setting, the key plus {@link #LOCK_SUFFIX}). With one service per type, a lock
 * screen wallpaper and a home screen one of the same type would share one setting, so setting
 * either changed both.
 */
public final class WallpaperTarget {

    public static final int HOME = WallpaperManager.FLAG_SYSTEM;
    public static final int LOCK = WallpaperManager.FLAG_LOCK;
    public static final int BOTH = HOME | LOCK;

    static final String LOCK_SUFFIX = "_lock";

    private WallpaperTarget() {
    }

    /** The setting that holds the current wallpaper for {@code which}: the lock key for lock only. */
    public static String key(String key, int which) {
        return which == LOCK ? key + LOCK_SUFFIX : key;
    }

    /**
     * The setting a running engine reads: the lock key in a {@code Lock} service, and also in a
     * home service the system runs on the lock screen alone (Android 14+, e.g. after "Home and
     * lock" was later split by setting just the home screen) once a lock choice is saved.
     */
    static String engineKey(Context context, String key, int target, android.service.wallpaper.WallpaperService.Engine engine) {
        if (target == LOCK) return key + LOCK_SUFFIX;
        if (android.os.Build.VERSION.SDK_INT >= 34 && !engine.isPreview()
                // Samsung adds display bits (cover screen, main screen), so only home/lock count.
                && (engine.getWallpaperFlags() & BOTH) == LOCK
                && EffectConfig.prefs(context).contains(key + LOCK_SUFFIX)) {
            return key + LOCK_SUFFIX;
        }
        return key;
    }

    /**
     * Saves {@code value} as the current wallpaper for {@code which}. "Both" fills both settings,
     * and "home" first keeps the old value for the lock screen when it had none, so a lock screen
     * still showing the home service keeps what it showed.
     */
    static void remember(Context context, String key, int which, String value) {
        android.content.SharedPreferences prefs = EffectConfig.prefs(context);
        android.content.SharedPreferences.Editor edit = prefs.edit();
        String lockKey = key + LOCK_SUFFIX;
        if (which == LOCK) {
            edit.putString(lockKey, value);
        } else {
            if (which == BOTH) {
                edit.putString(lockKey, value);
            } else if (!prefs.contains(lockKey) && prefs.getString(key, null) != null) {
                edit.putString(lockKey, prefs.getString(key, null));
            }
            edit.putString(key, value);
        }
        edit.commit();
    }

    /** Clears {@code value} from the home and lock settings, for a deleted wallpaper. */
    static void forget(Context context, String key, String value) {
        android.content.SharedPreferences prefs = EffectConfig.prefs(context);
        android.content.SharedPreferences.Editor edit = prefs.edit();
        if (value.equals(prefs.getString(key, null))) edit.remove(key);
        if (value.equals(prefs.getString(key + LOCK_SUFFIX, null))) edit.remove(key + LOCK_SUFFIX);
        edit.apply();
    }

    /**
     * Where a live wallpaper goes. The app no longer asks: Set goes straight to the system's own
     * screen, which asks home / lock / both itself, so this is always BOTH, the system's default.
     */
    public static void choose(Activity activity, IntConsumer onPick) {
        choose(activity, onPick, null);
    }

    /** Same as {@link #choose(Activity, IntConsumer)}; nothing is asked, so it is never cancelled. */
    public static void choose(Activity activity, IntConsumer onPick, @androidx.annotation.Nullable Runnable onCancel) {
        onPick.accept(BOTH);
    }

    /**
     * Opens the system live wallpaper preview on {@code home}'s service, or {@code lock}'s when
     * the wallpaper is for the lock screen only. The system screen asks home / lock / both again;
     * picking the same there keeps the other screen as it was.
     */
    public static void openLivePicker(Activity activity, Class<?> home, Class<?> lock, int which) {
        Context app = activity.getApplicationContext();
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(app, which == LOCK ? lock : home));
        try {
            activity.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(app, R.string.live_picker_missing, Toast.LENGTH_SHORT).show();
        }
    }
}
