package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;

/**
 * Paint-on cut-out editor for {@link FourDEditorActivity}: shows the picture with the near layer
 * tinted cyan and the mid layer amber. One finger paints with the current tool (or, in tap mode,
 * moves the tapped subject to the next layer); two fingers zoom and pan.
 */
public class MaskEditView extends View {

    public static final int TOOL_FRONT = 0, TOOL_MIDDLE = 1, TOOL_ERASE = 2, TOOL_TAP = 3;

    public interface Listener {
        /** The masks changed (after a stroke or tap). */
        void onEdited();

        /** A tap moved something to {@code layer} ({@link FourDMaker#FRONT} etc), or -1 for nothing there. */
        void onTapped(int layer);
    }

    private static final int NEAR_TINT = 0x00E5FF, MID_TINT = 0xFFB300;
    private static final int TINT_ALPHA = 150;
    private static final int MAX_UNDO = 8;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix base = new Matrix(), view = new Matrix(), inverse = new Matrix();
    private final ArrayDeque<byte[][]> undo = new ArrayDeque<>();
    private final float touchSlop;

    @Nullable
    private FourDMaker.Session session;
    @Nullable
    private Bitmap overlay;
    private int[] overlayRow = new int[0];
    private int tool = TOOL_FRONT;
    /** Brush radius in screen pixels; turned into image pixels for the current zoom. */
    private float brushRadius;
    @Nullable
    private Listener listener;

    // Gesture state.
    private boolean stroking, pinching, moved;
    private float lastX, lastY, downX, downY;
    private float pinchDist, pinchMidX, pinchMidY;
    private float cursorX = -1, cursorY = -1;

    public MaskEditView(Context context) {
        this(context, null);
    }

