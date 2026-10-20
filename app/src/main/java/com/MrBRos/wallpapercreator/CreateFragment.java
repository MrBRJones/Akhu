package com.MrBRos.wallpapercreator;

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

import com.MrBRos.wallpapercreator.databinding.DialogUploadBinding;
import com.MrBRos.wallpapercreator.databinding.FragmentCreateBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Create tab: 4D or motion mode (THEME opens the theme editor); pick/preview, save, share, set, upload. */
public class CreateFragment extends Fragment {

    private static final int PREVIEW_DIM = 2048;
    private static final int WALLPAPER_DIM = 4096;
    private static final int MODE_FOURD = 0;
    private static final int MODE_MOTION = 1;
    private static final String STATE_MODE = "mode";

    private FragmentCreateBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private File currentImage;
    private Bitmap currentPreview;
    private boolean busy;
    private int mode = MODE_FOURD;
    @Nullable
    private String pendingPath;

    private final ActivityResultLauncher<Intent> pickLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) {
                    onImagePicked(result.getData().getData());
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentCreateBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.previewCard.setOnClickListener(v -> pick());
        binding.btnPrimary.setOnClickListener(v -> {
            if (mode == MODE_MOTION) applyLiveWallpaper();
            else makeFourD();
        });
        binding.btnDownload.setOnClickListener(v -> saveCurrent());
        binding.btnUpload.setOnClickListener(v -> showUploadDialog());
        binding.btnSetStatic.setOnClickListener(v -> setStaticCurrent());
        binding.btnMore.setOnClickListener(this::showMoreMenu);
        binding.modeFourD.setOnClickListener(v -> setMode(MODE_FOURD));
        binding.modeMotion.setOnClickListener(v -> setMode(MODE_MOTION));
        // Themes have their own full-screen editor, so THEME opens it rather than switching modes.
        binding.modeTheme.setOnClickListener(v -> ThemesFragment.createTheme(requireContext()));
        if (savedInstanceState != null) mode = savedInstanceState.getInt(STATE_MODE, MODE_FOURD);
        setMode(mode);
        if (currentPreview != null) {
            showPreview();
        }
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
    }

    /** Switches the screen between 4D and motion: highlights the tab and swaps the main button. */
    private void setMode(int newMode) {
        mode = newMode;
        binding.modeFourD.setSelected(mode == MODE_FOURD);
        binding.modeMotion.setSelected(mode == MODE_MOTION);
        binding.btnPrimary.setText(mode == MODE_MOTION ? R.string.set_live : R.string.make_fourd);
        binding.btnPrimary.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(),
                        mode == MODE_MOTION ? R.color.brand_primary : R.color.brand_secondary)));
        if (currentPreview == null) {
            setStatus(mode == MODE_MOTION ? R.string.mode_motion_hint : R.string.mode_fourd_hint);
        }
    }

    /** Less-used actions, kept out of sight so the screen stays clean. */
    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.pick_image);
        menu.getMenu().add(0, 3, 2, R.string.share);
        if (mode == MODE_MOTION) menu.getMenu().add(0, 4, 3, R.string.motion_settings);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: pick(); break;
                case 3: shareCurrent(); break;
                case 4: startActivity(new Intent(requireContext(), LiveWallpaperSettingsActivity.class)); break;
            }
            return true;
        });
        menu.show();
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
                showPreview();
            });
        });
    }

    private void showPreview() {
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
        android.content.Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            Bitmap full = ImagePickerHelper.decodeSampled(source, WALLPAPER_DIM);
            int msg = R.string.save_failed;
            if (full != null) {
                try {
                    ImageExporter.saveToGallery(app, full);
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

    private void shareCurrent() {
        if (!requireImage()) return;
        startActivity(ImageExporter.createShareIntent(requireContext(), currentImage));
    }

    private void setStaticCurrent() {
        if (!requireImage() || busy) return;
        busy = true;
        setStatus(R.string.setting_wallpaper);
        File source = currentImage;
        android.content.Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            Bitmap full = ImagePickerHelper.decodeSampled(source, WALLPAPER_DIM);
            int msg = R.string.wallpaper_set_failed;
            if (full != null) {
                try {
                    WallpaperHelper.setStaticWallpaper(app, full);
                    msg = R.string.wallpaper_set;
                } catch (IOException ignored) {
                } finally {
                    full.recycle();
                }
            }
            int finalMsg = msg;
            runOnUi(() -> {
                busy = false;
                setStatus(finalMsg);
                toast(finalMsg);
            });
        });
    }

    /** Seeds current_wallpaper_path with a persistent copy, then opens the system live wallpaper picker. */
    private void applyLiveWallpaper() {
        File source = currentImage;
        if (source == null) {
            String latest = ImageExporter.getLatestPath(requireContext());
            if (latest == null) {
                toast(R.string.pick_first);
                return;
            }
            source = new File(latest);
        }
        applyLive(requireActivity(), source);
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

    // --- Upload ------------------------------------------------------------------------------

    public void showUploadDialog() {
        if (!FirebaseHelper.isAvailable(requireContext())) {
            toast(R.string.cloud_disabled);
            return;
        }
        if (!requireImage()) return;
        if (FirebaseHelper.currentUser(requireContext()) == null) {
            toast(R.string.sign_in_required);
            startActivity(new Intent(requireContext(), AuthActivity.class));
            return;
        }
        DialogUploadBinding dialog = DialogUploadBinding.inflate(getLayoutInflater());
        for (int i = 0; i < Categories.IDS.length; i++) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.view_category_chip, dialog.categoryChips, false);
            chip.setId(View.generateViewId());
            chip.setText(Categories.LABELS[i]);
            chip.setTag(Categories.IDS[i]);
            dialog.categoryChips.addView(chip);
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.upload_title)
                .setView(dialog.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.upload, (d, w) -> {
                    String title = dialog.inputTitle.getText() == null ? "" : dialog.inputTitle.getText().toString().trim();
                    if (title.isEmpty()) title = getString(R.string.untitled);
                    View checked = dialog.categoryChips.findViewById(dialog.categoryChips.getCheckedChipId());
                    String category = checked == null ? null : (String) checked.getTag();
                    startUpload(title, dialog.switchPublic.isChecked(), category);
                })
                .show();
    }

    private void startUpload(String title, boolean isPublic, @Nullable String category) {
        busy = true;
        binding.uploadProgress.setVisibility(View.VISIBLE);
        binding.uploadProgress.setProgressCompat(0, false);
        setStatus(R.string.uploading);
        FirebaseHelper.uploadWallpaper(requireContext(), currentImage, title, isPublic, category,
                new FirebaseHelper.UploadCallback() {
                    @Override
                    public void onProgress(int percent) {
                        if (binding != null) binding.uploadProgress.setProgressCompat(percent, true);
                    }

                    @Override
                    public void onSuccess(String docId) {
                        busy = false;
                        if (binding == null) return;
                        binding.uploadProgress.setVisibility(View.GONE);
                        setStatus(R.string.upload_done);
                        toast(R.string.upload_done);
                    }

                    @Override
                    public void onError(Exception e) {
                        busy = false;
                        if (binding == null) return;
                        binding.uploadProgress.setVisibility(View.GONE);
                        setStatus(R.string.upload_failed);
                        Toast.makeText(requireContext(),
                                getString(R.string.upload_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
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
