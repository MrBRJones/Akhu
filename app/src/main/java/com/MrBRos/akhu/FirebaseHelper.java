package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Firebase access: availability check, resumable uploads (full + 480px thumbnail),
 * gallery queries and the like/unlike transaction. Cloud features are disabled when
 * the app was built without google-services.json.
 */
public final class FirebaseHelper {

    public static final String COLLECTION = "wallpapers";
    /** Posted home screen themes: the wallpaper fields plus {@code theme}, the theme JSON. */
    public static final String THEMES = "themes";
    public static final String VISIBILITY_PUBLIC = "public";
    public static final String VISIBILITY_PRIVATE = "private";
    private static final int FULL_MAX_DIM = 4096;
    private static final int FULL_QUALITY = 90;
    private static final int THUMB_WIDTH = 480;
    private static final int THUMB_QUALITY = 85;
    private static final int GALLERY_LIMIT = 50;
    public static final long VIDEO_MAX_BYTES = 100L * 1024 * 1024;
    public static final String MEDIA_IMAGE = "image";
    public static final String MEDIA_VIDEO = "video";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FirebaseHelper() {
    }

    public interface UploadCallback {
        void onProgress(int percent);

        void onSuccess(String docId);

        void onError(Exception e);
    }

    /** True when a Firebase app was initialized from google-services.json. */
    public static boolean isAvailable(Context context) {
        return !FirebaseApp.getApps(context).isEmpty();
    }

    @Nullable
    public static FirebaseUser currentUser(Context context) {
        return isAvailable(context) ? FirebaseAuth.getInstance().getCurrentUser() : null;
    }

    /**
     * Compresses the image (full at 90%, 480px-wide thumbnail at 85%), uploads both to
     * users/{uid}/wallpapers and users/{uid}/wallnails, then writes the Firestore document.
     * Callbacks run on the main thread. Refused when {@link NsfwDetector} flags the image.
     */
    public static void uploadWallpaper(Context context, File source, String title, boolean isPublic,
                                       @Nullable String category, UploadCallback cb) {
        uploadImage(context, source, title, isPublic, category, COLLECTION, null, cb);
    }

    /**
     * Posts a theme: its wallpaper goes up like a 2D post, and the {@link #THEMES} document also
     * carries the theme (icon style, shape, colours, apps) as JSON. Icon photos stay on the phone.
     */
    public static void uploadTheme(Context context, CustomTheme theme, File wallpaper, String title,
                                   boolean isPublic, UploadCallback cb) {
        String json;
        try {
            CustomTheme shared = theme.copy();
            for (CustomTheme.App a : shared.apps) a.photo = null;
            json = shared.toJson().toString();
        } catch (org.json.JSONException e) {
            cb.onError(new IOException(e));
            return;
        }
        Map<String, Object> extra = new HashMap<>();
        extra.put("theme", json);
        uploadImage(context, wallpaper, title, isPublic, null, THEMES, extra, cb);
    }

