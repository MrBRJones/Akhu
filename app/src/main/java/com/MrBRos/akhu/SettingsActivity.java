package com.MrBRos.akhu;

import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.MrBRos.akhu.databinding.ActivitySettingsBinding;
import com.google.android.material.color.MaterialColors;

import java.util.Arrays;

/** Settings screen opened from the gear at the top right: dark mode and app colors. */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivitySettingsBinding binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Before the switch is first used the app follows the phone, so show whatever is on screen now.
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        binding.switchDark.setChecked(dark);
        binding.switchDark.setOnCheckedChangeListener((b, checked) -> App.setNightMode(this,
                checked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO));

        // App colors: the main color rebuilds the app's M3 color set, the second joins it in the
        // banner gradient and accents. Each swatch opens the creation window's color picker.
        int[] colors = App.appColors(this);
        int main = colors != null ? colors[0] : App.CLASSIC_MAIN;
        int second = colors != null ? colors[1] : App.CLASSIC_SECOND;
        binding.appColorSwatch.setBackground(dot(main, false));
        binding.appColorSwatch2.setBackground(dot(second, false));
        binding.btnResetAppColor.setVisibility(colors != null ? View.VISIBLE : View.GONE);
        binding.btnResetAppColor.setOnClickListener(v -> setColors(null, null));
        View.OnClickListener pickMain = v -> {
            if (checkAvailable()) ColorPickerSheet.show(this, R.string.app_color_main, main,
                    color -> setColors(color, second));
        };
        binding.rowAppColor.setOnClickListener(pickMain);
        binding.appColorSwatch.setOnClickListener(pickMain);
        binding.appColorSwatch2.setOnClickListener(v -> {
            if (checkAvailable()) ColorPickerSheet.show(this, R.string.app_color_second, second,
                    color -> setColors(main, color));
        });
        addColorPresets(binding, colors);
    }

    private boolean checkAvailable() {
        if (App.canChangeAppColor()) return true;
        Toast.makeText(this, R.string.app_color_unavailable, Toast.LENGTH_LONG).show();
        return false;
    }

    private void setColors(@Nullable Integer main, @Nullable Integer second) {
        App.setAppColors(this, main, second);
        recreate();
    }

    /** Round swatch of one color, or a main-to-second gradient for a preset pair. */
    private GradientDrawable dot(int color, boolean selected, int... more) {
        float dp = getResources().getDisplayMetrics().density;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (more.length > 0) {
            d.setOrientation(GradientDrawable.Orientation.TL_BR);
            d.setColors(new int[]{color, more[0]});
        } else {
            d.setColor(color);
        }
        View root = findViewById(android.R.id.content);
        d.setStroke(Math.round((selected ? 3 : 1) * dp), MaterialColors.getColor(root, selected
                ? com.google.android.material.R.attr.colorOnSurface
                : com.google.android.material.R.attr.colorOutlineVariant));
        return d;
    }

    /** One-tap color pairs under the App color row; the current one gets a thick ring. Classic blue is the default. */
    private void addColorPresets(ActivitySettingsBinding binding, @Nullable int[] colors) {
        if (!App.canChangeAppColor()) {
            binding.appColorPresets.setVisibility(View.GONE);
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < PRESETS.length; i++) {
            int[] pair = PRESETS[i];
            // Classic blue is the built-in theme, so picking it clears the custom colors.
            boolean classic = i == 0;
            boolean selected = classic ? colors == null : Arrays.equals(pair, colors);
            View swatch = new View(this);
            swatch.setBackground(dot(pair[0], selected, pair[1]));
            swatch.setContentDescription(getString(PRESET_NAMES[i]));
            swatch.setOnClickListener(v -> setColors(classic ? null : pair[0], classic ? null : pair[1]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(36 * dp), 1);
            lp.setMargins(Math.round(6 * dp), 0, Math.round(6 * dp), 0);
            FrameLayout cell = new FrameLayout(this);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp));
            dotLp.gravity = Gravity.CENTER;
            cell.addView(swatch, dotLp);
            binding.appColorPresets.addView(cell, lp);
        }
    }

    /** {main, second} for each preset. */
    private static final int[][] PRESETS = {
            {App.CLASSIC_MAIN, App.CLASSIC_SECOND},
            {0xFF00A88F, 0xFF2E8FD6},
            {0xFFE0457B, 0xFFFF8A3D},
            {0xFF7B5CD6, 0xFFE0457B},
            {0xFFF07A1A, 0xFFE0457B},
            {0xFF3E9B3A, 0xFF00A88F},
            {0xFFD93A3A, 0xFF8E44FF}};
    private static final int[] PRESET_NAMES = {
            R.string.color_classic, R.string.color_teal, R.string.color_pink, R.string.color_purple,
            R.string.color_orange, R.string.color_green, R.string.color_red};

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
