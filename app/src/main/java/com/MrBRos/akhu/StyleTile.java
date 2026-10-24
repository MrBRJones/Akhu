package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * One tile in the text tool's Fonts / Animation / Effects grids: its name drawn in the style it
 * picks, on a rounded dark tile that's outlined when chosen.
 */
public class StyleTile extends View {

    private final MotionOverlay sample = new MotionOverlay(MotionOverlay.TEXT);
    private final OverlayRenderer renderer = new OverlayRenderer();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density = getResources().getDisplayMetrics().density;

    public StyleTile(Context context, String name, int font, int effect) {
        super(context);
        sample.text = name;
        sample.font = font;
        sample.effect = effect;
        // Blur effects (Neon) need a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = 12 * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2A2C33);
        c.drawRoundRect(0, 0, w, h, r, r, paint);
        if (isSelected()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            paint.setColor(0xFFFFFFFF);
            c.drawRoundRect(density, density, w - density, h - density, r, r, paint);
        }
        // Text height about a third of the tile, shrunk if the name is too wide.
        sample.size = 15 * density / w;
        float[] half = renderer.halfSize(sample, (int) w);
        if (half[0] * 2 > w * 0.85f) sample.size *= w * 0.85f / (half[0] * 2);
        renderer.draw(c, (int) w, (int) h, sample, 0);
    }
}
