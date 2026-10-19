package com.MrBRos.wallpapercreator;

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
 * Turns device orientation into a smoothed tilt in -1..1 on each axis. The neutral pose slowly
 * follows however the phone is being held, so the image re-centres instead of sitting off to
 * one side.
 */
public class TiltSensor implements SensorEventListener {

    /** Tilt (radians) that maps to full parallax. */
    private static final float MAX_ANGLE = 0.35f;
    private static final float BASELINE_FOLLOW = 0.015f;
    private static final float SMOOTHING = 0.18f;

    private final SensorManager sensorManager;
    private final Sensor sensor;
    /** Orientation quaternions (w, x, y, z): the latest reading and the neutral pose. */
    private final float[] current = new float[4], base = new float[4];
    private boolean hasBaseline;
    private volatile float x, y;

    private final Display display;
    private int lastRotation = -1;

    public TiltSensor(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) s = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensor = s;
        display = displayOf(context);
    }

    /** The screen this context draws on (an activity's), else the default one (services). */
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
        hasBaseline = false;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    /**
     * Tilt is measured as the rotation from the (slowly following) neutral pose rather than as
     * pitch/roll angles: those break down when the phone is held straight up (gimbal lock), which
     * made the image kick sideways.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getQuaternionFromVector(current, event.values);
        int screenRotation = display.getRotation();
        if (screenRotation != lastRotation) {
            lastRotation = screenRotation;
            hasBaseline = false;
        }
        if (!hasBaseline) {
            System.arraycopy(current, 0, base, 0, 4);
            hasBaseline = true;
        }
        follow(base, current, BASELINE_FOLLOW);

        // Rotation from the neutral pose to now, in device axes (w, x, y, z), as a small-angle
        // vector: radians about the device's X (top toward/away) and Y (left/right) axes.
        float bw = base[0], bx = -base[1], by = -base[2], bz = -base[3];
        float cw = current[0], cx = current[1], cy = current[2], cz = current[3];
        float rw = bw * cw - bx * cx - by * cy - bz * cz;
        float rx = bw * cx + bx * cw + by * cz - bz * cy;
        float ry = bw * cy - bx * cz + by * cw + bz * cx;
        float sign = rw < 0 ? -2f : 2f;
        float ax = rx * sign, ay = ry * sign;

        // Sensor axes are fixed to the device; turn them to match the screen so "left" stays
        // left when the screen is rotated (e.g. a foldable's inner screen held sideways).
        float sx, sy;
        switch (screenRotation) {
            case Surface.ROTATION_90:
                sx = ay;
                sy = -ax;
                break;
            case Surface.ROTATION_180:
                sx = -ax;
                sy = -ay;
                break;
            case Surface.ROTATION_270:
                sx = -ay;
                sy = ax;
                break;
            default:
                sx = ax;
                sy = ay;
        }
        // Signs match the old pitch/roll mapping: left/right is +Y, top away from you is -X.
        float tx = clamp(sy / MAX_ANGLE);
        float ty = clamp(-sx / MAX_ANGLE);
        x += (tx - x) * SMOOTHING;
        y += (ty - y) * SMOOTHING;
    }

    /** Moves quaternion {@code q} a fraction {@code t} toward {@code target} (normalised lerp). */
    private static void follow(float[] q, float[] target, float t) {
        float dot = q[0] * target[0] + q[1] * target[1] + q[2] * target[2] + q[3] * target[3];
        float s = dot < 0 ? -t : t;
        float len = 0;
        for (int i = 0; i < 4; i++) {
            q[i] = q[i] * (1 - t) + target[i] * s;
            len += q[i] * q[i];
        }
        len = (float) Math.sqrt(len);
        for (int i = 0; i < 4; i++) q[i] /= len;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
