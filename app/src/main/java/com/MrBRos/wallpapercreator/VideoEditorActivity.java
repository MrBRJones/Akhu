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
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.UnstableApi;
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
