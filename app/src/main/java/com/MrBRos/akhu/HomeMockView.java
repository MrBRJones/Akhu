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
    /** The widget card last rendered, the key it was rendered for, and the key being rendered. */
    @Nullable
    private Bitmap card;
    @Nullable
    private String cardKey, cardPending;

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
        // Same 12 or 24 hour clock as the phone.
        String hm = android.text.format.DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm";
        labelPaint.setTextSize(w * 0.032f);
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(new SimpleDateFormat(hm, Locale.getDefault()).format(now), w * 0.06f, w * 0.07f, labelPaint);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.DEFAULT);

        iconRects.clear();
        if (lock) {
            clockPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            clockPaint.setTextSize(w * 0.24f);
            canvas.drawText(new SimpleDateFormat(hm, Locale.getDefault()).format(now), w / 2f, h * 0.25f, clockPaint);
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
        if (t.widget != CustomTheme.WIDGET_NONE) top = drawWidget(canvas, t, now, hm, w, top) + w * 0.06f;
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

    /**
     * The theme's clock widget above the icons, laid out like {@link ThemeClockWidgetProvider}'s
     * (its {@link WidgetCard}, then the time and a smaller second line). Returns the y it ends at.
     */
    private float drawWidget(Canvas canvas, CustomTheme t, Date now, String hm, int w, float top) {
        boolean date = t.widget == CustomTheme.WIDGET_DATE;
        float big = w * (date ? 0.12f : 0.2f);
        float small = w * 0.05f;
        if (WidgetCard.has(t)) {
            // A 4x2-ish card across the screen with the text centred in it.
            float textH = big * 0.85f + (t.widget != CustomTheme.WIDGET_TIME ? small * 1.5f : 0f);
            int cw = Math.round(w * 0.9f);
            int ch = Math.round(Math.max(textH + w * 0.12f, cw * 0.4f));
            float left = (w - cw) / 2f;
            Bitmap card = cardFor(t, cw, ch);
            if (card != null && !card.isRecycled()) canvas.drawBitmap(card, left, top, bitmapPaint);
            float pad = (ch - textH) / 2f;
            drawClockText(canvas, t, now, hm, w, top + pad, big, small, date);
            return top + ch;
        }
        return drawClockText(canvas, t, now, hm, w, top, big, small, date);
    }

    /** The card at this size, rendered off the main thread; null (drawn next frame) until it's ready. */
    @Nullable
    private Bitmap cardFor(CustomTheme t, int cw, int ch) {
        String key = t.widgetStyle + "|" + t.widgetShape + "|" + t.widgetBg + "|" + t.widgetPhoto + "|" + cw + "x" + ch;
        if (key.equals(cardKey)) return card;
        if (!key.equals(cardPending)) {
            cardPending = key;
            Context app = getContext().getApplicationContext();
            CustomTheme copy = t.copy();
            LOADER.execute(() -> {
                Bitmap b = WidgetCard.render(app, copy, cw, ch);
                MAIN.post(() -> {
                    if (!key.equals(cardPending)) return;
                    card = b;
                    cardKey = key;
                    invalidate();
                });
            });
        }
        return card;
    }

    private float drawClockText(Canvas canvas, CustomTheme t, Date now, String hm, int w, float top,
                                float big, float small, boolean date) {
        clockPaint.setColor(t.widgetColor);
        Typeface face = t.widgetBold ? Typeface.DEFAULT_BOLD : Typeface.create("sans-serif-light", Typeface.NORMAL);
        clockPaint.setTypeface(face);
        clockPaint.setTextSize(big);
        String first = new SimpleDateFormat(date ? "EEEE" : hm, Locale.getDefault()).format(now);
        float y = top + big * 0.85f;
        canvas.drawText(first, w / 2f, y, clockPaint);
        if (t.widget != CustomTheme.WIDGET_TIME) {
            clockPaint.setTextSize(small);
            String second = new SimpleDateFormat(date ? "MMMM d" : "EEEE, MMMM d", Locale.getDefault()).format(now);
            y += small * 1.5f;
            canvas.drawText(second, w / 2f, y, clockPaint);
        }
        clockPaint.setColor(0xFFFFFFFF);
        clockPaint.setTypeface(Typeface.DEFAULT);
        return y;
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
