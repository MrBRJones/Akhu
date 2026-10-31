package com.MrBRos.akhu;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

/** The theme's note: any text the user typed in the theme editor. Tapping it opens the editor. */
public class NoteWidgetProvider extends ThemedWidgetProvider {

    @Override
    RemoteViews build(Context context, int widgetId, @Nullable CustomTheme t) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_note);
        v.setTextColor(R.id.note_text, textColor(t));
        String note = t == null ? "" : t.widgetNote.trim();
        v.setTextViewText(R.id.note_text, note.isEmpty() ? context.getString(R.string.note_empty) : note);
        applyCard(context, v, t, widgetId);
        Intent edit = t == null || ThemeStore.isReadOnly(t) ? new Intent(context, MainActivity.class)
                : new Intent(context, ThemeEditorActivity.class).putExtra(ThemePreviewActivity.EXTRA_ID, t.id);
        v.setOnClickPendingIntent(R.id.widget_root, activity(context, 7, edit));
        return v;
    }

    @Override
    int[] defaultSizeDp() {
        return new int[]{240, 150};
    }
}
