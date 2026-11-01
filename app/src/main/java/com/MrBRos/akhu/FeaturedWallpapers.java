package com.MrBRos.akhu;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Wallpapers bundled in assets/featured/&lt;category&gt;/ (sources listed in CREDITS.txt there). */
public final class FeaturedWallpapers {

    private static final String ROOT = "featured";

    private FeaturedWallpapers() {
    }

    /** Asset paths for one category, or for every featured category when {@code category} is null. */
    public static List<String> list(Context context, @Nullable String category) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Categories.FEATURED_COUNT; i++) {
            String id = Categories.IDS[i];
            if (category != null && !category.equals(id)) continue;
            try {
                String[] files = context.getAssets().list(ROOT + "/" + id);
                if (files == null) continue;
                // 1, 2, ... 10 in number order, not 1, 10, 2.
                Arrays.sort(files, (a, b) -> Integer.compare(number(a), number(b)));
                for (String f : files) out.add(ROOT + "/" + id + "/" + f);
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    /** The leading number of a file name like "12.jpg", or a large value when it has none. */
    static int number(String file) {
        int end = 0;
        while (end < file.length() && Character.isDigit(file.charAt(end))) end++;
        return end == 0 ? Integer.MAX_VALUE : Integer.parseInt(file.substring(0, end));
    }

    /** Copies an asset into cache so the Create tab can decode, share and set it like a picked file. */
    public static File copyToCache(Context context, String assetPath) throws IOException {
        File dir = new File(context.getCacheDir(), "picked");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File out = new File(dir, "featured_" + assetPath.replace('/', '_'));
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }
}
