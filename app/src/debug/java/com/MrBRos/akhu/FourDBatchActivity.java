package com.MrBRos.akhu;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Debug-only tool for making the bundled 3D wallpapers: runs {@link FourDMaker} on every picture
 * in files/fourd_in (external app storage) and copies each finished set to files/fourd_out as
 * N.jpg, N_l0.jpg, N_l1.webp, N_l2.webp and N_motion.txt, ready for assets/fourd. The user copy
 * the maker saves is deleted again, so nothing shows up in Profile. Start it with
 * {@code adb shell am start -n com.MrBRos.akhu/.FourDBatchActivity}; it logs "FourDBatch".
 */
public class FourDBatchActivity extends Activity {

    private static final String TAG = "FourDBatch";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setPadding(48, 160, 48, 48);
        status.setText("Making 3D sets…");
        setContentView(status);
        File in = getExternalFilesDir("fourd_in"), out = getExternalFilesDir("fourd_out");
        new Thread(() -> {
            File[] files = in == null ? null : in.listFiles();
            if (files == null || out == null) {
                Log.w(TAG, "no input");
                return;
            }
            Arrays.sort(files);
            int ok = 0;
            for (File f : files) {
                String name = f.getName().substring(0, f.getName().lastIndexOf('.'));
                try {
                    String made = FourDMaker.make(this, f, step -> { });
                    String base = made.substring(0, made.length() - 4);
                    copy(new File(made), new File(out, name + ".jpg"));
                    String[] layers = FourDWallpapers.layersFor(made);
                    copy(new File(layers[0]), new File(out, name + "_l0.jpg"));
                    copy(new File(layers[1]), new File(out, name + "_l1.webp"));
                    copy(new File(layers[2]), new File(out, name + "_l2.webp"));
                    copy(new File(base + FourDWallpapers.MOTION_SUFFIX), new File(out, name + FourDWallpapers.MOTION_SUFFIX));
                    FourDWallpapers.delete(this, made);
                    ok++;
                    Log.i(TAG, "made " + name);
                } catch (Exception e) {
                    Log.w(TAG, "failed " + name, e);
                }
            }
            int done = ok;
            Log.i(TAG, "done " + done + "/" + files.length);
            runOnUiThread(() -> status.setText("Done: " + done + " of " + files.length));
        }, TAG).start();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream i = new FileInputStream(from); OutputStream o = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = i.read(buf)) > 0; ) o.write(buf, 0, r);
        }
    }
}
