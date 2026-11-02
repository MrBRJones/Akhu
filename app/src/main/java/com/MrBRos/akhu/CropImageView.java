package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * The picture fitted in the view with a crop box on top: drag inside the box to move it, drag a
 * corner or edge to resize it. With an aspect ratio set, resizing keeps it. Outside the box is
 * dimmed. {@link #getCrop} gives the box in the picture's own 0..1 coordinates.
 */
public class CropImageView extends View {

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    @Nullable
    private Bitmap bitmap;
    /** Where the picture is drawn, in view pixels. */
    private final RectF image = new RectF();
    /** The crop box, in view pixels. */
    private final RectF box = new RectF();
    /** Width / height to keep, or 0 for free. */
    private float aspect;

    private static final int NONE = 0, MOVE = 1, LEFT = 2, TOP = 4, RIGHT = 8, BOTTOM = 16;
    private int drag = NONE;
    private float lastX, lastY;

    public CropImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        dimPaint.setColor(0x99000000);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setColor(0xFFFFFFFF);
        linePaint.setStrokeWidth(2 * density);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(0x66FFFFFF);
        gridPaint.setStrokeWidth(density);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStrokeWidth(4 * density);
        handlePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setBitmap(Bitmap b) {
        bitmap = b;
        layoutImage();
        resetBox();
    }

    /** Keeps the box at {@code ratio} (width / height), or 0 for any shape; recentres it. */
    public void setAspect(float ratio) {
        aspect = ratio;
        resetBox();
    }

    /** The box as fractions of the picture: left, top, right, bottom. */
    public RectF getCrop() {
        if (image.isEmpty()) return new RectF(0, 0, 1, 1);
        return new RectF((box.left - image.left) / image.width(), (box.top - image.top) / image.height(),
                (box.right - image.left) / image.width(), (box.bottom - image.top) / image.height());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutImage();
        resetBox();
    }

    private void layoutImage() {
        Bitmap b = bitmap;
        if (b == null || getWidth() == 0) return;
        float pad = 16 * density;
        float s = Math.min((getWidth() - 2 * pad) / b.getWidth(), (getHeight() - 2 * pad) / b.getHeight());
        float w = b.getWidth() * s, h = b.getHeight() * s;
        image.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
    }

    /** The largest box of the current aspect, centred on the picture. */
    private void resetBox() {
        if (image.isEmpty()) return;
        float w = image.width(), h = image.height();
        if (aspect > 0) {
            if (w / h > aspect) w = h * aspect;
            else h = w / aspect;
        }
        box.set(image.centerX() - w / 2, image.centerY() - h / 2, image.centerX() + w / 2, image.centerY() + h / 2);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap b = bitmap;
        if (b == null) return;
        canvas.drawBitmap(b, null, image, bitmapPaint);
        canvas.drawRect(image.left, image.top, image.right, box.top, dimPaint);
        canvas.drawRect(image.left, box.bottom, image.right, image.bottom, dimPaint);
        canvas.drawRect(image.left, box.top, box.left, box.bottom, dimPaint);
        canvas.drawRect(box.right, box.top, image.right, box.bottom, dimPaint);
        // Rule-of-thirds guides, the frame, then corner marks.
        for (int i = 1; i < 3; i++) {
            float x = box.left + box.width() * i / 3f, y = box.top + box.height() * i / 3f;
            canvas.drawLine(x, box.top, x, box.bottom, gridPaint);
            canvas.drawLine(box.left, y, box.right, y, gridPaint);
        }
        canvas.drawRect(box, linePaint);
        float l = 18 * density;
        Path p = new Path();
        p.moveTo(box.left, box.top + l); p.lineTo(box.left, box.top); p.lineTo(box.left + l, box.top);
        p.moveTo(box.right - l, box.top); p.lineTo(box.right, box.top); p.lineTo(box.right, box.top + l);
        p.moveTo(box.right, box.bottom - l); p.lineTo(box.right, box.bottom); p.lineTo(box.right - l, box.bottom);
        p.moveTo(box.left + l, box.bottom); p.lineTo(box.left, box.bottom); p.lineTo(box.left, box.bottom - l);
        canvas.drawPath(p, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drag = hit(x, y);
                lastX = x;
                lastY = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                return drag != NONE;
            case MotionEvent.ACTION_MOVE:
                if (drag == MOVE) move(x - lastX, y - lastY);
                else if (drag != NONE) resize(x, y);
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                // fall through
            case MotionEvent.ACTION_CANCEL:
                drag = NONE;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** Which edges (or the inside) a touch grabs. */
    private int hit(float x, float y) {
        float slop = 28 * density;
        int edges = NONE;
        boolean inY = y > box.top - slop && y < box.bottom + slop, inX = x > box.left - slop && x < box.right + slop;
        if (inY && Math.abs(x - box.left) < slop) edges |= LEFT;
        if (inY && Math.abs(x - box.right) < slop) edges |= RIGHT;
        if (inX && Math.abs(y - box.top) < slop) edges |= TOP;
        if (inX && Math.abs(y - box.bottom) < slop) edges |= BOTTOM;
        if (edges != NONE) return edges;
        return box.contains(x, y) ? MOVE : NONE;
    }

    private void move(float dx, float dy) {
        dx = Math.max(image.left - box.left, Math.min(image.right - box.right, dx));
        dy = Math.max(image.top - box.top, Math.min(image.bottom - box.bottom, dy));
        box.offset(dx, dy);
    }

    private void resize(float x, float y) {
        float min = 64 * density;
        float l = box.left, t = box.top, r = box.right, b = box.bottom;
        if ((drag & LEFT) != 0) l = Math.max(image.left, Math.min(x, r - min));
        if ((drag & RIGHT) != 0) r = Math.min(image.right, Math.max(x, l + min));
        if ((drag & TOP) != 0) t = Math.max(image.top, Math.min(y, b - min));
        if ((drag & BOTTOM) != 0) b = Math.min(image.bottom, Math.max(y, t + min));
        if (aspect > 0) {
            // Keep the shape: the dragged side leads, the other follows, anchored opposite.
            boolean horizontal = (drag & (LEFT | RIGHT)) != 0;
            float w = r - l, h = b - t;
            if (horizontal) h = w / aspect;
            else w = h * aspect;
            if (w > image.width()) { w = image.width(); h = w / aspect; }
            if (h > image.height()) { h = image.height(); w = h * aspect; }
            if ((drag & LEFT) != 0) l = r - w; else r = l + w;
            if ((drag & TOP) != 0) t = b - h; else b = t + h;
            if (!horizontal && (drag & (TOP | BOTTOM)) != 0) {
                // Dragging only top/bottom: grow sideways around the centre.
                float cx = box.centerX();
                l = cx - w / 2;
                r = cx + w / 2;
            } else if (horizontal && (drag & (TOP | BOTTOM)) == 0) {
                float cy = box.centerY();
                t = cy - h / 2;
                b = cy + h / 2;
            }
            // Slide back inside the picture if the new box pokes out.
            float dx = Math.max(0, image.left - l) - Math.max(0, r - image.right);
            float dy = Math.max(0, image.top - t) - Math.max(0, b - image.bottom);
            l += dx; r += dx; t += dy; b += dy;
        }
        box.set(l, t, r, b);
    }
}
