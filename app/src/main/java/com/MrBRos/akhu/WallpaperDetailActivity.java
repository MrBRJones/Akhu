package com.MrBRos.akhu;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.MrBRos.akhu.databinding.ActivityWallpaperDetailBinding;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseUser;

/**
 * Full-screen cloud wallpaper, looping video, 3D post or live reactive post. The right rail has the poster (tap for
 * their profile, "+" to follow), like, comments and share (long press copies the link); the
 * bottom has the title and Set. Tap hides everything; double tap likes.
 */
public class WallpaperDetailActivity extends AppCompatActivity {

    public static final String EXTRA_DOC_ID = "doc_id";

    private ActivityWallpaperDetailBinding binding;
    private String docId;
    private CloudWallpaper wallpaper;
    private boolean chromeVisible = true;
    /** Whether the signed-in user follows the poster; null until known (or their own post). */
    private Boolean following;
    /** A reactive post playing live over its picture, once downloaded. */
    @androidx.annotation.Nullable
    private ReactView reactView;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWallpaperDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());
        docId = getIntent().getStringExtra(EXTRA_DOC_ID);
        if (docId == null || !FirebaseHelper.isAvailable(this)) {
            finish();
            return;
        }
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnSet.setOnClickListener(v -> setAsWallpaper());
        binding.btnLike.setOnClickListener(v -> toggleLike());
        // Tap shows or hides the buttons; double tap likes. On a reactive post, touches also ripple the water.
        Runnable likeOnce = () -> {
            if (wallpaper != null && !Likes.isLiked(this, Likes.CLOUD + docId)) toggleLike();
        };
        HeartPop.attach(binding.image, this::toggleChrome, likeOnce, (v, e) -> {
            if (reactView != null) reactView.feed(e);
            return false;
        });
        HeartPop.attach(binding.video, this::toggleChrome, likeOnce);
        binding.author.setOnClickListener(v -> openProfile());
        binding.avatar.setOnClickListener(v -> openProfile());
        binding.btnFollow.setOnClickListener(v -> follow());
        binding.btnComment.setOnClickListener(v -> {
            if (wallpaper != null) Comments.show(this, wallpaper, this::loadCommentCount);
        });
        binding.btnShare.setOnClickListener(v -> shareLink());
        binding.btnDelete.setOnClickListener(v -> confirmDelete());
        binding.btnShare.setOnLongClickListener(v -> {
            copyLink();
            return true;
        });
        loadCommentCount();
        load();
    }

    private void load() {
        FirebaseHelper.doc(docId).get().addOnCompleteListener(this, t -> {
            if (!t.isSuccessful() || t.getResult() == null || !t.getResult().exists()) {
                Toast.makeText(this, R.string.gallery_load_failed, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            boolean first = wallpaper == null;
            wallpaper = CloudWallpaper.from(t.getResult());
            bind(first);
        });
    }

    private void bind(boolean loadImage) {
        if (loadImage && wallpaper.isVideo()) {
            Glide.with(this).load(wallpaper.thumbnailUrl).fitCenter().into(binding.image);
            playVideo();
        } else if (loadImage) {
            // The small thumbnail shows at once; "Loading…" stays until the full picture is in.
            showLoading(true);
            Glide.with(this).load(wallpaper.downloadUrl).thumbnail(Glide.with(this).load(wallpaper.thumbnailUrl))
                    .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override
                        public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                                    Object model,
                                                    @androidx.annotation.NonNull com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                    boolean isFirstResource) {
                            showLoading(false);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(@androidx.annotation.NonNull android.graphics.drawable.Drawable resource, @androidx.annotation.NonNull Object model,
                                                       com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                       @androidx.annotation.NonNull com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                            showLoading(false);
                            return false;
                        }
                    })
                    .fitCenter().into(binding.image);
            if (wallpaper.isReact()) playReact();
        }
        binding.btnSet.setText(wallpaper.isVideo() ? R.string.set_live
                : wallpaper.isFourD() ? R.string.set_fourd : wallpaper.isReact() ? R.string.set_react : R.string.set_static);
        setTitle(wallpaper.title);
        binding.title.setText(wallpaper.title);
        binding.author.setText(getString(R.string.by_author, wallpaper.displayName));
        binding.likeCount.setText(String.valueOf(wallpaper.likesCount));
        FirebaseUser user = FirebaseHelper.currentUser(this);
        boolean liked = user != null && wallpaper.isLikedBy(user.getUid());
        Likes.sync(this, Likes.CLOUD + docId, liked);
        binding.btnLike.setImageResource(liked ? R.drawable.ic_heart : R.drawable.ic_heart_outline);
        binding.btnLike.setContentDescription(getString(liked ? R.string.unlike : R.string.like));
        if (loadImage) loadPoster();
    }

    /** The poster's picture and @username, and the follow "+" unless it's the user's own post or they follow already. */
    private void loadPoster() {
        if (wallpaper.uid == null) return;
        Social.INSTANCE.load(wallpaper.uid).addOnSuccessListener(this, p -> {
            if (p.getPhotoUrl() != null) Glide.with(this).load(p.getPhotoUrl()).circleCrop().into(binding.avatar);
            // The poster's display name (from Edit profile), then their @username.
            String shown = !p.getName().isEmpty() ? p.getName() : wallpaper.displayName;
            // The official account's name carries its star.
            if (wallpaper.isOfficial()) shown += " \u2605";
            binding.author.setText(p.getUsername().isEmpty() ? shown : shown + "  @" + p.getUsername());
        });
        FirebaseUser user = FirebaseHelper.currentUser(this);
        if (user == null) {
            following = false;
            updateFollow();
        } else if (!user.getUid().equals(wallpaper.uid)) {
            Social.INSTANCE.isFollowing(user.getUid(), wallpaper.uid).addOnSuccessListener(this, f -> {
                following = f;
                updateFollow();
            });
        } else {
            binding.deleteItem.setVisibility(View.VISIBLE);
        }
    }

    /** The poster can take their post down; it leaves Wallpapers for everyone. */
    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_post_title)
                .setMessage(R.string.delete_post_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    android.content.Context app = getApplicationContext();
                    Posts.delete(app, FirebaseHelper.COLLECTION, docId).addOnCompleteListener(t ->
                            Toast.makeText(app, t.isSuccessful() ? getString(R.string.post_deleted)
                                    : getString(R.string.save_failed) + ": " + t.getException(), Toast.LENGTH_LONG).show());
                    finish();
                })
                .show();
    }

    private void updateFollow() {
        binding.btnFollow.setVisibility(Boolean.FALSE.equals(following) ? View.VISIBLE : View.GONE);
    }

    private void openProfile() {
        if (wallpaper != null && wallpaper.uid != null) {
            startActivity(UserProfileActivity.intent(this, wallpaper.uid, wallpaper.displayName));
        }
    }

    private void follow() {
        if (wallpaper == null || wallpaper.uid == null) return;
        FirebaseUser user = FirebaseHelper.currentUser(this);
        if (user == null) {
            Toast.makeText(this, R.string.sign_in_to_follow, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, AuthActivity.class));
            return;
        }
        binding.btnFollow.setEnabled(false);
        Social.INSTANCE.setFollowing(user.getUid(), wallpaper.uid, true).addOnCompleteListener(this, t -> {
            binding.btnFollow.setEnabled(true);
            if (t.isSuccessful()) {
                following = true;
                updateFollow();
                Toast.makeText(this, getString(R.string.followed, wallpaper.displayName), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.follow_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadCommentCount() {
        Comments.count(docId).addOnSuccessListener(this, n -> binding.commentCount.setText(String.valueOf(n)));
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

    /** The spinner and a "Loading…" label at the bottom, while a video or picture is coming in. */
    private void showLoading(boolean on) {
        binding.progress.setVisibility(on ? View.VISIBLE : View.GONE);
        binding.loadingLabel.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** Streams the post muted and looping; the thumbnail stays underneath until the first frame. */
    private void playVideo() {
        showLoading(true);
        binding.video.setVisibility(View.VISIBLE);
        binding.video.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            showLoading(false);
        });
        binding.video.setOnErrorListener((mp, what, extra) -> {
            showLoading(false);
            binding.video.setVisibility(View.GONE);
            Toast.makeText(this, R.string.video_play_failed, Toast.LENGTH_SHORT).show();
            return true;
        });
        binding.video.setVideoURI(android.net.Uri.parse(wallpaper.downloadUrl));
        binding.video.start();
    }

    /**
     * Plays a reactive post live, with tilt and touch, as the preview does: it is downloaded to
     * the cache (as the grid does), then drawn over its picture, which shows until then.
     */
    private void playReact() {
        CloudWallpaper wp = wallpaper;
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String character;
            try {
                character = ReactWallpapers.downloadPreview(app, wp);
            } catch (Exception e) {
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || reactView != null) return;
                reactView = new ReactView(this);
                // The picture beneath takes the touches and passes them in.
                reactView.setInteractive(false);
                android.view.ViewGroup root = (android.view.ViewGroup) binding.image.getParent();
                root.addView(reactView, root.indexOfChild(binding.image) + 1, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                reactView.setSource(character);
                if (resumed) reactView.onResume();
            });
        }, "DetailReact").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (wallpaper != null && wallpaper.isVideo() && binding.video.getVisibility() == View.VISIBLE) {
            binding.video.start();
        }
        if (reactView != null) reactView.onResume();
    }

    @Override
    protected void onPause() {
        resumed = false;
        binding.video.pause();
        if (reactView != null) reactView.onPause();
        super.onPause();
    }

    private void setAsWallpaper() {
        if (wallpaper == null) return;
        showLoading(true);
        binding.btnSet.setEnabled(false);
        String url = wallpaper.downloadUrl;
        if (wallpaper.isVideo()) {
            // Downloads the MP4, then opens the system picker on the motion wallpaper.
            MotionWallpapers.applyFromUrl(this, url, () -> {
                if (isDestroyed()) return;
                showLoading(false);
                binding.btnSet.setEnabled(true);
            });
            return;
        }
        if (wallpaper.isFourD()) {
            // Downloads the layers, then opens the system picker on the 3D wallpaper.
            CloudWallpaper wp = wallpaper;
            new Thread(() -> {
                String image;
                try {
                    image = FourDWallpapers.downloadCloud(this, wp);
                } catch (Exception e) {
                    image = null;
                }
                String done = image;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    showLoading(false);
                    binding.btnSet.setEnabled(true);
                    if (done != null) FourDWallpapers.apply(this, done);
                    else Toast.makeText(this, R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show();
                });
            }, "DetailSetFourD").start();
            return;
        }
        if (wallpaper.isReact()) {
            // Downloads its characters and settings, then opens the system picker on it.
            CloudWallpaper wp = wallpaper;
            new Thread(() -> {
                String character;
                try {
                    character = ReactWallpapers.downloadCloud(this, wp);
                } catch (Exception e) {
                    character = null;
                }
                String done = character;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    showLoading(false);
                    binding.btnSet.setEnabled(true);
                    if (done != null) ReactWallpapers.apply(this, done);
                    else Toast.makeText(this, R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show();
                });
            }, "DetailSetReact").start();
            return;
        }
        // Images ask home / lock / both first; the live types above ask before the system screen.
        showLoading(false);
        binding.btnSet.setEnabled(true);
        WallpaperTarget.choose(this, which -> setImageWallpaper(url, which));
    }

    private void setImageWallpaper(String url, int which) {
        showLoading(true);
        binding.btnSet.setEnabled(false);
        new Thread(() -> {
            int msg;
            try {
                Bitmap bmp = FirebaseHelper.downloadBitmap(this, url);
                WallpaperHelper.setStaticWallpaper(this, bmp, which);
                msg = R.string.wallpaper_set;
            } catch (Exception e) {
                msg = R.string.wallpaper_set_failed;
            }
            int finalMsg = msg;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                showLoading(false);
                binding.btnSet.setEnabled(true);
                Toast.makeText(this, finalMsg, Toast.LENGTH_SHORT).show();
            });
        }, "DetailSetWallpaper").start();
    }

    private void toggleLike() {
        FirebaseUser user = FirebaseHelper.currentUser(this);
        if (user == null) {
            Toast.makeText(this, R.string.sign_in_required, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, AuthActivity.class));
            return;
        }
        // Taking a like back never needs a verified email; giving one does.
        if (!(wallpaper != null && wallpaper.isLikedBy(user.getUid())) && !Verify.ok(this)) return;
        binding.btnLike.setEnabled(false);
        FirebaseHelper.toggleLike(docId, user.getUid()).addOnCompleteListener(this, t -> {
            binding.btnLike.setEnabled(true);
            if (t.isSuccessful() && Boolean.TRUE.equals(t.getResult()) && wallpaper != null) {
                Notices.send(this, Notices.LIKE, FirebaseHelper.COLLECTION, wallpaper);
            }
            if (!t.isSuccessful()) {
                Toast.makeText(this, R.string.like_failed, Toast.LENGTH_SHORT).show();
            }
            load();
        });
    }

    private void shareLink() {
        if (wallpaper == null) return;
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, wallpaper.title)
                .putExtra(Intent.EXTRA_TEXT, wallpaper.downloadUrl);
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)));
        Notices.send(this, Notices.SHARE, FirebaseHelper.COLLECTION, wallpaper);
    }

    private void copyLink() {
        if (wallpaper == null) return;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText(wallpaper.title, wallpaper.downloadUrl));
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
        Notices.send(this, Notices.SHARE, FirebaseHelper.COLLECTION, wallpaper);
    }
}
