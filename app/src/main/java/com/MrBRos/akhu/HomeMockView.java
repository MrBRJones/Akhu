package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pretend phone screen showing a {@link CustomTheme}: its wallpaper with either the home screen
 * (themed icons in a 4-column grid) or the lock screen (big clock). Icons are tappable when an
 * {@link OnIconClickListener} is set.
 */
public class HomeMockView extends View {

    public interface OnIconClickListener {
        void onIconClick(int index);
    }

    private static final int COLUMNS = 4;
    private static final int MAX_ICONS = 24;
    private static final LruCache<String, Bitmap> WALLPAPERS = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final List<RectF> iconRects = new ArrayList<>();

    @Nullable
    private CustomTheme theme;
    @Nullable
    private Bitmap wallpaper;
    private Bitmap[] icons = new Bitmap[0];
    private boolean lock;
    private int generation;
    private int wallpaperMax = 1080;
    @Nullable
    private OnIconClickListener iconClick;

    public HomeMockView(Context context) {
        this(context, null);
    }

    public HomeMockView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        labelPaint.setColor(0xFFFFFFFF);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setShadowLayer(4f, 0f, 1f, 0xDD000000); // readable on white wallpapers too
        clockPaint.setColor(0xFFFFFFFF);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setShadowLayer(10f, 0f, 2f, 0xAA000000);
    }

    /** Lower for small grid cards so they decode less. */
    public void setWallpaperMax(int px) {
        wallpaperMax = px;
    }

    public void setOnIconClickListener(@Nullable OnIconClickListener l) {
        iconClick = l;
    }

    public void setLock(boolean lock) {
        this.lock = lock;
        invalidate();
    }

    public boolean isLock() {
        return lock;
    }

    /** Shows {@code theme}; it is copied, so later edits need another call. */
    public void setTheme(@Nullable CustomTheme theme) {
        this.theme = theme == null ? null : theme.copy();
        reload();
    }

    private void reload() {
        int gen = ++generation;
        CustomTheme t = theme;
        if (t == null) {
            wallpaper = null;
            icons = new Bitmap[0];
            invalidate();
            return;
        }
        File wpFile = ThemeStore.wallpaper(getContext(), t);
        String wpKey = wpFile.getAbsolutePath() + '|' + wpFile.lastModified() + '|' + wallpaperMax;
        Bitmap cachedWp = WALLPAPERS.get(wpKey);
        if (cachedWp != null) wallpaper = cachedWp;
        if (icons.length != Math.min(MAX_ICONS, t.apps.size())) icons = new Bitmap[Math.min(MAX_ICONS, t.apps.size())];
        invalidate();
        Context app = getContext().getApplicationContext();
        int iconPx = Math.max(48, Math.round(iconSize(getWidth() > 0 ? getWidth() : 600)));
        int max = wallpaperMax;
        LOADER.execute(() -> {
            Bitmap wp = WALLPAPERS.get(wpKey);
            if (wp == null) {
                wp = ImagePickerHelper.decodeSampled(wpFile, max);
                if (wp != null) WALLPAPERS.put(wpKey, wp);
            }
            Bitmap[] ic = new Bitmap[Math.min(MAX_ICONS, t.apps.size())];
            for (int i = 0; i < ic.length; i++) ic[i] = ThemeIcons.render(app, t, t.apps.get(i), iconPx);
            Bitmap finalWp = wp;
            MAIN.post(() -> {
                if (gen != generation) return;
                wallpaper = finalWp;
                icons = ic;
                invalidate();
            });
        });
    }

    private static float iconSize(int width) {
        return width * 0.15f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Icons are rendered for the view width; re-render when it first becomes known.
        if (theme != null && oldw == 0) reload();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        canvas.drawColor(0xFF1C1F26);
        Bitmap wp = wallpaper;
        if (wp != null && !wp.isRecycled()) {
            float s = Math.max((float) w / wp.getWidth(), (float) h / wp.getHeight());
            matrix.setScale(s, s);
            matrix.postTranslate((w - wp.getWidth() * s) / 2f, (h - wp.getHeight() * s) / 2f);
            canvas.drawBitmap(wp, matrix, bitmapPaint);
        }
        Date now = new Date();
        labelPaint.setTextSize(w * 0.032f);
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(new SimpleDateFormat("H:mm", Locale.getDefault()).format(now), w * 0.06f, w * 0.07f, labelPaint);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.DEFAULT);

        iconRects.clear();
        if (lock) {
            clockPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            clockPaint.setTextSize(w * 0.24f);
            canvas.drawText(new SimpleDateFormat("H:mm", Locale.getDefault()).format(now), w / 2f, h * 0.25f, clockPaint);
            clockPaint.setTypeface(Typeface.DEFAULT);
            clockPaint.setTextSize(w * 0.045f);
            canvas.drawText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now), w / 2f, h * 0.30f, clockPaint);
            return;
        }
        CustomTheme t = theme;
        if (t == null) return;
        float cell = w / (float) COLUMNS;
        float icon = iconSize(w);
        float rowH = icon + w * 0.1f;
        float top = h * 0.1f;
        labelPaint.setTextSize(w * 0.03f);
        for (int i = 0; i < icons.length; i++) {
            float cx = cell * (i % COLUMNS + 0.5f);
            float y = top + rowH * (i / COLUMNS);
            if (y + rowH > h * 0.97f) break;
            RectF r = new RectF(cx - icon / 2f, y, cx + icon / 2f, y + icon);
            iconRects.add(r);
            Bitmap b = icons[i];
            if (b != null && !b.isRecycled()) canvas.drawBitmap(b, null, r, bitmapPaint);
            String label = t.apps.get(i).label;
            if (label.length() > 10) label = label.substring(0, 9) + "…";
            canvas.drawText(label, cx, r.bottom + w * 0.045f, labelPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (iconClick == null || lock) return super.onTouchEvent(e);
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            for (int i = 0; i < iconRects.size(); i++) {
                RectF r = iconRects.get(i);
                if (r.contains(e.getX(), e.getY() - getWidth() * 0.02f) || r.contains(e.getX(), e.getY())) {
                    iconClick.onIconClick(i);
                    performClick();
                    return true;
                }
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
