package com.MrBRos.akhu;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Which way is downhill on the screen, for reactive wallpapers: gravity measured against how the
 * phone is usually being held (that pose slowly follows), so the character drifts when the phone
 * is tipped rather than always sinking to the bottom. Gives -1..1 toward screen right and down.
 */
public class GravityTilt implements SensorEventListener {

    /** Gravity change (m/s²) that counts as full tilt. */
    private static final float FULL = 3.2f;
    /** How fast the resting pose follows the phone, per second. */
    private static final float FOLLOW = 0.35f;

    private final SensorManager sensorManager;
    private final Sensor sensor;
    private final Display display;
    private final float[] base = new float[2];
    private boolean hasBase;
    private long lastNanos;
    private volatile float x, y;

    public GravityTilt(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (s == null) s = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensor = s;
        display = displayOf(context);
    }

    @SuppressWarnings("deprecation")
    private static Display displayOf(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return context.getDisplay();
            } catch (UnsupportedOperationException ignored) {
                // Not a visual context.
            }
        }
        return ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
    }

    public void start() {
        if (sensor != null) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        hasBase = false;
        x = 0;
        y = 0;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // The sensor reports "up"; downhill on the device is the opposite of its x and y.
        float dx = -event.values[0], dy = event.values[1];
        // Device axes to screen axes (x right, y down) for the current screen rotation.
        float sx, sy;
        switch (display.getRotation()) {
            case Surface.ROTATION_90:
                // Screen right is the device's top, screen down its right side.
                sx = -dy;
                sy = dx;
                break;
            case Surface.ROTATION_180:
                sx = -dx;
                sy = -dy;
                break;
            case Surface.ROTATION_270:
                sx = dy;
                sy = -dx;
                break;
            default:
                sx = dx;
                sy = dy;
        }
        long now = event.timestamp;
        float dt = lastNanos == 0 ? 0 : Math.min(0.2f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (!hasBase) {
            base[0] = sx;
            base[1] = sy;
            hasBase = true;
        }
        float f = 1f - (float) Math.exp(-FOLLOW * dt);
        base[0] += (sx - base[0]) * f;
        base[1] += (sy - base[1]) * f;
        x = clamp((sx - base[0]) / FULL);
        y = clamp((sy - base[1]) / FULL);
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
