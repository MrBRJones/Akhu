package com.MrBRos.akhu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

/** Glide transformation: like centerCrop, but the crop window is centred on a focal point. */
public class FocalCrop extends BitmapTransformation {

    private static final String ID = "com.MrBRos.akhu.FocalCrop";
    private final float fx, fy;

    public FocalCrop(float fx, float fy) {
        this.fx = fx;
        this.fy = fy;
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap src, int outWidth, int outHeight) {
        if (src.getWidth() == outWidth && src.getHeight() == outHeight) return src;
        Rect crop = Focal.cropRect(src.getWidth(), src.getHeight(), outWidth, outHeight, fx, fy);
        Bitmap.Config config = src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888;
        Bitmap out = pool.get(outWidth, outHeight, config);
        out.setHasAlpha(src.hasAlpha());
        new Canvas(out).drawBitmap(src, crop, new RectF(0, 0, outWidth, outHeight),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FocalCrop && ((FocalCrop) o).fx == fx && ((FocalCrop) o).fy == fy;
    }

    @Override
    public int hashCode() {
        return ID.hashCode() * 31 + Float.hashCode(fx) * 17 + Float.hashCode(fy);
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest digest) {
        digest.update(ID.getBytes(CHARSET));
        digest.update(ByteBuffer.allocate(8).putFloat(fx).putFloat(fy).array());
    }
}
