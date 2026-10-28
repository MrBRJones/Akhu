package com.MrBRos.akhu;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.MrBRos.akhu.databinding.FragmentCreateBinding;
import com.MrBRos.akhu.databinding.ItemFilterBinding;
import com.MrBRos.akhu.databinding.SheetFiltersBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Create tab: 2D, 3D or motion mode (THEME opens the theme editor); pick, preview and create. */
public class CreateFragment extends Fragment {

    private static final int PREVIEW_DIM = 2048;
    private static final int WALLPAPER_DIM = 4096;
    private static final int MODE_FOURD = 0;
    private static final int MODE_MOTION = 1;
    private static final int MODE_2D = 2;
    private static final String STATE_MODE = "mode";
    private static final String STATE_NAMES = "names";

    private FragmentCreateBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private File currentImage;
    private Bitmap currentPreview;
    // The picture as loaded, before any filter, so filters never stack.
    private File originalImage;
    private Bitmap originalPreview;
    private int filterIndex = Filters.ORIGINAL;
    // MOTION mode works on its own video or GIF, kept apart from the 2D/3D picture.
    @Nullable
    private File motionFile;
    private boolean motionIsGif;
    private boolean busy;
    private int mode = MODE_FOURD;
    /** The Name box text for each mode (indexed by MODE_*), so each creation keeps its own name. */
    private String[] names = {"", "", ""};
    @Nullable
    private String pendingPath;

