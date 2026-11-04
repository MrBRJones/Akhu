package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * GLES3 renderer for reactive wallpapers: the water (or photo) background with its effects, then
 * each character's shadow and the characters, placed by {@link ReactPhysics}. Shared by
 * {@link ReactWallpaperService} and the in-app {@link ReactView}.
 *
 * <p>{@link #setCharacters}, {@link #setSpec}, {@link #setPhoto}, {@link #setVideo} and
 * {@link #setPlaying} may be called from any thread; bitmaps are uploaded and recycled, and a video
 * is started, on the GL thread. Everything else runs on the GL thread.</p>
 */
public class ReactRenderer {

    private static final float[] QUAD_POS = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] QUAD_UV = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
    private static final float[] UNIT = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
    private static final int MAX = ReactPhysics.MAX_BODIES;

    private final FloatBuffer posBuf = buffer(QUAD_POS);
    private final FloatBuffer uvBuf = buffer(QUAD_UV);
    private final FloatBuffer unitBuf = buffer(UNIT);
    final ReactPhysics physics = new ReactPhysics();

    private final Object lock = new Object();
    /** Characters waiting for upload, or null when nothing changed. */
    @Nullable
    private Bitmap[] pendingCharacters;
    @Nullable
    private float[][] pendingPivots, pendingHulls;
    @Nullable
    private Bitmap pendingPhoto;
    private boolean photoCleared;
    private volatile ReactWallpapers.Spec spec = new ReactWallpapers.Spec();

    private ShaderProgram water, sprite;
    private final int[] characterTex = new int[MAX];
    private final int[] characterW = new int[MAX], characterH = new int[MAX];
    private final float[][] pivots = new float[MAX][];
    private final float[][] hulls = new float[MAX][];
    private int characters;
    private int photoTex;
    private int photoW = 1, photoH = 1;

    // A video background: a looping, muted MediaPlayer decoding into an external texture.
    @Nullable
    private String pendingVideo, videoPath;
    private int pendingVideoW = 1, pendingVideoH = 1;
    private boolean videoChanged;
    private int videoTex;
    @Nullable
    private android.graphics.SurfaceTexture videoTexture;
    @Nullable
    private android.view.Surface videoSurface;
    @Nullable
    private android.media.MediaPlayer player;
    private volatile boolean videoReady, videoFrame;
    private volatile boolean playing = true;
    private int videoW = 1, videoH = 1;
    private final float[] videoMatrix = new float[16];
    private int surfaceW = 1, surfaceH = 1;
    /** Fraction of the height kept clear at the top and bottom (status and navigation bars). */
    private volatile float insetTop = 0.05f, insetBottom = 0.05f;
    private long lastNanos;
    private final float[] place = new float[6];

    private static FloatBuffer buffer(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);
        return b;
    }

    /** Queues the characters (at most {@link ReactPhysics#MAX_BODIES}); the renderer takes ownership and recycles them. */
    public void setCharacters(List<Bitmap> list) {
        int n = Math.min(MAX, list.size());
        Bitmap[] b = new Bitmap[n];
        float[][] p = new float[n][], h = new float[n][];
        for (int i = 0; i < n; i++) {
            b[i] = list.get(i);
            p[i] = ReactWallpapers.pivot(b[i]);
            h[i] = ReactWallpapers.hull(b[i]);
        }
        for (int i = n; i < list.size(); i++) list.get(i).recycle();
        synchronized (lock) {
            recyclePending();
            pendingCharacters = b;
            pendingPivots = p;
            pendingHulls = h;
        }
    }

    private void recyclePending() {
        if (pendingCharacters != null) for (Bitmap old : pendingCharacters) old.recycle();
        pendingCharacters = null;
    }

    /** Queues the background photo (null for drawn water); the renderer takes ownership. */
    public void setPhoto(@Nullable Bitmap photo) {
        synchronized (lock) {
            if (pendingPhoto != null) pendingPhoto.recycle();
            pendingPhoto = photo;
            photoCleared = photo == null;
        }
    }

    /** Queues a video (.mp4 path) to play as the background, or null for none. Blocking briefly (reads its size). */
    public void setVideo(@Nullable String path) {
        int w = 1, h = 1;
        if (path != null) {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            try {
                mmr.setDataSource(path);
                w = parse(mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                h = parse(mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                int rotation = parse(mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                // A phone video filmed upright is stored sideways with a rotation; it shows upright.
                if (rotation % 180 != 0) {
                    int t = w;
                    w = h;
                    h = t;
                }
            } catch (RuntimeException ignored) {
            } finally {
                try {
                    mmr.release();
                } catch (java.io.IOException ignored) {
                }
            }
        }
        synchronized (lock) {
            if (java.util.Objects.equals(path, videoChanged ? pendingVideo : videoPath)) return;
            pendingVideo = path;
            pendingVideoW = Math.max(1, w);
            pendingVideoH = Math.max(1, h);
            videoChanged = true;
        }
    }

    private static int parse(@Nullable String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Plays the video background while true; it pauses while the wallpaper or preview is hidden. */
    public void setPlaying(boolean play) {
        playing = play;
        synchronized (lock) {
            android.media.MediaPlayer mp = player;
            if (mp == null || !videoReady) return;
            try {
                if (play && !mp.isPlaying()) mp.start();
                else if (!play && mp.isPlaying()) mp.pause();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    /** On the GL thread, with {@link #lock} held. */
    private void startVideo(@Nullable String path, int w, int h) {
        stopVideo();
        videoPath = path;
        if (path == null) return;
        videoW = w;
        videoH = h;
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        videoTex = t[0];
        GLES30.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex);
        GLES30.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        videoTexture = new android.graphics.SurfaceTexture(videoTex);
        videoTexture.setOnFrameAvailableListener(st -> videoFrame = true);
        videoSurface = new android.view.Surface(videoTexture);
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(path);
            mp.setSurface(videoSurface);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(m -> {
                synchronized (lock) {
                    if (m != player) return;
                    videoReady = true;
                    // Started even while hidden, then paused, so its first frame shows.
                    m.start();
                    if (!playing) m.pause();
                }
            });
            mp.setOnErrorListener((m, what, extra) -> {
                synchronized (lock) {
                    if (m == player) videoReady = false;
                }
                return true;
            });
            mp.prepareAsync();
        } catch (java.io.IOException | RuntimeException e) {
            mp.release();
            player = null;
        }
    }

    /** Stops the video and deletes its texture. On the GL thread, with {@link #lock} held. */
    private void stopVideo() {
        android.media.MediaPlayer mp = player;
        player = null;
        videoReady = false;
        videoFrame = false;
        if (mp != null) mp.release();
        if (videoSurface != null) videoSurface.release();
        videoSurface = null;
        if (videoTexture != null) videoTexture.release();
        videoTexture = null;
        if (videoTex != 0) GLES30.glDeleteTextures(1, new int[]{videoTex}, 0);
        videoTex = 0;
    }

    public void setSpec(ReactWallpapers.Spec spec) {
        this.spec = spec;
    }

    /** Fractions of the screen height to keep the characters out of at the top and bottom. */
    public void setInsets(float top, float bottom) {
        insetTop = top;
        insetBottom = bottom;
    }

    public void onSurfaceCreated(Context context) {
        water = new ShaderProgram(
                ShaderProgram.loadRaw(context, R.raw.wallpaper_vertex),
                ShaderProgram.loadRaw(context, R.raw.react_water_fragment));
        sprite = new ShaderProgram(
                ShaderProgram.loadRaw(context, R.raw.react_sprite_vertex),
                ShaderProgram.loadRaw(context, R.raw.react_sprite_fragment));
        // Textures from an old context are gone; the owner re-queues bitmaps after this.
        java.util.Arrays.fill(characterTex, 0);
        characters = 0;
        photoTex = 0;
        synchronized (lock) {
            // The video's texture went with the old context too: start it again on this one.
            videoTex = 0;
            if (!videoChanged && videoPath != null) {
                pendingVideo = videoPath;
                pendingVideoW = videoW;
                pendingVideoH = videoH;
                videoChanged = true;
            }
        }
        lastNanos = 0;
    }

    public void onSurfaceChanged(int width, int height) {
        surfaceW = Math.max(1, width);
        surfaceH = Math.max(1, height);
        GLES30.glViewport(0, 0, surfaceW, surfaceH);
    }

    /** Steps the physics with the tilt (-1..1, toward screen right and down) and draws a frame. */
    public void draw(float tiltX, float tiltY) {
        synchronized (lock) {
            if (pendingCharacters != null && pendingPivots != null && pendingHulls != null) {
                int n = pendingCharacters.length;
                for (int i = 0; i < n; i++) {
                    Bitmap b = pendingCharacters[i];
                    characterTex[i] = upload(characterTex[i], b);
                    characterW[i] = b.getWidth();
                    characterH[i] = b.getHeight();
                    pivots[i] = pendingPivots[i];
                    hulls[i] = pendingHulls[i];
                    b.recycle();
                }
                for (int i = n; i < MAX; i++) {
                    if (characterTex[i] != 0) GLES30.glDeleteTextures(1, new int[]{characterTex[i]}, 0);
                    characterTex[i] = 0;
                }
                characters = n;
                pendingCharacters = null;
            }
            if (pendingPhoto != null) {
                photoTex = upload(photoTex, pendingPhoto);
                photoW = pendingPhoto.getWidth();
                photoH = pendingPhoto.getHeight();
                pendingPhoto.recycle();
                pendingPhoto = null;
            } else if (photoCleared && photoTex != 0) {
                GLES30.glDeleteTextures(1, new int[]{photoTex}, 0);
                photoTex = 0;
            }
            photoCleared = false;
            if (videoChanged) {
                videoChanged = false;
                startVideo(pendingVideo, pendingVideoW, pendingVideoH);
            }
            if (videoTexture != null && videoFrame) {
                videoFrame = false;
                videoTexture.updateTexImage();
                videoTexture.getTransformMatrix(videoMatrix);
            }
        }

        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1 / 60f : (now - lastNanos) / 1e9f;
        lastNanos = now;

        ReactWallpapers.Spec s = spec;
        ReactPhysics ph = physics;
        float top = surfaceH * insetTop, bottom = surfaceH * insetBottom;
        ph.setScreen(surfaceW, surfaceH, top, bottom);
        int n = Math.max(1, characters);
        ph.setCount(n);
        // Never taller than most of the space it floats in, so it can still move about.
        float room = (surfaceH - top - bottom) * 0.85f;
        for (int i = 0; i < n; i++) {
            float size = i < s.sizes.length ? s.sizes[i] : 0.4f;
            float drawW = size * Math.min(surfaceW, surfaceH * 0.75f);
            float drawH = drawW * Math.max(1, characterH[i]) / Math.max(1, characterW[i]);
            if (drawH > room) {
                drawW *= room / drawH;
                drawH = room;
            }
            float[] pv = pivots[i];
            ph.setCharacter(i, drawW, drawH, pv != null ? pv[0] : 0.5f, pv != null ? pv[1] : 0.5f, hulls[i]);
        }
        ph.setDrag(ReactWallpapers.dragFor(s.style));
        ph.setSquish(s.squish);
        ph.step(dt, tiltX, tiltY);

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (water == null) return;
        drawWater(s, ph);
        if (characters > 0) drawCharacters(ph, s);
    }

    private void drawWater(ReactWallpapers.Spec s, ReactPhysics ph) {
        int[] style = ReactWallpapers.STYLES[s.style];
        boolean video = s.style == ReactWallpapers.STYLE_PHOTO && videoTex != 0 && videoReady;
        boolean photo = s.style == ReactWallpapers.STYLE_PHOTO && (video || photoTex != 0);
        water.use();
        GLES30.glUniform2f(water.uniform("u_res"), surfaceW, surfaceH);
        GLES30.glUniform1f(water.uniform("u_time"), ph.time());
        color(water.uniform("u_shallow"), style[0]);
        color(water.uniform("u_deep"), style[1]);
        color(water.uniform("u_light"), style[2]);
        GLES30.glUniform1f(water.uniform("u_tiles"), style[3]);
        GLES30.glUniform1f(water.uniform("u_mode"), style[4]);
        GLES30.glUniform1i(water.uniform("u_effects"), s.effects);
        GLES30.glUniform1i(water.uniform("u_layer"), s.layer);
        GLES30.glUniform1f(water.uniform("u_hasPhoto"), photo ? 1f : 0f);
        GLES30.glUniform1f(water.uniform("u_hasVideo"), video ? 1f : 0f);
        // Fixed units: a 2D and an external sampler may never share one.
        GLES30.glUniform1i(water.uniform("u_photo"), 0);
        GLES30.glUniform1i(water.uniform("u_video"), 1);
        if (video) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex);
            GLES30.glUniformMatrix4fv(water.uniform("u_videoMatrix"), 1, false, videoMatrix, 0);
        }
        if (photo) {
            // Cover-crop the photo (or video) to the screen's shape.
            float sa = (float) surfaceW / surfaceH;
            float pa = video ? (float) videoW / videoH : (float) photoW / photoH;
            float sx = 1f, sy = 1f;
            if (pa > sa) sx = sa / pa;
            else sy = pa / sa;
            GLES30.glUniform2f(water.uniform("u_photoScale"), sx, sy);
            if (!video) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTex);
            }
        }
        GLES30.glUniform4fv(water.uniform("u_ripples"), ReactPhysics.MAX_RIPPLES, ph.ripples, 0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, posBuf);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, uvBuf);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(0);
    }

    /** All the shadows (and glows) first, so none falls across another character. */
    private void drawCharacters(ReactPhysics ph, ReactWallpapers.Spec s) {
        sprite.use();
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glUniform1i(sprite.uniform("u_tex"), 0);
        GLES30.glUniform2f(sprite.uniform("u_res"), surfaceW, surfaceH);
        GLES30.glUniform2f(sprite.uniform("u_texel"), 1f / 60f, 1f / 60f);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, unitBuf);
        boolean glow = (s.effects & ReactWallpapers.EFFECT_GLOW) != 0;
        int light = ReactWallpapers.STYLES[s.style][2];
        float shift = Math.min(surfaceW, surfaceH) * 0.025f;
        float[] m = place;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < characters; i++) {
                if (characterTex[i] == 0) continue;
                place(ph.bodies[i], ph.time() + i * 1.3f, m);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, characterTex[i]);
                GLES30.glUniform4f(sprite.uniform("u_matrix"), m[0], m[1], m[2], m[3]);
                if (pass == 0) {
                    // Shadow: offset down and to the right, as if lit from the top left.
                    GLES30.glUniform1f(sprite.uniform("u_shadow"), 1f);
                    GLES30.glUniform1f(sprite.uniform("u_grow"), 0.08f);
                    GLES30.glUniform2f(sprite.uniform("u_offset"), m[4] + shift, m[5] + shift * 1.4f);
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                    if (glow) {
                        // A soft, breathing halo in the scene's light colour right around it.
                        GLES30.glUniform1f(sprite.uniform("u_shadow"), 2f);
                        color(sprite.uniform("u_glow"), light);
                        GLES30.glUniform1f(sprite.uniform("u_pulse"), 0.75f + 0.25f * (float) Math.sin(ph.time() * 1.6 + i));
                        GLES30.glUniform2f(sprite.uniform("u_offset"), m[4], m[5]);
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                    }
                } else {
                    GLES30.glUniform1f(sprite.uniform("u_shadow"), 0f);
                    GLES30.glUniform1f(sprite.uniform("u_grow"), 0f);
                    GLES30.glUniform2f(sprite.uniform("u_offset"), m[4], m[5]);
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                }
            }
        }
        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    /** The unit square -> pixels transform for a body: m00, m01, m10, m11, offset x, offset y. */
    private static void place(ReactPhysics.Body b, float time, float[] out) {
        float w = b.w, h = b.h;
        // Squash along the contact normal, a little stretch across it, plus a slow bob.
        float bob = 1f + 0.012f * (float) Math.sin(time * 1.7);
        float along = (1f - b.squash) * bob, across = (1f + b.squash * 0.8f) * bob;
        float nx = b.squashNx, ny = b.squashNy;
        // S = along * n n^T + across * (I - n n^T)
        float s00 = across + (along - across) * nx * nx;
        float s01 = (along - across) * nx * ny;
        float s11 = across + (along - across) * ny * ny;
        float cos = (float) Math.cos(b.angle), sin = (float) Math.sin(b.angle);
        // M = S * R * diag(w, h): unit square -> pixels around the pivot.
        float r00 = cos * w, r01 = -sin * h, r10 = sin * w, r11 = cos * h;
        out[0] = s00 * r00 + s01 * r10;
        out[1] = s00 * r01 + s01 * r11;
        out[2] = s01 * r00 + s11 * r10;
        out[3] = s01 * r01 + s11 * r11;
        // The pivot (in the unit square) lands on the physics position.
        out[4] = b.x - (out[0] * b.pivotX + out[1] * b.pivotY);
        out[5] = b.y - (out[2] * b.pivotX + out[3] * b.pivotY);
    }

    private static void color(int location, int rgb) {
        GLES30.glUniform3f(location, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
    }

    private static int upload(int tex, Bitmap bitmap) {
        if (tex == 0) {
            int[] ids = new int[1];
            GLES30.glGenTextures(1, ids, 0);
            tex = ids[0];
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        return tex;
    }

    public void releaseGl() {
        for (int i = 0; i < MAX; i++) {
            if (characterTex[i] != 0) GLES30.glDeleteTextures(1, new int[]{characterTex[i]}, 0);
            characterTex[i] = 0;
        }
        characters = 0;
        if (photoTex != 0) GLES30.glDeleteTextures(1, new int[]{photoTex}, 0);
        photoTex = 0;
        synchronized (lock) {
            stopVideo();
            // Kept so a new surface plays it again.
            if (videoPath != null && !videoChanged) {
                pendingVideo = videoPath;
                pendingVideoW = videoW;
                pendingVideoH = videoH;
                videoChanged = true;
            }
        }
        if (water != null) water.release();
        if (sprite != null) sprite.release();
        water = null;
        sprite = null;
    }

    public void dispose() {
        synchronized (lock) {
            recyclePending();
            if (pendingPhoto != null) pendingPhoto.recycle();
            pendingPhoto = null;
            // Off the GL thread: the player goes, its texture went with the context.
            android.media.MediaPlayer mp = player;
            player = null;
            videoReady = false;
            if (mp != null) mp.release();
        }
    }
}
