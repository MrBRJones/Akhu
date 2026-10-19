package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

/** Muted, looping, center-cropped player for a bundled video asset (used by the full-screen preview). */
public class CropVideoView extends TextureView implements TextureView.SurfaceTextureListener {

    private final String asset;
    private MediaPlayer player;
    private boolean wantPlaying;
    private int videoW, videoH;

    public CropVideoView(Context context, String asset) {
        super(context);
        this.asset = asset;
        setSurfaceTextureListener(this);
    }

    public void play() {
        wantPlaying = true;
        if (player != null) player.start();
    }

    public void pause() {
        wantPlaying = false;
        if (player != null && player.isPlaying()) player.pause();
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int width, int height) {
        try (AssetFileDescriptor afd = getContext().getAssets().openFd(asset)) {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.setSurface(new Surface(st));
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                videoW = w;
                videoH = h;
                applyCrop();
            });
            mp.setOnPreparedListener(p -> {
                if (wantPlaying) p.start();
            });
            mp.prepareAsync();
            player = mp;
        } catch (Exception e) {
            Log.e("CropVideoView", "Cannot play " + asset, e);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int width, int height) {
        applyCrop();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
    }

    /** TextureView stretches to fill; rescale so the video covers the view at its own aspect ratio. */
    private void applyCrop() {
        int vw = getWidth(), vh = getHeight();
        if (videoW == 0 || videoH == 0 || vw == 0 || vh == 0) return;
        float scale = Math.max((float) vw / videoW, (float) vh / videoH);
        float sx = videoW * scale / vw;
        float sy = videoH * scale / vh;
        Matrix m = new Matrix();
        m.setScale(sx, sy, vw / 2f, vh / 2f);
        // Slide the cropped video so its subject sits at the centre, without exposing an edge.
        float[] focal = Focal.get(getContext(), asset);
        float contentW = videoW * scale, contentH = videoH * scale;
        float maxDx = (contentW - vw) / 2f, maxDy = (contentH - vh) / 2f;
        float dx = Math.max(-maxDx, Math.min(maxDx, contentW * (0.5f - focal[0])));
        float dy = Math.max(-maxDy, Math.min(maxDy, contentH * (0.5f - focal[1])));
        m.postTranslate(dx, dy);
        setTransform(m);
    }
}
