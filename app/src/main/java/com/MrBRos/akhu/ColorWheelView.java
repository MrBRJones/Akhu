package com.MrBRos.akhu;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * A hue ring around a saturation/brightness square, like paint apps use. Drag the ring to pick the
 * hue and the square to pick how strong and how bright it is.
 */
public class ColorWheelView extends View {

    public interface OnColorChangedListener {
        void onColorChanged(float[] hsv);
    }

    private final float[] hsv = {0f, 1f, 1f};
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint squarePaint = new Paint();
    private final Paint thumbFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF square = new RectF();
    private float cx, cy, ringRadius, ringWidth, thumbRadius;
    private boolean draggingRing;
    @Nullable
    private OnColorChangedListener listener;

    public ColorWheelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        thumbRadius = 11 * d;
        ringPaint.setStyle(Paint.Style.STROKE);
        thumbRim.setStyle(Paint.Style.STROKE);
        thumbRim.setStrokeWidth(3 * d);
        thumbRim.setColor(Color.WHITE);
        thumbRim.setShadowLayer(2 * d, 0, 0, 0x66000000);
    }

    public void setOnColorChangedListener(@Nullable OnColorChangedListener l) {
        listener = l;
    }

    public void setHsv(float[] value) {
        System.arraycopy(value, 0, hsv, 0, 3);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? w : MeasureSpec.getSize(heightSpec);
        int side = Math.min(w, h);
        setMeasuredDimension(w, side);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cx = w / 2f;
        cy = h / 2f;
        float outer = Math.min(w, h) / 2f - thumbRadius - 2;
        ringWidth = outer * 0.16f;
        ringRadius = outer - ringWidth / 2f;
        ringPaint.setStrokeWidth(ringWidth);
        int[] hues = new int[7];
        for (int i = 0; i < hues.length; i++) hues[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
        ringPaint.setShader(new SweepGradient(cx, cy, hues, null));
        float half = (ringRadius - ringWidth / 2f - thumbRadius * 0.8f) / (float) Math.sqrt(2);
        square.set(cx - half, cy - half, cx + half, cy + half);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawCircle(cx, cy, ringRadius, ringPaint);

        int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
        squarePaint.setShader(new LinearGradient(square.left, 0, square.right, 0, Color.WHITE, pure, Shader.TileMode.CLAMP));
        canvas.drawRect(square, squarePaint);
        squarePaint.setShader(new LinearGradient(0, square.top, 0, square.bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        canvas.drawRect(square, squarePaint);

        double a = Math.toRadians(hsv[0]);
        drawThumb(canvas, cx + (float) Math.cos(a) * ringRadius, cy + (float) Math.sin(a) * ringRadius, pure);
        drawThumb(canvas, square.left + hsv[1] * square.width(), square.top + (1f - hsv[2]) * square.height(),
                Color.HSVToColor(hsv));
    }

    private void drawThumb(Canvas canvas, float x, float y, int color) {
        thumbFill.setColor(color);
        canvas.drawCircle(x, y, thumbRadius, thumbFill);
        canvas.drawCircle(x, y, thumbRadius, thumbRim);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float dist = (float) Math.hypot(x - cx, y - cy);
                draggingRing = dist > ringRadius - ringWidth / 2f - thumbRadius * 0.5f;
                if (!draggingRing && !square.contains(x, y)) {
                    float slack = thumbRadius;
                    if (x < square.left - slack || x > square.right + slack
                            || y < square.top - slack || y > square.bottom + slack) return false;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (draggingRing) {
                    float deg = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
                    hsv[0] = (deg + 360f) % 360f;
                } else {
                    hsv[1] = clamp((x - square.left) / square.width());
                    hsv[2] = 1f - clamp((y - square.top) / square.height());
                }
                invalidate();
                if (listener != null) listener.onColorChanged(hsv.clone());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
