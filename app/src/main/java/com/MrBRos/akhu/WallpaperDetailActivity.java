package com.MrBRos.akhu;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.MrBRos.akhu.databinding.ActivityWallpaperDetailBinding;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseUser;

/** Full-size cloud wallpaper or looping video post: set as (motion) wallpaper, like/unlike, share link, copy link. */
public class WallpaperDetailActivity extends AppCompatActivity {

    public static final String EXTRA_DOC_ID = "doc_id";

    private ActivityWallpaperDetailBinding binding;
    private String docId;
    private CloudWallpaper wallpaper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWallpaperDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        docId = getIntent().getStringExtra(EXTRA_DOC_ID);
        if (docId == null || !FirebaseHelper.isAvailable(this)) {
            finish();
            return;
        }
        binding.btnSet.setOnClickListener(v -> setAsWallpaper());
        binding.btnLike.setOnClickListener(v -> toggleLike());
        // Double tap the picture to like it; tap the name to see who posted it.
        HeartPop.attach(binding.image, null, () -> {
            if (wallpaper != null && !Likes.isLiked(this, Likes.CLOUD + docId)) toggleLike();
        });
        HeartPop.attach(binding.video, null, () -> {
            if (wallpaper != null && !Likes.isLiked(this, Likes.CLOUD + docId)) toggleLike();
        });
        binding.author.setOnClickListener(v -> {
            if (wallpaper != null && wallpaper.uid != null) {
                startActivity(UserProfileActivity.intent(this, wallpaper.uid, wallpaper.displayName));
            }
        });
        binding.btnShare.setOnClickListener(v -> shareLink());
        binding.btnCopy.setOnClickListener(v -> copyLink());
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
        binding.btnSet.setText(wallpaper.isVideo() ? R.string.set_live : R.string.set_static);
        setTitle(wallpaper.title);
        binding.title.setText(wallpaper.title);
        binding.author.setText(getString(R.string.by_author, wallpaper.displayName));
        binding.likeCount.setText(getString(R.string.likes_count, wallpaper.likesCount));
        FirebaseUser user = FirebaseHelper.currentUser(this);
        boolean liked = user != null && wallpaper.isLikedBy(user.getUid());
        Likes.sync(this, Likes.CLOUD + docId, liked);
        binding.btnLike.setText(liked ? R.string.unlike : R.string.like);
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
