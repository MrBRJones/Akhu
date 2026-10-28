package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * On-device nudity check run on picked media and before anything is posted. Uses the bundled
 * NudeNet detector (assets/nsfw), which finds body parts and says whether each is covered or
 * exposed. A picture is blocked for exposed genitals or anus, or an exposed breast that scores
 * higher than a covered one. Exposed buttocks, belly and armpits never block: bras, underwear and
 * swimwear are allowed. Videos are checked about once a second across the clip.
 */
final class NsfwDetector {

    private static final String MODEL = "nsfw/nudenet_320n.onnx";
    private static final int SIZE = 320;
    /** Pictures are scaled down to this long side before checking. */
    private static final int MAX_DIM = 1280;
    // Label indices in the model's output, after the 4 box values.
    private static final int FEMALE_BREAST_EXPOSED = 3, FEMALE_GENITALIA_EXPOSED = 4, ANUS_EXPOSED = 6,
            MALE_GENITALIA_EXPOSED = 14, FEMALE_BREAST_COVERED = 16, LABELS = 18;
    private static final float GENITALIA_LIMIT = 0.45f;
    private static final float BREAST_LIMIT = 0.5f;
    private static final int MIN_VIDEO_FRAMES = 12, MAX_VIDEO_FRAMES = 120;

    private static OrtEnvironment env;
    private static OrtSession session;

    private NsfwDetector() {
    }

    /** Thrown when picked media is turned away for looking like adult content. */
    static final class Rejected extends IOException {
        Rejected() {
            super("NSFW");
        }
    }

    /** True when the image file (a GIF's first frame) shows nudity. */
    @WorkerThread
    static boolean isImageNsfw(Context context, File image) throws IOException {
        Bitmap bitmap = ImagePickerHelper.decodeSampled(image, MAX_DIM);
        if (bitmap == null) throw new IOException("Cannot decode image");
        try {
            return isNsfw(context, bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** True when any frame, taken about once a second across the video, shows nudity. */
    @WorkerThread
    static boolean isVideoNsfw(Context context, File video) throws IOException {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(video.getAbsolutePath());
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationUs = (d == null ? 0 : Long.parseLong(d)) * 1000;
            int frames = frameCount(durationUs / 1000);
            int checked = 0;
            for (int i = 0; i < frames; i++) {
                long t = durationUs * (2L * i + 1) / (2L * frames);
                Bitmap frame = mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, MAX_DIM, MAX_DIM);
                if (frame == null) continue;
                checked++;
                try {
                    if (isNsfw(context, frame)) return true;
                } finally {
                    frame.recycle();
                }
            }
            if (checked == 0) throw new IOException("Cannot read video");
            return false;
        } finally {
            mmr.release();
        }
    }

    /** True when any frame, taken about once a second across the GIF, shows nudity. */
    @WorkerThread
    @SuppressWarnings("deprecation") // Movie is the only stock API that renders a GIF at a given time.
    static boolean isGifNsfw(Context context, File gif) throws IOException {
        android.graphics.Movie movie = android.graphics.Movie.decodeFile(gif.getAbsolutePath());
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) throw new IOException("Cannot read GIF");
        int frames = movie.duration() > 0 ? frameCount(movie.duration()) : 1;
        Bitmap frame = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(frame);
        try {
            for (int i = 0; i < frames; i++) {
                movie.setTime((int) ((long) movie.duration() * (2L * i + 1) / (2L * frames)));
                frame.eraseColor(android.graphics.Color.BLACK);
                movie.draw(canvas, 0, 0);
                if (isNsfw(context, frame)) return true;
            }
            return false;
        } finally {
            frame.recycle();
        }
    }

    /** One frame a second, at least {@link #MIN_VIDEO_FRAMES} and at most {@link #MAX_VIDEO_FRAMES}. */
    private static int frameCount(long durationMs) {
        return (int) Math.max(MIN_VIDEO_FRAMES, Math.min(MAX_VIDEO_FRAMES, durationMs / 1000));
    }

