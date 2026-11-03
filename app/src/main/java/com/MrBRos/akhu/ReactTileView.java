package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Grid tile that plays a reactive wallpaper live, as the full preview does: the scene moves and
 * the characters float about and slide with the tilt. Each tile has its own {@link ReactRenderer},
 * but all of them draw on one shared GL thread and context, and only while they are on screen.
 * Until a tile's first frame the still poster beneath shows through.
 */
public class ReactTileView extends TextureView implements TextureView.SurfaceTextureListener {

    /** Tiles draw at most this wide; the view scales the frame up. */
    private static final int MAX_WIDTH = 420;
    /** Characters and photo are decoded no larger than a tile needs. */
    private static final int MAX_CHARACTER = 512, MAX_PHOTO = 720;

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private final Tile tile = new Tile();
    @Nullable
    private String character;

    public ReactTileView(Context context) {
        this(context, null);
    }

    public ReactTileView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOpaque(false);
        setSurfaceTextureListener(this);
        tile.renderer.setInsets(0.04f, 0.04f);
    }

    /** Shows a reactive wallpaper by its character path; loaded off the main thread. */
    public void setCharacter(String path) {
        if (path.equals(character)) return;
        character = path;
        load();
    }

    private void load() {
        String path = character;
        if (path == null) return;
        Context app = getContext().getApplicationContext();
        ReactRenderer renderer = tile.renderer;
        LOADER.execute(() -> {
            if (!path.equals(character)) return;
            ReactWallpapers.Spec spec = ReactWallpapers.spec(app, path);
            List<Bitmap> chars = new ArrayList<>();
            for (Bitmap full : ReactWallpapers.loadCharacters(app, path, spec)) {
                float s = Math.min(1f, (float) MAX_CHARACTER / Math.max(full.getWidth(), full.getHeight()));
                if (s >= 1f) {
                    chars.add(full);
                    continue;
                }
                chars.add(Bitmap.createScaledBitmap(full, Math.max(1, Math.round(full.getWidth() * s)),
                        Math.max(1, Math.round(full.getHeight() * s)), true));
                full.recycle();
            }
            // A video background would need a player per tile; its first frame stands in.
            String bg = spec.style == ReactWallpapers.STYLE_PHOTO ? spec.photo : null;
            Bitmap photo = bg == null ? null : ReactWallpapers.backgroundStill(bg, MAX_PHOTO);
            if (!path.equals(character) || chars.isEmpty()) {
                for (Bitmap b : chars) b.recycle();
                if (photo != null) photo.recycle();
                return;
            }
            renderer.setSpec(spec);
            renderer.setCharacters(chars);
            renderer.setPhoto(photo);
        });
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        sized(surface, width, height);
        Tiles.get().add(tile, surface, getContext());
        // The renderer let go of its pictures with its last surface; give it them again.
        if (tile.loadedOnce) load();
        tile.loadedOnce = true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        sized(surface, width, height);
    }

    /** Draws at a capped size; the view stretches it to fit. */
    private void sized(SurfaceTexture surface, int width, int height) {
        float s = Math.min(1f, (float) MAX_WIDTH / Math.max(1, width));
        int w = Math.max(1, Math.round(width * s)), h = Math.max(1, Math.round(height * s));
        surface.setDefaultBufferSize(w, h);
        tile.w = w;
        tile.h = h;
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        // The GL thread releases it once it has stopped drawing into it.
        Tiles.get().remove(tile);
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        tile.visible = isVisible;
        if (isVisible) Tiles.get().wake(getContext());
    }

    /** One tile as the GL thread sees it. */
    private static final class Tile {
        final ReactRenderer renderer = new ReactRenderer();
        @Nullable
        SurfaceTexture texture;
        @Nullable
        EGLSurface surface;
        volatile int w = 1, h = 1;
        volatile boolean visible;
        boolean created, loadedOnce;
    }

    /** The shared GL thread: one context, a surface per tile, frames paced by the display. */
    private static final class Tiles implements Choreographer.FrameCallback {

        private static final int EGL_OPENGL_ES3_BIT = 0x40;
        @Nullable
        private static Tiles instance;

        static Tiles get() {
            if (instance == null) instance = new Tiles();
            return instance;
        }

        private final Handler handler;
        private final List<Tile> tiles = new ArrayList<>();
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        @Nullable
        private EGLConfig config;
        private EGLSurface idle = EGL14.EGL_NO_SURFACE;
        @Nullable
        private Context app;
        @Nullable
        private GravityTilt tilt;
        private boolean running;
        private int frame;

        private Tiles() {
            HandlerThread thread = new HandlerThread("ReactTiles");
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        void add(Tile t, SurfaceTexture texture, Context ctx) {
            Context a = ctx.getApplicationContext();
            handler.post(() -> {
                app = a;
                if (!initEgl()) {
                    texture.release();
                    return;
                }
                int[] none = {EGL14.EGL_NONE};
                EGLSurface s = EGL14.eglCreateWindowSurface(display, config, texture, none, 0);
                if (s == null || s == EGL14.EGL_NO_SURFACE) {
                    texture.release();
                    return;
                }
                t.texture = texture;
                t.surface = s;
                t.created = false;
                if (EGL14.eglMakeCurrent(display, s, s, context)) EGL14.eglSwapInterval(display, 0);
                tiles.add(t);
            });
            wake(ctx);
        }

        void remove(Tile t) {
            handler.post(() -> {
                tiles.remove(t);
                if (t.surface != null) {
                    if (t.created && EGL14.eglMakeCurrent(display, t.surface, t.surface, context)) {
                        t.renderer.releaseGl();
                    }
                    EGL14.eglMakeCurrent(display, idle, idle, context);
                    EGL14.eglDestroySurface(display, t.surface);
                }
                t.surface = null;
                t.created = false;
                t.renderer.dispose();
                if (t.texture != null) t.texture.release();
                t.texture = null;
            });
        }

        /** Starts the frames (and the tilt) if they aren't running. */
        void wake(Context ctx) {
            Context ui = ctx;
            handler.post(() -> {
                if (running) return;
                running = true;
                if (tilt == null) tilt = new GravityTilt(ui);
                tilt.start();
                Choreographer.getInstance().postFrameCallback(this);
            });
        }

        private boolean initEgl() {
            if (context != EGL14.EGL_NO_CONTEXT) return true;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false;
            int[] attribs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return false;
            config = configs[0];
            int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                context = EGL14.EGL_NO_CONTEXT;
                return false;
            }
            int[] pb = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            idle = EGL14.eglCreatePbufferSurface(display, config, pb, 0);
            return true;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            boolean any = false;
            for (Tile t : tiles) any |= t.visible && t.surface != null;
            if (!any) {
                // Nothing on screen: stop until a tile shows again.
                running = false;
                if (tilt != null) tilt.stop();
                tilt = null;
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
            // Thirty frames a second is smooth at tile size and halves the cost.
            if ((frame++ & 1) == 1) return;
            float tx = tilt != null ? tilt.x() : 0f, ty = tilt != null ? tilt.y() : 0f;
            for (Tile t : tiles) {
                EGLSurface s = t.surface;
                if (!t.visible || s == null || app == null) continue;
                if (!EGL14.eglMakeCurrent(display, s, s, context)) continue;
                if (!t.created) {
                    t.renderer.onSurfaceCreated(app);
                    t.created = true;
                }
                t.renderer.onSurfaceChanged(t.w, t.h);
                t.renderer.draw(tx, ty);
                EGL14.eglSwapBuffers(display, s);
            }
        }
    }
}
