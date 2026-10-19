package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** In-app 4D preview: a GLSurfaceView that renders {@link FourDRenderer} with live tilt. */
public class FourDView extends GLSurfaceView {

    private final FourDRenderer renderer = new FourDRenderer();
    private final TiltSensor tilt;
    private String asset;
    /** Layers handed in directly (the editor); the renderer gets copies, as it recycles them. */
    @androidx.annotation.Nullable
    private Bitmap[] direct;

    public FourDView(Context context) {
        super(context);
        tilt = new TiltSensor(context);
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

    /** Shows a bundled 4D wallpaper, e.g. "fourd/1.jpg". */
    public void setAsset(String asset) {
        if (asset.equals(this.asset)) return;
        this.asset = asset;
        direct = null;
        renderer.setFocal(Focal.get(getContext(), asset));
        renderer.setExtraMotion(FourDWallpapers.extraMotion(getContext(), asset));
        renderer.setMotion(FourDWallpapers.motion(getContext(), asset));
        reload();
    }

    /** Shows layers made in memory instead of a saved image; keeps them for GL context loss. */
    public void setLayers(Bitmap[] layers) {
        asset = null;
        direct = layers;
        reload();
    }

    public void setMotion(float[] motion) {
        renderer.setMotion(motion);
    }

    private void reload() {
        String a = asset;
        Bitmap[] d = direct;
        if (a == null && d != null) {
            Bitmap[] copies = new Bitmap[4];
            for (int i = 0; i < 3; i++) copies[i] = d[i].copy(Bitmap.Config.ARGB_8888, false);
            renderer.setLayers(copies);
            return;
        }
        if (a == null) return;
        Context app = getContext().getApplicationContext();
        new Thread(() -> {
            Bitmap[] layers = FourDWallpapers.load(app, a);
            if (layers != null && a.equals(asset)) renderer.setLayers(layers);
        }, "FourDPreviewLoad").start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) renderer.setViewAspect((float) w / h);
    }

    private boolean running;

    /** Safe to call repeatedly; the preview re-syncs on every page change. */
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
