package com.MrBRos.akhu;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import androidx.annotation.WorkerThread;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * On-device nudity check run before anything is posted. Uses the bundled MobileNet classifier
 * (assets/nsfw, classes drawings/hentai/neutral/porn/sexy); a picture is blocked when the
 * explicit classes (porn + hentai) score high. "Sexy" alone never blocks: bras, underwear and
 * swimwear are allowed. Videos are checked frame by frame across the clip.
 */
final class NsfwDetector {

    private static final String MODEL = "nsfw/nsfw_mobilenet_v2_224.tflite";
    private static final int SIZE = 224;
    private static final int HENTAI = 1, PORN = 3, SEXY = 4;
    private static final float EXPLICIT_LIMIT = 0.5f;
    /** Tiles see less context and score safe photos higher, so they need a clearer signal. */
    private static final float TILE_LIMIT = 0.7f;
    private static final int VIDEO_FRAMES = 12;

    private static Interpreter interpreter;

    private NsfwDetector() {
    }

    /** Thrown when picked media is turned away for looking like adult content. */
    static final class Rejected extends IOException {
        Rejected() {
            super("NSFW");
        }
    }

    /** True when the image file (a GIF's first frame) looks like nudity or porn. */
    @WorkerThread
    static boolean isImageNsfw(Context context, File image) throws IOException {
        Bitmap bitmap = ImagePickerHelper.decodeSampled(image, SIZE * 4);
        if (bitmap == null) throw new IOException("Cannot decode image");
        try {
            return isNsfw(context, bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** True when any of {@link #VIDEO_FRAMES} frames spread across the video looks like nudity or porn. */
    @WorkerThread
    static boolean isVideoNsfw(Context context, File video) throws IOException {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(video.getAbsolutePath());
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationUs = (d == null ? 0 : Long.parseLong(d)) * 1000;
            int checked = 0;
            for (int i = 0; i < VIDEO_FRAMES; i++) {
                long t = durationUs * (2L * i + 1) / (2L * VIDEO_FRAMES);
                Bitmap frame = mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, SIZE * 4, SIZE * 4);
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

    /** True when any of {@link #VIDEO_FRAMES} frames spread across the GIF looks like nudity or porn. */
    @WorkerThread
    @SuppressWarnings("deprecation") // Movie is the only stock API that renders a GIF at a given time.
    static boolean isGifNsfw(Context context, File gif) throws IOException {
        android.graphics.Movie movie = android.graphics.Movie.decodeFile(gif.getAbsolutePath());
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) throw new IOException("Cannot read GIF");
        int frames = movie.duration() > 0 ? VIDEO_FRAMES : 1;
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

    /**
     * True when the picture looks like nudity or porn. Squashing a tall phone photo into the
     * model's square distorts it, so a non-square picture is checked whole (letterboxed, not
     * stretched) and as square crops from the start, middle and end of its long side. Every
     * picture is also checked in overlapping tiles 3/4 of its short side across, so a photo
     * inside a screenshot or web page is seen on its own. Any one flagged view blocks it.
     */
    @WorkerThread
    static synchronized boolean isNsfw(Context context, Bitmap picture) throws IOException {
        if (interpreter == null) interpreter = new Interpreter(loadModel(context));
        int w = picture.getWidth(), h = picture.getHeight();
        int side = Math.min(w, h), extra = Math.max(w, h) - side;
        boolean tall = h > w;
        java.util.List<Bitmap> views = new java.util.ArrayList<>();
        if (Math.max(w, h) < side * 1.25f) {
            views.add(Bitmap.createScaledBitmap(picture, SIZE, SIZE, true));
        } else {
            views.add(letterbox(picture));
            for (int o : new int[]{0, extra / 2, extra}) views.add(crop(picture, tall ? 0 : o, tall ? o : 0, side));
        }
        int wholeViews = views.size();
        // Tiles step half a tile at a time, the last one flush with the far edge.
        int tile = side * 3 / 4;
        int nx = (int) Math.ceil((w - tile) / (tile / 2f)) + 1, ny = (int) Math.ceil((h - tile) / (tile / 2f)) + 1;
        for (int yi = 0; yi < ny; yi++) {
            for (int xi = 0; xi < nx; xi++) {
                views.add(crop(picture, nx == 1 ? 0 : xi * (w - tile) / (nx - 1), ny == 1 ? 0 : yi * (h - tile) / (ny - 1), tile));
            }
        }
        boolean flagged = false;
        boolean log = (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        for (int i = 0; i < views.size(); i++) {
            float[] s = classify(views.get(i));
            if (views.get(i) != picture) views.get(i).recycle();
            boolean hit = s[PORN] + s[HENTAI] >= (i < wholeViews ? EXPLICIT_LIMIT : TILE_LIMIT);
            if (log) {
                android.util.Log.i("NsfwDetector", String.format(java.util.Locale.US,
                        "%dx%d view %d: drawings %.2f hentai %.2f neutral %.2f porn %.2f sexy %.2f%s",
                        w, h, i, s[0], s[HENTAI], s[2], s[PORN], s[SEXY], hit ? " FLAGGED" : ""));
            }
            flagged |= hit;
        }
        return flagged;
    }

    /** The whole picture fitted into the model's square on black, keeping its shape. */
    private static Bitmap letterbox(Bitmap picture) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        out.eraseColor(android.graphics.Color.BLACK);
        float scale = SIZE / (float) Math.max(picture.getWidth(), picture.getHeight());
        float dw = picture.getWidth() * scale, dh = picture.getHeight() * scale;
        android.graphics.RectF dst = new android.graphics.RectF((SIZE - dw) / 2, (SIZE - dh) / 2, (SIZE + dw) / 2, (SIZE + dh) / 2);
        new android.graphics.Canvas(out).drawBitmap(picture, null, dst, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** The side x side square at (x, y), scaled to the model's size. */
    private static Bitmap crop(Bitmap picture, int x, int y, int side) {
        Bitmap square = Bitmap.createBitmap(picture, x, y, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true);
        if (square != scaled && square != picture) square.recycle();
        return scaled;
    }

    /** The model's five class scores for a SIZE x SIZE picture. */
    private static float[] classify(Bitmap view) {
        int[] pixels = new int[SIZE * SIZE];
        view.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        ByteBuffer input = ByteBuffer.allocateDirect(4 * 3 * SIZE * SIZE).order(ByteOrder.nativeOrder());
        for (int p : pixels) {
            input.putFloat(((p >> 16) & 0xFF) / 255f);
            input.putFloat(((p >> 8) & 0xFF) / 255f);
            input.putFloat((p & 0xFF) / 255f);
        }
        input.rewind();
        float[][] out = new float[1][5];
        interpreter.run(input, out);
        return out[0];
    }

    private static MappedByteBuffer loadModel(Context context) throws IOException {
        try (AssetFileDescriptor fd = context.getAssets().openFd(MODEL);
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            return in.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }
}
