package com.MrBRos.akhu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import androidx.annotation.Nullable;

/** Colour filters for 2D wallpapers, each a single ColorMatrix so they're cheap at any size. */
final class Filters {

    static final int ORIGINAL = 0;

    static final int[] NAMES = {
            R.string.filter_original, R.string.filter_mono, R.string.filter_noir, R.string.filter_sepia,
            R.string.filter_vivid, R.string.filter_warm, R.string.filter_cool, R.string.filter_fade,
            R.string.filter_invert,
    };

    private Filters() {
    }

    /** The matrix for filter {@code index}, or null for Original. */
    @Nullable
    static ColorMatrix matrix(int index) {
        ColorMatrix m = new ColorMatrix();
        switch (index) {
            case 1: // Mono
                m.setSaturation(0f);
                return m;
            case 2: // Noir: mono with hard contrast
                m.setSaturation(0f);
                m.postConcat(contrast(1.5f));
                return m;
            case 3: // Sepia
                m.setSaturation(0f);
                m.postConcat(new ColorMatrix(new float[]{
                        1.07f, 0, 0, 0, 20,
                        0, 0.95f, 0, 0, 5,
                        0, 0, 0.75f, 0, -10,
                        0, 0, 0, 1, 0}));
                return m;
            case 4: // Vivid
                m.setSaturation(1.6f);
                m.postConcat(contrast(1.1f));
                return m;
            case 5: // Warm
                m.setScale(1.1f, 1.02f, 0.85f, 1f);
                return m;
            case 6: // Cool
                m.setScale(0.88f, 1f, 1.12f, 1f);
                return m;
            case 7: // Fade: washed out, lifted blacks
                m.setSaturation(0.7f);
                m.postConcat(new ColorMatrix(new float[]{
                        0.8f, 0, 0, 0, 40,
                        0, 0.8f, 0, 0, 40,
                        0, 0, 0.8f, 0, 40,
                        0, 0, 0, 1, 0}));
                return m;
            case 8: // Invert
                m.set(new float[]{
                        -1, 0, 0, 0, 255,
                        0, -1, 0, 0, 255,
                        0, 0, -1, 0, 255,
                        0, 0, 0, 1, 0});
                return m;
            default:
                return null;
        }
    }

    private static ColorMatrix contrast(float c) {
        float t = 128f * (1f - c);
        return new ColorMatrix(new float[]{
                c, 0, 0, 0, t,
                0, c, 0, 0, t,
                0, 0, c, 0, t,
                0, 0, 0, 1, 0});
    }

    /** The filter baked into a size³ colour cube of ARGB values indexed [r][g][b], for video export. */
    static int[][][] lutCube(int index, int size) {
        ColorMatrix m = matrix(index);
        float[] a = m == null ? new ColorMatrix().getArray() : m.getArray();
        int[][][] cube = new int[size][size][size];
        for (int ri = 0; ri < size; ri++) {
            for (int gi = 0; gi < size; gi++) {
                for (int bi = 0; bi < size; bi++) {
                    float r = ri * 255f / (size - 1);
                    float g = gi * 255f / (size - 1);
                    float b = bi * 255f / (size - 1);
                    int ro = clamp(a[0] * r + a[1] * g + a[2] * b + a[3] * 255 + a[4]);
                    int go = clamp(a[5] * r + a[6] * g + a[7] * b + a[8] * 255 + a[9]);
                    int bo = clamp(a[10] * r + a[11] * g + a[12] * b + a[13] * 255 + a[14]);
                    cube[ri][gi][bi] = 0xff000000 | (ro << 16) | (go << 8) | bo;
                }
            }
        }
        return cube;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /** A new bitmap with the filter applied; the source is left as it is. */
    static Bitmap apply(Bitmap source, int index) {
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix m = matrix(index);
        if (m != null) paint.setColorFilter(new ColorMatrixColorFilter(m));
        new Canvas(out).drawBitmap(source, 0, 0, paint);
        return out;
    }
}
