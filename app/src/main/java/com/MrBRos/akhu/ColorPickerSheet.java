package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.StringRes;

import com.MrBRos.akhu.databinding.SheetColorPickerBinding;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * A pop-up colour picker: hue ring with a shade square, a HEX box and H/S/V sliders, all kept in
 * step. {@code onPick} gets the colour when the user taps Apply.
 */
public final class ColorPickerSheet {

    private final SheetColorPickerBinding b;
    private final float[] hsv = new float[3];
    private final float corner;
    private final HsvSliders sliders;
    /** True while the views are being set from code, so their listeners don't feed back. */
    private boolean updating;

    private ColorPickerSheet(SheetColorPickerBinding b, int initial) {
        this.b = b;
        corner = 4 * b.getRoot().getResources().getDisplayMetrics().density;
        Color.colorToHSV(initial, hsv);
        b.swatchOld.setBackground(rounded(initial | 0xFF000000, true));
        sliders = new HsvSliders(b.rowH, b.rowS, b.rowV, new String[]{"H", "S", "V"}, hsv, () -> sync(true));
    }

    public static void show(Context context, @StringRes int title, int initial, IntConsumer onPick) {
        SheetColorPickerBinding b = SheetColorPickerBinding.inflate(LayoutInflater.from(context));
        ColorPickerSheet picker = new ColorPickerSheet(b, initial);
        b.pickerTitle.setText(title);

        BottomSheetDialog dialog = new BottomSheetDialog(context);
        dialog.setContentView(b.getRoot());
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);

        b.wheel.setOnColorChangedListener(v -> {
            System.arraycopy(v, 0, picker.hsv, 0, 3);
            picker.sync(true);
        });
        b.hexInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (picker.updating) return;
                String hex = s.toString().replace("#", "");
                if (hex.length() != 6) return;
                Color.colorToHSV(0xFF000000 | Integer.parseInt(hex, 16), picker.hsv);
                picker.sync(false);
            }
        });
        b.hexInput.setOnEditorActionListener((v, action, e) -> {
            if (action != EditorInfo.IME_ACTION_DONE && (e == null || e.getKeyCode() != KeyEvent.KEYCODE_ENTER)) {
                return false;
            }
            v.clearFocus();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        b.btnUseColour.setOnClickListener(v -> {
            onPick.accept(Color.HSVToColor(picker.hsv));
            dialog.dismiss();
        });
        picker.sync(true);
        dialog.show();
    }

    /** Pushes {@link #hsv} out to every control; the HEX box is skipped while it is being typed in. */
    private void sync(boolean hex) {
        updating = true;
        int color = Color.HSVToColor(hsv);
        b.wheel.setHsv(hsv);
        if (hex) {
            b.hexInput.setText(String.format(Locale.US, "#%06X", color & 0xFFFFFF));
        }
        b.swatchNew.setBackground(rounded(color, false));
        sliders.sync();
        updating = false;
    }

    /** Left or right half of the before/after swatch. */
    private GradientDrawable rounded(int color, boolean left) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(left
                ? new float[]{corner, corner, 0, 0, 0, 0, corner, corner}
                : new float[]{0, 0, corner, corner, corner, corner, 0, 0});
        return g;
    }
}