    private final ActivityResultLauncher<Intent> pickLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) {
                    onImagePicked(result.getData().getData());
                }
            });

    private final ActivityResultLauncher<Intent> motionPickLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) {
                    onMotionPicked(result.getData().getData());
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentCreateBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.previewCard.setOnClickListener(v -> {
            if (mode == MODE_MOTION) pickMotion();
            else pick();
        });
        binding.btnPrimary.setOnClickListener(v -> {
            if (mode == MODE_MOTION) openMotionEditor();
            else if (mode == MODE_2D) showFilters();
            else makeFourD();
        });
        binding.mode2D.setOnClickListener(v -> setMode(MODE_2D));
        binding.modeFourD.setOnClickListener(v -> setMode(MODE_FOURD));
        binding.modeMotion.setOnClickListener(v -> setMode(MODE_MOTION));
        // Themes have their own full-screen editor, so THEME opens it rather than switching modes.
        binding.modeTheme.setOnClickListener(v -> ThemesFragment.createTheme(requireContext()));
        setUpModeSwipe();
        if (savedInstanceState != null) {
            mode = savedInstanceState.getInt(STATE_MODE, MODE_FOURD);
            String[] saved = savedInstanceState.getStringArray(STATE_NAMES);
            if (saved != null && saved.length == names.length) names = saved;
        }
        binding.nameInput.setText(names[mode]);
        binding.nameInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                names[mode] = s.toString();
            }
        });
        binding.nameInput.setOnEditorActionListener((v, action, e) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) v.clearFocus();
            return false;
        });
        setMode(mode);
        if (pendingPath != null) {
            String p = pendingPath;
            pendingPath = null;
            loadImage(p);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_MODE, mode);
        outState.putStringArray(STATE_NAMES, names);
    }

    /** The Name box for the current mode, or null when it's empty. */
    @Nullable
    private String currentName() {
        String n = names[mode].trim();
        return n.isEmpty() ? null : n;
    }

    /** Switches the screen between 2D, 3D and motion: highlights the tab and swaps the main button. */
    private void setMode(int newMode) {
        mode = newMode;
        // Show this mode's own name (the text watcher then writes the same text back).
        if (!names[mode].contentEquals(binding.nameInput.getText() == null ? "" : binding.nameInput.getText())) {
            binding.nameInput.setText(names[mode]);
        }
        binding.mode2D.setSelected(mode == MODE_2D);
        binding.modeFourD.setSelected(mode == MODE_FOURD);
        binding.modeMotion.setSelected(mode == MODE_MOTION);
        // Same label in every mode; the colour and the hint above it say what Create will do.
        binding.btnPrimary.setText(R.string.create_action);
        binding.btnPrimary.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(),
                        mode == MODE_MOTION ? R.color.brand_primary
                                : mode == MODE_2D ? R.color.brand_2d : R.color.brand_secondary)));
        refreshCard();
    }

    /** Shows what the current mode works on: the picture for 2D/3D, the video or GIF for MOTION. */
    private void refreshCard() {
        com.bumptech.glide.Glide.with(this).clear(binding.preview);
        if (mode == MODE_MOTION && motionFile != null) {
            showMotionPreview();
            return;
        }
        if (mode != MODE_MOTION && currentPreview != null) {
            showPreview();
            return;
        }
        binding.preview.setImageResource(R.drawable.ic_add_photo);
        binding.preview.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        binding.previewHint.setVisibility(View.VISIBLE);
        binding.previewHint.setText(mode == MODE_MOTION ? R.string.upload_video_gif : R.string.tap_to_pick);
        setStatus(mode == MODE_MOTION ? R.string.mode_motion_hint
                : mode == MODE_2D ? R.string.mode_2d_hint : R.string.mode_fourd_hint);
    }

    /** Left/right swipes step through the modes in strip order; THEME stays tap-only as it opens another screen. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setUpModeSwipe() {
        int[] order = {MODE_2D, MODE_FOURD, MODE_MOTION};
        float minDistance = 60 * getResources().getDisplayMetrics().density;
        android.view.GestureDetector detector = new android.view.GestureDetector(requireContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(@NonNull android.view.MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onFling(@Nullable android.view.MotionEvent e1, @NonNull android.view.MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || busy) return false;
                        float dx = e2.getX() - e1.getX();
                        if (Math.abs(dx) < minDistance || Math.abs(dx) < Math.abs(e2.getY() - e1.getY())) return false;
                        int at = 0;
                        for (int i = 0; i < order.length; i++) if (order[i] == mode) at = i;
                        int next = at + (dx < 0 ? 1 : -1);
                        if (next < 0 || next >= order.length) return false;
                        setMode(order[next]);
                        return true;
                    }
                });
        // On the preview a fling is eaten so it doesn't also open the picker; elsewhere taps fall through as usual.
        binding.previewCard.setOnTouchListener((v, e) -> {
            if (!detector.onTouchEvent(e) || e.getActionMasked() != android.view.MotionEvent.ACTION_UP) return false;
            android.view.MotionEvent cancel = android.view.MotionEvent.obtain(e);
            cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
            v.onTouchEvent(cancel);
            cancel.recycle();
            return true;
        });
        binding.getRoot().setOnTouchListener((v, e) -> detector.onTouchEvent(e));
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    public void pick() {
        pickLauncher.launch(ImagePickerHelper.createPickIntent());
    }

    /** Loads an existing file (e.g. a saved wallpaper tapped in the grid). */
    public void loadImage(String path) {
        if (binding == null) {
            pendingPath = path;
            return;
        }
        File file = new File(path);
        io.execute(() -> {
            Bitmap bmp = ImagePickerHelper.decodeSampled(file, PREVIEW_DIM);
            runOnUi(() -> {
                if (bmp == null) {
                    toast(R.string.pick_failed);
                    return;
                }
                currentImage = file;
                currentPreview = bmp;
                originalImage = file;
                originalPreview = bmp;
                filterIndex = Filters.ORIGINAL;
                showPreview();
            });
        });
    }

    private void showPreview() {
        if (mode == MODE_MOTION) return;
        binding.preview.setImageBitmap(currentPreview);
        binding.preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        binding.previewHint.setVisibility(View.GONE);
        setStatus(R.string.image_ready);
    }

    private void onImagePicked(Uri uri) {
        setStatus(R.string.loading);
        io.execute(() -> {
            try {
                File file = ImagePickerHelper.copyToCache(requireContext(), uri);
                runOnUi(() -> loadImage(file.getAbsolutePath()));
            } catch (NsfwDetector.Rejected e) {
                runOnUi(() -> {
                    setStatus(currentImage != null ? R.string.image_ready : R.string.pick_first);
                    toast(R.string.nsfw_photo_rejected);
                });
            } catch (IOException e) {
                runOnUi(() -> toast(R.string.pick_failed));
            }
        });
    }

    private boolean requireImage() {
        if (currentImage == null || currentPreview == null) {
            toast(R.string.pick_first);
            return false;
        }
        return true;
    }

    private void saveCurrent() {
        if (!requireImage() || busy) return;
        busy = true;
        File source = currentImage;
        String name = currentName();
        android.content.Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            Bitmap full = ImagePickerHelper.decodeSampled(source, WALLPAPER_DIM);
            int msg = R.string.save_failed;
            if (full != null) {
                try {
                    ImageExporter.saveToGallery(app, full, name);
                    ImageExporter.saveLocalCopy(app, full);
                    msg = R.string.saved_to_gallery;
                } catch (IOException ignored) {
                } finally {
                    full.recycle();
                }
            }
            int finalMsg = msg;
            runOnUi(() -> {
                busy = false;
                toast(finalMsg);
            });
        });
    }

    /** Cuts the picture into 4D layers on the phone, then opens it in the tilt preview. */
    private void makeFourD() {
        if (!requireImage() || busy) return;
        busy = true;
        binding.btnPrimary.setEnabled(false);
        File source = currentImage;
        android.content.Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            String made = null;
            int msg = R.string.fourd_made_short;
            try {
                made = FourDMaker.make(app, source, step -> runOnUi(() -> setStatus(step)));
            } catch (FourDMaker.MakeException e) {
                android.util.Log.w("FourDMaker", "make failed", e);
                msg = e.messageRes;
            }
            String finalMade = made;
            int finalMsg = msg;
            runOnUi(() -> {
                busy = false;
                binding.btnPrimary.setEnabled(true);
                setStatus(finalMsg);
                toast(finalMsg);
                if (finalMade == null) return;
                // Straight into the editor to fix the cut-out; the first version is already saved.
                lastMadeFourD = finalMade;
                editFourD.launch(FourDEditorActivity.intent(requireContext(), finalMade));
            });
        });
    }

    @Nullable
    private String lastMadeFourD;

    /** After the editor (saved or not), shows the 4D image with live tilt. */
    private final ActivityResultLauncher<Intent> editFourD = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                String path = lastMadeFourD;
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    String saved = result.getData().getStringExtra(FourDEditorActivity.EXTRA_PATH);
                    if (saved != null) path = saved;
                }
                if (path == null || getContext() == null) return;
                java.util.List<String> all = FourDWallpapers.list(requireContext());
                int index = all.indexOf(path);
                if (index < 0) return;
                java.util.ArrayList<String> sources = new java.util.ArrayList<>();
                for (String s : all) sources.add(PreviewActivity.FOURD_PREFIX + s);
                startActivity(PreviewActivity.intent(requireContext(), sources, index));
            });

    /** 2D Create: a sheet with the picture in every filter; Save bakes the chosen one in and saves it. */
    private void showFilters() {
        if (!requireImage() || busy) return;
        SheetFiltersBinding sheet = SheetFiltersBinding.inflate(getLayoutInflater());
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        dialog.setContentView(sheet.getRoot());
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);

        Bitmap big = scaleDown(originalPreview, 900);
        Bitmap small = scaleDown(originalPreview, 200);
        int[] selected = {filterIndex};
        ItemFilterBinding[] items = new ItemFilterBinding[Filters.NAMES.length];
        int stroke = Math.round(3 * getResources().getDisplayMetrics().density);
        Runnable refresh = () -> {
            sheet.filterPreview.setImageBitmap(Filters.apply(big, selected[0]));
            for (int i = 0; i < items.length; i++) items[i].filterCard.setStrokeWidth(i == selected[0] ? stroke : 0);
        };
        for (int i = 0; i < Filters.NAMES.length; i++) {
            ItemFilterBinding item = ItemFilterBinding.inflate(getLayoutInflater(), sheet.filterRow, false);
            item.filterThumb.setImageBitmap(Filters.apply(small, i));
            item.filterName.setText(Filters.NAMES[i]);
            int index = i;
            item.getRoot().setOnClickListener(v -> {
                selected[0] = index;
                refresh.run();
            });
            items[i] = item;
            sheet.filterRow.addView(item.getRoot());
        }
        refresh.run();
        sheet.btnApplyFilter.setOnClickListener(v -> {
            dialog.dismiss();
            applyFilter(selected[0], this::saveCurrent);
        });
        dialog.show();
    }

    private static Bitmap scaleDown(Bitmap src, int maxDim) {
        float scale = Math.min(1f, maxDim / (float) Math.max(src.getWidth(), src.getHeight()));
        if (scale >= 1f) return src;
        return Bitmap.createScaledBitmap(src, Math.round(src.getWidth() * scale),
                Math.round(src.getHeight() * scale), true);
    }

    /** Bakes filter {@code index} into the picture at full size, then runs {@code then}. */
    private void applyFilter(int index, Runnable then) {
        if (index == Filters.ORIGINAL) {
            filterIndex = index;
            currentImage = originalImage;
            currentPreview = originalPreview;
            showPreview();
            then.run();
            return;
        }
        busy = true;
        setStatus(R.string.loading);
        File source = originalImage;
        Bitmap preview = originalPreview;
        File dir = new File(requireContext().getCacheDir(), "filtered");
        io.execute(() -> {
            File out = null;
            Bitmap full = ImagePickerHelper.decodeSampled(source, WALLPAPER_DIM);
            if (full != null) {
                Bitmap filtered = Filters.apply(full, index);
                full.recycle();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                dir.mkdirs();
                File file = new File(dir, "filtered_" + System.currentTimeMillis() + ".jpg");
                try (OutputStream os = new FileOutputStream(file)) {
                    filtered.compress(Bitmap.CompressFormat.JPEG, 95, os);
                    out = file;
                } catch (IOException ignored) {
                } finally {
                    filtered.recycle();
                }
            }
            Bitmap filteredPreview = out == null ? null : Filters.apply(preview, index);
            File finalOut = out;
            runOnUi(() -> {
                busy = false;
                if (finalOut == null) {
                    setStatus(R.string.filter_failed);
                    toast(R.string.filter_failed);
                    return;
                }
                filterIndex = index;
                currentImage = finalOut;
                currentPreview = filteredPreview;
                showPreview();
                setStatus(R.string.filter_applied);
                then.run();
            });
        });
    }

    /** MOTION only takes videos and GIFs; the picker is asked for just those. */
    private void pickMotion() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "image/gif"});
        motionPickLauncher.launch(intent);
    }

    private void onMotionPicked(Uri uri) {
        String type = requireContext().getContentResolver().getType(uri);
        boolean isGif = "image/gif".equals(type);
        if (!isGif && (type == null || !type.startsWith("video/"))) {
            toast(R.string.motion_only);
            return;
        }
        setStatus(R.string.loading);
        android.content.Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            try {
                File dir = new File(app.getCacheDir(), "picked_motion");
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
                File out = new File(dir, "motion_" + System.currentTimeMillis() + (isGif ? ".gif" : ".mp4"));
                try (InputStream in = app.getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(out)) {
                    if (in == null) throw new IOException("Cannot open " + uri);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                // Adult content never makes it into the app: scan before the clip is accepted.
                runOnUi(() -> setStatus(R.string.nsfw_scanning));
                if (isGif ? NsfwDetector.isGifNsfw(app, out) : NsfwDetector.isVideoNsfw(app, out)) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    runOnUi(() -> {
                        motionFile = null;
                        refreshCard();
                        toast(R.string.nsfw_rejected);
                    });
                    return;
                }
                runOnUi(() -> {
                    motionFile = out;
                    motionIsGif = isGif;
                    if (mode == MODE_MOTION) refreshCard();
                    else setMode(MODE_MOTION);
                });
            } catch (IOException e) {
                runOnUi(() -> toast(R.string.video_open_failed));
            }
        });
    }

    private void showMotionPreview() {
        // Glide animates a GIF and shows a still frame for a video.
        com.bumptech.glide.Glide.with(this).load(motionFile).centerCrop().into(binding.preview);
        binding.preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        binding.previewHint.setVisibility(View.GONE);
        setStatus(motionIsGif ? R.string.gif_ready : R.string.motion_ready);
    }

    /** Create in MOTION: the editor with trim, speed, filters and sound. */
    private void openMotionEditor() {
        if (motionFile == null) {
            pickMotion();
            return;
        }
        startActivity(VideoEditorActivity.intent(requireContext(), motionFile, motionIsGif, currentName()));
    }

    /** Shared with the Live tab and the full-screen preview. */
    static void applyLive(android.app.Activity activity, File source) {
        android.content.Context app = activity.getApplicationContext();
        new Thread(() -> {
            try {
                File dir = new File(app.getFilesDir(), "live");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
                File dest = new File(dir, "current.jpg");
                copyFile(source, dest);
                EffectConfig.prefs(app).edit()
                        .putString(EffectConfig.KEY_CURRENT_PATH, dest.getAbsolutePath())
                        .commit();
                activity.runOnUiThread(() -> {
                    LiveWallpaperService.requestReload(app);
                    if (activity.isFinishing()) return;
                    try {
                        activity.startActivity(WallpaperHelper.createLiveWallpaperIntent(app));
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(app, R.string.live_picker_missing, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, R.string.save_failed, Toast.LENGTH_SHORT).show());
            }
        }, "ApplyLive").start();
    }

    private static void copyFile(File src, File dest) throws IOException {
        if (src.getCanonicalPath().equals(dest.getCanonicalPath())) return;
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) throw new IOException("rename failed");
    }

    // --- Utils -------------------------------------------------------------------------------

    private void runOnUi(Runnable r) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (binding != null) r.run();
        });
    }

    private void setStatus(@StringRes int res) {
        if (binding != null) binding.status.setText(res);
    }

    private void toast(@StringRes int res) {
        if (getContext() != null) Toast.makeText(getContext(), res, Toast.LENGTH_SHORT).show();
    }
}
