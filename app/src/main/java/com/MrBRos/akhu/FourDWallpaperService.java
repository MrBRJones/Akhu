package com.MrBRos.akhu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.core.content.ContextCompat;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 4D wallpaper: renders {@link FourDRenderer} on its own GL thread, driven by {@link TiltSensor}. */
public class FourDWallpaperService extends WallpaperService {

    public static final String ACTION_RELOAD = "com.MrBRos.akhu.RELOAD_FOURD_WALLPAPER";
    private static final String TAG = "FourDWallpaper";
    private static final long FRAME_MS = 16;

    private final Set<FourDEngine> engines = new CopyOnWriteArraySet<>();

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            for (FourDEngine e : engines) e.loadImages();
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
        FourDEngine e = new FourDEngine();
        engines.add(e);
        return e;
    }

    private class FourDEngine extends Engine {
        private final FourDRenderer renderer = new FourDRenderer();
        private TiltSensor tilt;
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
            tilt = new TiltSensor(getApplicationContext());
            thread = new HandlerThread("FourDGL");
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
            loadImages();
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

        @Override
        public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            if (visible) {
                tilt.start();
                gl.removeCallbacks(frame);
                gl.post(frame);
            } else {
                // Sensor and rendering both stop while hidden, so 4D costs nothing in other apps.
                tilt.stop();
                gl.removeCallbacks(frame);
            }
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

        void loadImages() {
            String asset = EffectConfig.prefs(getApplicationContext()).getString(FourDWallpapers.KEY_CURRENT, null);
            if (asset == null) {
                java.util.List<String> all = FourDWallpapers.list(getApplicationContext());
                if (all.isEmpty()) return;
                asset = all.get(0);
            }
            String finalAsset = asset;
            renderer.setFocal(Focal.get(getApplicationContext(), finalAsset));
            renderer.setExtraMotion(FourDWallpapers.extraMotion(getApplicationContext(), finalAsset));
            renderer.setMotion(FourDWallpapers.motion(getApplicationContext(), finalAsset));
            new Thread(() -> {
                Bitmap[] layers = FourDWallpapers.load(getApplicationContext(), finalAsset);
                if (layers != null) renderer.setLayers(layers);
            }, "FourDLoad").start();
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
