package com.MrBRos.akhu;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

/** A row of the theme's first five apps in its icon style; each opens its app. */
public class AppsWidgetProvider extends ThemedWidgetProvider {

    private static final int[] SLOTS = {R.id.app_0, R.id.app_1, R.id.app_2, R.id.app_3, R.id.app_4};
    private static final int ICON_PX = 160;

    @Override
    RemoteViews build(Context context, int widgetId, @Nullable CustomTheme t) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_apps);
        applyCard(context, v, t, widgetId);
        for (int i = 0; i < SLOTS.length; i++) {
            CustomTheme.App app = t == null || i >= t.apps.size() ? null : t.apps.get(i);
            if (app == null) {
                // Without a theme yet the first slot shows Akhu, to open it.
                if (i == 0 && t == null) {
                    v.setImageViewResource(SLOTS[0], R.mipmap.ic_launcher_round);
                    v.setOnClickPendingIntent(SLOTS[0], activity(context, 20,
                            new Intent(context, MainActivity.class)));
                } else {
                    v.setViewVisibility(SLOTS[i], View.GONE);
                }
                continue;
            }
            v.setViewVisibility(SLOTS[i], View.VISIBLE);
            v.setImageViewBitmap(SLOTS[i], ThemeIcons.render(context, t, app, ICON_PX));
            v.setContentDescription(SLOTS[i], app.label);
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component());
            v.setOnClickPendingIntent(SLOTS[i], activity(context, 20 + i, launch));
        }
        return v;
    }

    @Override
    int[] defaultSizeDp() {
        return new int[]{320, 90};
    }
}
