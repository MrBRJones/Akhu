package com.MrBRos.wallpapercreator;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Sits exactly over the video in the motion editor and draws its text and stickers at the player's
 * current time. Drag one to move it, pinch to resize and twist to rotate, tap it to edit, and drop it
 * on the bin that appears at the bottom to delete it.
 */
public class OverlayView extends View {

    interface Listener {
        /** A move, resize or rotate is about to change an overlay (save an undo step). */
        void onChangeStart();

        void onTap(MotionOverlay overlay);

        void onDelete(MotionOverlay overlay);
    }

    private final OverlayRenderer renderer = new OverlayRenderer();
    private final Paint binPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density = getResources().getDisplayMetrics().density;
    private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    private List<MotionOverlay> overlays = new ArrayList<>();
    @Nullable
    private Listener listener;
    private long timeMs;
    private long clipStartMs;
    private boolean interactive = true;

    // The gesture in progress.
    @Nullable
    private MotionOverlay target;
    private float downX, downY, lastX, lastY, lastDist, lastAngle;
    private long downTime;
    private boolean changing, overBin;

    public OverlayView(Context context) {
        this(context, null);
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // Blur effects (Neon) need a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    void setOverlays(List<MotionOverlay> overlays) {
        this.overlays = overlays;
        invalidate();
    }

    void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** Off while framing the video, so drags there pan the clip instead. */
    void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    void setTime(long timeMs, long clipStartMs) {
        boolean changed = timeMs != this.timeMs || clipStartMs != this.clipStartMs;
        this.timeMs = timeMs;
        this.clipStartMs = clipStartMs;
        if (changed && needsRedraw()) invalidate();
    }

    /** Something on screen changes with time: an animation, or an overlay with its own start/end. */
    private boolean needsRedraw() {
        for (MotionOverlay o : overlays) {
            if (OverlayRenderer.moving(o) || o.startMs > 0 || o.endMs != Long.MAX_VALUE) return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        renderer.draw(canvas, w, h, overlays, timeMs, clipStartMs, null);
        if (target != null && changing) drawBin(canvas);
    }

    private float binX() {
        return getWidth() / 2f;
    }

    private float binY() {
        return getHeight() - 44 * density;
    }

    private void drawBin(Canvas c) {
        float r = (overBin ? 30 : 24) * density;
        binPaint.setStyle(Paint.Style.FILL);
        binPaint.setColor(overBin ? 0xFFE53935 : 0x99000000);
        c.drawCircle(binX(), binY(), r, binPaint);
        // A simple bin: lid, body and two ribs.
        binPaint.setColor(0xFFFFFFFF);
        binPaint.setStyle(Paint.Style.STROKE);
        binPaint.setStrokeWidth(2 * density);
        binPaint.setStrokeCap(Paint.Cap.ROUND);
        float s = r * 0.42f, x = binX(), y = binY();
        c.drawLine(x - s, y - s * 0.75f, x + s, y - s * 0.75f, binPaint);
        c.drawLine(x - s * 0.3f, y - s, x + s * 0.3f, y - s, binPaint);
        c.drawRoundRect(x - s * 0.7f, y - s * 0.55f, x + s * 0.7f, y + s, s * 0.15f, s * 0.15f, binPaint);
        c.drawLine(x - s * 0.22f, y - s * 0.25f, x - s * 0.22f, y + s * 0.7f, binPaint);
        c.drawLine(x + s * 0.22f, y - s * 0.25f, x + s * 0.22f, y + s * 0.7f, binPaint);
    }

    /** The topmost overlay showing now under (x, y), allowing for its rotation. */
    @Nullable
    private MotionOverlay hit(float x, float y) {
        int w = getWidth(), h = getHeight();
        float pad = 12 * density;
        for (int i = overlays.size() - 1; i >= 0; i--) {
            MotionOverlay o = overlays.get(i);
            if (!o.visibleAt(timeMs)) continue;
            float[] half = renderer.halfSize(o, w);
            double a = Math.toRadians(-o.rotation);
            float dx = x - o.x * w, dy = y - o.y * h;
            float rx = (float) (dx * Math.cos(a) - dy * Math.sin(a));
            float ry = (float) (dx * Math.sin(a) + dy * Math.cos(a));
            if (Math.abs(rx) <= half[0] + pad && Math.abs(ry) <= half[1] + pad) return o;
        }
        return null;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                target = hit(e.getX(), e.getY());
                if (target == null) return false;
                // Bring the touched one to the front.
                overlays.remove(target);
                overlays.add(target);
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downTime = e.getEventTime();
                changing = false;
                overBin = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (target != null && e.getPointerCount() == 2) {
                    lastDist = distance(e);
                    lastAngle = angle(e);
                    begin();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (target == null) return false;
                if (e.getPointerCount() >= 2) {
                    float d = distance(e), a = angle(e);
                    if (lastDist > 0) target.size = Math.max(0.02f, Math.min(1.5f, target.size * d / lastDist));
                    target.rotation += a - lastAngle;
                    lastDist = d;
                    lastAngle = a;
                } else {
                    if (!changing && Math.hypot(e.getX() - downX, e.getY() - downY) < slop) return true;
                    begin();
                    target.x = Math.max(0f, Math.min(1f, target.x + (e.getX() - lastX) / getWidth()));
                    target.y = Math.max(0f, Math.min(1f, target.y + (e.getY() - lastY) / getHeight()));
                    overBin = Math.hypot(e.getX() - binX(), e.getY() - binY()) < 40 * density;
                }
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // Carry on dragging with the finger that stays down.
                int stay = e.getActionIndex() == 0 ? 1 : 0;
                lastX = e.getX(stay);
                lastY = e.getY(stay);
                lastDist = 0;
                return true;
            case MotionEvent.ACTION_UP:
                if (target != null && listener != null) {
                    if (!changing && e.getEventTime() - downTime < 400) listener.onTap(target);
                    else if (overBin) listener.onDelete(target);
                }
                end();
                return true;
            case MotionEvent.ACTION_CANCEL:
                end();
                return true;
            default:
                return true;
        }
    }

    private void begin() {
        if (changing) return;
        changing = true;
        if (listener != null) listener.onChangeStart();
    }

    private void end() {
        target = null;
        changing = false;
        overBin = false;
        invalidate();
    }

    private static float distance(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private static float angle(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }

    @Override
    protected void onDetachedFromWindow() {
        renderer.release();
        super.onDetachedFromWindow();
    }
}
