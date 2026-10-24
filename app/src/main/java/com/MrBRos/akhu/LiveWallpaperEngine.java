package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Wallpaper engine that owns a {@link GLWallpaperRenderer} on a dedicated render thread.
 * Bitmaps are decoded on a loader thread and handed to the renderer, which uploads,
 * swaps and recycles them under its texture lock on the render thread.
 */
public class LiveWallpaperEngine extends WallpaperService.Engine {

    private static final String TAG = "LiveWallpaperEngine";
    private static final long FRAME_MS = 16;
    private static final int MAX_TEXTURE_DIM = 2560;

    private final LiveWallpaperService service;
    private final Context appContext;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final GLWallpaperRenderer renderer;
    private HandlerThread renderThread;
    private Handler renderHandler;
    private EglHelper egl;
    private volatile boolean visible;
    private volatile EffectConfig config;
    private int cycleIndex;
    private long lastCycleMs;

    private final Runnable frameTask = new Runnable() {
        @Override
        public void run() {
            if (egl == null || !egl.hasSurface()) return;
            maybeCycle();
            renderer.drawFrame();
            egl.swap();
            if (visible) {
                renderHandler.postDelayed(this, FRAME_MS);
            }
        }
    };

    LiveWallpaperEngine(LiveWallpaperService service) {
        service.super();
        this.service = service;
        this.appContext = service.getApplicationContext();
        this.config = EffectConfig.load(appContext);
        this.renderer = new GLWallpaperRenderer(config);
    }

    @Override
    public void onCreate(SurfaceHolder surfaceHolder) {
        super.onCreate(surfaceHolder);
        setTouchEventsEnabled(false);
        renderThread = new HandlerThread("WallpaperGL");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        loadCurrentBitmap();
    }

    @Override
    public void onSurfaceCreated(SurfaceHolder holder) {
        super.onSurfaceCreated(holder);
        renderHandler.post(() -> {
            try {
                if (egl == null) {
                    egl = new EglHelper();
                    egl.start();
                }
                egl.createSurface(holder);
                renderer.onSurfaceCreated(appContext);
            } catch (RuntimeException e) {
                Log.e(TAG, "GL init failed", e);
            }
        });
        // GL objects were dropped with the previous surface; queue the image again.
        loadCurrentBitmap();
    }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        super.onSurfaceChanged(holder, format, width, height);
        renderHandler.post(() -> {
            if (egl != null && egl.hasSurface()) {
                renderer.onSurfaceChanged(width, height);
                scheduleFrame();
            }
        });
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        this.visible = visible;
        if (visible) {
            renderHandler.post(this::scheduleFrame);
        } else {
            renderHandler.removeCallbacks(frameTask);
        }
    }

    @Override
    public void onSurfaceDestroyed(SurfaceHolder holder) {
        // The surface is gone once this returns, so tear down GL synchronously.
        runOnRenderThreadAndWait(() -> {
            renderHandler.removeCallbacks(frameTask);
            if (egl != null) {
                renderer.releaseGl();
                egl.releaseCurrent();
                egl.finish();
                egl = null;
            }
        });
        super.onSurfaceDestroyed(holder);
    }

    @Override
    public void onDestroy() {
        visible = false;
        service.onEngineDestroyed(this);
        loader.shutdownNow();
        runOnRenderThreadAndWait(() -> {
            renderHandler.removeCallbacks(frameTask);
            if (egl != null) {
                renderer.releaseGl();
                egl.finish();
                egl = null;
            }
            renderer.dispose();
        });
        renderThread.quitSafely();
        super.onDestroy();
    }

    /** Re-reads settings and the current wallpaper image. Safe to call from any thread. */
    public void reload() {
        config = EffectConfig.load(appContext);
        renderer.setConfig(config);
        loadCurrentBitmap();
    }

    private void scheduleFrame() {
        renderHandler.removeCallbacks(frameTask);
        renderHandler.post(frameTask);
    }

    private void loadCurrentBitmap() {
        String path = EffectConfig.prefs(appContext).getString(EffectConfig.KEY_CURRENT_PATH, null);
        if (path == null) {
            List<String> saved = ImageExporter.getSavedPaths(appContext);
            if (!saved.isEmpty()) path = saved.get(0);
        }
        loadBitmapAsync(path);
    }

    private void loadBitmapAsync(String path) {
        if (path == null || loader.isShutdown()) return;
        try {
            loader.execute(() -> {
                Bitmap bmp = ImagePickerHelper.decodeSampled(new File(path), MAX_TEXTURE_DIM);
                if (bmp != null) {
                    renderer.setBitmap(bmp);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Engine is shutting down.
        }
    }

    /** With "Cycle" on, steps through the saved wallpapers strip on the Ken Burns period. */
    private void maybeCycle() {
        EffectConfig cfg = config;
        if (!cfg.kbCycle) return;
        long now = SystemClock.uptimeMillis();
        long periodMs = (long) (20000 / cfg.kbSpeed);
        if (lastCycleMs == 0) {
            lastCycleMs = now;
            return;
        }
        if (now - lastCycleMs < periodMs) return;
        lastCycleMs = now;
        List<String> saved = ImageExporter.getSavedPaths(appContext);
        if (saved.size() < 2) return;
        cycleIndex = (cycleIndex + 1) % saved.size();
        loadBitmapAsync(saved.get(cycleIndex));
    }

    private void runOnRenderThreadAndWait(Runnable task) {
        if (renderHandler == null) return;
        CountDownLatch latch = new CountDownLatch(1);
        boolean posted = renderHandler.post(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        if (!posted) return;
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
