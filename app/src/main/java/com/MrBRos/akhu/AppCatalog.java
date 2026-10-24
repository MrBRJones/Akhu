package com.MrBRos.akhu;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Launchable apps on this device (visible through the manifest's &lt;queries&gt; launcher entry). */
public final class AppCatalog {

    /** Package fragments of everyday apps, used to pick a sensible default set for a new theme. */
    private static final String[] FAVOURITES = {
            "dialer", "messag", "camera", "chrome", "gallery", "photos", "settings", "vending",
            "youtube", "maps", "android.gm", "spotify", "calendar", "clock", "calculator", "whatsapp",
            "instagram", "tiktok", "musically", "discord", "netflix", "snapchat",
    };

    private AppCatalog() {
    }

    /** Every launchable app except this one, sorted by label. Call off the main thread. */
    public static List<CustomTheme.App> all(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<CustomTheme.App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo == null || context.getPackageName().equals(ri.activityInfo.packageName)) continue;
            out.add(new CustomTheme.App(ri.activityInfo.packageName, ri.activityInfo.name,
                    String.valueOf(ri.loadLabel(pm))));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    /** Up to {@code count} everyday apps, in FAVOURITES order, topped up alphabetically. */
    public static List<CustomTheme.App> defaults(Context context, int count) {
        List<CustomTheme.App> all = all(context);
        List<CustomTheme.App> out = new ArrayList<>();
        for (String fav : FAVOURITES) {
            for (CustomTheme.App a : all) {
                if (out.size() >= count) return out;
                if (a.pkg.toLowerCase(Locale.ROOT).contains(fav) && !contains(out, a)) {
                    out.add(a);
                    break;
                }
            }
        }
        for (CustomTheme.App a : all) {
            if (out.size() >= count) break;
            if (!contains(out, a)) out.add(a);
        }
        return out;
    }

    static boolean contains(List<CustomTheme.App> list, CustomTheme.App app) {
        for (CustomTheme.App a : list) {
            if (a.pkg.equals(app.pkg) && a.activity.equals(app.activity)) return true;
        }
        return false;
    }
}
