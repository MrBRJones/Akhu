package com.MrBRos.akhu;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.Collections;

/** Dynamic "Set Latest Wallpaper" launcher shortcut, shown once something has been saved. */
public final class ShortcutHelper {

    private static final String SHORTCUT_ID = "set_latest";

    private ShortcutHelper() {
    }

    public static void updateShortcut(Context context) {
        if (ImageExporter.getLatestPath(context) == null) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, Collections.singletonList(SHORTCUT_ID));
            return;
        }
        ShortcutManagerCompat.pushDynamicShortcut(context, build(context));
    }

    public static ShortcutInfoCompat build(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(MainActivity.ACTION_SET_LATEST)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel(context.getString(R.string.shortcut_short))
                .setLongLabel(context.getString(R.string.shortcut_long))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_wallpaper))
                .setIntent(intent)
                .build();
    }
}
