package com.MrBRos.wallpapercreator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * CPU-side particle simulation. Positions are in normalized screen space
 * (0..1, y up) and are packed into two vec4[64] uniform arrays:
 * posSize = (x, y, size, alpha), color = (r, g, b, 0).
 */
public class ParticleSystem {

    public static final int MAX = EffectConfig.MAX_PARTICLES;

    private final float[] x = new float[MAX];
    private final float[] y = new float[MAX];
    private final float[] vx = new float[MAX];
    private final float[] vy = new float[MAX];
    private final float[] size = new float[MAX];
    private final float[] phase = new float[MAX];
    private final float[][] color = new float[MAX][3];
    private final Random random = new Random();
    private float time;

    public final FloatBuffer posSizeBuf = newBuffer();
    public final FloatBuffer colorBuf = newBuffer();

    public ParticleSystem() {
        for (int i = 0; i < MAX; i++) {
            spawn(i, true);
        }
    }

    private static FloatBuffer newBuffer() {
        return ByteBuffer.allocateDirect(MAX * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private void spawn(int i, boolean anywhere) {
        x[i] = random.nextFloat();
        y[i] = anywhere ? random.nextFloat() : -0.05f;
        vx[i] = (random.nextFloat() - 0.5f) * 0.02f;
        vy[i] = 0.015f + random.nextFloat() * 0.04f;
        size[i] = 0.004f + random.nextFloat() * 0.014f;
        phase[i] = random.nextFloat() * 6.2831855f;
        // Warm, slightly varied palette.
        float warm = random.nextFloat();
        color[i][0] = 1.0f;
        color[i][1] = 0.65f + 0.35f * warm;
        color[i][2] = 0.35f + 0.55f * random.nextFloat();
    }

    /** Advances the simulation and repacks the first {@code count} particles. */
    public void update(float dt, int count) {
        time += dt;
        count = Math.min(count, MAX);
        posSizeBuf.clear();
        colorBuf.clear();
        for (int i = 0; i < MAX; i++) {
            if (i < count) {
                x[i] += (vx[i] + 0.01f * (float) Math.sin(time * 0.7f + phase[i])) * dt;
                y[i] += vy[i] * dt;
                if (y[i] > 1.05f || x[i] < -0.05f || x[i] > 1.05f) {
                    spawn(i, false);
                }
            }
            float alpha = i < count ? 0.45f + 0.35f * (float) Math.sin(time * 1.3f + phase[i]) : 0f;
            posSizeBuf.put(x[i]).put(y[i]).put(size[i]).put(alpha);
            colorBuf.put(color[i][0]).put(color[i][1]).put(color[i][2]).put(0f);
        }
        posSizeBuf.position(0);
        colorBuf.position(0);
    }
}
