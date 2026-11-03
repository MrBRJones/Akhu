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
 * Brush editor for a reactive wallpaper's cut-out ({@link ReactWallpapers.Piece}): the picture
 * with what's cut away tinted red over a checkerboard. One finger erases or restores with a soft
 * round brush; two fingers zoom and pan. Edits change the piece's mask in place.
 */
public class CutoutView extends View {

    public static final int TOOL_ERASE = 0, TOOL_RESTORE = 1;

    public interface Listener {
        /** The mask changed (after a stroke, an undo or a new mask). */
        void onEdited();
    }

    private static final int CUT_TINT = 0xFF3D5A, CUT_ALPHA = 150;
    private static final int MAX_UNDO = 12;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint();
    private final Matrix base = new Matrix(), view = new Matrix(), inverse = new Matrix();
    private final ArrayDeque<byte[]> undo = new ArrayDeque<>();

    @Nullable
    private ReactWallpapers.Piece piece;
    /** The picture with the mask applied (kept pixels show, cut ones are clear). */
    @Nullable
    private Bitmap shown;
    /** Red tint over what's cut away. */
    @Nullable
    private Bitmap tint;
    private int[] srcPx = new int[0];
    private int[] row = new int[0], tintRow = new int[0];
    private int tool = TOOL_ERASE;
    /** Brush radius in screen pixels; turned into image pixels for the current zoom. */
    private float brushRadius;
    @Nullable
    private Listener listener;

    // Gesture state.
    private boolean stroking, pinching;
    private float lastX, lastY;
    private float pinchDist, pinchMidX, pinchMidY;
    private float cursorX = -1, cursorY = -1;

    public CutoutView(Context context) {
        this(context, null);
    }

    public CutoutView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        brushRadius = 20 * d;
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(2 * d);
        cursorPaint.setColor(0xFFFFFFFF);
        cursorPaint.setShadowLayer(3 * d, 0, 0, 0xAA000000);
        // A checkerboard behind, the usual sign for "transparent".
        Bitmap check = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        check.setPixels(new int[]{0xFF3A3A3A, 0xFF2A2A2A, 0xFF2A2A2A, 0xFF3A3A3A}, 0, 2, 0, 0, 2, 2);
        android.graphics.BitmapShader shader = new android.graphics.BitmapShader(check,
                android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        Matrix m = new Matrix();
        m.setScale(9 * d, 9 * d);
        shader.setLocalMatrix(m);
        checkPaint.setShader(shader);
    }

    public void setListener(@Nullable Listener l) {
        listener = l;
    }

    /** Edits this piece's mask in place. */
    public void setPiece(ReactWallpapers.Piece p) {
        piece = p;
        undo.clear();
        int w = p.source.getWidth(), h = p.source.getHeight();
        srcPx = new int[w * h];
        p.source.getPixels(srcPx, 0, w, 0, 0, w, h);
        shown = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        tint = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        row = new int[w];
        tintRow = new int[w];
        refresh(new Rect(0, 0, w, h));
        view.reset();
        updateBase();
        invalidate();
    }

    /** Swaps in a whole new mask (the automatic cut), undoably. */
    public void setMask(byte[] mask) {
        ReactWallpapers.Piece p = piece;
        if (p == null || mask.length != p.mask.length) return;
        pushUndo();
        p.mask = mask;
        refresh(new Rect(0, 0, p.source.getWidth(), p.source.getHeight()));
        invalidate();
        if (listener != null) listener.onEdited();
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
        ReactWallpapers.Piece p = piece;
        if (p == null || undo.isEmpty()) return;
        p.mask = undo.pop();
        refresh(new Rect(0, 0, p.source.getWidth(), p.source.getHeight()));
        invalidate();
        if (listener != null) listener.onEdited();
    }

    private void pushUndo() {
        ReactWallpapers.Piece p = piece;
        if (p == null) return;
        undo.push(p.mask.clone());
        while (undo.size() > MAX_UNDO) undo.removeLast();
    }

    /** Redraws the kept picture and the tint for an image-space area. */
    private void refresh(Rect r) {
        ReactWallpapers.Piece p = piece;
        Bitmap s = shown, t = tint;
        if (p == null || s == null || t == null) return;
        int w = p.source.getWidth(), h = p.source.getHeight();
        if (!r.intersect(0, 0, w, h)) return;
        int rw = r.width();
        for (int y = r.top; y < r.bottom; y++) {
            for (int x = r.left; x < r.right; x++) {
                int i = y * w + x;
                int c = srcPx[i];
                int a = Math.min(p.mask[i] & 0xFF, c >>> 24);
                row[x - r.left] = (a << 24) | (c & 0xFFFFFF);
                // The cut-away part still shows, dimmed and red, so it's clear what restore brings back.
                int cut = 255 - (p.mask[i] & 0xFF);
                tintRow[x - r.left] = ((cut * CUT_ALPHA / 255 * (c >>> 24) / 255) << 24) | blend(c, CUT_TINT);
            }
            s.setPixels(row, 0, rw, r.left, y, rw, 1);
            t.setPixels(tintRow, 0, rw, r.left, y, rw, 1);
        }
    }

