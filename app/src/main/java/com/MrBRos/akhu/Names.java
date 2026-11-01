package com.MrBRos.akhu;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Display names of the bundled wallpapers (assets/names.json), keyed by asset path. */
public final class Names {

    private static final Map<String, String> NAMES = new HashMap<>();

    private Names() {
    }

    /** Reads names.json once; called from {@link App#onCreate}. */
    static synchronized void init(Context context) {
        if (!NAMES.isEmpty()) return;
        try (InputStream in = context.getAssets().open("names.json")) {
            JSONObject json = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String key = keys.next();
                NAMES.put(key, json.getString(key));
            }
        } catch (Exception e) {
            Log.w("Names", "No wallpaper names", e);
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** The bundled wallpaper's name, or "" when it has none. */
    public static synchronized String of(String assetPath) {
        String name = NAMES.get(assetPath);
        return name == null ? "" : name;
    }
}
