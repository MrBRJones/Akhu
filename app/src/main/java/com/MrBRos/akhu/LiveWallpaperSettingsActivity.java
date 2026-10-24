package com.MrBRos.akhu;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.MrBRos.akhu.databinding.ActivityLiveWallpaperSettingsBinding;
import com.MrBRos.akhu.databinding.ItemEffectLayerBinding;
import com.MrBRos.akhu.databinding.ItemEffectSliderBinding;
import com.google.android.material.slider.Slider;

import java.util.Locale;

/** Ken Burns + per-layer effect settings. Values persist immediately; Reload pushes them to the engine. */
public class LiveWallpaperSettingsActivity extends AppCompatActivity {

    private ActivityLiveWallpaperSettingsBinding binding;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLiveWallpaperSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        prefs = EffectConfig.prefs(this);
        EffectConfig cfg = EffectConfig.load(this);

        bindSlider(binding.kbSpeed, binding.kbSpeedValue, EffectConfig.KB_SPEED_MIN, EffectConfig.KB_SPEED_MAX,
                0f, cfg.kbSpeed, EffectConfig.KEY_KB_SPEED);
        bindSlider(binding.kbZoom, binding.kbZoomValue, EffectConfig.KB_ZOOM_MIN, EffectConfig.KB_ZOOM_MAX,
                0f, cfg.kbZoom, EffectConfig.KEY_KB_ZOOM);
        binding.kbCycle.setChecked(cfg.kbCycle);
        binding.kbCycle.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(EffectConfig.KEY_KB_CYCLE, checked).apply());

        buildEffectLayers(cfg);
        binding.effectsHeader.setOnClickListener(v -> toggleEffects());

        binding.particleCount.setValueFrom(0);
        binding.particleCount.setValueTo(EffectConfig.MAX_PARTICLES);
        binding.particleCount.setStepSize(EffectConfig.PARTICLE_STEP);
        int count = (cfg.particleCount / EffectConfig.PARTICLE_STEP) * EffectConfig.PARTICLE_STEP;
        binding.particleCount.setValue(count);
        binding.particleCountValue.setText(String.valueOf(count));
        binding.particleCount.addOnChangeListener((s, value, fromUser) -> {
            binding.particleCountValue.setText(String.valueOf((int) value));
            prefs.edit().putInt(EffectConfig.KEY_PARTICLE_COUNT, (int) value).apply();
        });

        binding.btnReload.setOnClickListener(v -> {
            LiveWallpaperService.requestReload(this);
            Toast.makeText(this, R.string.reload_sent, Toast.LENGTH_SHORT).show();
        });
        binding.btnApplyLive.setOnClickListener(v -> {
            try {
                startActivity(WallpaperHelper.createLiveWallpaperIntent(this));
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, R.string.live_picker_missing, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void toggleEffects() {
        boolean show = binding.effectsContainer.getVisibility() != View.VISIBLE;
        binding.effectsContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        binding.effectsChevron.setRotation(show ? 180f : 0f);
    }

    private void buildEffectLayers(EffectConfig cfg) {
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < EffectConfig.LAYERS.length; i++) {
            EffectConfig.Layer layer = EffectConfig.LAYERS[i];
            ItemEffectLayerBinding row = ItemEffectLayerBinding.inflate(inflater, binding.effectsContainer, true);
            row.layerSwitch.setText(layer.label);
            row.layerSwitch.setChecked(cfg.isEnabled(i));
            row.layerParams.setVisibility(cfg.isEnabled(i) ? View.VISIBLE : View.GONE);
            row.layerSwitch.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean(layer.enabledKey(), checked).apply();
                row.layerParams.setVisibility(checked ? View.VISIBLE : View.GONE);
            });
            for (int j = 0; j < layer.params.length; j++) {
                EffectConfig.Param p = layer.params[j];
                ItemEffectSliderBinding slider = ItemEffectSliderBinding.inflate(inflater, row.layerParams, true);
                slider.label.setText(p.label);
                bindSlider(slider.slider, slider.value, p.min, p.max, p.step, cfg.get(i, j), layer.paramKey(p));
            }
        }
    }

    private void bindSlider(Slider slider, TextView valueView, float min, float max, float step,
                            float value, String key) {
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(step);
        float v = EffectConfig.clamp(value, min, max);
        if (step > 0) v = min + Math.round((v - min) / step) * step;
        slider.setValue(v);
        valueView.setText(format(v));
        slider.addOnChangeListener((s, newValue, fromUser) -> {
            valueView.setText(format(newValue));
            prefs.edit().putFloat(key, newValue).apply();
        });
    }

    private static String format(float v) {
        return Math.abs(v) >= 100 ? String.format(Locale.US, "%.0f", v)
                : Math.abs(v) >= 1 ? String.format(Locale.US, "%.2f", v)
                : String.format(Locale.US, "%.3f", v);
    }
}
