package com.MrBRos.akhu;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

/**
 * Muted, looping, center-cropped player for a bundled video asset, a video file on the phone
 * (an absolute path) or a posted one (an http(s) URL). It can loop just the start of a video
 * (the grid's tiles), and it pauses while its window is hidden.
 */
public class CropVideoView extends TextureView implements TextureView.SurfaceTextureListener {

    private final String asset;
    private MediaPlayer player;
    private boolean wantPlaying;
    private int videoW, videoH;
    /** Plays only the first this-many ms before starting over; 0 plays it all. */
    private int loopMs;
    @androidx.annotation.Nullable
    private Runnable onReady, onError;
    private final Runnable loopCheck = new Runnable() {
        @Override
        public void run() {
            MediaPlayer p = player;
            if (p == null || loopMs <= 0) return;
            try {
                if (p.isPlaying() && p.getCurrentPosition() >= loopMs) p.seekTo(0);
            } catch (IllegalStateException ignored) {
            }
            postDelayed(this, 100);
        }
    };

    public CropVideoView(Context context, String asset) {
        super(context);
        this.asset = asset;
        setSurfaceTextureListener(this);
    }

    /** Loops only the first {@code ms} of the video; 0 (the default) loops all of it. */
    public void setLoopMs(int ms) {
        loopMs = ms;
    }

    /** {@code ready} runs at the first frame; {@code error} if the video can't be played. */
    public void setListeners(@androidx.annotation.Nullable Runnable ready, @androidx.annotation.Nullable Runnable error) {
        onReady = ready;
        onError = error;
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
        removeCallbacks(loopCheck);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int width, int height) {
        try {
            MediaPlayer mp = new MediaPlayer();
            if (asset.startsWith("/") || asset.startsWith("http")) {
                mp.setDataSource(asset);
            } else {
                try (AssetFileDescriptor afd = getContext().getAssets().openFd(asset)) {
                    mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                }
            }
            mp.setSurface(new Surface(st));
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                videoW = w;
                videoH = h;
                applyCrop();
            });
            mp.setOnPreparedListener(p -> {
                if (wantPlaying && getWindowVisibility() == VISIBLE) p.start();
                removeCallbacks(loopCheck);
                if (loopMs > 0) post(loopCheck);
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && onReady != null) onReady.run();
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (onError != null) onError.run();
                return true;
            });
            mp.prepareAsync();
            player = mp;
        } catch (Exception e) {
            Log.e("CropVideoView", "Cannot play " + asset, e);
            if (onError != null) onError.run();
        }
    }

    /** Stops decoding while the screen it's on is hidden (another app, or the phone locked). */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        MediaPlayer p = player;
        if (p == null) return;
        try {
            if (visibility == VISIBLE && wantPlaying) p.start();
            else if (p.isPlaying()) p.pause();
        } catch (IllegalStateException ignored) {
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
