package com.MrBRos.akhu;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;

import com.MrBRos.akhu.databinding.ItemHsvSliderBinding;

import java.util.Locale;

/**
 * Hue, saturation and brightness sliders over a shared {@code hsv} array, each on a track painted
 * with the colours it runs through. Used by the colour picker and the motion editor's text colour.
 */
final class HsvSliders {

    private static final int[] RAINBOW = new int[7];

    static {
        for (int i = 0; i < RAINBOW.length; i++) RAINBOW[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
    }

    private final ItemHsvSliderBinding rowH, rowS, rowV;
    private final float[] hsv;
    private final Runnable onUserChange;
    private boolean updating;

    /** {@code onUserChange} runs after a drag has changed {@code hsv}. */
    HsvSliders(ItemHsvSliderBinding rowH, ItemHsvSliderBinding rowS, ItemHsvSliderBinding rowV,
               String[] letters, float[] hsv, Runnable onUserChange) {
        this.rowH = rowH;
        this.rowS = rowS;
        this.rowV = rowV;
        this.hsv = hsv;
        this.onUserChange = onUserChange;
        setUp(rowH, letters[0], 360f, 0);
        setUp(rowS, letters[1], 100f, 1);
        setUp(rowV, letters[2], 100f, 2);
    }

    private void setUp(ItemHsvSliderBinding row, String letter, float max, int index) {
        row.letter.setText(letter);
        row.slider.setValueFrom(0f);
        row.slider.setValueTo(max);
        row.slider.addOnChangeListener((s, value, fromUser) -> {
            if (!fromUser || updating) return;
            hsv[index] = index == 0 ? value % 360f : value / 100f;
            sync();
            onUserChange.run();
        });
        // Line the gradient up with the slider's track, which is inset by the thumb.
        row.slider.post(() -> {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.track.getLayoutParams();
            lp.leftMargin = lp.rightMargin = row.slider.getTrackSidePadding();
            row.track.setLayoutParams(lp);
        });
    }

    /** Moves the sliders, numbers and track colours to match {@code hsv}. */
    void sync() {
        updating = true;
        rowH.slider.setValue(Math.min(360f, Math.round(hsv[0])));
        rowS.slider.setValue(Math.round(hsv[1] * 100f));
        rowV.slider.setValue(Math.round(hsv[2] * 100f));
        rowH.value.setText(String.format(Locale.US, "%d°", Math.round(hsv[0]) % 360));
        rowS.value.setText(String.format(Locale.US, "%d%%", Math.round(hsv[1] * 100f)));
        rowV.value.setText(String.format(Locale.US, "%d%%", Math.round(hsv[2] * 100f)));
        rowH.track.setBackground(bar(RAINBOW));
        rowS.track.setBackground(bar(Color.HSVToColor(new float[]{hsv[0], 0f, hsv[2]}),
                Color.HSVToColor(new float[]{hsv[0], 1f, hsv[2]})));
        rowV.track.setBackground(bar(0xFF000000, Color.HSVToColor(new float[]{hsv[0], hsv[1], 1f})));
        updating = false;
    }

    private static GradientDrawable bar(int... colors) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        g.setCornerRadius(100f);
        return g;
    }
}
