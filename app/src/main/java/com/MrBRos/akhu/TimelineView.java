package com.MrBRos.akhu;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The motion editor's timeline: the clip as a strip of frames with trim handles, a playhead, and a
 * track under it for each text or sticker showing when it's on screen. Drag the strip's handles to
 * trim, a track's ends (or middle) to retime it, or anywhere else to scrub.
 */
public class TimelineView extends View {

    interface Listener {
        /** A drag is about to change the trim or a track (save an undo step). */
        void onChangeStart();

        void onSeek(long ms);

        void onTrimChanged(long startMs, long endMs);

        void onOverlayTimeChanged();
    }

    private static final int NONE = 0, TRIM_START = 1, TRIM_END = 2, SCRUB = 3, TRACK_START = 4, TRACK_END = 5,
            TRACK_MOVE = 6;
    private static final long MIN_MS = 300;

    private final float density = getResources().getDisplayMetrics().density;
    private final float stripH = 52 * density, trackH = 26 * density, gap = 6 * density, side = 14 * density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private List<MotionOverlay> overlays = new ArrayList<>();
    private Bitmap[] frames = new Bitmap[0];
    private long durationMs = 1, startMs, endMs = 1, positionMs;
    @Nullable
    private Listener listener;

    private int dragging = NONE;
    @Nullable
    private MotionOverlay dragTrack;
    private long dragOffsetMs;

