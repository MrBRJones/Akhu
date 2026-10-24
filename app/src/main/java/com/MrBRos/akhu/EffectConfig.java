package com.MrBRos.akhu;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences-backed settings for the live wallpaper: Ken Burns motion,
 * five independent shader layers (toggle + params each) and the particle count.
 * An instance is an immutable snapshot that is safe to hand to the render thread.
 */
public final class EffectConfig {

    public static final String PREFS = "wallpaper_prefs";
    public static final String KEY_CURRENT_PATH = "current_wallpaper_path";

    public static final String KEY_KB_SPEED = "kb_speed";
    public static final String KEY_KB_ZOOM = "kb_zoom";
    public static final String KEY_KB_CYCLE = "kb_cycle";
    public static final String KEY_PARTICLE_COUNT = "particle_count";

    public static final float KB_SPEED_MIN = 0.1f, KB_SPEED_MAX = 2.0f, KB_SPEED_DEF = 0.5f;
    public static final float KB_ZOOM_MIN = 1.0f, KB_ZOOM_MAX = 1.5f, KB_ZOOM_DEF = 1.15f;
    public static final int MAX_PARTICLES = 64;
    public static final int PARTICLE_STEP = 16;
    public static final int PARTICLE_DEF = 32;

    public static final class Param {
        public final String key;
        public final String label;
        public final float min, max, def;
        /** Slider step; 0 means continuous. */
        public final float step;

        Param(String key, String label, float min, float max, float def, float step) {
            this.key = key;
            this.label = label;
            this.min = min;
            this.max = max;
            this.def = def;
            this.step = step;
        }
    }

    public static final class Layer {
        public final String id;
        public final String label;
        public final boolean defEnabled;
        public final Param[] params;

        Layer(String id, String label, boolean defEnabled, Param... params) {
            this.id = id;
            this.label = label;
            this.defEnabled = defEnabled;
            this.params = params;
        }

        public String enabledKey() {
            return id + "_enabled";
        }

        public String paramKey(Param p) {
            return id + "_" + p.key;
        }

        /** Uniform names follow u_<layer>_enabled / u_<layer>_<param>. */
        public String enabledUniform() {
            return "u_" + id + "_enabled";
        }

        public String paramUniform(Param p) {
            return "u_" + id + "_" + p.key;
        }
    }

    /** Fixed shader order: motion, blur, color shift, glow, noise. */
    public static final Layer[] LAYERS = {
            new Layer("motion", "Motion / Displacement", true,
                    new Param("speed", "Speed", 0f, 3f, 0.6f, 0f),
                    new Param("amplitude", "Amplitude", 0f, 0.05f, 0.008f, 0f),
                    new Param("frequency", "Frequency", 1f, 30f, 8f, 0f)),
            new Layer("blur", "Blur", false,
                    new Param("radius", "Radius (px)", 0f, 12f, 2f, 0f),
                    new Param("samples", "Samples", 4f, 16f, 8f, 1f)),
            new Layer("colorshift", "Color Shift", false,
                    new Param("hue", "Hue (deg)", -180f, 180f, 0f, 0f),
                    new Param("saturation", "Saturation", 0f, 2f, 1.1f, 0f),
                    new Param("contrast", "Contrast", 0.5f, 1.5f, 1.05f, 0f)),
            new Layer("glow", "Glow", false,
                    new Param("intensity", "Intensity", 0f, 2f, 0.5f, 0f),
                    new Param("threshold", "Threshold", 0f, 1f, 0.7f, 0f),
                    new Param("radius", "Radius (px)", 1f, 12f, 4f, 0f)),
            new Layer("noise", "Noise / Grain", false,
                    new Param("scale", "Scale", 50f, 1000f, 400f, 0f),
                    new Param("intensity", "Intensity", 0f, 0.3f, 0.05f, 0f),
                    new Param("speed", "Speed", 0f, 10f, 1f, 0f)),
    };

    public final float kbSpeed;
    public final float kbZoom;
    public final boolean kbCycle;
    public final int particleCount;
    private final boolean[] enabled;
    private final float[][] values;

    private EffectConfig(SharedPreferences sp) {
        kbSpeed = clamp(sp.getFloat(KEY_KB_SPEED, KB_SPEED_DEF), KB_SPEED_MIN, KB_SPEED_MAX);
        kbZoom = clamp(sp.getFloat(KEY_KB_ZOOM, KB_ZOOM_DEF), KB_ZOOM_MIN, KB_ZOOM_MAX);
        kbCycle = sp.getBoolean(KEY_KB_CYCLE, false);
        int count = sp.getInt(KEY_PARTICLE_COUNT, PARTICLE_DEF);
        particleCount = Math.max(0, Math.min(MAX_PARTICLES, count));
        enabled = new boolean[LAYERS.length];
        values = new float[LAYERS.length][];
        for (int i = 0; i < LAYERS.length; i++) {
            Layer layer = LAYERS[i];
            enabled[i] = sp.getBoolean(layer.enabledKey(), layer.defEnabled);
            values[i] = new float[layer.params.length];
            for (int j = 0; j < layer.params.length; j++) {
                Param p = layer.params[j];
                values[i][j] = clamp(sp.getFloat(layer.paramKey(p), p.def), p.min, p.max);
            }
        }
    }

    public static EffectConfig load(Context context) {
        return new EffectConfig(prefs(context));
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled(int layer) {
        return enabled[layer];
    }

    public float get(int layer, int param) {
        return values[layer][param];
    }

    public static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
