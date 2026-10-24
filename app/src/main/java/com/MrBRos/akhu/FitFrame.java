package com.MrBRos.akhu;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * Holds one preview page's content. On a screen wider than tall it shrinks to the image's own
 * aspect ratio (full height, centred), so a portrait wallpaper shows whole instead of being cropped
 * to a thin band; otherwise it fills the page.
 */
public class FitFrame extends FrameLayout {

    private float aspect;

    public FitFrame(Context context) {
        super(context);
    }

    /** Image width / height; 0 always fills. */
    public void setAspect(float aspect) {
        if (this.aspect == aspect) return;
        this.aspect = aspect;
        requestLayout();
    }

    /** True when this frame is narrower than its page, i.e. the sides need a backdrop. */
    public static boolean fits(int pageW, int pageH, float aspect) {
        return aspect > 0f && pageW > pageH && pageH * aspect < pageW;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec), h = MeasureSpec.getSize(heightMeasureSpec);
        if (fits(w, h, aspect)) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(Math.round(h * aspect), MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
