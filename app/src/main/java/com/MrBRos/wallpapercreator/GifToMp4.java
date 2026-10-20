package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.bumptech.glide.Glide;
import com.bumptech.glide.gifdecoder.GifDecoder;
import com.bumptech.glide.gifdecoder.GifHeader;
import com.bumptech.glide.gifdecoder.GifHeaderParser;
import com.bumptech.glide.gifdecoder.StandardGifDecoder;
import com.bumptech.glide.load.resource.gif.GifBitmapProvider;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/**
 * Turns a GIF into a silent H.264 MP4 so the video editor, the motion wallpaper and cloud posts
 * only ever deal with one format. Frames come from Glide's GIF decoder and are fed to the encoder as YUV.
 */
final class GifToMp4 {

    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int MAX_LONG_SIDE = 1080;
    // Pixel-art GIFs are often tiny; scale them up so encoders accept them and they aren't a blur on screen.
    private static final int MIN_SHORT_SIDE = 480;
    private static final long TIMEOUT_US = 10_000;

    private GifToMp4() {
    }

    static void convert(Context context, File gif, File out) throws IOException {
        byte[] data = Files.readAllBytes(gif.toPath());
        GifHeader header = new GifHeaderParser().setData(data).parseHeader();
        if (header.getStatus() != GifDecoder.STATUS_OK || header.getNumFrames() <= 0) {
            throw new IOException("Not a readable GIF");
        }
        StandardGifDecoder decoder = new StandardGifDecoder(
                new GifBitmapProvider(Glide.get(context).getBitmapPool()));
        decoder.setData(header, data);

        int srcW = header.getWidth();
        int srcH = header.getHeight();
        float scale = Math.min(MAX_LONG_SIDE / (float) Math.max(srcW, srcH),
                Math.max(1f, MIN_SHORT_SIDE / (float) Math.min(srcW, srcH)));
        int w = Math.max(16, Math.round(srcW * scale / 16f) * 16);
        int h = Math.max(16, Math.round(srcH * scale / 16f) * 16);
        boolean smooth = scale < 1f;

        MediaFormat format = MediaFormat.createVideoFormat(MIME, w, h);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, w * h * 6);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        MediaMuxer muxer = null;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Drain drain = new Drain(codec, muxer);
            int[] argb = new int[w * h];
            long ptsUs = 0;
            for (int i = 0; i < decoder.getFrameCount(); i++) {
                decoder.advance();
                int delayMs = decoder.getNextDelay();
                // Browsers treat 0-10 ms delays as "as fast as possible" and show them at 100 ms.
                if (delayMs < 20) delayMs = 100;
                Bitmap frame = decoder.getNextFrame();
                if (frame == null) continue;
                Bitmap scaled = Bitmap.createScaledBitmap(frame, w, h, smooth);
                scaled.getPixels(argb, 0, w, 0, 0, w, h);
                if (scaled != frame) scaled.recycle();

                int index;
                while ((index = codec.dequeueInputBuffer(TIMEOUT_US)) < 0) drain.run(false);
                Image image = codec.getInputImage(index);
                if (image == null) throw new IOException("Encoder gave no input image");
                writeYuv(image, argb, w, h);
                codec.queueInputBuffer(index, 0, w * h * 3 / 2, ptsUs, 0);
                ptsUs += delayMs * 1000L;
                drain.run(false);
            }
            int index;
            while ((index = codec.dequeueInputBuffer(TIMEOUT_US)) < 0) drain.run(false);
            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain.run(true);
            if (!drain.started) throw new IOException("Encoder produced nothing");
        } finally {
            decoder.clear();
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    /** Writes ARGB pixels into the encoder's YUV 4:2:0 planes (BT.601, limited range), honouring strides. */
    private static void writeYuv(Image image, int[] argb, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride();
        int uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPix = planes[2].getPixelStride();
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int c = argb[row * w + col];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                y.put(row * yRow + col * yPix, (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
                if ((row & 1) == 0 && (col & 1) == 0) {
                    int cr = row >> 1;
                    int cc = col >> 1;
                    u.put(cr * uRow + cc * uPix, (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                    v.put(cr * vRow + cc * vPix, (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
                }
            }
        }
    }

    /** Moves finished encoder output into the muxer; with {@code untilEnd} it waits for end of stream. */
    private static final class Drain {
        private final MediaCodec codec;
        private final MediaMuxer muxer;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int track = -1;
        boolean started;

        Drain(MediaCodec codec, MediaMuxer muxer) {
            this.codec = codec;
            this.muxer = muxer;
        }

        void run(boolean untilEnd) {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEnd) return;
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    started = true;
                } else if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && started && buffer != null) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                }
            }
        }
    }
}
