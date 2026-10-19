package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/** Frame that keeps a phone's 9:19.5 shape, as large as fits in the space it's given. */
public class PhoneFrame extends FrameLayout {

    private static final float ASPECT = 9f / 19.5f;

    public PhoneFrame(Context context) {
        this(context, null);
    }

    public PhoneFrame(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec), h = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || h * ASPECT > w) {
            h = Math.round(w / ASPECT);
        } else {
            w = Math.round(h * ASPECT);
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
