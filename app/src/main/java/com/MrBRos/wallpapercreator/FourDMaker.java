package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Build;

import androidx.annotation.Nullable;

import com.google.android.gms.common.moduleinstall.InstallStatusListener;
import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.Subject;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Turns one of the user's pictures into a 4D image, on the phone. ML Kit's subject segmentation
 * finds the subjects: the biggest becomes the near layer, any others the mid layer, and the far
 * layer is the picture with the subjects painted out (push-pull fill), so nothing doubles up
 * when the layers slide apart.
 *
 * <p>Besides the layers, a set in files/fourd_user/ keeps what's needed to edit it again in
 * {@link FourDEditorActivity}: uN_mask.png (red = near, green = mid, blue = subject number) and
 * uN_subj.png (the subjects' cut-out confidence, grey).
 */
public final class FourDMaker {

    /** Thrown with a user-facing message resource. */
    public static class MakeException extends Exception {
        public final int messageRes;

        MakeException(int messageRes, @Nullable Throwable cause) {
            super(cause);
            this.messageRes = messageRes;
        }
    }

    public interface Progress {
        /** A status string resource; called on the worker thread. */
        void onStep(int messageRes);
    }

    public static final int BACK = 0, MIDDLE = 1, FRONT = 2;

    /**
     * Editable state of one 4D image: the picture and, per pixel, how much of it is on the near
     * and mid layers (0..255; the rest is background). {@code conf} and {@code label} remember
     * the found subjects, so a subject sent to the background can be brought back.
     */
    public static final class Session {
        public final Bitmap image;
        public final int w, h;
        final int[] px;
        public byte[] near, mid;
        final byte[] conf, label;

        Session(Bitmap image, byte[] near, byte[] mid, byte[] conf, byte[] label) {
            this.image = image;
            w = image.getWidth();
            h = image.getHeight();
            px = new int[w * h];
            image.getPixels(px, 0, w, 0, 0, w, h);
            this.near = near;
            this.mid = mid;
            this.conf = conf;
            this.label = label;
        }

        /** The layer the picture is on at a pixel. */
        public int layerAt(int x, int y) {
            int i = y * w + x;
            int n = near[i] & 0xFF, m = mid[i] & 0xFF;
            if (n > 127 && n >= m) return FRONT;
            if (m > 127) return MIDDLE;
            return BACK;
        }

        /**
         * Moves the subject (or painted shape) under a pixel to the next layer: front, middle,
         * background (found subjects only), front again. Returns the new layer, or -1 if there's
         * nothing there to move.
         */
        public int cycleAt(int x, int y) {
            if (x < 0 || y < 0 || x >= w || y >= h) return -1;
            int i0 = y * w + x;
            int k = label[i0] & 0xFF;
            boolean[] region;
            byte[] source;
            if (k > 0) {
                boolean[] own = new boolean[w * h];
                boolean[] free = new boolean[w * h];
                for (int i = 0; i < own.length; i++) {
                    own[i] = (label[i] & 0xFF) == k;
                    free[i] = label[i] == 0;
                }
                // Take in the soft edge around it too, but not a neighbouring subject.
                region = dilate(own, w, h, 3);
                for (int i = 0; i < region.length; i++) region[i] = own[i] || (region[i] && free[i]);
                source = conf;
            } else {
                if (Math.max(near[i0] & 0xFF, mid[i0] & 0xFF) <= 127) return -1;
                region = flood(x, y);
                source = new byte[w * h];
                for (int i = 0; i < source.length; i++) source[i] = (byte) Math.max(near[i] & 0xFF, mid[i] & 0xFF);
            }
            int now = layerAt(x, y);
            int next = now == FRONT ? MIDDLE : now == MIDDLE ? (k > 0 ? BACK : FRONT) : FRONT;
            for (int i = 0; i < region.length; i++) {
                if (!region[i]) continue;
                byte a = source[i];
                near[i] = next == FRONT ? a : 0;
                mid[i] = next == MIDDLE ? a : 0;
            }
            return next;
        }

        /** 4-connected shape of non-background pixels around (x, y). */
        private boolean[] flood(int x, int y) {
            boolean[] in = new boolean[w * h];
            int[] stack = new int[w * h];
            int top = 0;
            stack[top++] = y * w + x;
            in[y * w + x] = true;
            while (top > 0) {
                int i = stack[--top];
                int cx = i % w, cy = i / w;
                int[] next = {cx > 0 ? i - 1 : -1, cx < w - 1 ? i + 1 : -1, cy > 0 ? i - w : -1, cy < h - 1 ? i + w : -1};
                for (int j : next) {
                    if (j < 0 || in[j] || Math.max(near[j] & 0xFF, mid[j] & 0xFF) <= 40) continue;
                    in[j] = true;
                    stack[top++] = j;
                }
            }
            return in;
        }

        /**
         * Paints a soft round dab of radius {@code r} (image pixels) onto a layer; {@code BACK}
         * erases. Returns the changed area.
         */
        public Rect stamp(float cx, float cy, float r, int layer) {
            int x0 = Math.max(0, (int) (cx - r)), x1 = Math.min(w - 1, (int) Math.ceil(cx + r));
            int y0 = Math.max(0, (int) (cy - r)), y1 = Math.min(h - 1, (int) Math.ceil(cy + r));
            float hard = r * 0.7f;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    float d = (float) Math.hypot(x - cx, y - cy);
                    if (d >= r) continue;
                    float f = d <= hard ? 1f : 1f - (d - hard) / (r - hard);
                    int i = y * w + x;
                    int n = near[i] & 0xFF, m = mid[i] & 0xFF;
                    if (layer == FRONT) {
                        n = Math.max(n, Math.round(255 * f));
                        m = Math.round(m * (1 - f));
                    } else if (layer == MIDDLE) {
                        m = Math.max(m, Math.round(255 * f));
                        n = Math.round(n * (1 - f));
                    } else {
                        n = Math.round(n * (1 - f));
                        m = Math.round(m * (1 - f));
                    }
                    near[i] = (byte) n;
                    mid[i] = (byte) m;
                }
            }
            return new Rect(x0, y0, x1 + 1, y1 + 1);
        }

        /** Copy of the near and mid masks, for undo. */
        public byte[][] snapshot() {
            return new byte[][]{near.clone(), mid.clone()};
        }

        public void restore(byte[][] s) {
            near = s[0];
            mid = s[1];
        }

        boolean hasForeground() {
            for (int i = 0; i < near.length; i++) if ((near[i] & 0xFF) > 127 || (mid[i] & 0xFF) > 127) return true;
            return false;
        }
    }

    /** Longest side of the made layers, like the bundled ones (1080 x 1350). */
    private static final int MAX_DIM = 1600;
    /** Mask value (0..255) above which a pixel counts as foreground for the far layer's hole. */
    private static final int HOLE_THRESHOLD = 64;
    /** Ignore subjects smaller than this share of the picture (specks, far-off people). */
    private static final float MIN_SUBJECT = 0.004f;

    private FourDMaker() {
    }

    /**
     * Finds the subjects in a picture and saves a first 4D set. Blocking; call off the main
     * thread. Returns the new image path for {@link FourDWallpapers}.
     */
    public static String make(Context context, File source, Progress progress) throws MakeException {
        Context app = context.getApplicationContext();
        Bitmap decoded = ImagePickerHelper.decodeSampled(source, MAX_DIM);
        if (decoded == null) throw new MakeException(R.string.pick_failed, null);
        Bitmap image = fit(decoded);

        progress.onStep(R.string.fourd_step_subject);
        SubjectSegmenter segmenter = SubjectSegmentation.getClient(new SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(new SubjectSegmenterOptions.SubjectResultOptions.Builder()
                        .enableConfidenceMask().build())
                .build());
        SubjectSegmentationResult result;
        try {
            ensureModule(app, segmenter, progress);
            result = Tasks.await(segmenter.process(InputImage.fromBitmap(image, 0)), 60, TimeUnit.SECONDS);
        } catch (MakeException e) {
            throw e;
        } catch (Exception e) {
            throw new MakeException(R.string.fourd_failed, e);
        } finally {
            segmenter.close();
        }

        progress.onStep(R.string.fourd_step_layers);
        Session s = fromSubjects(image, result.getSubjects());
        if (!s.hasForeground()) throw new MakeException(R.string.fourd_no_subject, null);
        try {
            return save(app, s, s.snapshot(), null, FourDWallpapers.DEFAULT_MOTION);
        } catch (IOException e) {
            throw new MakeException(R.string.fourd_failed, e);
        }
    }

    /**
     * The picture's subjects alone on a transparent background, trimmed to them: a "cutout" sticker
     * for the motion editor. Blocking, call off the main thread.
     */
    public static Bitmap cutout(Context context, File source) throws MakeException {
        Context app = context.getApplicationContext();
        Bitmap decoded = ImagePickerHelper.decodeSampled(source, MAX_DIM);
        if (decoded == null) throw new MakeException(R.string.pick_failed, null);
        Bitmap image = fit(decoded);
        SubjectSegmenter segmenter = SubjectSegmentation.getClient(new SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build());
        Bitmap fg;
        try {
            ensureModule(app, segmenter, step -> { });
            fg = Tasks.await(segmenter.process(InputImage.fromBitmap(image, 0)), 60, TimeUnit.SECONDS)
                    .getForegroundBitmap();
        } catch (MakeException e) {
            throw e;
        } catch (Exception e) {
            throw new MakeException(R.string.fourd_failed, e);
        } finally {
            segmenter.close();
            image.recycle();
        }
        if (fg == null) throw new MakeException(R.string.fourd_no_subject, null);
        // Trim the empty margin so the sticker's handles hug the subject.
        int w = fg.getWidth(), h = fg.getHeight();
        int[] px = new int[w * h];
        fg.getPixels(px, 0, w, 0, 0, w, h);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) < 16) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        if (right < left) throw new MakeException(R.string.fourd_no_subject, null);
        Bitmap trimmed = Bitmap.createBitmap(fg, left, top, right - left + 1, bottom - top + 1);
        if (trimmed != fg) fg.recycle();
        return trimmed;
    }

    /** Scales so the longest side is at most {@link #MAX_DIM}. */
    private static Bitmap fit(Bitmap b) {
        int max = Math.max(b.getWidth(), b.getHeight());
        if (max <= MAX_DIM && b.getConfig() == Bitmap.Config.ARGB_8888) return b;
        float s = Math.min(1f, (float) MAX_DIM / max);
        Bitmap scaled = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
        if (scaled.getConfig() != Bitmap.Config.ARGB_8888) scaled = scaled.copy(Bitmap.Config.ARGB_8888, false);
        if (scaled != b) b.recycle();
        return scaled;
    }

    /**
     * The segmentation model is an optional Play services module; it is normally fetched at
     * install time (see the manifest), but may still need downloading the first time.
     */
    private static void ensureModule(Context app, SubjectSegmenter segmenter, Progress progress) throws Exception {
        ModuleInstallClient client = ModuleInstall.getClient(app);
        ModuleAvailabilityResponse available = Tasks.await(client.areModulesAvailable(segmenter), 30, TimeUnit.SECONDS);
        if (available.areModulesAvailable()) return;
        progress.onStep(R.string.fourd_step_download);
        CountDownLatch done = new CountDownLatch(1);
        int[] state = {ModuleInstallStatusUpdate.InstallState.STATE_UNKNOWN};
        InstallStatusListener listener = update -> {
            int s = update.getInstallState();
            if (s == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED
                    || s == ModuleInstallStatusUpdate.InstallState.STATE_FAILED
                    || s == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED) {
                state[0] = s;
                done.countDown();
            }
        };
        ModuleInstallRequest request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build();
        try {
            if (Tasks.await(client.installModules(request), 30, TimeUnit.SECONDS).areModulesAlreadyInstalled()) return;
            if (!done.await(3, TimeUnit.MINUTES)
                    || state[0] != ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED) {
                throw new MakeException(R.string.fourd_download_failed, null);
            }
        } finally {
            client.unregisterListener(listener);
        }
    }

    /** Biggest subject on the near layer, the others on the mid layer. */
    private static Session fromSubjects(Bitmap image, List<Subject> found) {
        int w = image.getWidth(), h = image.getHeight(), n = w * h;
        List<Subject> subjects = new ArrayList<>();
        List<Double> areas = new ArrayList<>();
        for (Subject s : found) {
            double a = area(s);
            if (a < MIN_SUBJECT * n) continue;
            int at = 0;
            while (at < areas.size() && areas.get(at) >= a) at++;
            subjects.add(at, s);
            areas.add(at, a);
        }
        float[] best = new float[n];
        byte[] label = new byte[n], near = new byte[n], mid = new byte[n], conf = new byte[n];
        for (int k = 0; k < subjects.size() && k < 255; k++) {
            Subject s = subjects.get(k);
            FloatBuffer m = s.getConfidenceMask();
            if (m == null) continue;
            m.rewind();
            int sx = s.getStartX(), sy = s.getStartY(), sw = s.getWidth(), sh = s.getHeight();
            for (int y = 0; y < sh; y++) {
                int iy = sy + y;
                for (int x = 0; x < sw; x++) {
                    float c = m.get();
                    int ix = sx + x;
                    if (iy < 0 || iy >= h || ix < 0 || ix >= w) continue;
                    int i = iy * w + ix;
                    if (c <= best[i]) continue;
                    best[i] = c;
                    label[i] = c > 0.3f ? (byte) (k + 1) : 0;
                    byte a = (byte) Math.round(255 * firm(c));
                    conf[i] = a;
                    near[i] = k == 0 ? a : 0;
                    mid[i] = k == 0 ? 0 : a;
                }
            }
        }
        return new Session(image, near, mid, conf, label);
    }

    private static double area(Subject s) {
        FloatBuffer m = s.getConfidenceMask();
        if (m == null) return 0;
        double sum = 0;
        m.rewind();
        while (m.hasRemaining()) sum += m.get();
        return sum;
    }

    /** Firms up the model's soft confidence so edges aren't ghostly. */
    private static float firm(float c) {
        float a = (c - 0.15f) / 0.6f;
        if (a <= 0f) return 0f;
        if (a >= 1f) return 1f;
        return a * a * (3f - 2f * a);
    }

    /** Loads a user 4D image for editing. Blocking. */
    public static Session load(Context context, String imagePath) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inScaled = false;
        Bitmap image = BitmapFactory.decodeFile(imagePath, opts);
        if (image == null) throw new IOException("can't read " + imagePath);
        if (!image.isMutable() && image.getConfig() != Bitmap.Config.ARGB_8888) {
            image = image.copy(Bitmap.Config.ARGB_8888, false);
        }
        int w = image.getWidth(), h = image.getHeight(), n = w * h;
        byte[] near = new byte[n], mid = new byte[n], conf = new byte[n], label = new byte[n];
        String base = imagePath.substring(0, imagePath.length() - 4);
        Bitmap mask = decodeExact(base + "_mask.png", w, h);
        Bitmap subj = decodeExact(base + "_subj.png", w, h);
        int[] p = new int[n];
        if (mask != null) {
            mask.getPixels(p, 0, w, 0, 0, w, h);
            for (int i = 0; i < n; i++) {
                near[i] = (byte) (p[i] >> 16);
                mid[i] = (byte) (p[i] >> 8);
                label[i] = (byte) p[i];
            }
            mask.recycle();
        } else {
            // Made before masks were kept: take them from the layers' transparency.
            String[] layers = FourDWallpapers.layersFor(imagePath);
            Bitmap l1 = decodeExact(layers[1], w, h), l2 = decodeExact(layers[2], w, h);
            if (l2 != null) {
                l2.getPixels(p, 0, w, 0, 0, w, h);
                for (int i = 0; i < n; i++) near[i] = (byte) (p[i] >>> 24);
                l2.recycle();
            }
            if (l1 != null) {
                l1.getPixels(p, 0, w, 0, 0, w, h);
                for (int i = 0; i < n; i++) mid[i] = (byte) (p[i] >>> 24);
                l1.recycle();
            }
        }
        if (subj != null) {
            subj.getPixels(p, 0, w, 0, 0, w, h);
            for (int i = 0; i < n; i++) conf[i] = (byte) p[i];
            subj.recycle();
        } else {
            for (int i = 0; i < n; i++) conf[i] = (byte) Math.max(near[i] & 0xFF, mid[i] & 0xFF);
        }
        return new Session(image, near, mid, conf, label);
    }

    @Nullable
    private static Bitmap decodeExact(String path, int w, int h) {
        if (!new File(path).exists()) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inScaled = false;
        opts.inPremultiplied = false;
        Bitmap b = BitmapFactory.decodeFile(path, opts);
        if (b == null) return null;
        if (b.getWidth() != w || b.getHeight() != h) {
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    /**
     * The far, mid and near layers for a snapshot of the session's masks ({near, mid}, see
     * {@link Session#snapshot}). Blocking (a few hundred ms).
     */
    public static Bitmap[] buildLayers(Session s, byte[][] masks) {
        byte[] near = masks[0], mid = masks[1];
        int w = s.w, h = s.h, n = w * h;
        // The far layer must not show the cut-outs: paint out what's under them, plus a thin rim
        // for their soft edges. The layers only slide, so a cut-out moving aside uncovers its own
        // hole and nothing more; a wider band would just leave a visible smear around it.
        boolean[] hole = new boolean[n];
        for (int i = 0; i < n; i++) hole[i] = Math.max(near[i] & 0xFF, mid[i] & 0xFF) > HOLE_THRESHOLD;
        hole = dilate(hole, w, h, Math.max(3, Math.round(Math.max(w, h) * 0.006f)));
        int[] far = fill(s.px, hole, w, h);
        return new Bitmap[]{
                Bitmap.createBitmap(far, w, h, Bitmap.Config.ARGB_8888),
                cutout(s.px, mid, w, h),
                cutout(s.px, near, w, h)};
    }

    /**
     * Writes the session as a new 4D set and returns its image path. When it replaces
     * {@code oldPath}, the old set is deleted, and if it was the current 4D wallpaper the
     * wallpaper switches to the new one. A new name each time keeps grid and wallpaper caches
     * from showing the old layers.
     */
    public static String save(Context context, Session s, byte[][] masks, @Nullable String oldPath, float[] motion) throws IOException {
        Context app = context.getApplicationContext();
        File dir = FourDWallpapers.userDir(app);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        String base = new File(dir, "u" + System.currentTimeMillis()).getAbsolutePath();
        String image0 = base + ".jpg";
        String[] layers = FourDWallpapers.layersFor(image0);
        Bitmap[] built = buildLayers(s, masks);
        try {
            if (oldPath != null && new File(oldPath).exists()) copy(new File(oldPath), new File(image0));
            else write(s.image, Bitmap.CompressFormat.JPEG, 92, image0);
            int n = s.w * s.h;
            int[] p = new int[n];
            for (int i = 0; i < n; i++) {
                p[i] = 0xFF000000 | ((masks[0][i] & 0xFF) << 16) | ((masks[1][i] & 0xFF) << 8) | (s.label[i] & 0xFF);
            }
            write(Bitmap.createBitmap(p, s.w, s.h, Bitmap.Config.ARGB_8888), Bitmap.CompressFormat.PNG, 100, base + "_mask.png");
            for (int i = 0; i < n; i++) {
                int c = s.conf[i] & 0xFF;
                p[i] = 0xFF000000 | (c << 16) | (c << 8) | c;
            }
            write(Bitmap.createBitmap(p, s.w, s.h, Bitmap.Config.ARGB_8888), Bitmap.CompressFormat.PNG, 100, base + "_subj.png");
            try (PrintWriter out = new PrintWriter(base + FourDWallpapers.MOTION_SUFFIX, "UTF-8")) {
                out.printf(Locale.US, "%.3f %.3f%n", motion[0], motion[1]);
            }
            write(built[0], Bitmap.CompressFormat.JPEG, 90, layers[0]);
            write(built[1], webp(), 88, layers[1]);
            // Near last: the set is only listed once it exists.
            write(built[2], webp(), 90, layers[2]);
        } catch (IOException e) {
            FourDWallpapers.delete(app, image0);
            throw e;
        } finally {
            for (Bitmap b : built) b.recycle();
        }
        if (oldPath != null) {
            android.content.SharedPreferences prefs = EffectConfig.prefs(app);
            boolean wasCurrent = oldPath.equals(prefs.getString(FourDWallpapers.KEY_CURRENT, null));
            FourDWallpapers.delete(app, oldPath);
            if (wasCurrent) {
                prefs.edit().putString(FourDWallpapers.KEY_CURRENT, image0).apply();
                FourDWallpaperService.requestReload(app);
            }
        }
        return image0;
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    /** The picture with a mask as its transparency. */
    private static Bitmap cutout(int[] px, byte[] alpha, int w, int h) {
        int[] out = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int a = alpha[i] & 0xFF;
            if (a == 0) continue;
            out[i] = (a << 24) | (px[i] & 0xFFFFFF);
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Grows {@code mask} by {@code r} pixels (chamfer distance). */
    static boolean[] dilate(boolean[] mask, int w, int h, int r) {
        int inf = Integer.MAX_VALUE / 4;
        int[] d = new int[mask.length];
        for (int i = 0; i < d.length; i++) d[i] = mask[i] ? 0 : inf;
        // 3-4 chamfer: straight steps cost 3, diagonal 4.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, v = d[i];
                if (x > 0) v = Math.min(v, d[i - 1] + 3);
                if (y > 0) {
                    v = Math.min(v, d[i - w] + 3);
                    if (x > 0) v = Math.min(v, d[i - w - 1] + 4);
                    if (x < w - 1) v = Math.min(v, d[i - w + 1] + 4);
                }
                d[i] = v;
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x, v = d[i];
                if (x < w - 1) v = Math.min(v, d[i + 1] + 3);
                if (y < h - 1) {
                    v = Math.min(v, d[i + w] + 3);
                    if (x < w - 1) v = Math.min(v, d[i + w + 1] + 4);
                    if (x > 0) v = Math.min(v, d[i + w - 1] + 4);
                }
                d[i] = v;
            }
        }
        boolean[] out = new boolean[mask.length];
        int lim = r * 3;
        for (int i = 0; i < d.length; i++) out[i] = d[i] <= lim;
        return out;
    }

    /**
     * Paints the hole from its surroundings with a push-pull pyramid: known colours are averaged
     * down to coarse levels, then pulled back up into the unknown pixels, giving a smooth fill.
     */
    private static int[] fill(int[] px, boolean[] hole, int w, int h) {
        int levels = 1;
        for (int s = Math.max(w, h); s > 1; s = (s + 1) / 2) levels++;
        float[][] r = new float[levels][], g = new float[levels][], b = new float[levels][], wt = new float[levels][];
        int[] lw = new int[levels], lh = new int[levels];
        lw[0] = w;
        lh[0] = h;
        r[0] = new float[w * h];
        g[0] = new float[w * h];
        b[0] = new float[w * h];
        wt[0] = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            if (hole[i]) continue;
            r[0][i] = (px[i] >> 16) & 0xFF;
            g[0][i] = (px[i] >> 8) & 0xFF;
            b[0][i] = px[i] & 0xFF;
            wt[0][i] = 1f;
        }
        // Push: each coarse pixel is the weighted mean of its 2 x 2 children.
        for (int l = 1; l < levels; l++) {
            int pw = lw[l - 1], ph = lh[l - 1], cw = (pw + 1) / 2, ch = (ph + 1) / 2;
            lw[l] = cw;
            lh[l] = ch;
            r[l] = new float[cw * ch];
            g[l] = new float[cw * ch];
            b[l] = new float[cw * ch];
            wt[l] = new float[cw * ch];
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    float sr = 0, sg = 0, sb = 0, sw = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        int py = 2 * y + dy;
                        if (py >= ph) continue;
                        for (int dx = 0; dx < 2; dx++) {
                            int pxx = 2 * x + dx;
                            if (pxx >= pw) continue;
                            int j = py * pw + pxx;
                            float k = wt[l - 1][j];
                            sr += r[l - 1][j] * k;
                            sg += g[l - 1][j] * k;
                            sb += b[l - 1][j] * k;
                            sw += k;
                        }
                    }
                    int i = y * cw + x;
                    if (sw > 0) {
                        r[l][i] = sr / sw;
                        g[l][i] = sg / sw;
                        b[l][i] = sb / sw;
                    }
                    wt[l][i] = Math.min(1f, sw);
                }
            }
        }
        // Pull: blend each level's gaps with the (bilinearly sampled) coarser level.
        for (int l = levels - 2; l >= 0; l--) {
            int fw = lw[l], fh = lh[l], cw = lw[l + 1], ch = lh[l + 1];
            for (int y = 0; y < fh; y++) {
                float cy = Math.max(0f, Math.min(ch - 1f, (y - 0.5f) / 2f));
                int y0 = (int) cy, y1 = Math.min(ch - 1, y0 + 1);
                float ty = cy - y0;
                for (int x = 0; x < fw; x++) {
                    int i = y * fw + x;
                    float k = wt[l][i];
                    if (k >= 1f) continue;
                    float cx = Math.max(0f, Math.min(cw - 1f, (x - 0.5f) / 2f));
                    int x0 = (int) cx, x1 = Math.min(cw - 1, x0 + 1);
                    float tx = cx - x0;
                    int a = y0 * cw + x0, bb = y0 * cw + x1, c = y1 * cw + x0, d = y1 * cw + x1;
                    float w00 = (1 - tx) * (1 - ty), w10 = tx * (1 - ty), w01 = (1 - tx) * ty, w11 = tx * ty;
                    float ur = r[l + 1][a] * w00 + r[l + 1][bb] * w10 + r[l + 1][c] * w01 + r[l + 1][d] * w11;
                    float ug = g[l + 1][a] * w00 + g[l + 1][bb] * w10 + g[l + 1][c] * w01 + g[l + 1][d] * w11;
                    float ub = b[l + 1][a] * w00 + b[l + 1][bb] * w10 + b[l + 1][c] * w01 + b[l + 1][d] * w11;
                    r[l][i] = r[l][i] * k + ur * (1 - k);
                    g[l][i] = g[l][i] * k + ug * (1 - k);
                    b[l][i] = b[l][i] * k + ub * (1 - k);
                    wt[l][i] = 1f;
                }
            }
        }
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            out[i] = hole[i]
                    ? 0xFF000000 | (clamp(r[0][i]) << 16) | (clamp(g[0][i]) << 8) | clamp(b[0][i])
                    : px[i] | 0xFF000000;
        }
        return out;
    }

    private static int clamp(float v) {
        return v <= 0 ? 0 : v >= 255 ? 255 : Math.round(v);
    }

    @SuppressWarnings("deprecation")
    private static Bitmap.CompressFormat webp() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
    }

    private static void write(Bitmap b, Bitmap.CompressFormat format, int quality, String path) throws IOException {
        try (OutputStream out = new FileOutputStream(path)) {
            if (!b.compress(format, quality, out)) throw new IOException("compress failed: " + path);
        }
    }
}