    public TimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        label.setColor(0xFFFFFFFF);
        label.setTextSize(12 * density);
        label.setFakeBoldText(true);
    }

    void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    void setFrames(Bitmap[] frames) {
        this.frames = frames;
        invalidate();
    }

    void setClip(long durationMs, long startMs, long endMs) {
        this.durationMs = Math.max(1, durationMs);
        this.startMs = startMs;
        this.endMs = endMs;
        invalidate();
    }

    void setOverlays(List<MotionOverlay> overlays) {
        this.overlays = overlays;
        requestLayout();
        invalidate();
    }

    void setPosition(long ms) {
        if (ms == positionMs) return;
        positionMs = ms;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = Math.round(stripH + overlays.size() * (trackH + gap) + 2 * gap);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(h, heightSpec));
    }

    private float left() {
        return side;
    }

    private float width() {
        return Math.max(1, getWidth() - 2 * side);
    }

    private float xOf(long ms) {
        return left() + width() * Math.max(0, Math.min(durationMs, ms)) / durationMs;
    }

    private long msAt(float x) {
        return Math.max(0, Math.min(durationMs, Math.round((x - left()) / width() * durationMs)));
    }

    private float trackTop(int i) {
        return gap + stripH + gap + i * (trackH + gap);
    }

    @Override
    protected void onDraw(Canvas c) {
        float top = gap, l = left(), r = left() + width();
        // Frames across the whole clip.
        if (frames.length > 0) {
            float fw = width() / frames.length;
            for (int i = 0; i < frames.length; i++) {
                if (frames[i] == null) continue;
                c.drawBitmap(frames[i], null, new RectF(l + i * fw, top, l + (i + 1) * fw, top + stripH), paint);
            }
        } else {
            paint.setColor(0xFF2A2D35);
            c.drawRect(l, top, r, top + stripH, paint);
        }
        // Dim what's trimmed off, then the white trim frame with its two grips.
        float s = xOf(startMs), e = xOf(endMs);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB3000000);
        c.drawRect(l, top, s, top + stripH, paint);
        c.drawRect(e, top, r, top + stripH, paint);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f * density);
        c.drawRect(s, top, e, top + stripH, paint);
        paint.setStyle(Paint.Style.FILL);
        float grip = 10 * density;
        c.drawRoundRect(s - grip, top, s, top + stripH, 3 * density, 3 * density, paint);
        c.drawRoundRect(e, top, e + grip, top + stripH, 3 * density, 3 * density, paint);
        paint.setColor(0xFF000000);
        c.drawRect(s - grip / 2 - density, top + stripH / 2 - 7 * density, s - grip / 2 + density, top + stripH / 2 + 7 * density, paint);
        c.drawRect(e + grip / 2 - density, top + stripH / 2 - 7 * density, e + grip / 2 + density, top + stripH / 2 + 7 * density, paint);

        // One track per overlay.
        for (int i = 0; i < overlays.size(); i++) {
            MotionOverlay o = overlays.get(i);
            float t = trackTop(i);
            paint.setColor(0xFF22252C);
            c.drawRoundRect(l, t, r, t + trackH, 6 * density, 6 * density, paint);
            float a = xOf(o.startMs), b = xOf(Math.min(o.endMs, durationMs));
            paint.setColor(o.type == MotionOverlay.TEXT ? 0xFF7C4DFF : 0xFFE0408A);
            c.drawRoundRect(a, t, Math.max(a + 8 * density, b), t + trackH, 6 * density, 6 * density, paint);
            paint.setColor(0x66FFFFFF);
            c.drawRoundRect(a, t, a + 6 * density, t + trackH, 3 * density, 3 * density, paint);
            c.drawRoundRect(b - 6 * density, t, b, t + trackH, 3 * density, 3 * density, paint);
            String name = o.type == MotionOverlay.PHOTO ? getContext().getString(R.string.sticker_photo) : o.text;
            CharSequence fit = TextUtils.ellipsize(name.replace('\n', ' '), label, Math.max(0, b - a - 16 * density),
                    TextUtils.TruncateAt.END);
            c.drawText(fit, 0, fit.length(), a + 9 * density, t + trackH / 2 + 4 * density, label);
        }

        // Playhead.
        float p = xOf(positionMs);
        paint.setColor(0xFFFFFFFF);
        c.drawRoundRect(p - 1.5f * density, 0, p + 1.5f * density, getHeight(), density, density, paint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = pick(x, y);
                getParent().requestDisallowInterceptTouchEvent(true);
                if (dragging != SCRUB && listener != null) listener.onChangeStart();
                move(x);
                return true;
            case MotionEvent.ACTION_MOVE:
                move(x);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = NONE;
                dragTrack = null;
                return true;
            default:
                return true;
        }
    }

    /** What a touch at (x, y) grabs: a trim grip, a track end or body, or else the playhead. */
    private int pick(float x, float y) {
        float reach = 22 * density;
        if (y <= gap + stripH + gap / 2) {
            if (Math.abs(x - xOf(startMs)) < reach) return TRIM_START;
            if (Math.abs(x - xOf(endMs)) < reach) return TRIM_END;
            return SCRUB;
        }
        for (int i = 0; i < overlays.size(); i++) {
            float t = trackTop(i);
            if (y < t - gap / 2 || y > t + trackH + gap / 2) continue;
            MotionOverlay o = overlays.get(i);
            float a = xOf(o.startMs), b = xOf(Math.min(o.endMs, durationMs));
            dragTrack = o;
            if (Math.abs(x - a) < reach) return TRACK_START;
            if (Math.abs(x - b) < reach) return TRACK_END;
            if (x > a && x < b) {
                dragOffsetMs = msAt(x) - o.startMs;
                return TRACK_MOVE;
            }
            dragTrack = null;
        }
        return SCRUB;
    }

    private void move(float x) {
        long ms = msAt(x);
        switch (dragging) {
            case TRIM_START:
                startMs = Math.min(ms, endMs - MIN_MS);
                if (listener != null) listener.onTrimChanged(startMs, endMs);
                break;
            case TRIM_END:
                endMs = Math.max(ms, startMs + MIN_MS);
                if (listener != null) listener.onTrimChanged(startMs, endMs);
                break;
            case TRACK_START:
            case TRACK_END:
            case TRACK_MOVE:
                if (dragTrack == null) return;
                long end = Math.min(dragTrack.endMs, durationMs);
                if (dragging == TRACK_START) {
                    dragTrack.startMs = Math.min(ms, end - MIN_MS);
                } else if (dragging == TRACK_END) {
                    long newEnd = Math.max(ms, dragTrack.startMs + MIN_MS);
                    // Reaching the clip's end means "until the end", so a longer trim keeps it on.
                    dragTrack.endMs = newEnd >= durationMs ? Long.MAX_VALUE : newEnd;
                } else {
                    long len = end - dragTrack.startMs;
                    long newStart = Math.max(0, Math.min(durationMs - len, ms - dragOffsetMs));
                    boolean toEnd = dragTrack.endMs == Long.MAX_VALUE;
                    dragTrack.startMs = newStart;
                    dragTrack.endMs = toEnd && newStart + len >= durationMs ? Long.MAX_VALUE : newStart + len;
                }
                if (listener != null) listener.onOverlayTimeChanged();
                ms = dragging == TRACK_END ? Math.min(dragTrack.endMs, durationMs) : dragTrack.startMs;
                break;
            default:
        }
        positionMs = ms;
        if (listener != null) listener.onSeek(ms);
        invalidate();
    }
}
