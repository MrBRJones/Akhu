package com.MrBRos.akhu;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Notification access for the music widget. Akhu never reads notifications: this only watches
 * media sessions (what's playing, and play / pause) and redraws {@link MusicWidgetProvider}. While
 * it runs it also keeps {@link BatteryWidgetProvider} current.
 */
public class AkhuMediaListener extends NotificationListenerService {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private MediaSessionManager sessions;
    private int lastBattery = -1;

    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions = this::watch;

    private final MediaController.Callback onChange = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            refreshMusic();
        }

        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            refreshMusic();
        }

        @Override
        public void onSessionDestroyed() {
            refreshMusic();
        }
    };

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            // BATTERY_CHANGED comes often; redraw only when what the widget shows changes.
            int key = level * 10 + (plugged != 0 ? 1 : 0);
            if (key == lastBattery) return;
            lastBattery = key;
            new BatteryWidgetProvider().updateAllOfKind(context);
        }
    };

    private final Runnable musicRefresh = () -> new MusicWidgetProvider().updateAllOfKind(this);

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, AkhuMediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(onSessions, me, main);
            watch(sessions.getActiveSessions(me));
        } catch (SecurityException ignored) {
        }
        registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        refreshMusic();
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(onSessions);
        watch(null);
        try {
            unregisterReceiver(battery);
        } catch (IllegalArgumentException ignored) {
        }
        refreshMusic();
    }

    private void watch(@Nullable List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(onChange);
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(onChange, main);
                watched.add(c);
            }
        }
        refreshMusic();
    }

    /** Coalesces bursts of changes (some players report several at once) into one redraw. */
    private void refreshMusic() {
        main.removeCallbacks(musicRefresh);
        main.postDelayed(musicRefresh, 250);
    }
}
