package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GLES3 renderer for 4D wallpapers: three pre-cut layers (far, mid, near) that slide rigidly by
 * device tilt, plus an optional extra cut-out with its own slide direction. Shared by {@link FourDWallpaperService} and the in-app {@link FourDView} preview.
 *
 * <p>{@link #setLayers} may be called from any thread; bitmaps are uploaded, swapped and recycled
 * under {@code mTextureLock} on the GL thread. Everything else runs on the GL thread.</p>
 */
public class FourDRenderer {

    /** Max slide in uv units at full tilt, before the per-layer factor. */
    static final float STRENGTH = 0.04f;
    /** Extra zoom so the slid layers never reveal their edges. */
    static final float OVERSCAN = 0.92f;
    /** How far each layer slides: background against the tilt, foreground with it. */
    static final float FACTOR_FAR = -0.35f, FACTOR_MID = 0.05f, FACTOR_NEAR = 0.6f;

    private static final float[] QUAD_POS = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    // Bitmap row 0 is uploaded at v = 0, so the bottom of the screen samples v = 1.
    private static final float[] QUAD_UV = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};
    private static final String[] SAMPLERS = {"u_far", "u_mid", "u_near", "u_extra"};

    private final Object mTextureLock = new Object();
    private Bitmap[] pending;

    private final FloatBuffer posBuf = buffer(QUAD_POS);
    private final FloatBuffer uvBuf = buffer(QUAD_UV);
    /** far, mid, near, extra (0 when the image has no extra layer). */
    private final int[] textures = new int[4];
    private boolean hasExtra;
    @androidx.annotation.Nullable
    private volatile float[] extraMotion;
    private final float[] extraOffset = new float[2];
    private volatile float motionStrength = 1f, midFactor = FACTOR_MID;

    /** Motion of the extra layer, see {@link FourDWallpapers#extraMotion}; null if none. */
    public void setExtraMotion(@androidx.annotation.Nullable float[] motion) {
        extraMotion = motion;
    }
    private ShaderProgram program;
    private int imageW = 1, imageH = 1;
    private int surfaceW = 1, surfaceH = 1;
    private volatile float focalX = 0.5f, focalY = 0.5f;
    /** Width / height the frame is shown at, when the owner knows it; 0 uses the surface's. */
    private volatile float viewAspect;

    /**
     * Shape the picture is actually displayed at. A view's GL buffer can lag behind its layout
     * (e.g. right after a re-layout) and is then stretched to fit; cropping for the displayed
     * shape keeps the image undistorted regardless.
     */
    public void setViewAspect(float aspect) {
        viewAspect = aspect;
    }

    /** Per-image motion, {@link FourDWallpapers#motion}'s {strength multiplier, mid layer factor}. */
    public void setMotion(float[] motion) {
        motionStrength = motion[0];
        midFactor = motion[1];
    }

    /** Subject focal point (0..1) the crop window centres on; see {@link Focal}. */
    public void setFocal(float[] focal) {
        focalX = focal[0];
        focalY = focal[1];
    }

    private static FloatBuffer buffer(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);
        return b;
    }

    /** Queues new far/mid/near(/extra) layers. The renderer takes ownership and recycles them. */
    public void setLayers(Bitmap[] layers) {
        synchronized (mTextureLock) {
            recycle(pending);
            pending = layers;
        }
    }

    private static void recycle(@androidx.annotation.Nullable Bitmap[] layers) {
        if (layers != null) for (Bitmap b : layers) if (b != null) b.recycle();
    }

    public void onSurfaceCreated(Context context) {
        program = new ShaderProgram(
                ShaderProgram.loadRaw(context, R.raw.wallpaper_vertex),
                ShaderProgram.loadRaw(context, R.raw.fourd_fragment));
        // Textures from an old context are gone; the owner re-queues layers after this.
        java.util.Arrays.fill(textures, 0);
        hasExtra = false;
    }

    public void onSurfaceChanged(int width, int height) {
        surfaceW = Math.max(1, width);
        surfaceH = Math.max(1, height);
        GLES30.glViewport(0, 0, surfaceW, surfaceH);
    }

    /** Draws one frame; {@code tiltX/tiltY} are in -1..1. */
    public void draw(float tiltX, float tiltY) {
        synchronized (mTextureLock) {
            if (pending != null) {
                for (int i = 0; i < 3; i++) textures[i] = upload(textures[i], pending[i]);
                hasExtra = pending.length > 3 && pending[3] != null;
                if (hasExtra) textures[3] = upload(textures[3], pending[3]);
                imageW = pending[0].getWidth();
                imageH = pending[0].getHeight();
                recycle(pending);
                pending = null;
            }
        }
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (program == null || textures[0] == 0) return;

        // Cover-crop: show the largest centred region with the surface's aspect ratio.
        float surfaceAspect = viewAspect > 0f ? viewAspect : (float) surfaceW / surfaceH;
        float imageAspect = (float) imageW / imageH;
        float sx = 1f, sy = 1f;
        if (imageAspect > surfaceAspect) sx = surfaceAspect / imageAspect;
        else sy = imageAspect / surfaceAspect;

        program.use();
        for (int i = 0; i < 4; i++) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i]);
            GLES30.glUniform1i(program.uniform(SAMPLERS[i]), i);
        }
        GLES30.glUniform2f(program.uniform("u_scale"), sx * OVERSCAN, sy * OVERSCAN);
        float[] center = Focal.windowCenter(focalX, focalY, sx * OVERSCAN, sy * OVERSCAN);
        GLES30.glUniform2f(program.uniform("u_center"), center[0], center[1]);
        // Tilting right moves near layers right; v grows downward in texture space.
        float strength = STRENGTH * motionStrength;
        float ox = -tiltX * strength * sx, oy = tiltY * strength * sy;
        GLES30.glUniform2f(program.uniform("u_offset"), ox, oy);
        GLES30.glUniform3f(program.uniform("u_factors"), FACTOR_FAR, midFactor, FACTOR_NEAR);
        float[] motion = extraMotion;
        boolean extra = hasExtra && motion != null;
        if (extra) FourDWallpapers.extraOffset(motion, ox, oy, imageW, imageH, extraOffset);
        GLES30.glUniform2f(program.uniform("u_extraOffset"), extra ? extraOffset[0] : 0f, extra ? extraOffset[1] : 0f);
        GLES30.glUniform1f(program.uniform("u_hasExtra"), extra ? 1f : 0f);

        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, posBuf);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, uvBuf);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    }

    private static int upload(int tex, Bitmap bitmap) {
        if (tex == 0) {
            int[] ids = new int[1];
            GLES30.glGenTextures(1, ids, 0);
            tex = ids[0];
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        return tex;
    }

    public void releaseGl() {
        for (int i = 0; i < textures.length; i++) {
            if (textures[i] != 0) GLES30.glDeleteTextures(1, textures, i);
            textures[i] = 0;
        }
        hasExtra = false;
        if (program != null) {
            program.release();
            program = null;
        }
    }

    public void dispose() {
        synchronized (mTextureLock) {
            recycle(pending);
            pending = null;
        }
    }
}
