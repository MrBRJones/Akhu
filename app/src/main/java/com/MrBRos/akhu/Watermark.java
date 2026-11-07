package com.MrBRos.akhu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * The preview a post for sale shows to people who haven't bought it: smaller than the real thing
 * and covered in a diagonal "AKHU PREVIEW" pattern, so it's no use as a wallpaper on its own.
 */
final class Watermark {

    /** Previews are at most this many pixels on their longer side. */
    static final int PREVIEW_MAX_DIM = 1080;
    private static final String TEXT = "AKHU  PREVIEW";

    private Watermark() {
    }

    /** A new watermarked copy of {@code src}, scaled down to {@code maxDim} if it's bigger. */
    static Bitmap preview(Bitmap src, int maxDim) {
        float scale = Math.min(1f, maxDim / (float) Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, Math.round(src.getWidth() * scale));
        int h = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap out = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(Math.max(w, h) / 18f);
        // White text with a dark edge stays visible on light and dark pictures alike.
        paint.setColor(Color.argb(110, 255, 255, 255));
        paint.setShadowLayer(paint.getTextSize() / 12f, 0, 0, Color.argb(120, 0, 0, 0));
        float step = paint.getTextSize() * 3.2f;
        float textWidth = paint.measureText(TEXT);
        canvas.save();
        canvas.rotate(-30, w / 2f, h / 2f);
        float diag = (float) Math.hypot(w, h);
        int row = 0;
        for (float y = h / 2f - diag; y < h / 2f + diag; y += step, row++) {
            // Alternate rows shift by half a word so the pattern can't be cropped around.
            float offset = (row % 2) * (textWidth + step) / 2f;
            for (float x = w / 2f - diag - offset; x < w / 2f + diag; x += textWidth + step) {
                canvas.drawText(TEXT, x, y, paint);
            }
        }
        canvas.restore();
        return out;
    }
}
