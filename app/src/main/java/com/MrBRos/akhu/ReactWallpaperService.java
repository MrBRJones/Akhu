package com.MrBRos.akhu;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reactive wallpaper: renders {@link ReactRenderer} on its own GL thread, driven by
 * {@link GravityTilt} and touches on the home screen.
 */
public class ReactWallpaperService extends WallpaperService {

    public static final String ACTION_RELOAD = "com.MrBRos.akhu.RELOAD_REACT_WALLPAPER";
    private static final String TAG = "ReactWallpaper";
    private static final long FRAME_MS = 16;

    private final Set<ReactEngine> engines = new CopyOnWriteArraySet<>();

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            for (ReactEngine e : engines) e.load();
        }
    };

    public static void requestReload(Context context) {
        context.sendBroadcast(new Intent(ACTION_RELOAD).setPackage(context.getPackageName()));
    }

    /** Which screen this service's wallpaper is for: {@link WallpaperTarget#LOCK} for {@link Lock}. */
    protected int target() {
        return WallpaperTarget.HOME;
    }

    /** The same reactive wallpaper for the lock screen only, with its own current wallpaper. */
    public static class Lock extends ReactWallpaperService {
        @Override
        protected int target() {
            return WallpaperTarget.LOCK;
        }
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
        ReactEngine e = new ReactEngine();
        engines.add(e);
        return e;
    }

    private class ReactEngine extends Engine {
        private final ReactRenderer renderer = new ReactRenderer();
        private GravityTilt tilt;
        private HandlerThread thread;
        private Handler gl;
        private EglHelper egl;
        private volatile boolean visible;

        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                if (egl == null || !egl.hasSurface()) return;
                renderer.draw(tilt.x(), tilt.y());
                egl.swap();
                if (visible) gl.postDelayed(this, FRAME_MS);
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            tilt = new GravityTilt(getApplicationContext());
            thread = new HandlerThread("ReactGL");
            thread.start();
            gl = new Handler(thread.getLooper());
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            gl.post(() -> {
                try {
                    if (egl == null) {
                        egl = new EglHelper();
                        egl.start();
                    }
                    egl.createSurface(holder);
                    renderer.onSurfaceCreated(getApplicationContext());
                } catch (RuntimeException e) {
                    Log.e(TAG, "GL init failed", e);
                }
            });
            load();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            gl.post(() -> {
                if (egl != null && egl.hasSurface()) {
                    renderer.onSurfaceChanged(width, height);
                    gl.removeCallbacks(frame);
                    gl.post(frame);
                }
            });
        }

        /** Moved between the home and lock screen (Android 14+), which can change what it shows. */
        @Override
        public void onWallpaperFlagsChanged(int which) {
            super.onWallpaperFlagsChanged(which);
            load();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            renderer.setPlaying(visible);
            if (visible) {
                tilt.start();
                gl.removeCallbacks(frame);
                gl.post(frame);
            } else {
                // Sensor and rendering both stop while hidden, so it costs nothing in other apps.
                tilt.stop();
                gl.removeCallbacks(frame);
            }
        }

        @Override
        public void onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    renderer.physics.tap(e.getX(), e.getY());
                    renderer.physics.touch(true, e.getX(), e.getY());
                    break;
                case MotionEvent.ACTION_MOVE:
                    renderer.physics.touch(true, e.getX(), e.getY());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    renderer.physics.touch(false, e.getX(), e.getY());
                    break;
                default:
            }
        }

        /** Launchers that keep touches to themselves still report taps on empty space. */
        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (WallpaperManager.COMMAND_TAP.equals(action)) renderer.physics.tap(x, y);
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            runAndWait(() -> {
                gl.removeCallbacks(frame);
                if (egl != null) {
                    renderer.releaseGl();
                    egl.finish();
                    egl = null;
                }
            });
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            visible = false;
            engines.remove(this);
            tilt.stop();
            runAndWait(() -> {
                gl.removeCallbacks(frame);
                if (egl != null) {
                    renderer.releaseGl();
                    egl.finish();
                    egl = null;
                }
                renderer.dispose();
            });
            thread.quitSafely();
            super.onDestroy();
        }

        void load() {
            Context app = getApplicationContext();
            String current = EffectConfig.prefs(app).getString(WallpaperTarget.engineKey(app, ReactWallpapers.KEY_CURRENT, target(), this), null);
            if (current == null || (ReactWallpapers.isUser(current) && !new File(current).exists())) {
                List<String> all = ReactWallpapers.bundled(app);
                if (all.isEmpty()) return;
                current = all.get(0);
            }
            String finalCurrent = current;
            new Thread(() -> {
                ReactWallpapers.Spec s = ReactWallpapers.spec(app, finalCurrent);
                renderer.setSpec(s);
                List<Bitmap> bmps = ReactWallpapers.loadCharacters(app, finalCurrent, s);
                if (!bmps.isEmpty()) renderer.setCharacters(bmps);
                ReactWallpapers.loadBackground(renderer, s);
            }, "ReactLoad").start();
        }

        private void runAndWait(Runnable task) {
            if (gl == null) return;
            CountDownLatch latch = new CountDownLatch(1);
            if (!gl.post(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            })) return;
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
