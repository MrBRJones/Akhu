package com.MrBRos.wallpapercreator;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.function.Consumer;

/** A thin vertical slider (top = biggest) for text size while editing text, like Instagram's. */
public class SizeBar extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density = getResources().getDisplayMetrics().density;
    /** 0 (bottom, smallest) to 1 (top, biggest). */
    private float value = 0.5f;
    @Nullable
    private Consumer<Float> listener;

    public SizeBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    void setValue(float value) {
        this.value = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    void setListener(@Nullable Consumer<Float> listener) {
        this.listener = listener;
    }

    private float pad() {
        return 12 * density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float x = getWidth() / 2f, top = pad(), bottom = getHeight() - pad();
        // Wedge: wide at the top, narrow at the bottom, so it reads as "bigger up here".
        paint.setColor(0x66FFFFFF);
        Path wedge = new Path();
        wedge.moveTo(x - 4 * density, top);
        wedge.lineTo(x + 4 * density, top);
        wedge.lineTo(x + density, bottom);
        wedge.lineTo(x - density, bottom);
        wedge.close();
        c.drawPath(wedge, paint);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(x, bottom - value * (bottom - top), 9 * density, paint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float top = pad(), bottom = getHeight() - pad();
        setValue((bottom - e.getY()) / (bottom - top));
        getParent().requestDisallowInterceptTouchEvent(true);
        if (listener != null) listener.accept(value);
        return true;
    }
}
