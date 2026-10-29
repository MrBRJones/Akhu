package com.MrBRos.akhu;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.MrBRos.akhu.databinding.ActivitySettingsBinding;

/** Settings screen opened from the gear at the top right: dark mode, app color, and a shortcut to the motion settings. */
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
        binding.rowMotionSettings.setOnClickListener(v ->
                startActivity(new Intent(this, LiveWallpaperSettingsActivity.class)));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