    public MaskEditView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        brushRadius = 22 * d;
        touchSlop = 8 * d;
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(2 * d);
        cursorPaint.setColor(0xFFFFFFFF);
        cursorPaint.setShadowLayer(3 * d, 0, 0, 0xAA000000);
    }

    public void setListener(@Nullable Listener l) {
        listener = l;
    }

    public void setSession(FourDMaker.Session s) {
        session = s;
        undo.clear();
        overlay = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888);
        overlayRow = new int[s.w];
        refreshOverlay(new Rect(0, 0, s.w, s.h));
        view.reset();
        updateBase();
        invalidate();
    }

    public void setTool(int tool) {
        this.tool = tool;
    }

    /** Brush radius in screen pixels. */
    public void setBrushRadius(float px) {
        brushRadius = px;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public void undo() {
        if (session == null || undo.isEmpty()) return;
        session.restore(undo.pop());
        refreshOverlay(new Rect(0, 0, session.w, session.h));
        invalidate();
        if (listener != null) listener.onEdited();
    }

    private void pushUndo() {
        if (session == null) return;
        undo.push(session.snapshot());
        while (undo.size() > MAX_UNDO) undo.removeLast();
    }

    /** Redraws the tint for an image-space area. */
    private void refreshOverlay(Rect r) {
        FourDMaker.Session s = session;
        Bitmap o = overlay;
        if (s == null || o == null || !r.intersect(0, 0, s.w, s.h)) return;
        int rw = r.width();
        if (overlayRow.length < rw) overlayRow = new int[rw];
        for (int y = r.top; y < r.bottom; y++) {
            for (int x = r.left; x < r.right; x++) {
                int i = y * s.w + x;
                int n = s.near[i] & 0xFF, m = s.mid[i] & 0xFF;
                int c;
                if (n == 0 && m == 0) c = 0;
                else if (n >= m) c = ((n * TINT_ALPHA / 255) << 24) | NEAR_TINT;
                else c = ((m * TINT_ALPHA / 255) << 24) | MID_TINT;
                overlayRow[x - r.left] = c;
            }
            o.setPixels(overlayRow, 0, rw, r.left, y, rw, 1);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateBase();
    }

    /** Fit-centre of the picture in the view, before the user's zoom. */
    private void updateBase() {
        FourDMaker.Session s = session;
        if (s == null || getWidth() == 0) return;
        float scale = Math.min((float) getWidth() / s.w, (float) getHeight() / s.h);
        base.setScale(scale, scale);
        base.postTranslate((getWidth() - s.w * scale) / 2f, (getHeight() - s.h * scale) / 2f);
    }

    private Matrix total() {
        Matrix m = new Matrix(base);
        m.postConcat(view);
        return m;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        FourDMaker.Session s = session;
        if (s == null) return;
        Matrix m = total();
        canvas.drawBitmap(s.image, m, bitmapPaint);
        if (overlay != null) canvas.drawBitmap(overlay, m, bitmapPaint);
        if (stroking && cursorX >= 0) canvas.drawCircle(cursorX, cursorY, brushRadius, cursorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (session == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                moved = false;
                pinching = false;
                stroking = false;
                if (tool != TOOL_TAP) {
                    pushUndo();
                    stroking = true;
                    paintTo(e.getX(), e.getY(), e.getX(), e.getY());
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    // A second finger means zoom/pan: take back the dab the first finger made.
                    if (stroking) {
                        stroking = false;
                        undo();
                    }
                    pinching = true;
                    pinchDist = dist(e);
                    pinchMidX = (e.getX(0) + e.getX(1)) / 2f;
                    pinchMidY = (e.getY(0) + e.getY(1)) / 2f;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching && e.getPointerCount() >= 2) {
                    float d = dist(e), mx = (e.getX(0) + e.getX(1)) / 2f, my = (e.getY(0) + e.getY(1)) / 2f;
                    if (pinchDist > 0) {
                        float f = d / pinchDist;
                        float[] vals = new float[9];
                        view.getValues(vals);
                        float zoom = vals[Matrix.MSCALE_X] * f;
                        if (zoom < 1f) f = 1f / vals[Matrix.MSCALE_X];
                        else if (zoom > 8f) f = 8f / vals[Matrix.MSCALE_X];
                        view.postScale(f, f, mx, my);
                    }
                    view.postTranslate(mx - pinchMidX, my - pinchMidY);
                    pinchDist = d;
                    pinchMidX = mx;
                    pinchMidY = my;
                    invalidate();
                } else if (stroking) {
                    paintTo(lastX, lastY, e.getX(), e.getY());
                    lastX = e.getX();
                    lastY = e.getY();
                } else if (Math.hypot(e.getX() - downX, e.getY() - downY) > touchSlop) {
                    moved = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (stroking) {
                    stroking = false;
                    invalidate();
                    if (listener != null) listener.onEdited();
                } else if (tool == TOOL_TAP && !pinching && !moved) {
                    tapAt(e.getX(), e.getY());
                    performClick();
                }
                pinching = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (stroking) {
                    stroking = false;
                    undo();
                }
                pinching = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private static float dist(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    /** Paints dabs along a screen-space segment. */
    private void paintTo(float x0, float y0, float x1, float y1) {
        FourDMaker.Session s = session;
        if (s == null) return;
        Matrix m = total();
        m.invert(inverse);
        float[] p = {x0, y0, x1, y1};
        inverse.mapPoints(p);
        float r = inverse.mapRadius(brushRadius);
        int layer = tool == TOOL_FRONT ? FourDMaker.FRONT : tool == TOOL_MIDDLE ? FourDMaker.MIDDLE : FourDMaker.BACK;
        float len = (float) Math.hypot(p[2] - p[0], p[3] - p[1]);
        int steps = Math.max(1, (int) Math.ceil(len / Math.max(1f, r / 3f)));
        Rect dirty = null;
        for (int k = 1; k <= steps; k++) {
            float t = (float) k / steps;
            Rect d = s.stamp(p[0] + (p[2] - p[0]) * t, p[1] + (p[3] - p[1]) * t, r, layer);
            if (dirty == null) dirty = d;
            else dirty.union(d);
        }
        if (dirty != null) refreshOverlay(dirty);
        cursorX = x1;
        cursorY = y1;
        invalidate();
    }

    private void tapAt(float x, float y) {
        FourDMaker.Session s = session;
        if (s == null) return;
        Matrix m = total();
        m.invert(inverse);
        float[] p = {x, y};
        inverse.mapPoints(p);
        byte[][] before = s.snapshot();
        int layer = s.cycleAt((int) p[0], (int) p[1]);
        if (layer >= 0) {
            undo.push(before);
            while (undo.size() > MAX_UNDO) undo.removeLast();
            refreshOverlay(new Rect(0, 0, s.w, s.h));
            invalidate();
            if (listener != null) listener.onEdited();
        }
        if (listener != null) listener.onTapped(layer);
    }
}
