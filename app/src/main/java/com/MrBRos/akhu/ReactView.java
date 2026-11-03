package com.MrBRos.akhu;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import java.io.File;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * In-app reactive wallpaper: a GLSurfaceView running {@link ReactRenderer} with live tilt.
 * Touching ripples the water and pulls the character toward the finger.
 */
public class ReactView extends GLSurfaceView {

    private final ReactRenderer renderer = new ReactRenderer();
    private final GravityTilt tilt;
    @Nullable
    private String character;
    /** A character made in memory (the editor); copies go to the renderer, which recycles them. */
    @Nullable
    private Bitmap direct;
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

    /** Shows a character made in memory with these settings (the editor). */
    public void setPreview(Bitmap character, ReactWallpapers.Spec spec) {
        this.character = null;
        direct = character;
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
        Bitmap d = direct;
        if (d != null) {
            renderer.setSpec(spec);
            renderer.setCharacter(d.copy(Bitmap.Config.ARGB_8888, false));
            loadPhoto(spec);
            return;
        }
        String c = character;
        if (c == null) return;
        Context app = getContext().getApplicationContext();
        new Thread(() -> {
            ReactWallpapers.Spec s = ReactWallpapers.spec(app, c);
            Bitmap bmp = ReactWallpapers.loadCharacter(app, c);
            if (!c.equals(character)) {
                if (bmp != null) bmp.recycle();
                return;
            }
            spec = s;
            renderer.setSpec(s);
            if (bmp != null) renderer.setCharacter(bmp);
            renderer.setPhoto(s.photo == null ? null : ImagePickerHelper.decodeSampled(new File(s.photo), 2048));
        }, "ReactPreviewLoad").start();
    }

    private void loadPhoto(ReactWallpapers.Spec s) {
        if (s.style != ReactWallpapers.STYLE_PHOTO || s.photo == null) {
            renderer.setPhoto(null);
            return;
        }
        String path = s.photo;
        new Thread(() -> renderer.setPhoto(ImagePickerHelper.decodeSampled(new File(path), 2048)), "ReactPhotoLoad").start();
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
    }

    @Override
    public void onPause() {
        if (!running) return;
        running = false;
        tilt.stop();
        super.onPause();
    }
}