    /**
     * True when the picture shows nudity. It is checked whole, then in overlapping square tiles
     * 3/4 and 1/3 of its short side across, so a small photo inside a screenshot or web page is
     * seen up close. Tiles smaller than the model's input are skipped: blown up, they set off
     * false alarms. Any one flagged view blocks it.
     */
    @WorkerThread
    static synchronized boolean isNsfw(Context context, Bitmap picture) throws IOException {
        if (session == null) {
            env = OrtEnvironment.getEnvironment();
            try {
                session = env.createSession(loadModel(context), new OrtSession.SessionOptions());
            } catch (OrtException e) {
                throw new IOException(e);
            }
        }
        Bitmap scaled = picture;
        int longest = Math.max(picture.getWidth(), picture.getHeight());
        if (longest > MAX_DIM) {
            float k = MAX_DIM / (float) longest;
            scaled = Bitmap.createScaledBitmap(picture, Math.round(picture.getWidth() * k), Math.round(picture.getHeight() * k), true);
        }
        try {
            int w = scaled.getWidth(), h = scaled.getHeight();
            if (isNsfwView(context, scaled, 0, 0, w, h, "whole")) return true;
            for (int tile : new int[]{Math.min(w, h) * 3 / 4, Math.min(w, h) / 3}) {
                if (tile < SIZE) continue;
                // Tiles step half a tile at a time, the last one flush with the far edge.
                int nx = (int) Math.ceil((w - tile) / (tile / 2f)) + 1, ny = (int) Math.ceil((h - tile) / (tile / 2f)) + 1;
                for (int yi = 0; yi < ny; yi++) {
                    for (int xi = 0; xi < nx; xi++) {
                        int x = nx == 1 ? 0 : xi * (w - tile) / (nx - 1), y = ny == 1 ? 0 : yi * (h - tile) / (ny - 1);
                        if (isNsfwView(context, scaled, x, y, tile, tile, "tile " + tile)) return true;
                    }
                }
            }
            return false;
        } finally {
            if (scaled != picture) scaled.recycle();
        }
    }

    /** Runs the detector on one rectangle of the picture and applies the blocking rule. */
    private static boolean isNsfwView(Context context, Bitmap picture, int x, int y, int w, int h, String name) throws IOException {
        float[] s = detect(picture, x, y, w, h);
        float genitalia = Math.max(Math.max(s[FEMALE_GENITALIA_EXPOSED], s[MALE_GENITALIA_EXPOSED]), s[ANUS_EXPOSED]);
        float breast = s[FEMALE_BREAST_EXPOSED];
        boolean hit = genitalia >= GENITALIA_LIMIT || (breast >= BREAST_LIMIT && breast > s[FEMALE_BREAST_COVERED]);
        if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.util.Log.i("NsfwDetector", String.format(java.util.Locale.US,
                    "%dx%d %s @%d,%d: genitalia/anus %.2f breast exposed %.2f covered %.2f%s",
                    picture.getWidth(), picture.getHeight(), name, x, y, genitalia, breast, s[FEMALE_BREAST_COVERED], hit ? " FLAGGED" : ""));
        }
        return hit;
    }

    /**
     * The best score for each label anywhere in the rectangle. Like NudeNet itself, the
     * rectangle is padded bottom/right to a square (black) and scaled to the model's size.
     */
    private static float[] detect(Bitmap picture, int x, int y, int w, int h) throws IOException {
        float k = SIZE / (float) Math.max(w, h);
        Bitmap square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        square.eraseColor(android.graphics.Color.BLACK);
        new android.graphics.Canvas(square).drawBitmap(picture, new android.graphics.Rect(x, y, x + w, y + h),
                new android.graphics.RectF(0, 0, w * k, h * k), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        int[] pixels = new int[SIZE * SIZE];
        square.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        square.recycle();
        // Channels first: all red, then all green, then all blue.
        int plane = SIZE * SIZE;
        FloatBuffer input = FloatBuffer.allocate(3 * plane);
        for (int p = 0; p < plane; p++) {
            int c = pixels[p];
            input.put(p, ((c >> 16) & 0xFF) / 255f);
            input.put(plane + p, ((c >> 8) & 0xFF) / 255f);
            input.put(2 * plane + p, (c & 0xFF) / 255f);
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input, new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Collections.singletonMap(session.getInputNames().iterator().next(), tensor))) {
            float[][] out = ((float[][][]) result.get(0).getValue())[0]; // [4 box values + LABELS][anchors]
            float[] best = new float[LABELS];
            for (int l = 0; l < LABELS; l++) {
                for (float v : out[4 + l]) best[l] = Math.max(best[l], v);
            }
            return best;
        } catch (OrtException e) {
            throw new IOException(e);
        }
    }

    private static byte[] loadModel(Context context) throws IOException {
        try (InputStream in = context.getAssets().open(MODEL)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(13 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
