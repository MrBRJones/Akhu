package com.MrBRos.akhu;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * In-app reactive wallpaper: a GLSurfaceView running {@link ReactRenderer} with live tilt.
 * Touching ripples the water and pulls the nearest character toward the finger.
 */
public class ReactView extends GLSurfaceView {

    private final ReactRenderer renderer = new ReactRenderer();
    private final GravityTilt tilt;
    @Nullable
    private String character;
    /** Characters made in memory (the editor); copies go to the renderer, which recycles them. */
    @Nullable
    private List<Bitmap> direct;
    private ReactWallpapers.Spec spec = new ReactWallpapers.Spec();
    private boolean running;
    /** False when the parent passes touches in through {@link #feed} (the preview, whose taps toggle its buttons). */
    private boolean interactive = true;

    public ReactView(Context context) {
        super(context);
        tilt = new GravityTilt(context);
        setEGLContextClientVersion(3);
        setRenderer(new Renderer() {
            @Override
            public void onSurfaceCreated(GL10 unused, EGLConfig config) {
                renderer.onSurfaceCreated(getContext());
                reload();
            }

            @Override
            public void onSurfaceChanged(GL10 unused, int width, int height) {
                renderer.onSurfaceChanged(width, height);
            }

            @Override
            public void onDrawFrame(GL10 unused) {
                renderer.draw(tilt.x(), tilt.y());
            }
        });
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** Shows a saved or bundled reactive wallpaper by its character path. */
    public void setSource(String character) {
        if (character.equals(this.character)) return;
        this.character = character;
        direct = null;
        reload();
    }

    /** Reads the showing wallpaper's settings again (after it was edited). */
    public void reloadSource() {
        reload();
    }

    /** Shows characters made in memory with these settings (the editor); they stay the caller's. */
    public void setPreview(List<Bitmap> characters, ReactWallpapers.Spec spec) {
        this.character = null;
        direct = new ArrayList<>(characters);
        this.spec = spec;
        reload();
    }

    /** New settings for what's showing (the editor's water and size). */
    public void setSpec(ReactWallpapers.Spec spec) {
        boolean photoChanged = !java.util.Objects.equals(spec.photo, this.spec.photo)
                || (spec.style == ReactWallpapers.STYLE_PHOTO) != (this.spec.style == ReactWallpapers.STYLE_PHOTO);
        this.spec = spec;
        renderer.setSpec(spec);
        if (photoChanged) loadPhoto(spec);
    }

    public void setInsets(float top, float bottom) {
        renderer.setInsets(top, bottom);
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    private void reload() {
        List<Bitmap> d = direct;
        if (d != null) {
            renderer.setSpec(spec);
            List<Bitmap> copies = new ArrayList<>();
            for (Bitmap b : d) copies.add(b.copy(Bitmap.Config.ARGB_8888, false));
            renderer.setCharacters(copies);
            loadPhoto(spec);
            return;
        }
        String c = character;
        if (c == null) return;
        Context app = getContext().getApplicationContext();
        new Thread(() -> {
            ReactWallpapers.Spec s = ReactWallpapers.spec(app, c);
            List<Bitmap> bmps = ReactWallpapers.loadCharacters(app, c, s);
            if (!c.equals(character)) {
                for (Bitmap b : bmps) b.recycle();
                return;
            }
            spec = s;
            renderer.setSpec(s);
            if (!bmps.isEmpty()) renderer.setCharacters(bmps);
            ReactWallpapers.loadBackground(renderer, s);
        }, "ReactPreviewLoad").start();
    }

    private void loadPhoto(ReactWallpapers.Spec s) {
        new Thread(() -> ReactWallpapers.loadBackground(renderer, s), "ReactPhotoLoad").start();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        feed(e);
        return true;
    }

    /** Ripples where the finger lands and pulls the character toward it while held. */
    public void feed(MotionEvent e) {
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

    /** Safe to call repeatedly. */
    @Override
    public void onResume() {
        if (running) return;
        running = true;
        super.onResume();
        tilt.start();
        renderer.setPlaying(true);
    }

    @Override
    public void onPause() {
        if (!running) return;
        running = false;
        tilt.stop();
        renderer.setPlaying(false);
        super.onPause();
    }
}
