package com.MrBRos.akhu;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
 * Debug-only tool that files pictures into the user's own Profile, as if they had saved them:
 * files/import/2d/*.jpg (external app storage) become saved 2D pictures, files/import/motion/*.mp4
 * kept motion videos, and files/import/3d/N.jpg with N_l0.jpg / N_l1.webp / N_l2.webp (and
 * N_motion.txt) the user's 3D images. Start it with
 * {@code adb shell am start -n com.MrBRos.akhu/.ImportActivity}; it logs "Import".
 */
public class ImportActivity extends Activity {

    private static final String TAG = "Import";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setPadding(48, 160, 48, 48);
        status.setText("Importing…");
        setContentView(status);
        File root = getExternalFilesDir("import");
        new Thread(() -> {
            int n = 0;
            for (File f : sorted(new File(root, "2d"))) {
                Bitmap bmp = BitmapFactory.decodeFile(f.getPath());
                if (bmp == null) continue;
                try {
                    ImageExporter.saveLocalCopy(this, bmp);
                    n++;
                    Thread.sleep(5); // file names are timestamps
                } catch (Exception e) {
                    Log.w(TAG, "2d " + f, e);
                } finally {
                    bmp.recycle();
                }
            }
            for (File f : sorted(new File(root, "motion"))) {
                try {
                    MotionWallpapers.keepCopy(this, f);
                    n++;
                    Thread.sleep(5);
                } catch (Exception e) {
                    Log.w(TAG, "motion " + f, e);
                }
            }
            File userDir = FourDWallpapers.userDir(this);
            userDir.mkdirs();
            long stamp = System.currentTimeMillis();
            for (File f : sorted(new File(root, "3d"))) {
                String name = f.getName();
                if (!name.endsWith(".jpg") || name.contains("_")) continue;
                String src = f.getPath().substring(0, f.getPath().length() - 4);
                String base = new File(userDir, "u" + (stamp++)).getPath();
                try {
                    String[] layers = FourDWallpapers.layersFor(base + ".jpg");
                    copy(new File(src + "_l0.jpg"), new File(layers[0]));
                    copy(new File(src + "_l1.webp"), new File(layers[1]));
                    File motion = new File(src + FourDWallpapers.MOTION_SUFFIX);
                    if (motion.exists()) copy(motion, new File(base + FourDWallpapers.MOTION_SUFFIX));
                    for (String extra : new String[]{"_l3.webp", "_l3.txt"}) {
                        File x = new File(src + extra);
                        if (x.exists()) copy(x, new File(base + extra));
                    }
                    copy(f, new File(base + ".jpg"));
                    // Near layer last: a set is only listed once it exists.
                    copy(new File(src + "_l2.webp"), new File(layers[2]));
                    n++;
                } catch (IOException e) {
                    Log.w(TAG, "3d " + f, e);
                }
            }
            int done = n;
            Log.i(TAG, "done " + done);
            runOnUiThread(() -> status.setText("Imported " + done));
        }, TAG).start();
    }

    private static File[] sorted(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream i = new FileInputStream(from); OutputStream o = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = i.read(buf)) > 0; ) o.write(buf, 0, r);
        }
    }
}
