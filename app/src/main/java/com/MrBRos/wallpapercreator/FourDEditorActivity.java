package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.MrBRos.wallpapercreator.databinding.ActivityFourdEditorBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fixes up a user-made 4D image: paint what goes on the front / middle layer, tap subjects to
 * move them between layers, check the result with live tilt and set how it moves. Saving
 * replaces the image with a new set and returns its path as {@link #EXTRA_PATH}.
 */
public class FourDEditorActivity extends AppCompatActivity {

    /** In: the user 4D image to edit. Out (RESULT_OK): the saved image's path. */
    public static final String EXTRA_PATH = "path";

    public static Intent intent(Context context, String imagePath) {
        return new Intent(context, FourDEditorActivity.class).putExtra(EXTRA_PATH, imagePath);
    }

    private ActivityFourdEditorBinding binding;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String path;
    @Nullable
    private FourDMaker.Session session;
    private float[] motion;
    @Nullable
    private FourDView fourDView;
    /** Built preview layers, or null when the masks changed since. */
    @Nullable
    private Bitmap[] previewLayers;
    private int editGeneration;
    private boolean dirty, saving, previewing, resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFourdEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !FourDWallpapers.isUser(path)) {
            finish();
            return;
        }
        motion = FourDWallpapers.motion(this, path);

        binding.btnBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!dirty) {
                    finish();
                    return;
                }
                new MaterialAlertDialogBuilder(FourDEditorActivity.this)
                        .setTitle(R.string.fourd_discard_title)
                        .setNegativeButton(R.string.keep_editing, null)
                        .setPositiveButton(R.string.discard, (d, w) -> finish())
                        .show();
            }
        });
        binding.btnSave.setOnClickListener(v -> save());

        binding.maskView.setListener(new MaskEditView.Listener() {
            @Override
            public void onEdited() {
                dirty = true;
                editGeneration++;
                previewLayers = null;
                binding.btnUndo.setEnabled(binding.maskView.canUndo());
            }

            @Override
            public void onTapped(int layer) {
                int msg = layer == FourDMaker.FRONT ? R.string.fourd_moved_front
                        : layer == FourDMaker.MIDDLE ? R.string.fourd_moved_middle
                        : layer == FourDMaker.BACK ? R.string.fourd_moved_back
                        : R.string.fourd_tap_nothing;
                Toast.makeText(FourDEditorActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
        binding.btnUndo.setOnClickListener(v -> binding.maskView.undo());
        binding.tools.setOnCheckedStateChangeListener((group, ids) -> updateTool());
        float density = getResources().getDisplayMetrics().density;
        binding.brushSize.addOnChangeListener((s, value, fromUser) -> binding.maskView.setBrushRadius(value * density));
        binding.maskView.setBrushRadius(binding.brushSize.getValue() * density);

        binding.modeToggle.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) setPreviewing(id == R.id.btnModePreview);
        });
        bindMotionControls();

        binding.maskView.setVisibility(View.INVISIBLE);
        binding.progress.setVisibility(View.VISIBLE);
        Context app = getApplicationContext();
        String p = path;
        worker.execute(() -> {
            FourDMaker.Session s;
            try {
                s = FourDMaker.load(app, p);
            } catch (Exception | OutOfMemoryError e) {
                android.util.Log.w("FourDEditor", "load failed", e);
                s = null;
            }
            FourDMaker.Session loaded = s;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (loaded == null) {
                    Toast.makeText(this, R.string.pick_failed, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                session = loaded;
                binding.progress.setVisibility(View.GONE);
                binding.maskView.setSession(loaded);
                binding.maskView.setVisibility(previewing ? View.INVISIBLE : View.VISIBLE);
                binding.btnSave.setEnabled(true);
                if (previewing) buildPreview();
            });
        });
    }

    private void updateTool() {
        int id = binding.tools.getCheckedChipId();
        int tool = id == R.id.toolMiddle ? MaskEditView.TOOL_MIDDLE
                : id == R.id.toolErase ? MaskEditView.TOOL_ERASE
                : id == R.id.toolTap ? MaskEditView.TOOL_TAP
                : MaskEditView.TOOL_FRONT;
        binding.maskView.setTool(tool);
        binding.hint.setText(tool == MaskEditView.TOOL_MIDDLE ? R.string.fourd_hint_middle
                : tool == MaskEditView.TOOL_ERASE ? R.string.fourd_hint_erase
                : tool == MaskEditView.TOOL_TAP ? R.string.fourd_hint_tap
                : R.string.fourd_hint_front);
        binding.brushSize.setEnabled(tool != MaskEditView.TOOL_TAP);
    }

    // --- Motion settings ---------------------------------------------------------------------

    private void bindMotionControls() {
        binding.strength.setValue(snap(motion[0], 0.3f, 2f, 0.1f));
        binding.midMotion.setValue(snap(motion[1], -0.6f, 0.6f, 0.05f));
        showMotionValues();
        Slider.OnChangeListener change = (s, value, fromUser) -> {
            if (!fromUser) return;
            motion = new float[]{binding.strength.getValue(), binding.midMotion.getValue()};
            dirty = true;
            showMotionValues();
            if (fourDView != null) fourDView.setMotion(motion);
        };
        binding.strength.addOnChangeListener(change);
        binding.midMotion.addOnChangeListener(change);
        // A stronger tilt needs a wider painted-out band behind the cut-outs; rebuild when let go.
        binding.strength.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                previewLayers = null;
                if (previewing) buildPreview();
            }
        });
        binding.btnResetMotion.setOnClickListener(v -> {
            motion = FourDWallpapers.DEFAULT_MOTION.clone();
            binding.strength.setValue(motion[0]);
            binding.midMotion.setValue(motion[1]);
            dirty = true;
            showMotionValues();
            if (fourDView != null) fourDView.setMotion(motion);
            previewLayers = null;
            if (previewing) buildPreview();
        });
    }

    /** Slider values must sit on a step inside the range. */
    private static float snap(float v, float from, float to, float step) {
        float s = from + Math.round((v - from) / step) * step;
        return Math.max(from, Math.min(to, s));
    }

    private void showMotionValues() {
        binding.strengthValue.setText(String.format(java.util.Locale.getDefault(), "%.1f×", binding.strength.getValue()));
        float m = binding.midMotion.getValue();
        binding.midValue.setText(getString(m < 0 ? R.string.fourd_mid_against : R.string.fourd_mid_with, m));
    }

    // --- Preview -----------------------------------------------------------------------------

    private void setPreviewing(boolean on) {
        previewing = on;
        binding.editPanel.setVisibility(on ? View.GONE : View.VISIBLE);
        binding.motionPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            binding.hint.setText(R.string.fourd_hint_preview);
            binding.maskView.setVisibility(View.INVISIBLE);
            if (fourDView == null) {
                fourDView = new FourDView(this);
                binding.stage.addView(fourDView, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            fourDView.setVisibility(View.VISIBLE);
            fourDView.setMotion(motion);
            if (resumed) fourDView.onResume();
            buildPreview();
        } else {
            updateTool();
            if (session != null) binding.maskView.setVisibility(View.VISIBLE);
            binding.progress.setVisibility(session == null ? View.VISIBLE : View.GONE);
            if (fourDView != null) {
                fourDView.onPause();
                fourDView.setVisibility(View.GONE);
            }
        }
    }

    /** Builds the layers for the current masks off the main thread and shows them. */
    private void buildPreview() {
        FourDMaker.Session s = session;
        if (s == null || fourDView == null) return;
        if (previewLayers != null) {
            fourDView.setLayers(previewLayers);
            return;
        }
        binding.progress.setVisibility(View.VISIBLE);
        int gen = ++editGeneration;
        byte[][] masks = s.snapshot();
        float[] m = motion.clone();
        worker.execute(() -> {
            Bitmap[] built = FourDMaker.buildLayers(s, masks, m);
            runOnUiThread(() -> {
                if (isDestroyed() || gen != editGeneration) return;
                binding.progress.setVisibility(View.GONE);
                previewLayers = built;
                if (fourDView != null) fourDView.setLayers(built);
            });
        });
    }

    // --- Save --------------------------------------------------------------------------------

    private void save() {
        FourDMaker.Session s = session;
        if (s == null || saving) return;
        if (!dirty) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, path));
            finish();
            return;
        }
        saving = true;
        binding.btnSave.setEnabled(false);
        binding.progress.setVisibility(View.VISIBLE);
        Context app = getApplicationContext();
        float[] m = motion.clone();
        byte[][] masks = s.snapshot();
        String old = path;
        worker.execute(() -> {
            String saved;
            try {
                saved = FourDMaker.save(app, s, masks, old, m);
            } catch (Exception | OutOfMemoryError e) {
                android.util.Log.w("FourDEditor", "save failed", e);
                saved = null;
            }
            String result = saved;
            runOnUiThread(() -> {
                Toast.makeText(app, result != null ? R.string.fourd_saved : R.string.fourd_save_failed,
                        Toast.LENGTH_SHORT).show();
                if (isDestroyed()) return;
                saving = false;
                binding.progress.setVisibility(View.GONE);
                binding.btnSave.setEnabled(true);
                if (result == null) return;
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, result));
                finish();
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (previewing && fourDView != null) fourDView.onResume();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (fourDView != null) fourDView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
