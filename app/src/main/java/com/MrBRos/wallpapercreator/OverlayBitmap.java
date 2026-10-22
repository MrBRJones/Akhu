package com.MrBRos.wallpapercreator;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;

import androidx.annotation.OptIn;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Burns the motion editor's text and stickers into the exported video: a frame-sized bitmap that is
 * redrawn only when what's on it changes (an overlay appears or goes, or one is animating).
 */
@OptIn(markerClass = UnstableApi.class)
final class OverlayBitmap extends BitmapOverlay {

    private final List<MotionOverlay> overlays = new ArrayList<>();
    private final long clipStartMs;
    private final OverlayRenderer renderer = new OverlayRenderer();
    private Bitmap bitmap;
    private Canvas canvas;
    private long firstUs = -1;
    private String lastKey;

    OverlayBitmap(List<MotionOverlay> overlays, long clipStartMs, int width, int height) {
        for (MotionOverlay o : overlays) this.overlays.add(o.copy());
        this.clipStartMs = clipStartMs;
        allocate(width, height);
    }

    private void allocate(int width, int height) {
        if (bitmap != null && bitmap.getWidth() == width && bitmap.getHeight() == height) return;
        bitmap = Bitmap.createBitmap(Math.max(2, width), Math.max(2, height), Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
        lastKey = null;
    }

    @Override
    public void configure(Size videoSize) {
        super.configure(videoSize);
        allocate(videoSize.getWidth(), videoSize.getHeight());
    }

    @Override
    public Bitmap getBitmap(long presentationTimeUs) {
        // Frames may be timed from 0 or from the clip's start; either way the first one is the clip start.
        if (firstUs < 0) firstUs = presentationTimeUs;
        long t = clipStartMs + (presentationTimeUs - firstUs) / 1000;
        StringBuilder key = new StringBuilder();
        boolean moving = false;
        for (int i = 0; i < overlays.size(); i++) {
            MotionOverlay o = overlays.get(i);
            if (!o.visibleAt(t)) continue;
            key.append(i).append(',');
            moving |= OverlayRenderer.moving(o);
        }
        if (moving) key.append('@').append(t);
        if (!key.toString().equals(lastKey)) {
            lastKey = key.toString();
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            renderer.draw(canvas, bitmap.getWidth(), bitmap.getHeight(), overlays, t, clipStartMs, null);
        }
        return bitmap;
    }

    @Override
    public void release() {
        renderer.release();
        try {
            super.release();
        } catch (Exception ignored) {
        }
    }
}
