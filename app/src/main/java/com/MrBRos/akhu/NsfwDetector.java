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
 * explicit classes or "sexy" score high. Videos are checked frame by frame across the clip.
 */
final class NsfwDetector {

    private static final String MODEL = "nsfw/nsfw_mobilenet_v2_224.tflite";
    private static final int SIZE = 224;
    private static final int HENTAI = 1, PORN = 3, SEXY = 4;
    private static final float EXPLICIT_LIMIT = 0.4f;
    private static final float SEXY_LIMIT = 0.8f;
    private static final int VIDEO_FRAMES = 12;

    private static Interpreter interpreter;

    private NsfwDetector() {
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
                Bitmap frame = mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, SIZE * 2, SIZE * 2);
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

    /** True when the picture looks like nudity or porn. */
    @WorkerThread
    static synchronized boolean isNsfw(Context context, Bitmap picture) throws IOException {
        if (interpreter == null) interpreter = new Interpreter(loadModel(context));
        Bitmap scaled = Bitmap.createScaledBitmap(picture, SIZE, SIZE, true);
        int[] pixels = new int[SIZE * SIZE];
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        if (scaled != picture) scaled.recycle();
        ByteBuffer input = ByteBuffer.allocateDirect(4 * 3 * SIZE * SIZE).order(ByteOrder.nativeOrder());
        for (int p : pixels) {
            input.putFloat(((p >> 16) & 0xFF) / 255f);
            input.putFloat(((p >> 8) & 0xFF) / 255f);
            input.putFloat((p & 0xFF) / 255f);
        }
        input.rewind();
        float[][] out = new float[1][5];
        interpreter.run(input, out);
        float[] s = out[0];
        return s[PORN] + s[HENTAI] >= EXPLICIT_LIMIT || s[SEXY] >= SEXY_LIMIT;
    }

    private static MappedByteBuffer loadModel(Context context) throws IOException {
        try (AssetFileDescriptor fd = context.getAssets().openFd(MODEL);
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            return in.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }
}
