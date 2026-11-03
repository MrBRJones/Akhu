package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GLES3 renderer for reactive wallpapers: the water (or photo) background, then the character's
 * shadow and the character, placed by {@link ReactPhysics}. Shared by
 * {@link ReactWallpaperService} and the in-app {@link ReactView}.
 *
 * <p>{@link #setCharacter}, {@link #setSpec} and {@link #setPhoto} may be called from any thread;
 * bitmaps are uploaded and recycled on the GL thread. Everything else runs on the GL thread.</p>
 */
public class ReactRenderer {

    private static final float[] QUAD_POS = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] QUAD_UV = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
    private static final float[] UNIT = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private final FloatBuffer posBuf = buffer(QUAD_POS);
    private final FloatBuffer uvBuf = buffer(QUAD_UV);
    private final FloatBuffer unitBuf = buffer(UNIT);
    final ReactPhysics physics = new ReactPhysics();

    private final Object lock = new Object();
    @Nullable
    private Bitmap pendingCharacter, pendingPhoto;
    private boolean photoCleared;
    private volatile ReactWallpapers.Spec spec = new ReactWallpapers.Spec();
    private volatile float[] pivot = {0.5f, 0.5f};

    private ShaderProgram water, sprite;
    private int characterTex, photoTex;
    private int characterW = 1, characterH = 1, photoW = 1, photoH = 1;
    private int surfaceW = 1, surfaceH = 1;
    /** Fraction of the height kept clear at the top and bottom (status and navigation bars). */
    private volatile float insetTop = 0.05f, insetBottom = 0.05f;
    private long lastNanos;

    private static FloatBuffer buffer(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);
        return b;
    }

    /** Queues the character; the renderer takes ownership and recycles it. */
    public void setCharacter(Bitmap character) {
        float[] p = ReactWallpapers.pivot(character);
        synchronized (lock) {
            if (pendingCharacter != null) pendingCharacter.recycle();
            pendingCharacter = character;
            pivot = p;
        }
    }

    /** Queues the background photo (null for drawn water); the renderer takes ownership. */
    public void setPhoto(@Nullable Bitmap photo) {
        synchronized (lock) {
            if (pendingPhoto != null) pendingPhoto.recycle();
            pendingPhoto = photo;
            photoCleared = photo == null;
        }
    }

    public void setSpec(ReactWallpapers.Spec spec) {
        this.spec = spec;
    }

    /** Fractions of the screen height to keep the character out of at the top and bottom. */
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
        characterTex = 0;
        photoTex = 0;
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
            if (pendingCharacter != null) {
                characterTex = upload(characterTex, pendingCharacter);
                characterW = pendingCharacter.getWidth();
                characterH = pendingCharacter.getHeight();
                pendingCharacter.recycle();
                pendingCharacter = null;
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
        }

        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1 / 60f : (now - lastNanos) / 1e9f;
        lastNanos = now;

        ReactWallpapers.Spec s = spec;
        float drawW = s.size * Math.min(surfaceW, surfaceH * 0.75f);
        float drawH = drawW * characterH / characterW;
        float[] pv = pivot;
        ReactPhysics ph = physics;
        ph.setScreen(surfaceW, surfaceH, surfaceH * insetTop, surfaceH * insetBottom);
        ph.setCharacter(drawW, drawH, pv[0], pv[1]);
        ph.setDrag(ReactWallpapers.dragFor(s.style));
        ph.step(dt, tiltX, tiltY);

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (water == null) return;
        drawWater(s, ph);
        if (characterTex != 0) drawCharacter(ph);
    }

    private void drawWater(ReactWallpapers.Spec s, ReactPhysics ph) {
        int[] style = ReactWallpapers.STYLES[s.style];
        boolean photo = s.style == ReactWallpapers.STYLE_PHOTO && photoTex != 0;
        water.use();
        GLES30.glUniform2f(water.uniform("u_res"), surfaceW, surfaceH);
        GLES30.glUniform1f(water.uniform("u_time"), ph.time());
        color(water.uniform("u_shallow"), style[0]);
        color(water.uniform("u_deep"), style[1]);
        color(water.uniform("u_light"), style[2]);
        GLES30.glUniform1f(water.uniform("u_tiles"), style[3]);
        GLES30.glUniform1f(water.uniform("u_mode"), style[4]);
        GLES30.glUniform1f(water.uniform("u_hasPhoto"), photo ? 1f : 0f);
        if (photo) {
            // Cover-crop the photo to the screen's shape.
            float sa = (float) surfaceW / surfaceH, pa = (float) photoW / photoH;
            float sx = 1f, sy = 1f;
            if (pa > sa) sx = sa / pa;
            else sy = pa / sa;
            GLES30.glUniform2f(water.uniform("u_photoScale"), sx, sy);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTex);
            GLES30.glUniform1i(water.uniform("u_photo"), 0);
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

    private void drawCharacter(ReactPhysics ph) {
        float w = ph.charW(), h = ph.charH();
        // Squash along the wall's normal, a little stretch across it, plus a slow bob.
        float bob = 1f + 0.012f * (float) Math.sin(ph.time() * 1.7);
        float along = (1f - ph.squash) * bob, across = (1f + ph.squash * 0.6f) * bob;
        float nx = ph.squashNx, ny = ph.squashNy;
        // S = along * n n^T + across * (I - n n^T)
        float s00 = across + (along - across) * nx * nx;
        float s01 = (along - across) * nx * ny;
        float s11 = across + (along - across) * ny * ny;
        float cos = (float) Math.cos(ph.angle), sin = (float) Math.sin(ph.angle);
        // M = S * R * diag(w, h): unit square -> pixels around the pivot.
        float r00 = cos * w, r01 = -sin * h, r10 = sin * w, r11 = cos * h;
        float m00 = s00 * r00 + s01 * r10, m01 = s00 * r01 + s01 * r11;
        float m10 = s01 * r00 + s11 * r10, m11 = s01 * r01 + s11 * r11;
        float px = ph.pivotX(), py = ph.pivotY();
        // The pivot (px, py in the unit square) lands on the physics position.
        float ox = ph.x - (m00 * px + m01 * py), oy = ph.y - (m10 * px + m11 * py);

        sprite.use();
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, characterTex);
        GLES30.glUniform1i(sprite.uniform("u_tex"), 0);
        GLES30.glUniform2f(sprite.uniform("u_res"), surfaceW, surfaceH);
        GLES30.glUniform4f(sprite.uniform("u_matrix"), m00, m01, m10, m11);
        GLES30.glUniform2f(sprite.uniform("u_texel"), 1f / 60f, 1f / 60f);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, unitBuf);

        // Shadow: offset down and to the right, as if lit from the top left.
        float shift = Math.min(surfaceW, surfaceH) * 0.025f;
        GLES30.glUniform1f(sprite.uniform("u_shadow"), 1f);
        GLES30.glUniform2f(sprite.uniform("u_offset"), ox + shift, oy + shift * 1.4f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glUniform1f(sprite.uniform("u_shadow"), 0f);
        GLES30.glUniform2f(sprite.uniform("u_offset"), ox, oy);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisable(GLES30.GL_BLEND);
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
        for (int t : new int[]{characterTex, photoTex}) if (t != 0) GLES30.glDeleteTextures(1, new int[]{t}, 0);
        characterTex = 0;
        photoTex = 0;
        if (water != null) water.release();
        if (sprite != null) sprite.release();
        water = null;
        sprite = null;
    }

    public void dispose() {
        synchronized (lock) {
            if (pendingCharacter != null) pendingCharacter.recycle();
            if (pendingPhoto != null) pendingPhoto.recycle();
            pendingCharacter = null;
            pendingPhoto = null;
        }
    }
}
