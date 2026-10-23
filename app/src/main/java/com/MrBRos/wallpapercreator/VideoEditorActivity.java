package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.SingleColorLut;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.MrBRos.wallpapercreator.databinding.ActivityVideoEditorBinding;
import com.MrBRos.wallpapercreator.databinding.ItemFilterBinding;
import com.MrBRos.wallpapercreator.databinding.ItemHsvSliderBinding;
import com.MrBRos.wallpapercreator.databinding.SheetStickersBinding;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Motion editor opened by Create in MOTION mode, laid out like Instagram's Reels editor: the clip
 * with text and stickers on it, then Edit video (timeline, trim, speed, crop, zoom), Text (fonts,
 * colour, animation, effects), Stickers (emoji, photo, cutout), Filters and Sound. Next renders it all
 * with Media3 and downloads, sets or shares the result. GIFs are turned into MP4 first.
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoEditorActivity extends AppCompatActivity {

    private static final String EXTRA_PATH = "path";
    private static final String EXTRA_GIF = "gif";
    private static final String EXTRA_NAME = "name";
    private static final float[] SPEEDS = {0.5f, 1f, 1.5f, 2f};
    private static final int[] SPEED_IDS = {R.id.speedHalf, R.id.speedNormal, R.id.speedFast, R.id.speedDouble};
    private static final int LUT_SIZE = 33;
    private static final float MAX_ZOOM = 4f;
    private static final int MAX_OUTPUT_SIDE = 1920;
    private static final int TIMELINE_FRAMES = 10;
    private static final float MIN_TEXT = 0.03f, MAX_TEXT = 0.2f;

    private static final int MODE_MAIN = 0, MODE_TIMELINE = 1, MODE_FILTERS = 2, MODE_TEXT = 3;
    private static final int TAB_KEYBOARD = 0, TAB_FONT = 1, TAB_COLOUR = 2, TAB_ANIM = 3, TAB_EFFECT = 4;

    static Intent intent(Context context, File file, boolean isGif, @Nullable String name) {
        return new Intent(context, VideoEditorActivity.class)
                .putExtra(EXTRA_PATH, file.getAbsolutePath())
                .putExtra(EXTRA_GIF, isGif)
                .putExtra(EXTRA_NAME, name);
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
    // Wallpapers play muted, so sound is off unless asked for (it only matters for Download and Share).
    private boolean sound;
    // Framing: the video size as shown (rotation applied), the crop shape (width/height, 0 = the video's own),
    // zoom, and pan as a fraction (-1..1) of how far the zoomed video can move each way.
    private int videoW;
    private int videoH;
    private float aspect;
    private int cropId = R.id.cropOriginal;
    private float zoom = 1f;
    private float panX;
    private float panY;
    private int cropW;
    private int cropH;
    private final ItemFilterBinding[] filterItems = new ItemFilterBinding[Filters.NAMES.length];

    /** Text and stickers, back to front. Shared with the overlay view and the timeline. */
    private final List<MotionOverlay> overlays = new ArrayList<>();
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    /** Restoring a snapshot sets the controls; their listeners must not record that as a new edit. */
    private boolean restoring;
    private int mode = MODE_MAIN;

    // The text tool.
    @Nullable
    private MotionOverlay editing;
    private boolean editingIsNew;
    private final float[] textHsv = new float[3];
    private HsvSliders textSliders;

    /** Plays the trimmed range on loop and keeps the overlays, timeline and clock in step with it. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null && durationMs > 0) {
                long pos = player.getCurrentPosition();
                if (player.isPlaying() && (pos >= endMs || pos < startMs - 150)) {
                    player.seekTo(startMs);
                    pos = startMs;
                }
                binding.overlays.setTime(pos, startMs);
                binding.timeline.setPosition(pos);
                binding.timeLabel.setText(getString(R.string.time_of, time(Math.max(0, pos - startMs)),
                        time(endMs - startMs)));
            }
            handler.postDelayed(this, 30);
        }
    };

    /** Whether the picture being picked becomes a cutout (its subject only) or a whole-photo sticker. */
    private boolean pendingCutout;
    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) addPhotoSticker(uri, pendingCutout);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVideoEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        workDir = new File(getCacheDir(), "motion_edit");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();

        binding.overlays.setOverlays(overlays);
        binding.timeline.setOverlays(overlays);
        binding.overlays.setListener(new OverlayView.Listener() {
            @Override
            public void onChangeStart() {
                pushUndo();
            }

            @Override
            public void onTap(MotionOverlay overlay) {
                if (overlay.type == MotionOverlay.TEXT) startTextEdit(overlay, false);
            }

            @Override
            public void onDelete(MotionOverlay overlay) {
                overlays.remove(overlay);
                overlaysChanged();
            }
        });
        binding.timeline.setListener(new TimelineView.Listener() {
            @Override
            public void onChangeStart() {
                pushUndo();
            }

            @Override
            public void onSeek(long ms) {
                if (player != null) player.seekTo(ms);
                binding.overlays.setTime(ms, startMs);
            }

            @Override
            public void onTrimChanged(long start, long end) {
                startMs = start;
                endMs = end;
            }

            @Override
            public void onOverlayTimeChanged() {
                binding.overlays.invalidate();
            }
        });

        binding.btnClose.setOnClickListener(v -> leave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mode == MODE_TEXT) finishTextEdit();
                else if (mode != MODE_MAIN) setMode(MODE_MAIN);
                else leave();
            }
        });
        binding.btnUndo.setOnClickListener(v -> step(undo, redo));
        binding.btnRedo.setOnClickListener(v -> step(redo, undo));
        binding.toolEditVideo.setOnClickListener(v -> setMode(MODE_TIMELINE));
        binding.btnTimelineDone.setOnClickListener(v -> setMode(MODE_MAIN));
        binding.toolText.setOnClickListener(v -> {
            pushUndo();
            MotionOverlay o = new MotionOverlay(MotionOverlay.TEXT);
            overlays.add(o);
            startTextEdit(o, true);
        });
        binding.toolStickers.setOnClickListener(v -> showStickers());
        binding.toolFilters.setOnClickListener(v -> setMode(mode == MODE_FILTERS ? MODE_MAIN : MODE_FILTERS));
        binding.toolSound.setOnClickListener(v -> {
            sound = !sound;
            if (player != null) player.setVolume(sound ? 1f : 0f);
            showSound();
            toast(sound ? R.string.sound_on_toast : R.string.sound_off_toast);
        });
        binding.btnNext.setOnClickListener(v -> showNext());
        binding.btnPlay.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause();
            else {
                if (player.getCurrentPosition() >= endMs - 50) player.seekTo(startMs);
                player.play();
            }
        });

        binding.frameTabs.check(R.id.tabSpeed);
        binding.frameTabs.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            binding.speedGroup.setVisibility(id == R.id.tabSpeed ? View.VISIBLE : View.GONE);
            binding.panelCrop.setVisibility(id == R.id.tabCrop ? View.VISIBLE : View.GONE);
            binding.panelZoom.setVisibility(id == R.id.tabZoom ? View.VISIBLE : View.GONE);
        });
        binding.speedGroup.check(R.id.speedNormal);
        binding.speedGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || restoring) return;
            pushUndo();
            for (int i = 0; i < SPEED_IDS.length; i++) if (SPEED_IDS[i] == id) speed = SPEEDS[i];
            if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
        });
        binding.cropGroup.check(R.id.cropOriginal);
        binding.cropGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked || restoring) return;
            pushUndo();
            cropId = id;
            aspect = aspectFor(id);
            panX = 0;
            panY = 0;
            layoutPreview();
        });
        binding.zoomSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) setZoom(value);
        });
        binding.zoomSlider.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull com.google.android.material.slider.Slider slider) {
                pushUndo();
            }

            @Override
            public void onStopTrackingTouch(@NonNull com.google.android.material.slider.Slider slider) {
            }
        });
        binding.stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::layoutPreview);
        });
        setUpStageGestures();
        updateZoomLabel();
        setUpTextTool();
        showSound();
        updateUndoButtons();
        setBusy(true);

        File in = new File(getIntent().getStringExtra(EXTRA_PATH));
        if (!getIntent().getBooleanExtra(EXTRA_GIF, false)) {
            load(in);
            return;
        }
        // A GIF has no sound, so that tool would do nothing.
        binding.toolSound.setVisibility(View.GONE);
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
            public void onIsPlayingChanged(boolean isPlaying) {
                binding.btnPlay.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
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
        handler.post(tick);
        buildFilterRow(file);
    }

    private void onReady() {
        durationMs = Math.max(1, player.getDuration());
        startMs = 0;
        endMs = durationMs;
        binding.timeline.setClip(durationMs, startMs, endMs);
        loadTimelineFrames();
        binding.loading.setVisibility(View.GONE);
        setBusy(false);
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // --- Modes ---------------------------------------------------------------------------------

    /** Main tool row, the Edit video timeline, the filter strip, or the text tool. */
    private void setMode(int newMode) {
        mode = newMode;
        binding.toolRow.setVisibility(newMode == MODE_TIMELINE ? View.GONE : View.VISIBLE);
        binding.panelTimeline.setVisibility(newMode == MODE_TIMELINE ? View.VISIBLE : View.GONE);
        binding.panelFilter.setVisibility(newMode == MODE_FILTERS ? View.VISIBLE : View.GONE);
        binding.textTool.setVisibility(newMode == MODE_TEXT ? View.VISIBLE : View.GONE);
        // The text tool's Done sits where undo and redo are.
        int topButtons = newMode == MODE_TEXT ? View.INVISIBLE : View.VISIBLE;
        binding.btnUndo.setVisibility(topButtons);
        binding.btnRedo.setVisibility(topButtons);
        // In the timeline, drags on the video pan and pinch it (framing); elsewhere they move stickers.
        boolean framing = newMode == MODE_TIMELINE;
        binding.overlays.setInteractive(!framing);
        binding.cropWindow.setForeground(framing ? getDrawable(R.drawable.bg_crop_frame) : null);
        if (framing) binding.timeline.setClip(durationMs, startMs, endMs);
        binding.toolFilters.setSelected(newMode == MODE_FILTERS);
    }

    private void leave() {
        if (undo.isEmpty()) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.discard_motion_title)
                .setNegativeButton(R.string.keep_editing, null)
                .setPositiveButton(R.string.discard, (d, w) -> finish())
                .show();
    }

    private void showSound() {
        binding.toolSound.setCompoundDrawablesRelativeWithIntrinsicBounds(0,
                sound ? R.drawable.ic_music : R.drawable.ic_music_off, 0, 0);
    }

    // --- Undo ----------------------------------------------------------------------------------

    /** Everything an edit can change, so undo can put it back. */
    private static final class Snapshot {
        final List<MotionOverlay> overlays = new ArrayList<>();
        long startMs, endMs;
        float speed, zoom, panX, panY;
        int filter, cropId;
    }

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        for (MotionOverlay o : overlays) s.overlays.add(o.copy());
        s.startMs = startMs;
        s.endMs = endMs;
        s.speed = speed;
        s.zoom = zoom;
        s.panX = panX;
        s.panY = panY;
        s.filter = filter;
        s.cropId = cropId;
        return s;
    }

    /** Call before an edit: saves how things are now as an undo step. */
    private void pushUndo() {
        if (restoring) return;
        undo.push(snapshot());
        redo.clear();
        updateUndoButtons();
    }

    /** Undo (from = undo, to = redo) or redo (the other way round). */
    private void step(Deque<Snapshot> from, Deque<Snapshot> to) {
        if (from.isEmpty() || mode == MODE_TEXT) return;
        to.push(snapshot());
        restore(from.pop());
        updateUndoButtons();
    }

    private void restore(Snapshot s) {
        restoring = true;
        overlays.clear();
        for (MotionOverlay o : s.overlays) overlays.add(o.copy());
        startMs = s.startMs;
        endMs = s.endMs;
        speed = s.speed;
        for (int i = 0; i < SPEEDS.length; i++) if (SPEEDS[i] == speed) binding.speedGroup.check(SPEED_IDS[i]);
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
        cropId = s.cropId;
        binding.cropGroup.check(cropId);
        aspect = aspectFor(cropId);
        zoom = s.zoom;
        panX = s.panX;
        panY = s.panY;
        binding.zoomSlider.setValue(zoom);
        updateZoomLabel();
        layoutPreview();
        setFilter(s.filter);
        binding.timeline.setClip(durationMs, startMs, endMs);
        overlaysChanged();
        restoring = false;
    }

    private void updateUndoButtons() {
        binding.btnUndo.setEnabled(!undo.isEmpty());
        binding.btnUndo.setAlpha(undo.isEmpty() ? 0.35f : 1f);
        binding.btnRedo.setEnabled(!redo.isEmpty());
        binding.btnRedo.setAlpha(redo.isEmpty() ? 0.35f : 1f);
    }

    private void overlaysChanged() {
        binding.overlays.invalidate();
        binding.timeline.setOverlays(overlays);
    }

    // --- Text tool -----------------------------------------------------------------------------

    private void setUpTextTool() {
        binding.textInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (editing == null) return;
                editing.text = s.toString();
                binding.overlays.invalidate();
            }
        });
        binding.textInput.setOnFocusChangeListener((v, focused) -> {
            if (focused && mode == MODE_TEXT) showTextTab(TAB_KEYBOARD);
        });
        binding.sizeBar.setListener(v -> {
            if (editing == null) return;
            editing.size = MIN_TEXT + v * (MAX_TEXT - MIN_TEXT);
            binding.overlays.invalidate();
        });
        binding.btnTextDone.setOnClickListener(v -> finishTextEdit());
        binding.tabKeyboard.setOnClickListener(v -> showTextTab(TAB_KEYBOARD));
        binding.tabFont.setOnClickListener(v -> showTextTab(TAB_FONT));
        binding.tabColour.setOnClickListener(v -> showTextTab(TAB_COLOUR));
        binding.tabAnim.setOnClickListener(v -> showTextTab(TAB_ANIM));
        binding.tabEffect.setOnClickListener(v -> showTextTab(TAB_EFFECT));
        binding.tabBackground.setOnClickListener(v -> {
            if (editing == null) return;
            editing.background = (editing.background + 1) % 3;
            binding.tabBackground.setSelected(editing.background != MotionOverlay.BG_NONE);
            binding.overlays.invalidate();
        });
        binding.tabAlign.setOnClickListener(v -> {
            if (editing == null) return;
            editing.align = editing.align == Layout.Alignment.ALIGN_CENTER ? Layout.Alignment.ALIGN_NORMAL
                    : editing.align == Layout.Alignment.ALIGN_NORMAL ? Layout.Alignment.ALIGN_OPPOSITE
                    : Layout.Alignment.ALIGN_CENTER;
            showAlign();
            binding.overlays.invalidate();
        });
        for (ItemHsvSliderBinding row : new ItemHsvSliderBinding[]{binding.rowH, binding.rowS, binding.rowV}) {
            row.letter.setTextColor(Color.WHITE);
            row.value.setTextColor(Color.WHITE);
        }
        textSliders = new HsvSliders(binding.rowH, binding.rowS, binding.rowV,
                new String[]{"H", "S", "V"},
                textHsv, () -> setTextColour(Color.HSVToColor(textHsv), false));
        buildSwatches();
    }

    private void startTextEdit(MotionOverlay o, boolean isNew) {
        if (!isNew) pushUndo();
        editing = o;
        editingIsNew = isNew;
        setMode(MODE_TEXT);
        binding.textInput.setText(o.text);
        binding.textInput.setSelection(o.text.length());
        binding.sizeBar.setValue((o.size - MIN_TEXT) / (MAX_TEXT - MIN_TEXT));
        binding.tabBackground.setSelected(o.background != MotionOverlay.BG_NONE);
        showAlign();
        setTextColour(o.color, true);
        showTextTab(TAB_KEYBOARD);
    }

    private void finishTextEdit() {
        MotionOverlay o = editing;
        editing = null;
        hideKeyboard();
        binding.textInput.clearFocus();
        if (o != null && o.text.trim().isEmpty()) {
            overlays.remove(o);
            // A new text that was never typed in isn't an edit worth undoing.
            if (editingIsNew && !undo.isEmpty()) undo.pop();
            updateUndoButtons();
        }
        setMode(MODE_MAIN);
        overlaysChanged();
    }

    private void showTextTab(int tab) {
        binding.tabKeyboard.setSelected(tab == TAB_KEYBOARD);
        binding.tabFont.setSelected(tab == TAB_FONT);
        binding.tabColour.setSelected(tab == TAB_COLOUR);
        binding.tabAnim.setSelected(tab == TAB_ANIM);
        binding.tabEffect.setSelected(tab == TAB_EFFECT);
        binding.textPanel.setVisibility(tab == TAB_KEYBOARD ? View.GONE : View.VISIBLE);
        binding.colourPanel.setVisibility(tab == TAB_COLOUR ? View.VISIBLE : View.GONE);
        binding.gridScroll.setVisibility(tab == TAB_FONT || tab == TAB_ANIM || tab == TAB_EFFECT ? View.VISIBLE : View.GONE);
        if (tab == TAB_KEYBOARD) {
            binding.textInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(binding.textInput, InputMethodManager.SHOW_IMPLICIT);
            return;
        }
        hideKeyboard();
        if (tab == TAB_COLOUR) textSliders.sync();
        else buildStyleGrid(tab);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(binding.textInput.getWindowToken(), 0);
    }

    /** Two columns of tiles for fonts, animations or effects, each drawn in the style it picks. */
    private void buildStyleGrid(int tab) {
        GridLayout grid = binding.styleGrid;
        grid.removeAllViews();
        if (editing == null) return;
        String[] names = tab == TAB_FONT ? OverlayRenderer.FONT_NAMES
                : tab == TAB_ANIM ? OverlayRenderer.ANIM_NAMES : OverlayRenderer.EFFECT_NAMES;
        int selected = tab == TAB_FONT ? editing.font : tab == TAB_ANIM ? editing.animation : editing.effect;
        int gap = Math.round(4 * getResources().getDisplayMetrics().density);
        int height = Math.round(52 * getResources().getDisplayMetrics().density);
        List<StyleTile> tiles = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            StyleTile tile = new StyleTile(this, names[i], tab == TAB_FONT ? i : editing.font,
                    tab == TAB_EFFECT ? i : OverlayRenderer.EFFECT_NONE);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = height;
            lp.setMargins(gap, gap, gap, gap);
            tile.setLayoutParams(lp);
            tile.setSelected(i == selected);
            tile.setContentDescription(names[i]);
            int index = i;
            tile.setOnClickListener(v -> {
                if (editing == null) return;
                if (tab == TAB_FONT) editing.font = index;
                else if (tab == TAB_ANIM) editing.animation = index;
                else editing.effect = index;
                for (StyleTile t : tiles) t.setSelected(t == v);
                binding.overlays.invalidate();
            });
            tiles.add(tile);
            grid.addView(tile);
        }
    }

    /** The colour row: a rainbow dot for any colour, then white, black and the theme presets. */
    private void buildSwatches() {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(30 * density), margin = Math.round(5 * density);
        List<Integer> colours = new ArrayList<>();
        Collections.addAll(colours, 0xFFFFFFFF, 0xFF000000);
        for (int p : ThemeEditorActivity.PRESETS) if (!colours.contains(p)) colours.add(p);
        View custom = new View(this);
        GradientDrawable rainbow = new GradientDrawable();
        rainbow.setShape(GradientDrawable.OVAL);
        rainbow.setGradientType(GradientDrawable.SWEEP_GRADIENT);
        rainbow.setColors(new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000});
        custom.setBackground(rainbow);
        custom.setContentDescription(getString(R.string.custom_colour));
        custom.setOnClickListener(v -> ColorPickerSheet.show(this, R.string.text_tab_colour,
                editing == null ? Color.WHITE : editing.color, c -> setTextColour(c, true)));
        addSwatch(custom, size, margin);
        for (int c : colours) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(Math.round(1.5f * density), 0x80FFFFFF);
            dot.setBackground(g);
            dot.setOnClickListener(v -> setTextColour(c, true));
            addSwatch(dot, size, margin);
        }
    }

    private void addSwatch(View v, int size, int margin) {
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(size, size);
        lp.setMargins(margin, 0, margin, 0);
        binding.swatchRow.addView(v, lp);
    }

    /** Sets the edited text's colour; {@code moveSliders} when it came from a swatch, not the sliders. */
    private void setTextColour(int color, boolean moveSliders) {
        if (editing != null) editing.color = color;
        if (moveSliders) {
            Color.colorToHSV(color, textHsv);
            textSliders.sync();
        }
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setStroke(Math.round(2 * getResources().getDisplayMetrics().density), Color.WHITE);
        int s = Math.round(22 * getResources().getDisplayMetrics().density);
        dot.setSize(s, s);
        binding.tabColour.setImageDrawable(dot);
        binding.overlays.invalidate();
    }

    private void showAlign() {
        if (editing == null) return;
        binding.tabAlign.setImageResource(editing.align == Layout.Alignment.ALIGN_NORMAL ? R.drawable.ic_align_left
                : editing.align == Layout.Alignment.ALIGN_OPPOSITE ? R.drawable.ic_align_right
                : R.drawable.ic_align_center);
    }

    // --- Stickers ------------------------------------------------------------------------------

    private void showStickers() {
        SheetStickersBinding sheet = SheetStickersBinding.inflate(getLayoutInflater());
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(sheet.getRoot());
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);
        // The sheet's own rounded dark background shows instead of the default one.
        View parent = (View) sheet.getRoot().getParent();
        if (parent != null) parent.setBackgroundColor(Color.TRANSPARENT);

        List<String> shown = new ArrayList<>(StickerCatalog.search(""));
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
                TextView tv = new TextView(p.getContext());
                tv.setTextSize(34);
                tv.setGravity(Gravity.CENTER);
                int pad = Math.round(6 * getResources().getDisplayMetrics().density);
                tv.setPadding(0, pad, 0, pad);
                tv.setBackgroundResource(android.R.drawable.list_selector_background);
                tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(tv) { };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                String emoji = shown.get(position);
                ((TextView) h.itemView).setText(emoji);
                h.itemView.setOnClickListener(v -> {
                    dialog.dismiss();
                    pushUndo();
                    MotionOverlay o = new MotionOverlay(MotionOverlay.EMOJI);
                    o.text = emoji;
                    overlays.add(o);
                    overlaysChanged();
                });
            }

            @Override
            public int getItemCount() {
                return shown.size();
            }
        };
        int widthDp = getResources().getConfiguration().screenWidthDp;
        sheet.stickerGrid.setLayoutManager(new GridLayoutManager(this, Math.max(5, widthDp / 70)));
        sheet.stickerGrid.setAdapter(adapter);
        sheet.stickerSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            @android.annotation.SuppressLint("NotifyDataSetChanged")
            public void afterTextChanged(Editable s) {
                shown.clear();
                shown.addAll(StickerCatalog.search(s.toString()));
                adapter.notifyDataSetChanged();
            }
        });
        sheet.btnPhotoSticker.setOnClickListener(v -> pickSticker(dialog, false));
        sheet.btnCutoutSticker.setOnClickListener(v -> pickSticker(dialog, true));
        dialog.show();
    }

    private void pickSticker(BottomSheetDialog dialog, boolean cutout) {
        dialog.dismiss();
        pendingCutout = cutout;
        photoPicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    /** Makes a picked picture into a sticker: as it is, or (cutout) just its subject. */
    private void addPhotoSticker(Uri uri, boolean cutout) {
        if (cutout) toast(R.string.cutting_out);
        Context app = getApplicationContext();
        io.execute(() -> {
            Bitmap b = null;
            int error = R.string.pick_failed;
            try {
                File picked = ImagePickerHelper.copyToCache(app, uri);
                b = cutout ? FourDMaker.cutout(app, picked) : ImagePickerHelper.decodeSampled(picked, 1024);
            } catch (FourDMaker.MakeException e) {
                error = e.messageRes;
            } catch (IOException ignored) {
            }
            File out = new File(workDir, "sticker_" + System.currentTimeMillis() + ".png");
            if (b != null) {
                try (OutputStream os = new FileOutputStream(out)) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, os);
                } catch (IOException e) {
                    b = null;
                }
            }
            boolean ok = b != null;
            int finalError = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (!ok) {
                    toast(finalError);
                    return;
                }
                pushUndo();
                MotionOverlay o = new MotionOverlay(MotionOverlay.PHOTO);
                o.photoPath = out.getAbsolutePath();
                o.size = 0.45f;
                overlays.add(o);
                overlaysChanged();
            });
        });
    }

    // --- Timeline ------------------------------------------------------------------------------

    /** Frames spread across the clip for the timeline strip. */
    private void loadTimelineFrames() {
        File file = source;
        long duration = durationMs;
        io.execute(() -> {
            Bitmap[] frames = new Bitmap[TIMELINE_FRAMES];
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < frames.length; i++) {
                    long us = (duration * 1000L) * (2 * i + 1) / (2L * frames.length);
                    frames[i] = retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160);
                }
            } catch (RuntimeException ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (IOException ignored) {
                }
            }
            runOnUiThread(() -> {
                if (!isDestroyed()) binding.timeline.setFrames(frames);
            });
        });
    }

    // --- Framing (crop, zoom, pan) -------------------------------------------------------------

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
        ViewGroup.LayoutParams lp = binding.cropWindow.getLayoutParams();
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

    /** In Edit video, pinch on the video to zoom and drag it to choose what stays in frame. */
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
            if (mode != MODE_TIMELINE) return false;
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
                    pushUndo();
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

    /**
     * Turns the on-screen crop window, zoom and pan into a Crop (plus a resize so zooming keeps detail).
     * Returns the size of the frames that come out.
     */
    private int[] addFraming(List<Effect> effects) {
        if (videoW == 0 || cropW == 0) return new int[]{videoW, videoH};
        float s = displayScale();
        float visW = cropW / s / videoW;
        float visH = cropH / s / videoH;
        if (visW > 0.999f && visH > 0.999f) return new int[]{videoW, videoH};
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
        return new int[]{outW, outH};
    }

    private static float clampNdc(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    // --- Filters -------------------------------------------------------------------------------

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
                for (int i = 0; i < Filters.NAMES.length; i++) {
                    ItemFilterBinding item = ItemFilterBinding.inflate(getLayoutInflater(), binding.filterRow, false);
                    if (thumbs[i] != null) item.filterThumb.setImageBitmap(thumbs[i]);
                    item.filterName.setText(Filters.NAMES[i]);
                    item.filterName.setTextColor(Color.WHITE);
                    int index = i;
                    item.getRoot().setOnClickListener(v -> {
                        if (index == filter) return;
                        pushUndo();
                        setFilter(index);
                    });
                    filterItems[i] = item;
                    binding.filterRow.addView(item.getRoot());
                }
                setFilter(filter);
            });
        });
    }

    private void setFilter(int index) {
        filter = index;
        int stroke = Math.round(3 * getResources().getDisplayMetrics().density);
        for (int j = 0; j < filterItems.length; j++) {
            if (filterItems[j] != null) filterItems[j].filterCard.setStrokeWidth(j == index ? stroke : 0);
        }
        View surface = binding.player.getVideoSurfaceView();
        if (!(surface instanceof TextureView)) return;
        ColorMatrix m = Filters.matrix(filter);
        Paint paint = new Paint();
        if (m != null) paint.setColorFilter(new ColorMatrixColorFilter(m));
        ((TextureView) surface).setLayerPaint(paint);
    }

    // --- Post: render, then save / save and post / set / share ---------------------------------

    /** Same choices as the Themes Save button (Save, or Save and post), plus Set Motion and Share. */
    private void showNext() {
        if (source == null || transformer != null) return;
        if (mode == MODE_TEXT) finishTextEdit();
        String[] choices = {getString(R.string.save_only), getString(R.string.save_and_post),
                getString(R.string.set_live), getString(R.string.share)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.next_title)
                .setItems(choices, (d, which) -> {
                    if (which == 1) {
                        saveAndPost();
                        return;
                    }
                    export(out -> {
                        if (which == 0) download(out);
                        else if (which == 2) MotionWallpapers.applyFile(this, out, null);
                        else startActivity(ImageExporter.createVideoShareIntent(this, out));
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Saves the video like Save does, then uploads it to the cloud as a motion post. */
    private void saveAndPost() {
        if (!FirebaseHelper.isAvailable(this)) {
            Toast.makeText(this, R.string.cloud_disabled, Toast.LENGTH_LONG).show();
            return;
        }
        if (FirebaseHelper.currentUser(this) == null) {
            Toast.makeText(this, R.string.sign_in_required, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, AuthActivity.class));
            return;
        }
        PostDialog.show(this, getIntent().getStringExtra(EXTRA_NAME), (title, isPublic, category) -> export(out -> {
            download(out);
            // The upload outlives this screen, so it reports with the app context.
            Context app = getApplicationContext();
            Toast.makeText(app, R.string.uploading, Toast.LENGTH_SHORT).show();
            FirebaseHelper.uploadVideo(app, out, title, isPublic, category, new FirebaseHelper.UploadCallback() {
                @Override
                public void onProgress(int percent) {
                }

                @Override
                public void onSuccess(String docId) {
                    Toast.makeText(app, R.string.upload_done, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(Exception e) {
                    Toast.makeText(app, app.getString(R.string.upload_failed) + ": " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }));
    }

    private void download(File out) {
        io.execute(() -> {
            int msg = R.string.save_failed;
            try {
                ImageExporter.saveVideoToGallery(getApplicationContext(), out, getIntent().getStringExtra(EXTRA_NAME));
                MotionWallpapers.keepCopy(getApplicationContext(), out);
                msg = R.string.video_saved;
            } catch (IOException ignored) {
            }
            int finalMsg = msg;
            runOnUiThread(() -> toast(finalMsg));
        });
    }

    /** Renders the clip with every edit applied into the work folder, then hands the file to {@code onDone}. */
    private void export(Consumer<File> onDone) {
        if (source == null || transformer != null) return;
        File[] old = workDir.listFiles((d, f) -> f.startsWith("edit_"));
        if (old != null) {
            for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
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
        int[] size = addFraming(videoEffects);
        if (filter != Filters.ORIGINAL) {
            videoEffects.add(SingleColorLut.createFromCube(Filters.lutCube(filter, LUT_SIZE)));
        }
        // Text and stickers go on after framing (they're placed on the framed picture) and before the
        // speed change (their times are the clip's own).
        if (!overlays.isEmpty()) {
            List<TextureOverlay> layers = new ArrayList<>();
            layers.add(new OverlayBitmap(overlays, startMs, size[0], size[1]));
            videoEffects.add(new OverlayEffect(layers));
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
        binding.btnNext.setEnabled(!busy);
        binding.btnNext.setAlpha(busy ? 0.5f : 1f);
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
