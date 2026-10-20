package com.MrBRos.wallpapercreator;

import android.content.res.Configuration;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.MrBRos.wallpapercreator.databinding.ActivitySettingsBinding;

/** Settings screen opened from the gear at the top right; holds the dark mode switch. */
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
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
