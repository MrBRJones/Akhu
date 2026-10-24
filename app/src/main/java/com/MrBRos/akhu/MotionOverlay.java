package com.MrBRos.akhu;

import android.text.Layout;

import androidx.annotation.Nullable;

/**
 * One thing laid over a motion clip: styled text, an emoji sticker or a photo sticker. Position and
 * size are fractions of the frame, so the same overlay draws the same on the preview and the export.
 * Times are in the source clip's milliseconds.
 */
final class MotionOverlay {

    static final int TEXT = 0, EMOJI = 1, PHOTO = 2;

    /** No coloured box, a solid box in the text colour, or a see-through dark box. */
    static final int BG_NONE = 0, BG_SOLID = 1, BG_DIM = 2;

    final int type;
    String text = "";
    /** For PHOTO: a PNG in the editor's work folder. */
    @Nullable
    String photoPath;
    int font;
    int color = 0xFFFFFFFF;
    int effect;
    int animation;
    int background = BG_NONE;
    Layout.Alignment align = Layout.Alignment.ALIGN_CENTER;
    /** Centre, as a fraction of the frame's width and height. */
    float x = 0.5f, y = 0.5f;
    /** TEXT: line height as a fraction of frame width. Stickers: width as a fraction of frame width. */
    float size;
    float rotation;
    long startMs;
    long endMs = Long.MAX_VALUE;

    MotionOverlay(int type) {
        this.type = type;
        size = type == TEXT ? 0.08f : 0.3f;
    }

    boolean visibleAt(long timeMs) {
        return timeMs >= startMs && timeMs <= endMs;
    }

    MotionOverlay copy() {
        MotionOverlay o = new MotionOverlay(type);
        o.text = text;
        o.photoPath = photoPath;
        o.font = font;
        o.color = color;
        o.effect = effect;
        o.animation = animation;
        o.background = background;
        o.align = align;
        o.x = x;
        o.y = y;
        o.size = size;
        o.rotation = rotation;
        o.startMs = startMs;
        o.endMs = endMs;
        return o;
    }
}
