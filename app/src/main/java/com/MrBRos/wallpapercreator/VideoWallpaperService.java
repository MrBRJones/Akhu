package com.MrBRos.wallpapercreator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaPlayer;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Plays the chosen looping, muted video (see {@link MotionWallpapers}) as the wallpaper. */
public class VideoWallpaperService extends WallpaperService {

    public static final String ACTION_RELOAD = "com.MrBRos.wallpapercreator.RELOAD_VIDEO_WALLPAPER";
    private static final String TAG = "VideoWallpaper";

    private final Set<VideoEngine> engines = new CopyOnWriteArraySet<>();

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            for (VideoEngine e : engines) e.reload();
        }
    };

    public static void requestReload(Context context) {
        context.sendBroadcast(new Intent(ACTION_RELOAD).setPackage(context.getPackageName()));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ContextCompat.registerReceiver(this, reloadReceiver, new IntentFilter(ACTION_RELOAD),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(reloadReceiver);
        super.onDestroy();
    }

    @Override
    public Engine onCreateEngine() {
        VideoEngine e = new VideoEngine();
        engines.add(e);
        return e;
    }

    private class VideoEngine extends Engine {
        private MediaPlayer player;
        private SurfaceHolder holder;
        private String playingPath;
        private boolean visible;

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            this.holder = holder;
            start();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            if (player == null) return;
            if (visible) player.start();
            else player.pause();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            release();
            this.holder = null;
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            engines.remove(this);
            release();
            super.onDestroy();
        }

        void reload() {
            String path = currentPath();
            if (path != null && !path.equals(playingPath) && holder != null) {
                release();
                start();
            }
        }

        private String currentPath() {
            String path = EffectConfig.prefs(getApplicationContext()).getString(MotionWallpapers.KEY_VIDEO_PATH, null);
            return path != null && new File(path).exists() ? path : null;
        }

        private void start() {
            String path = currentPath();
            if (path == null || holder == null) return;
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setSurface(holder.getSurface());
                mp.setDataSource(path);
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                mp.setOnPreparedListener(p -> {
                    // Fill the screen without stretching.
                    p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    if (visible || isPreview()) p.start();
                });
                mp.prepareAsync();
                player = mp;
                playingPath = path;
            } catch (Exception e) {
                Log.e(TAG, "Cannot play " + path, e);
                release();
            }
        }

        private void release() {
            if (player != null) {
                player.release();
                player = null;
            }
            playingPath = null;
        }
    }
}
