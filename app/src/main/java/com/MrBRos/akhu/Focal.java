package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Subject focal points for bundled images (assets/focal.json, made offline from AI depth maps).
 * Crops are centred on the subject instead of the image middle, so it stays in frame on both the
 * tall cover screen and the near-square inner screen of a foldable.
 */
public final class Focal {

    /**
     * The depth model counts the ground as "near", which drags the vertical point down. Portrait
     * images mostly lose width (not height) on phone screens, so vertical placement is damped
     * toward the middle to avoid cutting off heads.
     */
    private static final float VERTICAL_WEIGHT = 0.35f;
    private static final float[] CENTER = {0.5f, 0.5f};

    private static Map<String, float[]> points;

    private Focal() {
    }

    /** Focal point (0..1, image space) for an asset path like "fourd/1.jpg"; centre if unknown. */
    public static synchronized float[] get(Context context, String asset) {
        if (points == null) points = load(context.getApplicationContext());
        float[] p = asset == null ? null : points.get(asset);
        return p == null ? CENTER : p;
    }

    private static Map<String, float[]> load(Context context) {
        Map<String, float[]> map = new HashMap<>();
        try (InputStream in = context.getAssets().open("focal.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            JSONObject json = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONArray xy = json.getJSONArray(key);
                float fy = (float) xy.getDouble(1);
                map.put(key, new float[]{(float) xy.getDouble(0), 0.5f + (fy - 0.5f) * VERTICAL_WEIGHT});
            }
        } catch (Exception ignored) {
            // No focal data: everything falls back to centre crops.
        }
        return map;
    }

    /**
     * The largest {@code targetW:targetH} window inside a {@code srcW x srcH} image, centred on the
     * focal point and clamped to the image bounds.
     */
    public static Rect cropRect(int srcW, int srcH, int targetW, int targetH, float fx, float fy) {
        float targetAspect = (float) targetW / targetH;
        int w = srcW, h = srcH;
        if ((float) srcW / srcH > targetAspect) w = Math.round(srcH * targetAspect);
        else h = Math.round(srcW / targetAspect);
        int left = Math.round(fx * srcW - w / 2f);
        int top = Math.round(fy * srcH - h / 2f);
        left = Math.max(0, Math.min(srcW - w, left));
        top = Math.max(0, Math.min(srcH - h, top));
        return new Rect(left, top, left + w, top + h);
    }

    /**
     * Centre (in 0..1 texture space) of a crop window of size {@code scaleX x scaleY} (fractions of
     * the texture), placed on the focal point but kept inside the texture.
     */
    public static float[] windowCenter(float fx, float fy, float scaleX, float scaleY) {
        // Keep room for the 4D layers to slide (max ~0.03 at full tilt) without hitting an edge.
        float margin = 0.03f;
        float hx = Math.min(0.5f, scaleX / 2f + margin), hy = Math.min(0.5f, scaleY / 2f + margin);
        return new float[]{Math.max(hx, Math.min(1f - hx, fx)), Math.max(hy, Math.min(1f - hy, fy))};
    }
}
