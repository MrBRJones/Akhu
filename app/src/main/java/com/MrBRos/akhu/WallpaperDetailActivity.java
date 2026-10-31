package com.MrBRos.akhu;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.MrBRos.akhu.databinding.ActivityWallpaperDetailBinding;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseUser;

/**
 * Full-screen cloud wallpaper, looping video or 3D post. The right rail has the poster (tap for
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
        // Tap shows or hides the buttons; double tap likes.
        Runnable likeOnce = () -> {
            if (wallpaper != null && !Likes.isLiked(this, Likes.CLOUD + docId)) toggleLike();
        };
        HeartPop.attach(binding.image, this::toggleChrome, likeOnce);
        HeartPop.attach(binding.video, this::toggleChrome, likeOnce);
        binding.author.setOnClickListener(v -> openProfile());
        binding.avatar.setOnClickListener(v -> openProfile());
        binding.btnFollow.setOnClickListener(v -> follow());
        binding.btnComment.setOnClickListener(v -> {
            if (wallpaper != null) Comments.show(this, docId, wallpaper.uid, this::loadCommentCount);
        });
        binding.btnShare.setOnClickListener(v -> shareLink());
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
            Glide.with(this).load(wallpaper.downloadUrl).thumbnail(Glide.with(this).load(wallpaper.thumbnailUrl))
                    .fitCenter().into(binding.image);
        }
        binding.btnSet.setText(wallpaper.isVideo() ? R.string.set_live
                : wallpaper.isFourD() ? R.string.set_fourd : R.string.set_static);
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
            if (!p.getUsername().isEmpty()) binding.author.setText("@" + p.getUsername());
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
        }
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

    /** Streams the post muted and looping; the thumbnail stays underneath until the first frame. */
    private void playVideo() {
        binding.progress.setVisibility(View.VISIBLE);
        binding.video.setVisibility(View.VISIBLE);
        binding.video.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            binding.progress.setVisibility(View.GONE);
        });
        binding.video.setOnErrorListener((mp, what, extra) -> {
            binding.progress.setVisibility(View.GONE);
            binding.video.setVisibility(View.GONE);
            Toast.makeText(this, R.string.video_play_failed, Toast.LENGTH_SHORT).show();
            return true;
        });
        binding.video.setVideoURI(android.net.Uri.parse(wallpaper.downloadUrl));
        binding.video.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wallpaper != null && wallpaper.isVideo() && binding.video.getVisibility() == View.VISIBLE) {
            binding.video.start();
        }
    }

    @Override
    protected void onPause() {
        binding.video.pause();
        super.onPause();
    }

    private void setAsWallpaper() {
        if (wallpaper == null) return;
        binding.progress.setVisibility(View.VISIBLE);
        binding.btnSet.setEnabled(false);
        String url = wallpaper.downloadUrl;
        if (wallpaper.isVideo()) {
            // Downloads the MP4, then opens the system picker on the motion wallpaper.
            MotionWallpapers.applyFromUrl(this, url, () -> {
                if (isDestroyed()) return;
                binding.progress.setVisibility(View.GONE);
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
                    binding.progress.setVisibility(View.GONE);
                    binding.btnSet.setEnabled(true);
                    if (done != null) FourDWallpapers.apply(this, done);
                    else Toast.makeText(this, R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show();
                });
            }, "DetailSetFourD").start();
            return;
        }
        new Thread(() -> {
            int msg;
            try {
                Bitmap bmp = FirebaseHelper.downloadBitmap(this, url);
                WallpaperHelper.setStaticWallpaper(this, bmp);
                msg = R.string.wallpaper_set;
            } catch (Exception e) {
                msg = R.string.wallpaper_set_failed;
            }
            int finalMsg = msg;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                binding.progress.setVisibility(View.GONE);
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
        binding.btnLike.setEnabled(false);
        FirebaseHelper.toggleLike(docId, user.getUid()).addOnCompleteListener(this, t -> {
            binding.btnLike.setEnabled(true);
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
    }

    private void copyLink() {
        if (wallpaper == null) return;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText(wallpaper.title, wallpaper.downloadUrl));
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
    }
}
