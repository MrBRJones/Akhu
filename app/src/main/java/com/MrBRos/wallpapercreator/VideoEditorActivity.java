package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.VideoSize;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.SingleColorLut;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.MrBRos.wallpapercreator.databinding.ActivityVideoEditorBinding;
import com.MrBRos.wallpapercreator.databinding.ItemFilterBinding;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Motion editor opened by Create in MOTION mode: trim, speed, colour filter and sound for a video or GIF
 * (GIFs are turned into MP4 first). The preview applies edits live; Download / Set Motion render them with Media3.
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoEditorActivity extends AppCompatActivity {

    private static final String EXTRA_PATH = "path";
    private static final String EXTRA_GIF = "gif";
    private static final float[] SPEEDS = {0.5f, 1f, 1.5f, 2f};
    private static final int LUT_SIZE = 33;
    private static final float MAX_ZOOM = 4f;
    private static final int MAX_OUTPUT_SIDE = 1920;

    static Intent intent(Context context, File file, boolean isGif) {
        return new Intent(context, VideoEditorActivity.class)
                .putExtra(EXTRA_PATH, file.getAbsolutePath())
                .putExtra(EXTRA_GIF, isGif);
    }

    private ActivityVideoEditorBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private Transformer transformer;
    private File source;
    private File workDir;
    private long durationMs;
    private long startMs;
    private long endMs;
    private float speed = 1f;
    private int filter = Filters.ORIGINAL;
    // Wallpapers play muted, so sound is off unless asked for (it only matters for Download).
    private boolean sound;
    // Framing: the video size as shown (rotation applied), the crop shape (width/height, 0 = the video's own),
    // zoom, and pan as a fraction (-1..1) of how far the zoomed video can move each way.
    private int videoW;
    private int videoH;
    private float aspect;
    private float zoom = 1f;
    private float panX;
    private float panY;
    private int cropW;
    private int cropH;
    private final ItemFilterBinding[] filterItems = new ItemFilterBinding[Filters.NAMES.length];

    /** Keeps the preview inside the trimmed range. */
    private final Runnable loopCheck = new Runnable() {
        @Override
        public void run() {
            if (player != null && durationMs > 0) {
                long pos = player.getCurrentPosition();
                if (pos >= endMs || pos < startMs - 150) player.seekTo(startMs);
            }
            handler.postDelayed(this, 30);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVideoEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        workDir = new File(getCacheDir(), "motion_edit");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.toolTabs.check(R.id.tabTrim);
        binding.toolTabs.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) showPanel(id);
        });
        binding.speedGroup.check(R.id.speedNormal);
        binding.speedGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            int[] ids = {R.id.speedHalf, R.id.speedNormal, R.id.speedFast, R.id.speedDouble};
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) speed = SPEEDS[i];
            if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
        });
        binding.cropGroup.check(R.id.cropOriginal);
        binding.cropGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            aspect = aspectFor(id);
            panX = 0;
            panY = 0;
            layoutPreview();
        });
        binding.zoomSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) setZoom(value);
        });
        binding.stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::layoutPreview);
        });
        setUpStageGestures();
        updateZoomLabel();
        binding.switchSound.setChecked(sound);
        binding.switchSound.setOnCheckedChangeListener((b, checked) -> {
            sound = checked;
            if (player != null) player.setVolume(sound ? 1f : 0f);
        });
        binding.btnDownload.setOnClickListener(v -> export(out -> io.execute(() -> {
            int msg = R.string.save_failed;
            try {
                ImageExporter.saveVideoToGallery(getApplicationContext(), out);
                msg = R.string.video_saved;
            } catch (IOException ignored) {
            }
            int finalMsg = msg;
            runOnUiThread(() -> toast(finalMsg));
        })));
        binding.btnSetMotion.setOnClickListener(v -> export(out -> MotionWallpapers.applyFile(this, out, null)));
        setBusy(true);

        File in = new File(getIntent().getStringExtra(EXTRA_PATH));
        if (!getIntent().getBooleanExtra(EXTRA_GIF, false)) {
            load(in);
            return;
        }
        // A GIF has no sound, so that tool would do nothing.
        binding.tabSound.setVisibility(View.GONE);
        binding.loadingText.setText(R.string.converting_gif);
        io.execute(() -> {
            File out = new File(workDir, "gif_" + System.currentTimeMillis() + ".mp4");
            try {
                GifToMp4.convert(getApplicationContext(), in, out);
                runOnUiThread(() -> {
                    if (!isDestroyed()) load(out);
                });
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("VideoEditor", "GIF conversion failed", e);
                runOnUiThread(this::failOpen);
            }
        });
    }

    private void load(File file) {
        source = file;
        binding.loadingText.setText(R.string.loading);
        player = new ExoPlayer.Builder(this).build();
        binding.player.setPlayer(player);
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setVolume(sound ? 1f : 0f);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && durationMs == 0) onReady();
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize size) {
                if (size.width == 0 || size.height == 0) return;
                int w = Math.round(size.width * size.pixelWidthHeightRatio);
                int h = size.height;
                //noinspection deprecation
                if (size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270) {
                    int swap = w;
                    w = h;
                    h = swap;
                }
                videoW = w;
                videoH = h;
                layoutPreview();
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                failOpen();
            }
        });
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
        player.play();
        handler.post(loopCheck);
        buildFilterRow(file);
    }

    private void onReady() {
        durationMs = Math.max(1, player.getDuration());
        startMs = 0;
        endMs = durationMs;
        float seconds = durationMs / 1000f;
        binding.trimSlider.setValueFrom(0f);
        binding.trimSlider.setValueTo(seconds);
        binding.trimSlider.setValues(0f, seconds);
        if (seconds > 1f) binding.trimSlider.setMinSeparationValue(0.5f);
        binding.trimSlider.addOnChangeListener((slider, value, fromUser) -> {
            List<Float> values = slider.getValues();
            long newStart = Math.round(values.get(0) * 1000);
            long newEnd = Math.round(values.get(1) * 1000);
            // Show the frame at whichever handle moved.
            if (fromUser && player != null) player.seekTo(newStart != startMs ? newStart : Math.max(newStart, newEnd - 300));
            startMs = newStart;
            endMs = newEnd;
            updateTrimLabel();
        });
        updateTrimLabel();
        binding.loading.setVisibility(View.GONE);
        setBusy(false);
    }

    private void updateTrimLabel() {
        binding.trimLabel.setText(getString(R.string.trim_range, time(startMs), time(endMs),
                (endMs - startMs) / 1000f));
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void showPanel(int tabId) {
        binding.panelTrim.setVisibility(tabId == R.id.tabTrim ? View.VISIBLE : View.GONE);
        binding.speedGroup.setVisibility(tabId == R.id.tabSpeed ? View.VISIBLE : View.GONE);
        binding.panelFilter.setVisibility(tabId == R.id.tabFilter ? View.VISIBLE : View.GONE);
        binding.switchSound.setVisibility(tabId == R.id.tabSound ? View.VISIBLE : View.GONE);
        binding.panelCrop.setVisibility(tabId == R.id.tabCrop ? View.VISIBLE : View.GONE);
        binding.panelZoom.setVisibility(tabId == R.id.tabZoom ? View.VISIBLE : View.GONE);
    }

    private float aspectFor(int buttonId) {
        if (buttonId == R.id.cropScreen) return screenAspect();
        if (buttonId == R.id.crop916) return 9f / 16f;
        if (buttonId == R.id.crop11) return 1f;
        if (buttonId == R.id.crop169) return 16f / 9f;
        return 0f;
    }

    /** This screen's shape held upright, so "Screen" fits the wallpaper on whichever display is in use. */
    private float screenAspect() {
        int w;
        int h;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
            w = bounds.width();
            h = bounds.height();
        } else {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        return Math.min(w, h) / (float) Math.max(w, h);
    }

    /** Sizes the crop window to the chosen shape inside the stage, then zooms and pans the video within it. */
    private void layoutPreview() {
        int stageW = binding.stage.getWidth();
        int stageH = binding.stage.getHeight();
        if (stageW == 0 || stageH == 0 || videoW == 0) return;
        float a = aspect > 0 ? aspect : videoW / (float) videoH;
        int w;
        int h;
        if (stageW / (float) stageH > a) {
            h = stageH;
            w = Math.round(stageH * a);
        } else {
            w = stageW;
            h = Math.round(stageW / a);
        }
        android.view.ViewGroup.LayoutParams lp = binding.cropWindow.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            binding.cropWindow.setLayoutParams(lp);
        }
        cropW = w;
        cropH = h;
        float[] travel = travel();
        binding.player.setScaleX(zoom);
        binding.player.setScaleY(zoom);
        binding.player.setTranslationX(panX * travel[0]);
        binding.player.setTranslationY(panY * travel[1]);
    }

    /** Scale from video pixels to window pixels (the player fills the window, then is zoomed). */
    private float displayScale() {
        return Math.max(cropW / (float) videoW, cropH / (float) videoH) * zoom;
    }

    /** How far, in window pixels, the zoomed video can move each way before an edge shows. */
    private float[] travel() {
        float s = displayScale();
        return new float[]{Math.max(0, (videoW * s - cropW) / 2f), Math.max(0, (videoH * s - cropH) / 2f)};
    }

    private void setZoom(float value) {
        zoom = Math.max(1f, Math.min(MAX_ZOOM, value));
        if (binding.zoomSlider.getValue() != zoom) binding.zoomSlider.setValue(zoom);
        updateZoomLabel();
        layoutPreview();
    }

    private void updateZoomLabel() {
        binding.zoomLabel.setText(getString(R.string.zoom_label, zoom));
    }

    /** Pinch on the video to zoom, drag it to choose what stays in frame. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setUpStageGestures() {
        android.view.ScaleGestureDetector pinch = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(@NonNull android.view.ScaleGestureDetector detector) {
                        setZoom(zoom * detector.getScaleFactor());
                        return true;
                    }
                });
        float[] last = {Float.NaN, Float.NaN};
        binding.stage.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() == 1 && !pinch.isInProgress() && !Float.isNaN(last[0])) {
                        float[] travel = travel();
                        if (travel[0] > 0) panX = Math.max(-1f, Math.min(1f, panX + (e.getX() - last[0]) / travel[0]));
                        if (travel[1] > 0) panY = Math.max(-1f, Math.min(1f, panY + (e.getY() - last[1]) / travel[1]));
                        layoutPreview();
                    }
                    last[0] = e.getX();
                    last[1] = e.getY();
                    break;
                case android.view.MotionEvent.ACTION_DOWN:
                    last[0] = e.getX();
                    last[1] = e.getY();
                    break;
                default:
                    // A finger added or lifted: restart the drag from the next move so the video doesn't jump.
                    last[0] = Float.NaN;
                    last[1] = Float.NaN;
            }
            return true;
        });
    }

    /** Turns the on-screen crop window, zoom and pan into a Crop (plus a resize so zooming keeps detail). */
    private void addFraming(List<Effect> effects) {
        if (videoW == 0 || cropW == 0) return;
        float s = displayScale();
        float visW = cropW / s / videoW;
        float visH = cropH / s / videoH;
        if (visW > 0.999f && visH > 0.999f) return;
        float[] travel = travel();
        float cx = 0.5f - panX * travel[0] / (videoW * s);
        float cy = 0.5f - panY * travel[1] / (videoH * s);
        float left = clampNdc((cx - visW / 2) * 2 - 1);
        float right = clampNdc((cx + visW / 2) * 2 - 1);
        float top = clampNdc(1 - (cy - visH / 2) * 2);
        float bottom = clampNdc(1 - (cy + visH / 2) * 2);
        effects.add(new Crop(left, right, bottom, top));

        int cw = Math.round(visW * videoW);
        int ch = Math.round(visH * videoH);
        // Scale the cut-out back up toward the source's size, but never past MAX_OUTPUT_SIDE.
        float scale = Math.min(MAX_OUTPUT_SIDE / (float) Math.max(cw, ch),
                Math.max(1f, Math.min(videoW, videoH) / (float) Math.min(cw, ch)));
        int outW = Math.max(2, Math.round(cw * scale / 2f) * 2);
        int outH = Math.max(2, Math.round(ch * scale / 2f) * 2);
        effects.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT));
    }

    private static float clampNdc(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    /** Thumbnails of the first frame in each filter; tapping one tints the live preview the same way. */
    private void buildFilterRow(File file) {
        io.execute(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 200, 200);
            } catch (RuntimeException ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (IOException ignored) {
                }
            }
            Bitmap[] thumbs = new Bitmap[Filters.NAMES.length];
            if (frame != null) for (int i = 0; i < thumbs.length; i++) thumbs[i] = Filters.apply(frame, i);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                int stroke = Math.round(3 * getResources().getDisplayMetrics().density);
                for (int i = 0; i < Filters.NAMES.length; i++) {
                    ItemFilterBinding item = ItemFilterBinding.inflate(getLayoutInflater(), binding.filterRow, false);
                    if (thumbs[i] != null) item.filterThumb.setImageBitmap(thumbs[i]);
                    item.filterName.setText(Filters.NAMES[i]);
                    item.filterCard.setStrokeWidth(i == filter ? stroke : 0);
                    int index = i;
                    item.getRoot().setOnClickListener(v -> {
                        filter = index;
                        for (int j = 0; j < filterItems.length; j++) {
                            filterItems[j].filterCard.setStrokeWidth(j == index ? stroke : 0);
                        }
                        applyPreviewFilter();
                    });
                    filterItems[i] = item;
                    binding.filterRow.addView(item.getRoot());
                }
            });
        });
    }

    private void applyPreviewFilter() {
        View surface = binding.player.getVideoSurfaceView();
        if (!(surface instanceof TextureView)) return;
        ColorMatrix m = Filters.matrix(filter);
        Paint paint = new Paint();
        if (m != null) paint.setColorFilter(new ColorMatrixColorFilter(m));
        ((TextureView) surface).setLayerPaint(paint);
    }

    /** Renders the clip with every edit applied into the work folder, then hands the file to {@code onDone}. */
    private void export(Consumer<File> onDone) {
        if (source == null || transformer != null) return;
        File[] old = workDir.listFiles();
        if (old != null) {
            for (File f : old) //noinspection ResultOfMethodCallIgnored
                if (!f.equals(source)) f.delete();
        }
        File out = new File(workDir, "edit_" + System.currentTimeMillis() + ".mp4");

        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.fromFile(source))
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build())
                .build();
        List<Effect> videoEffects = new ArrayList<>();
        List<AudioProcessor> audioProcessors = new ArrayList<>();
        addFraming(videoEffects);
        if (filter != Filters.ORIGINAL) {
            videoEffects.add(SingleColorLut.createFromCube(Filters.lutCube(filter, LUT_SIZE)));
        }
        if (speed != 1f) {
            videoEffects.add(new SpeedChangeEffect(speed));
            if (sound) {
                SonicAudioProcessor sonic = new SonicAudioProcessor();
                sonic.setSpeed(speed);
                audioProcessors.add(sonic);
            }
        }
        EditedMediaItem edited = new EditedMediaItem.Builder(item)
                .setRemoveAudio(!sound)
                .setEffects(new Effects(audioProcessors, videoEffects))
                .build();

        setBusy(true);
        binding.exportProgress.setVisibility(View.VISIBLE);
        binding.exportProgress.setProgressCompat(0, false);
        toast(R.string.exporting_video);
        transformer = new Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(@NonNull Composition composition, @NonNull ExportResult result) {
                        finishExport();
                        onDone.accept(out);
                    }

                    @Override
                    public void onError(@NonNull Composition composition, @NonNull ExportResult result,
                                        @NonNull ExportException exception) {
                        android.util.Log.w("VideoEditor", "export failed", exception);
                        finishExport();
                        toast(R.string.export_failed);
                    }
                })
                .build();
        transformer.start(edited, out.getAbsolutePath());
        ProgressHolder holder = new ProgressHolder();
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (transformer == null) return;
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    binding.exportProgress.setProgressCompat(holder.progress, true);
                }
                handler.postDelayed(this, 200);
            }
        });
    }

    private void finishExport() {
        transformer = null;
        binding.exportProgress.setVisibility(View.GONE);
        setBusy(false);
    }

    private void setBusy(boolean busy) {
        binding.btnDownload.setEnabled(!busy);
        binding.btnSetMotion.setEnabled(!busy);
    }

    private void failOpen() {
        if (isDestroyed()) return;
        toast(R.string.video_open_failed);
        finish();
    }

    private void toast(@StringRes int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (transformer != null) transformer.cancel();
        if (player != null) player.release();
        io.shutdown();
        super.onDestroy();
    }
}
