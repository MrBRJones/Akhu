package com.MrBRos.akhu;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Home screen shortcuts that carry a theme's icons. Android has no API for changing another app's
 * icon, so (like other theme apps) each themed icon is a pinned shortcut that launches the app.
 */
public final class ThemeShortcuts {

    /** Shortcut icon bitmap size; launchers scale it to their icon size. */
    private static final int ICON_PX = 192;

    private ThemeShortcuts() {
    }

    /** One id per app, shared by all themes, so applying another theme restyles the same icon. */
    static String id(CustomTheme.App app) {
        return "app_" + Integer.toHexString((app.pkg + '/' + app.activity).hashCode());
    }

    public static ShortcutInfoCompat build(Context context, CustomTheme theme, CustomTheme.App app) {
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        String label = app.label.isEmpty() ? app.pkg : app.label;
        return new ShortcutInfoCompat.Builder(context, id(app))
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithBitmap(ThemeIcons.render(context, theme, app, ICON_PX)))
                .setIntent(launch)
                .build();
    }

    /** Ids of this app's shortcuts already on the home screen. */
    public static Set<String> pinnedIds(Context context) {
        Set<String> out = new HashSet<>();
        for (ShortcutInfoCompat s : ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)) {
            out.add(s.getId());
        }
        return out;
    }

    /**
     * Restyles the theme's apps that are already pinned (no prompt needed) and returns the apps
     * that still have to be added. Call off the main thread: it renders every icon.
     */
    public static List<CustomTheme.App> updatePinned(Context context, CustomTheme theme) {
        Set<String> pinned = pinnedIds(context);
        List<ShortcutInfoCompat> updates = new ArrayList<>();
        List<CustomTheme.App> missing = new ArrayList<>();
        for (CustomTheme.App a : theme.apps) {
            if (pinned.contains(id(a))) updates.add(build(context, theme, a));
            else missing.add(a);
        }
        if (!updates.isEmpty()) ShortcutManagerCompat.updateShortcuts(context, updates);
        return missing;
    }
}
