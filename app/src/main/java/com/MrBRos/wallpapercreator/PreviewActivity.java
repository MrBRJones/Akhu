package com.MrBRos.wallpapercreator;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.Build;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.MrBRos.wallpapercreator.databinding.ActivityPreviewBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen, swipeable wallpaper preview. Images are center-cropped to the screen so they
 * look the way they will as a wallpaper. Tap toggles the overlay.
 *
 * <p>Sources are absolute file paths, or asset paths prefixed with {@link #ASSET_PREFIX}.</p>
 */
public class PreviewActivity extends AppCompatActivity {

    public static final String EXTRA_SOURCES = "sources";
    public static final String EXTRA_INDEX = "index";
    public static final String ASSET_PREFIX = "asset:";
    /** A bundled video asset, e.g. "video:motion/1.mp4". */
    public static final String VIDEO_PREFIX = "video:";
    /** A bundled 4D wallpaper image, e.g. "fourd:fourd/1.jpg"; previewed with live tilt. */
    public static final String FOURD_PREFIX = "fourd:";
    private FourDView fourDView;
    private static final int WALLPAPER_DIM = 4096;

    private ActivityPreviewBinding binding;
    private List<String> sources;
    private final android.util.SparseArray<CropVideoView> videoViews = new android.util.SparseArray<>();
    private boolean resumed;
    private boolean chromeVisible = true;
    private boolean busy;

    public static Intent intent(Context context, ArrayList<String> sources, int index) {
        return new Intent(context, PreviewActivity.class)
                .putStringArrayListExtra(EXTRA_SOURCES, sources)
                .putExtra(EXTRA_INDEX, index);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setSystemBarsVisible(false);

        sources = getIntent().getStringArrayListExtra(EXTRA_SOURCES);
        if (sources == null || sources.isEmpty()) {
            finish();
            return;
        }
        binding.pager.setAdapter(new PageAdapter());
        binding.pager.setCurrentItem(getIntent().getIntExtra(EXTRA_INDEX, 0), false);
        binding.pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateCounter();
                updateActions();
                syncPlayback();
            }
        });
        updateCounter();
        updateActions();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnSet.setOnClickListener(v -> {
            if (isVideo(currentSource())) {
                MotionWallpapers.apply(this, currentSource().substring(VIDEO_PREFIX.length()));
            } else if (isFourD(currentSource())) {
                FourDWallpapers.apply(this, currentSource().substring(FOURD_PREFIX.length()));
            } else {
                chooseTarget();
            }
        });
        binding.btnLive.setOnClickListener(v -> withFile(file -> CreateFragment.applyLive(this, file)));
        binding.btnEdit.setOnClickListener(v -> {
            String source = currentSource();
            if (isFourD(source) && FourDWallpapers.isUser(source.substring(FOURD_PREFIX.length()))) {
                editFourD.launch(FourDEditorActivity.intent(this, source.substring(FOURD_PREFIX.length())));
            } else {
                openInCreate();
            }
        });
        binding.btnSave.setOnClickListener(v -> {
            if (isVideo(currentSource())) saveVideo();
            else save();
        });
        binding.btnShare.setOnClickListener(v -> withFile(file ->
                startActivity(isVideo(currentSource())
                        ? ImageExporter.createVideoShareIntent(this, file)
                        : ImageExporter.createShareIntent(this, file))));
    }

    private void openInCreate() {
        withFile(file -> {
            startActivity(new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_OPEN_IN_CREATE)
                    .putExtra(MainActivity.EXTRA_PATH, file.getAbsolutePath())
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
    }

    /** A saved 4D edit is a new file: reopen on the 4D list with it in place of the old one. */
    private final androidx.activity.result.ActivityResultLauncher<Intent> editFourD = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                String saved = result.getData().getStringExtra(FourDEditorActivity.EXTRA_PATH);
                if (saved == null) return;
                java.util.List<String> all = FourDWallpapers.list(this);
                ArrayList<String> fresh = new ArrayList<>();
                for (String s : all) fresh.add(FOURD_PREFIX + s);
                startActivity(intent(this, fresh, Math.max(0, all.indexOf(saved))));
                finish();
            });

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        syncPlayback();
    }

    @Override
    protected void onPause() {
        resumed = false;
        syncPlayback();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (fourDView != null) fourDView.onPause();
        for (int i = 0; i < videoViews.size(); i++) videoViews.valueAt(i).release();
        videoViews.clear();
        super.onDestroy();
    }

    static boolean isVideo(String source) {
        return source.startsWith(VIDEO_PREFIX);
    }

    static boolean isFourD(String source) {
        return source.startsWith(FOURD_PREFIX);
    }

    /** Asset path used as the {@link Focal} key, or null for the user's own files. */
    @androidx.annotation.Nullable
    static String assetKey(String source) {
        if (source.startsWith(ASSET_PREFIX)) return source.substring(ASSET_PREFIX.length());
        if (source.startsWith(VIDEO_PREFIX)) return source.substring(VIDEO_PREFIX.length());
        if (source.startsWith(FOURD_PREFIX)) return source.substring(FOURD_PREFIX.length());
        return null;
    }

    /** Photo-only actions (motion effects, edit) are hidden on video and 4D pages. */
    private void updateActions() {
        String source = currentSource();
        int photoOnly = isVideo(source) || isFourD(source) ? View.GONE : View.VISIBLE;
        binding.btnLive.setVisibility(photoOnly);
        // The user's own 4D images open in the cut-out editor.
        boolean ownFourD = isFourD(source) && FourDWallpapers.isUser(source.substring(FOURD_PREFIX.length()));
        binding.btnEdit.setVisibility(ownFourD ? View.VISIBLE : photoOnly);
    }

    /** Only the visible page plays, and only while the activity is in front. */
    private void syncPlayback() {
        int current = binding.pager.getCurrentItem();
        for (int i = 0; i < videoViews.size(); i++) {
            if (resumed && videoViews.keyAt(i) == current) videoViews.valueAt(i).play();
            else videoViews.valueAt(i).pause();
        }
        syncFourD();
    }

    /**
     * A single {@link FourDView} is moved onto whichever page is showing a 4D wallpaper, so only
     * one GL surface and tilt listener exist at a time.
     */
    private void syncFourD() {
        String source = currentSource();
        android.widget.FrameLayout current = currentPage();
        FitFrame page = current == null ? null : content(current);
        boolean want = resumed && isFourD(source) && page != null;
        if (!want) {
            if (fourDView != null) {
                fourDView.onPause();
                if (fourDView.getParent() != null) ((ViewGroup) fourDView.getParent()).removeView(fourDView);
            }
            return;
        }
        if (fourDView == null) fourDView = new FourDView(this);
        if (fourDView.getParent() != page) {
            if (fourDView.getParent() != null) ((ViewGroup) fourDView.getParent()).removeView(fourDView);
            page.addView(fourDView, 1, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        fourDView.setAsset(source.substring(FOURD_PREFIX.length()));
        fourDView.onResume();
    }

    @androidx.annotation.Nullable
    private android.widget.FrameLayout currentPage() {
        RecyclerView rv = (RecyclerView) binding.pager.getChildAt(0);
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(binding.pager.getCurrentItem());
        return vh == null ? null : (android.widget.FrameLayout) vh.itemView;
    }

    private void saveVideo() {
        if (busy) return;
        withFile(file -> {
            busy = true;
            binding.progress.setVisibility(View.VISIBLE);
            Context app = getApplicationContext();
            new Thread(() -> {
                int msg;
                try {
                    ImageExporter.saveVideoToGallery(app, file, null);
                    msg = R.string.saved_to_gallery;
                } catch (IOException e) {
                    msg = R.string.save_failed;
                }
                int finalMsg = msg;
                runOnUiThread(() -> {
                    busy = false;
                    binding.progress.setVisibility(View.GONE);
                    toast(finalMsg);
                });
            }, "PreviewSaveVideo").start();
        });
    }

    private void updateCounter() {
        binding.counter.setText(getString(R.string.preview_counter,
                binding.pager.getCurrentItem() + 1, sources.size()));
        binding.counter.setVisibility(sources.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        float alpha = chromeVisible ? 1f : 0f;
        for (View v : new View[]{binding.topBar, binding.bottomBar}) {
            v.animate().alpha(alpha).setDuration(180).withEndAction(() ->
                    v.setVisibility(chromeVisible ? View.VISIBLE : View.INVISIBLE)).start();
            if (chromeVisible) v.setVisibility(View.VISIBLE);
        }
    }

    private void setSystemBarsVisible(boolean visible) {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (visible) c.show(WindowInsetsCompat.Type.systemBars());
        else c.hide(WindowInsetsCompat.Type.systemBars());
    }

    private String currentSource() {
        return sources.get(binding.pager.getCurrentItem());
    }

    /** Glide model for a source string. */
    static Object model(String source) {
        if (source.startsWith(ASSET_PREFIX)) {
            return Uri.parse("file:///android_asset/" + source.substring(ASSET_PREFIX.length()));
        }
        if (source.startsWith(FOURD_PREFIX)) {
            return FourDWallpapers.uri(source.substring(FOURD_PREFIX.length()));
        }
        return new File(source);
    }

    /** Resolves the current page to a real file (assets are copied to cache) off the main thread. */
    private void withFile(java.util.function.Consumer<File> action) {
        String source = currentSource();
        String asset = source.startsWith(ASSET_PREFIX) ? source.substring(ASSET_PREFIX.length())
                : isVideo(source) ? source.substring(VIDEO_PREFIX.length())
                : isFourD(source) ? source.substring(FOURD_PREFIX.length()) : null;
        if (asset == null || FourDWallpapers.isUser(asset)) {
            // The user's own 4D images are already files.
            action.accept(new File(asset != null ? asset : source));
            return;
        }
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                File f = FeaturedWallpapers.copyToCache(app, asset);
                runOnUiThread(() -> {
                    if (!isFinishing()) action.accept(f);
                });
            } catch (IOException e) {
                runOnUiThread(() -> toast(R.string.pick_failed));
            }
        }, "PreviewResolve").start();
    }

    // --- Actions -------------------------------------------------------------------------------

    private void chooseTarget() {
        // Dark rounded card with icons, matching the system dialog used for motion wallpapers.
        android.app.Dialog dialog = new android.app.Dialog(this);
        com.MrBRos.wallpapercreator.databinding.DialogSetTargetBinding b =
                com.MrBRos.wallpapercreator.databinding.DialogSetTargetBinding.inflate(getLayoutInflater());
        dialog.setContentView(b.getRoot());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels * 9 / 10,
                    Math.round(420 * getResources().getDisplayMetrics().density));
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        View.OnClickListener pick = v -> {
            int which = v == b.optionHome ? WallpaperManager.FLAG_SYSTEM
                    : v == b.optionLock ? WallpaperManager.FLAG_LOCK
                    : WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
            dialog.dismiss();
            String key = assetKey(currentSource());
            float[] focal = key == null ? null : Focal.get(this, key);
            withFile(file -> setWallpaper(file, which, focal));
        };
        b.optionHome.setOnClickListener(pick);
        b.optionLock.setOnClickListener(pick);
        b.optionBoth.setOnClickListener(pick);
        dialog.show();
    }

    private void setWallpaper(File file, int which, @androidx.annotation.Nullable float[] focal) {
        if (busy) return;
        busy = true;
        binding.progress.setVisibility(View.VISIBLE);
        Context app = getApplicationContext();
        new Thread(() -> {
            int msg = R.string.wallpaper_set_failed;
            Bitmap bmp = ImagePickerHelper.decodeSampled(file, WALLPAPER_DIM);
            if (bmp != null) {
                try {
                    WallpaperHelper.setStaticWallpaper(app, bmp, which, focal);
                    msg = R.string.wallpaper_set;
                } catch (IOException ignored) {
                } finally {
                    bmp.recycle();
                }
            }
            int finalMsg = msg;
            runOnUiThread(() -> {
                busy = false;
                binding.progress.setVisibility(View.GONE);
                toast(finalMsg);
            });
        }, "PreviewSetWallpaper").start();
    }

    private void save() {
        if (busy) return;
        withFile(file -> {
            busy = true;
            binding.progress.setVisibility(View.VISIBLE);
            Context app = getApplicationContext();
            new Thread(() -> {
                int msg = R.string.save_failed;
                Bitmap bmp = ImagePickerHelper.decodeSampled(file, WALLPAPER_DIM);
                if (bmp != null) {
                    try {
                        ImageExporter.saveToGallery(app, bmp, null);
                        if (currentSource().startsWith(ASSET_PREFIX)) ImageExporter.saveLocalCopy(app, bmp);
                        msg = R.string.saved_to_gallery;
                    } catch (IOException ignored) {
                    } finally {
                        bmp.recycle();
                    }
                }
                int finalMsg = msg;
                runOnUiThread(() -> {
                    busy = false;
                    binding.progress.setVisibility(View.GONE);
                    toast(finalMsg);
                });
            }, "PreviewSave").start();
        });
    }

    private void toast(@StringRes int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    // --- Pages ---------------------------------------------------------------------------------

    /** The {@link FitFrame} holding a page's image, video or 4D view. */
    private static FitFrame content(android.widget.FrameLayout page) {
        return (FitFrame) page.getChildAt(1);
    }

    /** Width / height of a source's image (the poster for videos), or 0 if unknown. */
    private float aspectOf(String source) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (java.io.InputStream in = source.startsWith(VIDEO_PREFIX)
                ? getAssets().open(MotionWallpapers.posterFor(source.substring(VIDEO_PREFIX.length())))
                : source.startsWith(FOURD_PREFIX) ? FourDWallpapers.open(this, assetKey(source))
                : source.startsWith(ASSET_PREFIX)
                ? getAssets().open(assetKey(source))
                : new java.io.FileInputStream(source)) {
            android.graphics.BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return 0f;
        }
        return o.outWidth > 0 && o.outHeight > 0 ? (float) o.outWidth / o.outHeight : 0f;
    }

    /**
     * Each page is a FrameLayout with a blurred backdrop (shown only when the screen is wider than
     * tall) under a {@link FitFrame}. The frame holds a center-cropped image (the poster frame for
     * videos); video pages add a {@link CropVideoView} on top, which stays transparent until it has
     * frames.
     */
    private class PageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            android.widget.FrameLayout page = new android.widget.FrameLayout(ctx);
            page.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView backdrop = new ImageView(ctx);
            backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
            backdrop.setColorFilter(android.graphics.Color.argb(110, 0, 0, 0));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        60f, 60f, android.graphics.Shader.TileMode.CLAMP));
            }
            page.addView(backdrop, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FitFrame frame = new FitFrame(ctx);
            page.addView(frame, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER));
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setContentDescription(getString(R.string.preview));
            frame.addView(iv, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            page.setOnClickListener(v -> toggleChrome());
            return new RecyclerView.ViewHolder(page) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            android.widget.FrameLayout page = (android.widget.FrameLayout) holder.itemView;
            FitFrame frame = content(page);
            releaseVideo(frame);
            String source = sources.get(position);
            Object image = isVideo(source)
                    ? Uri.parse("file:///android_asset/"
                    + MotionWallpapers.posterFor(source.substring(VIDEO_PREFIX.length())))
                    : model(source);
            float aspect = aspectOf(source);
            frame.setAspect(aspect);
            ImageView backdrop = (ImageView) page.getChildAt(0);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            if (FitFrame.fits(dm.widthPixels, dm.heightPixels, aspect)) {
                backdrop.setVisibility(View.VISIBLE);
                // A small decode is enough under the blur (and is the blur itself before Android 12).
                Glide.with(page).load(image).override(96).into(backdrop);
            } else {
                backdrop.setVisibility(View.GONE);
                Glide.with(page).clear(backdrop);
            }
            float[] focal = Focal.get(page.getContext(), assetKey(source));
            Glide.with(page).load(image).transform(new FocalCrop(focal[0], focal[1]))
                    .into((ImageView) frame.getChildAt(0));
            if (isVideo(source)) {
                CropVideoView video = new CropVideoView(page.getContext(), source.substring(VIDEO_PREFIX.length()));
                video.setOpaque(false);
                frame.addView(video, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                frame.setTag(position);
                videoViews.put(position, video);
                if (resumed && position == binding.pager.getCurrentItem()) video.play();
            } else if (isFourD(source) && position == binding.pager.getCurrentItem()) {
                // The page isn't attached yet; attach the 4D view once layout settles.
                page.post(PreviewActivity.this::syncFourD);
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            releaseVideo(content((android.widget.FrameLayout) holder.itemView));
        }

        /** Strips anything layered over the base image: a video player or the shared 4D view. */
        private void releaseVideo(FitFrame page) {
            for (int i = page.getChildCount() - 1; i >= 1; i--) {
                View child = page.getChildAt(i);
                if (child instanceof CropVideoView) {
                    CropVideoView video = (CropVideoView) child;
                    video.release();
                    Object tag = page.getTag();
                    if (tag instanceof Integer && videoViews.get((Integer) tag) == video) {
                        videoViews.remove((Integer) tag);
                    }
                } else if (child == fourDView) {
                    fourDView.onPause();
                }
                page.removeViewAt(i);
            }
        }

        @Override
        public int getItemCount() {
            return sources.size();
        }
    }
}