    private static void uploadImage(Context context, File source, String title, boolean isPublic,
                                    @Nullable String category, String collection,
                                    @Nullable Map<String, Object> extra, UploadCallback cb) {
        FirebaseUser user = currentUser(context);
        if (user == null) {
            cb.onError(new IllegalStateException(context.getString(R.string.sign_in_required)));
            return;
        }
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            File fullFile;
            byte[] thumbBytes;
            try {
                Bitmap full = ImagePickerHelper.decodeSampled(source, FULL_MAX_DIM);
                if (full == null) throw new IOException("Cannot decode image");
                if (NsfwDetector.isNsfw(app, full)) {
                    full.recycle();
                    throw new IOException(app.getString(R.string.nsfw_blocked));
                }
                File dir = new File(app.getCacheDir(), "upload");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
                fullFile = new File(dir, "full_" + System.currentTimeMillis() + ".jpg");
                try (OutputStream os = new FileOutputStream(fullFile)) {
                    full.compress(Bitmap.CompressFormat.JPEG, FULL_QUALITY, os);
                }
                int thumbH = Math.max(1, Math.round(full.getHeight() * (THUMB_WIDTH / (float) full.getWidth())));
                Bitmap thumb = Bitmap.createScaledBitmap(full, THUMB_WIDTH, thumbH, true);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, bos);
                thumbBytes = bos.toByteArray();
                if (thumb != full) thumb.recycle();
                full.recycle();
            } catch (IOException e) {
                MAIN.post(() -> cb.onError(e));
                return;
            }
            File finalFull = fullFile;
            MAIN.post(() -> startUpload(user, finalFull, false, thumbBytes, title, isPublic, category, collection, extra, cb));
        });
    }

    /**
     * Uploads an MP4 as-is (max {@link #VIDEO_MAX_BYTES}) with a 480px JPEG thumbnail taken from
     * its first frame; the Firestore document gets {@code mediaType: "video"}.
     * Refused when {@link NsfwDetector} flags any of its frames.
     */
    public static void uploadVideo(Context context, File video, String title, boolean isPublic,
                                   @Nullable String category, UploadCallback cb) {
        FirebaseUser user = currentUser(context);
        if (user == null) {
            cb.onError(new IllegalStateException(context.getString(R.string.sign_in_required)));
            return;
        }
        if (video.length() > VIDEO_MAX_BYTES) {
            cb.onError(new IOException(context.getString(R.string.video_too_big)));
            return;
        }
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                if (NsfwDetector.isVideoNsfw(app, video)) {
                    MAIN.post(() -> cb.onError(new IOException(app.getString(R.string.nsfw_blocked))));
                    return;
                }
            } catch (IOException e) {
                MAIN.post(() -> cb.onError(e));
                return;
            }
            byte[] thumbBytes;
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            try {
                mmr.setDataSource(video.getAbsolutePath());
                Bitmap frame = mmr.getFrameAtTime(0);
                if (frame == null) throw new IOException("Cannot read video");
                int thumbH = Math.max(1, Math.round(frame.getHeight() * (THUMB_WIDTH / (float) frame.getWidth())));
                Bitmap thumb = Bitmap.createScaledBitmap(frame, THUMB_WIDTH, thumbH, true);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, bos);
                thumbBytes = bos.toByteArray();
                if (thumb != frame) thumb.recycle();
                frame.recycle();
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e instanceof IOException ? e : new IOException("Cannot read video", e)));
                return;
            } finally {
                try {
                    mmr.release();
                } catch (IOException ignored) {
                }
            }
            MAIN.post(() -> startUpload(user, video, true, thumbBytes, title, isPublic, category, COLLECTION, null, cb));
        });
    }

    private static void startUpload(FirebaseUser user, File fullFile, boolean isVideo, byte[] thumbBytes,
                                    String title, boolean isPublic, @Nullable String category, String collection,
                                    @Nullable Map<String, Object> extra, UploadCallback cb) {
        String uid = user.getUid();
        long ts = System.currentTimeMillis();
        StorageReference root = FirebaseStorage.getInstance().getReference().child("users").child(uid);
        StorageReference fullRef = root.child("wallpapers").child(ts + (isVideo ? ".mp4" : ".jpg"));
        StorageReference thumbRef = root.child("wallnails").child(ts + ".jpg");
        StorageMetadata meta = new StorageMetadata.Builder().setContentType("image/jpeg").build();
        StorageMetadata fullMeta = isVideo
                ? new StorageMetadata.Builder().setContentType("video/mp4").build() : meta;

        String[] urls = new String[2];
        UploadTask fullTask = fullRef.putFile(Uri.fromFile(fullFile), fullMeta);
        fullTask.addOnProgressListener(snap -> {
            long total = snap.getTotalByteCount();
            if (total > 0) cb.onProgress((int) (90 * snap.getBytesTransferred() / total));
        });
        fullTask
                .continueWithTask(t -> {
                    if (!t.isSuccessful()) throw t.getException();
                    return fullRef.getDownloadUrl();
                })
                .continueWithTask(t -> {
                    urls[0] = t.getResult().toString();
                    return thumbRef.putBytes(thumbBytes, meta);
                })
                .continueWithTask(t -> {
                    if (!t.isSuccessful()) throw t.getException();
                    cb.onProgress(95);
                    return thumbRef.getDownloadUrl();
                })
                .continueWithTask(t -> {
                    urls[1] = t.getResult().toString();
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("uid", uid);
                    doc.put("displayName", displayNameOf(user));
                    doc.put("downloadUrl", urls[0]);
                    doc.put("thumbnailUrl", urls[1]);
                    doc.put("title", title);
                    doc.put("mediaType", isVideo ? MEDIA_VIDEO : MEDIA_IMAGE);
                    doc.put("visibility", isPublic ? VISIBILITY_PUBLIC : VISIBILITY_PRIVATE);
                    doc.put("createdAt", FieldValue.serverTimestamp());
                    List<String> tags = new ArrayList<>();
                    if (category != null) tags.add(category);
                    doc.put("tags", tags);
                    doc.put("likesCount", 0);
                    doc.put("likedBy", new ArrayList<String>());
                    if (extra != null) doc.putAll(extra);
                    return FirebaseFirestore.getInstance().collection(collection).add(doc);
                })
                .addOnCompleteListener(t -> {
                    //noinspection ResultOfMethodCallIgnored
                    fullFile.delete();
                    if (t.isSuccessful()) {
                        cb.onProgress(100);
                        cb.onSuccess(t.getResult().getId());
                    } else {
                        cb.onError(t.getException() != null ? t.getException() : new IOException("Upload failed"));
                    }
                });
    }

    public static String displayNameOf(FirebaseUser user) {
        String name = user.getDisplayName();
        if (name != null && !name.trim().isEmpty()) return name;
        String email = user.getEmail();
        if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        return "Anonymous";
    }

    /** Public wallpapers, optionally filtered to one category, sorted by likes (hot) or newest. */
    public static Query publicQuery(@Nullable String category, boolean hot) {
        Query q = FirebaseFirestore.getInstance().collection(COLLECTION)
                .whereEqualTo("visibility", VISIBILITY_PUBLIC);
        if (category != null) q = q.whereArrayContains("tags", category);
        return q.orderBy(hot ? "likesCount" : "createdAt", Query.Direction.DESCENDING)
                .limit(GALLERY_LIMIT);
    }

    public static Query mineQuery(String uid) {
        return FirebaseFirestore.getInstance().collection(COLLECTION)
                .whereEqualTo("uid", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(GALLERY_LIMIT);
    }

    public static DocumentReference doc(String docId) {
        return FirebaseFirestore.getInstance().collection(COLLECTION).document(docId);
    }

    /**
     * Toggles the caller's like inside a transaction so likedBy and likesCount move together
     * and a user can never be counted twice. Resolves to the new liked state.
     */
    public static Task<Boolean> toggleLike(String docId, String uid) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference ref = doc(docId);
        return db.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(ref);
            CloudWallpaper wp = CloudWallpaper.from(snap);
            List<String> likedBy = new ArrayList<>(wp.likedBy);
            boolean nowLiked;
            if (likedBy.contains(uid)) {
                likedBy.removeAll(Collections.singleton(uid));
                nowLiked = false;
            } else {
                likedBy.add(uid);
                nowLiked = true;
            }
            Map<String, Object> update = new HashMap<>();
            update.put("likedBy", likedBy);
            update.put("likesCount", wp.likesCount + (nowLiked ? 1 : -1));
            tx.update(ref, update);
            return nowLiked;
        });
    }

    /**
     * Sets the caller's like on a post in {@code collection} to {@code on}, in a transaction so
     * likedBy and likesCount move together. Does nothing when it is already that way.
     */
    public static Task<Void> setLiked(String collection, String docId, String uid, boolean on) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference ref = db.collection(collection).document(docId);
        return db.runTransaction(tx -> {
            CloudWallpaper wp = CloudWallpaper.from(tx.get(ref));
            if (wp.likedBy.contains(uid) == on) return null;
            List<String> likedBy = new ArrayList<>(wp.likedBy);
            if (on) likedBy.add(uid);
            else likedBy.removeAll(Collections.singleton(uid));
            Map<String, Object> update = new HashMap<>();
            update.put("likedBy", likedBy);
            update.put("likesCount", wp.likesCount + (on ? 1 : -1));
            tx.update(ref, update);
            return null;
        });
    }

    /** Public themes, most liked or newest first. */
    public static Query publicThemesQuery(boolean hot) {
        return FirebaseFirestore.getInstance().collection(THEMES)
                .whereEqualTo("visibility", VISIBILITY_PUBLIC)
                .orderBy(hot ? "likesCount" : "createdAt", Query.Direction.DESCENDING)
                .limit(GALLERY_LIMIT);
    }

    /** Someone's public posts, newest first (for their profile page). */
    public static Query userPublicQuery(String uid) {
        return FirebaseFirestore.getInstance().collection(COLLECTION)
                .whereEqualTo("uid", uid)
                .whereEqualTo("visibility", VISIBILITY_PUBLIC)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(GALLERY_LIMIT);
    }

    /** Downloads a full-size image via Glide. Blocking; call off the main thread. */
    public static Bitmap downloadBitmap(Context context, String url) throws Exception {
        return Glide.with(context.getApplicationContext()).asBitmap().load(url).submit().get();
    }
}
