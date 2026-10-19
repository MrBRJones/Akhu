package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GLES3 renderer for the live wallpaper. Ken Burns pan/zoom is applied on the CPU
 * by rewriting the quad's texture coordinates each frame; the fragment shader runs
 * the five effect layers and the particle pass.
 *
 * <p>Threading: {@link #setBitmap} and {@link #setConfig} may be called from any thread.
 * Everything else runs on the render thread with the EGL context current. Pending
 * bitmaps are uploaded, swapped in and recycled under {@code mTextureLock} on the
 * render thread so a bitmap is never recycled while GL is reading it.</p>
 */
public class GLWallpaperRenderer {

    private static final float[] QUAD_POS = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};

    private final Object mTextureLock = new Object();
    private Bitmap mPendingBitmap;

    private final ParticleSystem particles = new ParticleSystem();
    private final FloatBuffer posBuf = newFloatBuffer(8);
    private final FloatBuffer texBuf = newFloatBuffer(8);

    private volatile EffectConfig config;
    private ShaderProgram program;
    private int textureId;
    private int texW, texH;
    private int surfaceW = 1, surfaceH = 1;
    private long startNanos;
    private long lastNanos;

    public GLWallpaperRenderer(EffectConfig config) {
        this.config = config;
        posBuf.put(QUAD_POS).position(0);
    }

    private static FloatBuffer newFloatBuffer(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public void setConfig(EffectConfig config) {
        this.config = config;
    }

    /** Queues a bitmap for upload. The renderer takes ownership and recycles it. */
    public void setBitmap(Bitmap bitmap) {
        synchronized (mTextureLock) {
            if (mPendingBitmap != null && mPendingBitmap != bitmap) {
                mPendingBitmap.recycle();
            }
            mPendingBitmap = bitmap;
        }
    }

    public void onSurfaceCreated(Context context) {
        if (program == null) {
            program = new ShaderProgram(
                    ShaderProgram.loadRaw(context, R.raw.wallpaper_vertex),
                    ShaderProgram.loadRaw(context, R.raw.wallpaper_fragment));
        }
        startNanos = lastNanos = System.nanoTime();
    }

    public void onSurfaceChanged(int width, int height) {
        surfaceW = Math.max(1, width);
        surfaceH = Math.max(1, height);
        GLES30.glViewport(0, 0, surfaceW, surfaceH);
    }

    public void drawFrame() {
        synchronized (mTextureLock) {
            if (mPendingBitmap != null) {
                uploadTexture(mPendingBitmap);
                mPendingBitmap.recycle();
                mPendingBitmap = null;
            }
        }

        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        float t = (now - startNanos) / 1e9f;
        lastNanos = now;

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (textureId == 0 || program == null) return;

        EffectConfig cfg = config;
        updateKenBurns(cfg, t);

        program.use();
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glUniform1i(program.uniform("u_texture"), 0);
        GLES30.glUniform1f(program.uniform("u_time"), t);
        GLES30.glUniform2f(program.uniform("u_resolution"), surfaceW, surfaceH);
        GLES30.glUniform2f(program.uniform("u_texel"), 1f / texW, 1f / texH);

        for (int i = 0; i < EffectConfig.LAYERS.length; i++) {
            EffectConfig.Layer layer = EffectConfig.LAYERS[i];
            GLES30.glUniform1i(program.uniform(layer.enabledUniform()), cfg.isEnabled(i) ? 1 : 0);
            for (int j = 0; j < layer.params.length; j++) {
                GLES30.glUniform1f(program.uniform(layer.paramUniform(layer.params[j])), cfg.get(i, j));
            }
        }

        int count = cfg.particleCount;
        particles.update(dt, count);
        GLES30.glUniform1i(program.uniform("u_particle_count"), count);
        GLES30.glUniform4fv(program.uniform("u_particle_posSize"), ParticleSystem.MAX, particles.posSizeBuf);
        GLES30.glUniform4fv(program.uniform("u_particle_color"), ParticleSystem.MAX, particles.colorBuf);

        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, posBuf);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texBuf);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisableVertexAttribArray(1);
    }

    /** Center-crops the image to the surface, then pans and zooms over time. */
    private void updateKenBurns(EffectConfig cfg, float t) {
        float surfaceAspect = (float) surfaceW / surfaceH;
        float imageAspect = (float) texW / texH;
        float uw = 1f, vh = 1f;
        if (imageAspect > surfaceAspect) {
            uw = surfaceAspect / imageAspect;
        } else {
            vh = imageAspect / surfaceAspect;
        }
        float s = cfg.kbSpeed;
        float zoomPhase = 0.5f + 0.5f * (float) Math.sin(t * s * 0.25f);
        float scale = 1f + (cfg.kbZoom - 1f) * zoomPhase;
        uw /= scale;
        vh /= scale;
        float cx = 0.5f + (1f - uw) * 0.5f * (float) Math.sin(t * s * 0.17f);
        float cy = 0.5f + (1f - vh) * 0.5f * (float) Math.cos(t * s * 0.13f);
        float u0 = cx - uw / 2f, u1 = cx + uw / 2f;
        float v0 = cy - vh / 2f, v1 = cy + vh / 2f;
        // Bitmap row 0 is uploaded at v = 0, so the bottom of the screen samples v1.
        texBuf.clear();
        texBuf.put(u0).put(v1).put(u1).put(v1).put(u0).put(v0).put(u1).put(v0);
        texBuf.position(0);
    }

    private void uploadTexture(Bitmap bitmap) {
        if (textureId == 0) {
            int[] ids = new int[1];
            GLES30.glGenTextures(1, ids, 0);
            textureId = ids[0];
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        texW = bitmap.getWidth();
        texH = bitmap.getHeight();
    }

    /** Frees GL objects; must run on the render thread while the context is current. */
    public void releaseGl() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
        if (program != null) {
            program.release();
            program = null;
        }
    }

    /** Drops any queued bitmap that was never uploaded. */
    public void dispose() {
        synchronized (mTextureLock) {
            if (mPendingBitmap != null) {
                mPendingBitmap.recycle();
                mPendingBitmap = null;
            }
        }
    }
}
