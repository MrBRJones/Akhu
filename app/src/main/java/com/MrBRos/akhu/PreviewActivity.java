package com.MrBRos.akhu;

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

import com.MrBRos.akhu.databinding.ActivityPreviewBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen, swipeable wallpaper preview. Images are center-cropped to the screen so they
 * look the way they will as a wallpaper. The actions sit on a right-hand rail like a cloud post;
 * tap toggles them.
 *
 * <p>Sources are absolute file paths, or asset paths prefixed with {@link #ASSET_PREFIX}.</p>
 */
public class PreviewActivity extends AppCompatActivity {

    public static final String EXTRA_SOURCES = "sources";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_LABELS = "labels";
    /** True when opened from the user's own Profile: their things get Edit (and Comments once posted). */
    public static final String EXTRA_OWN = "own";
    public static final String ASSET_PREFIX = "asset:";
    /** A bundled video asset, e.g. "video:motion/1.mp4". */
    public static final String VIDEO_PREFIX = "video:";
    /** A bundled 4D wallpaper image, e.g. "fourd:fourd/1.jpg"; previewed with live tilt. */
    public static final String FOURD_PREFIX = "fourd:";
    /** A reactive wallpaper, e.g. "react:react/1.png" or a user's absolute path; shown live. */
    public static final String REACT_PREFIX = "react:";
    private FourDView fourDView;
    /** One live view, moved onto whichever page shows a reactive wallpaper. */
    private ReactView reactView;
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

    /** As {@link #intent}, for the user's own things in their Profile. */
    public static Intent ownIntent(Context context, ArrayList<String> sources, int index) {
        return intent(context, sources, index).putExtra(EXTRA_OWN, true);
    }

    /** As {@link #intent}, with each page's name and tag shown over the Set button. */
    public static Intent intent(Context context, ArrayList<String> sources, int index, ArrayList<String> labels) {
        return intent(context, sources, index).putStringArrayListExtra(EXTRA_LABELS, labels);
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
                updateLike();
                updateActions();
                syncPlayback();
            }
        });
        updateLike();
        updateCounter();
        updateActions();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.avatar.setOnClickListener(v -> {
            String source = currentSource();
            int page = isFourD(source) ? AkhuProfileActivity.PAGE_3D : isVideo(source) ? AkhuProfileActivity.PAGE_MOTION
                    : isReact(source) ? AkhuProfileActivity.PAGE_REACT : 0;
            startActivity(AkhuProfileActivity.intent(this, page));
        });
        binding.btnLike.setOnClickListener(v -> {
            Likes.toggle(this, currentSource());
            updateLike();
        });
        binding.btnSet.setOnClickListener(v -> {
            if (isReact(currentSource())) {
                ReactWallpapers.apply(this, currentSource().substring(REACT_PREFIX.length()));
            } else if (isSavedVideo(currentSource())) {
                MotionWallpapers.applyFile(this, new File(currentSource()), null);
            } else if (isVideo(currentSource())) {
                MotionWallpapers.apply(this, currentSource().substring(VIDEO_PREFIX.length()));
            } else if (isFourD(currentSource())) {
                FourDWallpapers.apply(this, currentSource().substring(FOURD_PREFIX.length()));
            } else {
                chooseTarget();
            }
        });
        binding.btnLive.setOnClickListener(v -> withFile(file -> CreateFragment.applyLive(this, file)));
        binding.btnSave.setOnClickListener(v -> {
            if (plays(currentSource())) saveVideo();
            else save();
        });
        binding.btnPost.setOnClickListener(v -> post());
        binding.btnComment.setOnClickListener(v -> openComments());
        binding.btnEdit.setOnClickListener(v -> openEditor());
        binding.btnShare.setOnClickListener(v -> {
            if (isReact(currentSource())) shareReact();
            else withFile(file ->
                    startActivity(plays(currentSource())
                            ? ImageExporter.createVideoShareIntent(this, file)
                            : ImageExporter.createShareIntent(this, file)));
        });
    }

    /** Shares a still picture of the reactive wallpaper on screen. */
    private void shareReact() {
        String character = currentSource().substring(REACT_PREFIX.length());
        Context app = getApplicationContext();
        new Thread(() -> {
            File poster = ReactPoster.get(app, character);
            runOnUiThread(() -> {
                if (poster == null) toast(R.string.save_failed);
                else if (!isFinishing()) startActivity(ImageExporter.createShareIntent(this, poster));
            });
        }, "ShareReact").start();
    }

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
        if (reactView != null) reactView.onPause();
        for (int i = 0; i < videoViews.size(); i++) videoViews.valueAt(i).release();
        videoViews.clear();
        super.onDestroy();
    }

    static boolean isVideo(String source) {
        return source.startsWith(VIDEO_PREFIX);
    }

    /** A motion video the user made, kept on this phone (an absolute .mp4 path). */
    static boolean isSavedVideo(String source) {
        return assetKey(source) == null && source.endsWith(".mp4");
    }

    /** Bundled or saved: the page plays a video. */
    static boolean plays(String source) {
        return isVideo(source) || isSavedVideo(source);
    }

    static boolean isFourD(String source) {
        return source.startsWith(FOURD_PREFIX);
    }

    static boolean isReact(String source) {
        return source.startsWith(REACT_PREFIX);
    }

    /** Asset path used as the {@link Focal} key, or null for the user's own files. */
    @androidx.annotation.Nullable
    static String assetKey(String source) {
        if (source.startsWith(ASSET_PREFIX)) return source.substring(ASSET_PREFIX.length());
        if (source.startsWith(VIDEO_PREFIX)) return source.substring(VIDEO_PREFIX.length());
        if (source.startsWith(FOURD_PREFIX)) return source.substring(FOURD_PREFIX.length());
        if (source.startsWith(REACT_PREFIX)) return source.substring(REACT_PREFIX.length());
        return null;
    }

    private void updateLike() {
        boolean liked = Likes.isLiked(this, currentSource());
        binding.btnLike.setImageResource(liked ? R.drawable.ic_heart : R.drawable.ic_heart_outline);
        binding.btnLike.setContentDescription(getString(liked ? R.string.unlike : R.string.like));
        binding.likeLabel.setText(liked ? R.string.liked : R.string.like);
    }

    /** Photo-only actions (motion effects) are hidden on video, 4D and reactive pages. */
    private void updateActions() {
        String source = currentSource();
        int photoOnly = plays(source) || isFourD(source) || isReact(source) ? View.GONE : View.VISIBLE;
        binding.liveItem.setVisibility(photoOnly);
        // Reactive wallpapers only exist live, so there is no picture to save.
        binding.saveItem.setVisibility(isReact(source) ? View.GONE : View.VISIBLE);
        binding.postItem.setVisibility(isSavedPicture(source) ? View.VISIBLE : View.GONE);
        binding.editItem.setVisibility(canEdit(source) ? View.VISIBLE : View.GONE);
        updateComments();
        // The Akhu logo (to the app's profile) is on the wallpapers that come with the app.
        binding.avatar.setVisibility(assetKey(source) == null || FourDWallpapers.isUser(assetKey(source))
                ? View.GONE : View.VISIBLE);
    }

    /** The user's own picture, 3D image, motion video or reactive wallpaper, opened from their Profile. */
    private boolean canEdit(String source) {
        if (!getIntent().getBooleanExtra(EXTRA_OWN, false)) return false;
        if (isFourD(source)) return FourDWallpapers.isUser(assetKey(source));
        return isReact(source) || assetKey(source) == null;
    }

    /** What the post made from this page is linked by: its file path, or its 3D / react asset path. */
    private static String postKey(String source) {
        String key = assetKey(source);
        return key != null ? key : source;
    }

    /** Shows Comments, with the count, when the page is the user's own and they've posted it. */
    private void updateComments() {
        String source = currentSource();
        String post = getIntent().getBooleanExtra(EXTRA_OWN, false) ? Posts.wallpaperPostOf(this, postKey(source)) : null;
        binding.commentItem.setVisibility(post == null ? View.GONE : View.VISIBLE);
        binding.commentCount.setText(R.string.comments);
        if (post == null) return;
        Comments.count(post).addOnSuccessListener(this, n -> {
            // Still on the same page.
            if (source.equals(currentSource())) binding.commentCount.setText(String.valueOf(n));
        });
    }

    private void openComments() {
        String post = Posts.wallpaperPostOf(this, postKey(currentSource()));
        if (post == null) return;
        FirebaseHelper.doc(post).get().addOnCompleteListener(this, t -> {
            if (isFinishing()) return;
            if (!t.isSuccessful() || !t.getResult().exists()) {
                toast(R.string.comments_load_failed);
                return;
            }
            Comments.show(this, CloudWallpaper.from(t.getResult()), this::updateComments);
        });
    }

    /**
     * Reopens the page in the editor it was made in: pictures in the Create tab, 3D images in the 3D
     * editor, motion videos in the motion editor, reactive ones in the reactive editor. This screen
     * closes, so the Profile shows whatever the editor saves.
     */
    private void openEditor() {
        String source = currentSource();
        if (!canEdit(source)) return;
        Intent edit;
        if (isFourD(source)) {
            edit = FourDEditorActivity.intent(this, assetKey(source));
        } else if (isReact(source)) {
            edit = ReactEditorActivity.intent(this, assetKey(source));
        } else if (isSavedVideo(source)) {
            edit = VideoEditorActivity.intent(this, new File(source), false, null);
        } else {
            edit = new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_OPEN_IN_CREATE)
                    .putExtra(MainActivity.EXTRA_PATH, source)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        startActivity(edit);
        finish();
    }

    /** A picture file saved on this phone (not a bundled asset, video or 4D image). */
    private static boolean isSavedPicture(String source) {
        return assetKey(source) == null && !isSavedVideo(source);
    }

    /** Posts the saved picture on screen; public posts show up in the 2D tab. */
    private void post() {
        String source = currentSource();
        if (!isSavedPicture(source)) return;
        if (FirebaseHelper.currentUser(this) == null) {
            toast(R.string.sign_in_required);
            return;
        }
        PostDialog.show(this, null, FirebaseHelper.MEDIA_IMAGE, (title, isPublic, category, saleTier) -> {
            // The upload outlives this screen, so it reports with the app context.
            Context app = getApplicationContext();
            Toast.makeText(app, R.string.uploading, Toast.LENGTH_SHORT).show();
            FirebaseHelper.uploadWallpaper(app, new File(source), title, isPublic, category, saleTier,
                    new FirebaseHelper.UploadCallback() {
                        @Override
                        public void onProgress(int percent) {
                        }

                        @Override
                        public void onSuccess(String docId) {
                            Posts.link(app, source, FirebaseHelper.COLLECTION, docId);
                            Toast.makeText(app, R.string.upload_done, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(app, app.getString(R.string.upload_failed) + ": " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        });
    }

    /** Only the visible page plays, and only while the activity is in front. */
    private void syncPlayback() {
        int current = binding.pager.getCurrentItem();
        for (int i = 0; i < videoViews.size(); i++) {
            if (resumed && videoViews.keyAt(i) == current) videoViews.valueAt(i).play();
            else videoViews.valueAt(i).pause();
        }
        syncFourD();
        syncReact();
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

    /** As {@link #syncFourD}, for the one live {@link ReactView}. */
    private void syncReact() {
        String source = currentSource();
        android.widget.FrameLayout current = currentPage();
        FitFrame page = current == null ? null : content(current);
        boolean want = resumed && isReact(source) && page != null;
        if (!want) {
            if (reactView != null) {
                reactView.onPause();
                if (reactView.getParent() != null) ((ViewGroup) reactView.getParent()).removeView(reactView);
            }
            return;
        }
        if (reactView == null) {
            reactView = new ReactView(this);
            // The page passes touches in, so its tap (buttons) and double tap (like) keep working.
            reactView.setInteractive(false);
        }
        if (reactView.getParent() != page) {
            if (reactView.getParent() != null) ((ViewGroup) reactView.getParent()).removeView(reactView);
            page.addView(reactView, 1, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        reactView.setSource(source.substring(REACT_PREFIX.length()));
        reactView.onResume();
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
        List<String> labels = getIntent().getStringArrayListExtra(EXTRA_LABELS);
        int i = binding.pager.getCurrentItem();
        String label = labels != null && i < labels.size() ? labels.get(i) : null;
        binding.label.setText(label);
        binding.label.setVisibility(label == null || label.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        float alpha = chromeVisible ? 1f : 0f;
        for (View v : new View[]{binding.topBar, binding.bottomBar, binding.rail}) {
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

    /** A picture goes straight to the phone's own set-wallpaper screen, as the live types do. */
    private void chooseTarget() {
        withFile(file -> WallpaperHelper.openSystemSetter(this, file));
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
        // Reactive wallpapers fill the screen, whatever its shape.
        if (isReact(source)) return 0f;
        if (isSavedVideo(source)) {
            try (android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever()) {
                r.setDataSource(source);
                float w = parse(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                float h = parse(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                boolean sideways = "90".equals(rot) || "270".equals(rot);
                return w > 0 && h > 0 ? (sideways ? h / w : w / h) : 0f;
            } catch (Exception e) {
                return 0f;
            }
        }
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

    private static float parse(@androidx.annotation.Nullable String number) {
        try {
            return number == null ? 0f : Float.parseFloat(number);
        } catch (NumberFormatException e) {
            return 0f;
        }
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
            // Tap shows or hides the buttons; double tap likes.
            // On a reactive wallpaper, touches also ripple the water.
            HeartPop.attach(page, PreviewActivity.this::toggleChrome, () -> {
                Likes.like(PreviewActivity.this, currentSource());
                updateLike();
            }, (v, e) -> {
                if (reactView != null && reactView.getParent() == content(page)) reactView.feed(e);
                return false;
            });
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
                    : isReact(source) ? null : model(source);
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
            if (plays(source)) {
                CropVideoView video = new CropVideoView(page.getContext(),
                        isVideo(source) ? source.substring(VIDEO_PREFIX.length()) : source);
                video.setOpaque(false);
                frame.addView(video, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                frame.setTag(position);
                videoViews.put(position, video);
                if (resumed && position == binding.pager.getCurrentItem()) video.play();
            } else if (isFourD(source) && position == binding.pager.getCurrentItem()) {
                // The page isn't attached yet; attach the 4D view once layout settles.
                page.post(PreviewActivity.this::syncFourD);
            } else if (isReact(source) && position == binding.pager.getCurrentItem()) {
                page.post(PreviewActivity.this::syncReact);
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
                } else if (child == reactView) {
                    reactView.onPause();
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
