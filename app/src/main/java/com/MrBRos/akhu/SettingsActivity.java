package com.MrBRos.akhu;

import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.MrBRos.akhu.databinding.ActivitySettingsBinding;
import com.google.android.material.color.MaterialColors;

import java.util.Objects;

/** Settings screen opened from the gear at the top right: dark mode and app color. */
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
        // App color: the whole app's M3 color set is rebuilt from the picked color.
        Integer appColor = App.appColor(this);
        binding.btnResetAppColor.setVisibility(appColor != null ? View.VISIBLE : View.GONE);
        binding.btnResetAppColor.setOnClickListener(v -> {
            App.setAppColor(this, null);
            recreate();
        });
        binding.rowAppColor.setOnClickListener(v -> {
            if (!App.canChangeAppColor()) {
                Toast.makeText(this, R.string.app_color_unavailable, Toast.LENGTH_LONG).show();
                return;
            }
            int current = appColor != null ? appColor : ContextCompat.getColor(this, R.color.brand_primary);
            ColorPickerSheet.show(this, R.string.app_color, current, color -> {
                App.setAppColor(this, color);
                recreate();
            });
        });
        addColorPresets(binding, appColor);
    }

    /** One-tap app colors under the App color row; the current one gets a thick ring. Classic blue is the default. */
    private void addColorPresets(ActivitySettingsBinding binding, Integer appColor) {
        if (!App.canChangeAppColor()) {
            binding.appColorPresets.setVisibility(View.GONE);
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        int ring = MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorOnSurface);
        int outline = MaterialColors.getColor(binding.getRoot(), com.google.android.material.R.attr.colorOutlineVariant);
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            // Classic blue is the built-in theme, so picking it clears the custom color.
            Integer preset = i == 0 ? null : PRESET_COLORS[i];
            boolean selected = Objects.equals(preset, appColor);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(PRESET_COLORS[i]);
            dot.setStroke(Math.round((selected ? 3 : 1) * dp), selected ? ring : outline);
            View swatch = new View(this);
            swatch.setBackground(dot);
            swatch.setContentDescription(getString(PRESET_NAMES[i]));
            swatch.setOnClickListener(v -> {
                App.setAppColor(this, preset);
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(36 * dp), 1);
            lp.setMargins(Math.round(6 * dp), 0, Math.round(6 * dp), 0);
            FrameLayout cell = new FrameLayout(this);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp));
            dotLp.gravity = Gravity.CENTER;
            cell.addView(swatch, dotLp);
            binding.appColorPresets.addView(cell, lp);
        }
    }

    private static final int[] PRESET_COLORS = {
            0xFF2F6BFF, 0xFF00A88F, 0xFFE0457B, 0xFF7B5CD6, 0xFFF07A1A, 0xFF3E9B3A, 0xFFD93A3A};
    private static final int[] PRESET_NAMES = {
            R.string.color_classic, R.string.color_teal, R.string.color_pink, R.string.color_purple,
            R.string.color_orange, R.string.color_green, R.string.color_red};

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