    /** Half the picture's colour, half the tint. */
    private static int blend(int c, int tintRgb) {
        int r = (((c >> 16) & 255) + ((tintRgb >> 16) & 255)) / 2;
        int g = (((c >> 8) & 255) + ((tintRgb >> 8) & 255)) / 2;
        int b = ((c & 255) + (tintRgb & 255)) / 2;
        return (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateBase();
    }

    /** Fit-centre of the picture in the view, before the user's zoom. */
    private void updateBase() {
        ReactWallpapers.Piece p = piece;
        if (p == null || getWidth() == 0) return;
        int w = p.source.getWidth(), h = p.source.getHeight();
        float scale = Math.min((float) getWidth() / w, (float) getHeight() / h) * 0.94f;
        base.setScale(scale, scale);
        base.postTranslate((getWidth() - w * scale) / 2f, (getHeight() - h * scale) / 2f);
    }

    private Matrix total() {
        Matrix m = new Matrix(base);
        m.postConcat(view);
        return m;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ReactWallpapers.Piece p = piece;
        if (p == null || shown == null || tint == null) return;
        Matrix m = total();
        float[] box = {0, 0, p.source.getWidth(), p.source.getHeight()};
        m.mapPoints(box);
        canvas.drawRect(box[0], box[1], box[2], box[3], checkPaint);
        canvas.drawBitmap(tint, m, bitmapPaint);
        canvas.drawBitmap(shown, m, bitmapPaint);
        if (stroking && cursorX >= 0) canvas.drawCircle(cursorX, cursorY, brushRadius, cursorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (piece == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                pinching = false;
                pushUndo();
                stroking = true;
                paintTo(e.getX(), e.getY(), e.getX(), e.getY());
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    // A second finger means zoom/pan: take back the dab the first finger made.
                    if (stroking) {
                        stroking = false;
                        undoSilently();
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
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (stroking) {
                    stroking = false;
                    invalidate();
                    if (listener != null) listener.onEdited();
                }
                pinching = false;
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (stroking) {
                    stroking = false;
                    undoSilently();
                }
                pinching = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void undoSilently() {
        ReactWallpapers.Piece p = piece;
        if (p == null || undo.isEmpty()) return;
        p.mask = undo.pop();
        refresh(new Rect(0, 0, p.source.getWidth(), p.source.getHeight()));
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private static float dist(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    /** Brushes soft dabs along a screen-space segment. */
    private void paintTo(float x0, float y0, float x1, float y1) {
        ReactWallpapers.Piece p = piece;
        if (p == null) return;
        total().invert(inverse);
        float[] pts = {x0, y0, x1, y1};
        inverse.mapPoints(pts);
        float r = Math.max(1f, inverse.mapRadius(brushRadius));
        float len = (float) Math.hypot(pts[2] - pts[0], pts[3] - pts[1]);
        int steps = Math.max(1, (int) Math.ceil(len / Math.max(1f, r / 3f)));
        Rect dirty = null;
        for (int k = 1; k <= steps; k++) {
            float t = (float) k / steps;
            Rect d = stamp(p, pts[0] + (pts[2] - pts[0]) * t, pts[1] + (pts[3] - pts[1]) * t, r);
            if (dirty == null) dirty = d;
            else dirty.union(d);
        }
        if (dirty != null) refresh(dirty);
        cursorX = x1;
        cursorY = y1;
        invalidate();
    }

    /** One soft round dab: solid in the middle, fading over the outer third. */
    private Rect stamp(ReactWallpapers.Piece p, float cx, float cy, float r) {
        int w = p.source.getWidth(), h = p.source.getHeight();
        int left = Math.max(0, (int) (cx - r)), right = Math.min(w - 1, (int) (cx + r));
        int top = Math.max(0, (int) (cy - r)), bottom = Math.min(h - 1, (int) (cy + r));
        boolean erase = tool == TOOL_ERASE;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                float d = (float) Math.hypot(x + 0.5f - cx, y + 0.5f - cy) / r;
                if (d >= 1f) continue;
                float strength = d < 0.66f ? 1f : (1f - d) / 0.34f;
                int i = y * w + x;
                int m = p.mask[i] & 0xFF;
                int target = erase ? 0 : 255;
                int v = Math.round(m + (target - m) * strength);
                p.mask[i] = (byte) (erase ? Math.min(m, v) : Math.max(m, v));
            }
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }
}
