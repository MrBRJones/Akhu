package com.MrBRos.akhu;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
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
import java.util.Arrays;
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
    /** A 3D post: the picture plus its depth layers, see {@link #uploadFourD}. */
    public static final String MEDIA_FOURD = "fourd";
    /** A reactive wallpaper post: a still of it plus its characters and settings, see {@link #uploadReact}. */
    public static final String MEDIA_REACT = "react";
    /** The app's official account (Akhu); everything it posts is Featured. */
    public static final String OFFICIAL_UID = "w04mWObnTIaMvsJi39z6AToDAsz2";

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
            String shownName = postedName(app, user);
            MAIN.post(() -> startUpload(user, shownName, finalFull, false, thumbBytes, title, isPublic, category, collection, extra, cb));
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
            String shownName = postedName(app, user);
            MAIN.post(() -> startUpload(user, shownName, video, true, thumbBytes, title, isPublic, category, COLLECTION, null, cb));
        });
    }

    /**
     * Posts a user-made 3D image: its layers go up first (far jpg, mid and near webp, plus the
     * optional extra cut-out), then the picture itself as an ordinary image post whose document
     * also has {@code mediaType: "fourd"}, the layer URLs and the motion settings.
     * Refused when {@link NsfwDetector} flags the picture.
     */
    public static void uploadFourD(Context context, String imagePath, String title, boolean isPublic,
                                   @Nullable String category, UploadCallback cb) {
        FirebaseUser user = currentUser(context);
        if (user == null) {
            cb.onError(new IllegalStateException(context.getString(R.string.sign_in_required)));
            return;
        }
        Context app = context.getApplicationContext();
        List<File> layers = new ArrayList<>();
        for (String l : FourDWallpapers.layersFor(imagePath)) layers.add(new File(l));
        File extraLayer = new File(imagePath.substring(0, imagePath.length() - 4) + "_l3.webp");
        float[] extraMotion = FourDWallpapers.extraMotion(app, imagePath);
        if (extraLayer.exists() && extraMotion != null) layers.add(extraLayer);
        float[] motion = FourDWallpapers.motion(app, imagePath);
        IO.execute(() -> {
            // Checked before anything goes up; uploadImage checks the picture again on its own.
            try {
                Bitmap full = ImagePickerHelper.decodeSampled(new File(imagePath), FULL_MAX_DIM);
                if (full == null) throw new IOException("Cannot decode image");
                boolean nsfw = NsfwDetector.isNsfw(app, full);
                full.recycle();
                if (nsfw) throw new IOException(app.getString(R.string.nsfw_blocked));
            } catch (IOException e) {
                MAIN.post(() -> cb.onError(e));
                return;
            }
            MAIN.post(() -> {
                StorageReference dir = FirebaseStorage.getInstance().getReference()
                        .child("users").child(user.getUid()).child("wallpapers");
                long ts = System.currentTimeMillis();
                List<String> urls = new ArrayList<>();
                Task<Void> chain = Tasks.forResult(null);
                for (int i = 0; i < layers.size(); i++) {
                    File f = layers.get(i);
                    boolean jpg = f.getName().endsWith(".jpg");
                    StorageReference ref = dir.child(ts + "_l" + i + (jpg ? ".jpg" : ".webp"));
                    StorageMetadata meta = new StorageMetadata.Builder()
                            .setContentType(jpg ? "image/jpeg" : "image/webp").build();
                    chain = chain
                            .continueWithTask(t -> {
                                if (!t.isSuccessful()) throw t.getException();
                                return ref.putFile(Uri.fromFile(f), meta);
                            })
                            .continueWithTask(t -> {
                                if (!t.isSuccessful()) throw t.getException();
                                return ref.getDownloadUrl();
                            })
                            .continueWith(t -> {
                                if (!t.isSuccessful()) throw t.getException();
                                urls.add(t.getResult().toString());
                                return null;
                            });
                }
                chain.addOnCompleteListener(t -> {
                    if (!t.isSuccessful()) {
                        cb.onError(t.getException() != null ? t.getException() : new IOException("Upload failed"));
                        return;
                    }
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("mediaType", MEDIA_FOURD);
                    extra.put("layers", urls);
                    extra.put("motion", Arrays.asList((double) motion[0], (double) motion[1]));
                    if (urls.size() > 3) {
                        extra.put("extraMotion", Arrays.asList((double) extraMotion[0], (double) extraMotion[1]));
                    }
                    uploadImage(app, new File(imagePath), title, isPublic, category, COLLECTION, extra, cb);
                });
            });
        });
    }

    /**
     * Posts a user-made reactive wallpaper: its characters (and photo background, if any) go up
     * first, then a still of it as an ordinary image post whose document also has
     * {@code mediaType: "react"}, the characters' URLs in {@code layers} and the settings in
     * {@code react}. Refused when {@link NsfwDetector} flags any character or the background.
     */
    public static void uploadReact(Context context, String character, String title, boolean isPublic,
                                   @Nullable String category, UploadCallback cb) {
        FirebaseUser user = currentUser(context);
        if (user == null) {
            cb.onError(new IllegalStateException(context.getString(R.string.sign_in_required)));
            return;
        }
        Context app = context.getApplicationContext();
        ReactWallpapers.Spec spec = ReactWallpapers.spec(app, character);
        List<File> files = new ArrayList<>();
        for (int i = 0; i < spec.count(); i++) files.add(new File(ReactWallpapers.piecePath(character, i)));
        File background = spec.photo != null ? new File(spec.photo) : null;
        IO.execute(() -> {
            File still;
            try {
                // Checked before anything goes up; uploadImage checks the still again on its own.
                List<File> check = new ArrayList<>(files);
                if (background != null) check.add(background);
                for (File f : check) {
                    if (ReactWallpapers.isVideo(f.getAbsolutePath())) {
                        if (NsfwDetector.isVideoNsfw(app, f)) throw new IOException(app.getString(R.string.nsfw_blocked));
                        continue;
                    }
                    Bitmap b = ImagePickerHelper.decodeSampled(f, 1024);
                    if (b == null) throw new IOException("Cannot decode " + f.getName());
                    boolean nsfw = NsfwDetector.isNsfw(app, b);
                    b.recycle();
                    if (nsfw) throw new IOException(app.getString(R.string.nsfw_blocked));
                }
                still = ReactPoster.still(app, character);
            } catch (IOException e) {
                MAIN.post(() -> cb.onError(e));
                return;
            }
            MAIN.post(() -> {
                StorageReference dir = FirebaseStorage.getInstance().getReference()
                        .child("users").child(user.getUid()).child("wallpapers");
                long ts = System.currentTimeMillis();
                List<File> up = new ArrayList<>(files);
                if (background != null) up.add(background);
                List<String> urls = new ArrayList<>();
                Task<Void> chain = Tasks.forResult(null);
                for (int i = 0; i < up.size(); i++) {
                    File f = up.get(i);
                    String name = f.getName();
                    String ext = name.endsWith(".mp4") ? ".mp4" : name.endsWith(".jpg") ? ".jpg" : ".png";
                    StorageReference ref = dir.child(ts + "_r" + i + ext);
                    StorageMetadata meta = new StorageMetadata.Builder()
                            .setContentType(ext.equals(".mp4") ? "video/mp4" : ext.equals(".jpg") ? "image/jpeg" : "image/png")
                            .build();
                    chain = chain
                            .continueWithTask(t -> {
                                if (!t.isSuccessful()) throw t.getException();
                                return ref.putFile(Uri.fromFile(f), meta);
                            })
                            .continueWithTask(t -> {
                                if (!t.isSuccessful()) throw t.getException();
                                return ref.getDownloadUrl();
                            })
                            .continueWith(t -> {
                                if (!t.isSuccessful()) throw t.getException();
                                urls.add(t.getResult().toString());
                                return null;
                            });
                }
                chain.addOnCompleteListener(t -> {
                    if (!t.isSuccessful()) {
                        cb.onError(t.getException() != null ? t.getException() : new IOException("Upload failed"));
                        return;
                    }
                    Map<String, Object> react = new HashMap<>();
                    react.put("style", spec.style);
                    react.put("effects", spec.effects);
                    // Water (0) is left out, so these posts also pass rules from before layers.
                    if (spec.layer != 0) react.put("layer", spec.layer);
                    if (spec.squish != ReactWallpapers.Spec.DEFAULT_SQUISH) react.put("squish", (double) spec.squish);
                    List<Double> sizes = new ArrayList<>();
                    for (float s : spec.sizes) sizes.add((double) s);
                    react.put("sizes", sizes);
                    if (background != null) react.put("background", urls.get(urls.size() - 1));
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("mediaType", MEDIA_REACT);
                    extra.put("layers", new ArrayList<>(urls.subList(0, files.size())));
                    extra.put("react", react);
                    uploadImage(app, still, title, isPublic, category, COLLECTION, extra, cb);
                });
            });
        });
    }

    private static void startUpload(FirebaseUser user, String shownName, File fullFile, boolean isVideo, byte[] thumbBytes,
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
                    doc.put("displayName", shownName);
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

    /**
     * The name a new post shows: the display name from Edit profile, else the @username, else the
     * account's own name. Never the start of the email address, which would show it to everyone.
     */
    static String postedName(Context context, FirebaseUser user) {
        Profile own = Social.INSTANCE.local(context);
        String name = own.getName().trim();
        if (name.isEmpty()) name = own.getUsername().trim();
        if (name.isEmpty() && user.getDisplayName() != null) name = user.getDisplayName().trim();
        return name.isEmpty() ? context.getString(R.string.akhu_user) : name;
    }

    public static String displayNameOf(FirebaseUser user) {
        String name = user.getDisplayName();
        if (name != null && !name.trim().isEmpty()) return name;
        String email = user.getEmail();
        if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        return "Anonymous";
    }

    /**
     * Public wallpapers of the given media types (pictures for 2D; videos and 3D for Motion), so
     * each tab gets its own full page of posts. Optionally filtered to one category, sorted by
     * likes (hot) or newest.
     */
    public static Query publicQuery(@Nullable String category, boolean hot, String... mediaTypes) {
        Query q = FirebaseFirestore.getInstance().collection(COLLECTION)
                .whereEqualTo("visibility", VISIBILITY_PUBLIC);
        q = mediaTypes.length == 1 ? q.whereEqualTo("mediaType", mediaTypes[0])
                : q.whereIn("mediaType", Arrays.asList((Object[]) mediaTypes));
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
     * likedBy and likesCount move together. Does nothing when it is already that way. Resolves to
     * the post when this call newly liked it, otherwise null.
     */
    public static Task<CloudWallpaper> setLiked(String collection, String docId, String uid, boolean on) {
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
            return on ? wp : null;
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
    /** All of the signed-in account's posts, public and private, newest first, for its own Profile. */
    public static Query ownPostsQuery(String uid) {
        return FirebaseFirestore.getInstance().collection(COLLECTION)
                .whereEqualTo("uid", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(OWN_POSTS_LIMIT);
    }

    private static final int OWN_POSTS_LIMIT = 300;

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
