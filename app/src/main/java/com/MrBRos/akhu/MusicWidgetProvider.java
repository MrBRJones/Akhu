package com.MrBRos.akhu;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Now playing, from whichever app is playing (Spotify, YouTube Music, ...): album art, song,
 * artist and previous / play-pause / next. Android only shares what's playing with apps that have
 * notification access, so until that's allowed the widget asks for it. {@link AkhuMediaListener}
 * redraws it when the song or play state changes.
 */
public class MusicWidgetProvider extends ThemedWidgetProvider {

    private static final String ACTION_PREV = "com.MrBRos.akhu.MUSIC_PREV";
    private static final String ACTION_PLAY = "com.MrBRos.akhu.MUSIC_PLAY";
    private static final String ACTION_NEXT = "com.MrBRos.akhu.MUSIC_NEXT";
    private static final int ART_PX = 192;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_PREV.equals(action) || ACTION_PLAY.equals(action) || ACTION_NEXT.equals(action)) {
            MediaController c = controller(context);
            if (c != null) {
                MediaController.TransportControls tc = c.getTransportControls();
                if (ACTION_PREV.equals(action)) tc.skipToPrevious();
                else if (ACTION_NEXT.equals(action)) tc.skipToNext();
                else if (isPlaying(c)) tc.pause();
                else tc.play();
            }
            return;
        }
        super.onReceive(context, intent);
    }

    /** Whether the user has given Akhu notification access. */
    static boolean hasAccess(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(context, AkhuMediaListener.class).flattenToString());
    }

    /** The playing session, else the most recent one; null without access or with nothing to show. */
    @Nullable
    static MediaController controller(Context context) {
        if (!hasAccess(context)) return null;
        MediaSessionManager msm = context.getSystemService(MediaSessionManager.class);
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(context, AkhuMediaListener.class));
        } catch (SecurityException e) {
            return null;
        }
        for (MediaController c : list) if (isPlaying(c)) return c;
        return list.isEmpty() ? null : list.get(0);
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    @Override
    RemoteViews build(Context context, int widgetId, @Nullable CustomTheme t) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_music);
        int color = textColor(t);
        v.setTextColor(R.id.music_title, color);
        v.setTextColor(R.id.music_artist, color);
        for (int id : new int[]{R.id.music_prev, R.id.music_play, R.id.music_next}) tint(v, id, color);
        applyCard(context, v, t, widgetId);

        if (!hasAccess(context)) {
            v.setTextViewText(R.id.music_title, context.getString(R.string.music_allow_title));
            v.setTextViewText(R.id.music_artist, context.getString(R.string.music_allow_body));
            v.setViewVisibility(R.id.music_controls, View.GONE);
            v.setImageViewResource(R.id.music_art, R.drawable.ic_music);
            tint(v, R.id.music_art, color);
            v.setOnClickPendingIntent(R.id.widget_root,
                    activity(context, 1, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            return v;
        }
        MediaController c = controller(context);
        MediaMetadata m = c == null ? null : c.getMetadata();
        if (m == null) {
            v.setTextViewText(R.id.music_title, context.getString(R.string.music_nothing));
            v.setTextViewText(R.id.music_artist, context.getString(R.string.music_nothing_body));
            v.setViewVisibility(R.id.music_controls, View.GONE);
            v.setImageViewResource(R.id.music_art, R.drawable.ic_music);
            tint(v, R.id.music_art, color);
            return v;
        }
        v.setTextViewText(R.id.music_title, text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
        v.setTextViewText(R.id.music_artist, text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        Bitmap art = art(m);
        if (art != null) {
            v.setImageViewBitmap(R.id.music_art, art);
            v.setInt(R.id.music_art, "setColorFilter", 0);
        } else {
            v.setImageViewResource(R.id.music_art, R.drawable.ic_music);
            tint(v, R.id.music_art, color);
        }
        v.setViewVisibility(R.id.music_controls, View.VISIBLE);
        v.setImageViewResource(R.id.music_play, isPlaying(c) ? R.drawable.ic_pause : R.drawable.ic_play);
        v.setOnClickPendingIntent(R.id.music_prev, self(context, 10, ACTION_PREV));
        v.setOnClickPendingIntent(R.id.music_play, self(context, 11, ACTION_PLAY));
        v.setOnClickPendingIntent(R.id.music_next, self(context, 12, ACTION_NEXT));
        // Tapping the rest opens the app that's playing.
        if (c.getSessionActivity() != null) v.setOnClickPendingIntent(R.id.widget_root, c.getSessionActivity());
        return v;
    }

    private static String text(MediaMetadata m, String key, String fallback) {
        CharSequence s = m.getText(key);
        if (s == null || s.length() == 0) s = m.getText(fallback);
        return s == null ? "" : s.toString();
    }

    /** The album art scaled down to fit easily in a widget. */
    @Nullable
    private static Bitmap art(MediaMetadata m) {
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b == null) return null;
        int side = Math.min(b.getWidth(), b.getHeight());
        if (side <= ART_PX) return b.copy(Bitmap.Config.ARGB_8888, false);
        float s = (float) ART_PX / side;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
    }

    @Override
    int[] defaultSizeDp() {
        return new int[]{320, 150};
    }
}
