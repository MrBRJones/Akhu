package com.MrBRos.akhu;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Firestore {@code wallpapers/{docId}} document. A post for sale shows a watermarked preview in
 * {@code downloadUrl} and has no content fields; {@link #from(DocumentSnapshot, DocumentSnapshot)}
 * fills them in from its paid document for the seller and buyers.
 */
public class CloudWallpaper {

    public final String id;
    public final String uid;
    public final String displayName;
    public final String downloadUrl;
    /** The post's own {@code downloadUrl}: the preview when it's for sale. Safe to share. */
    public final String publicUrl;
    /** For sale at {@link #tier} (a {@link Market} product ID). */
    public final boolean forSale;
    @Nullable
    public final String tier;
    /** For sale, and its paid content isn't loaded (this account hasn't bought it). */
    private final boolean locked;
    public final String thumbnailUrl;
    public final String title;
    public final String visibility;
    /** {@link FirebaseHelper#MEDIA_VIDEO}, {@link FirebaseHelper#MEDIA_FOURD}, {@link FirebaseHelper#MEDIA_REACT} or {@link FirebaseHelper#MEDIA_IMAGE}; older docs have none and are images. */
    public final String mediaType;
    public final long likesCount;
    public final List<String> likedBy;
    /**
     * 3D posts: layer URLs (far, mid, near, then the optional extra cut-out). Reactive posts: the
     * characters' images. Empty otherwise.
     */
    public final List<String> layers;
    /** 3D posts only: {strength, mid factor}, and the extra layer's {factor, angle} or null. */
    public final float[] motion;
    @Nullable
    public final float[] extraMotion;
    /** Reactive posts only: their settings ({@code style}, {@code sizes}, {@code effects}), else null. */
    @Nullable
    private final Map<String, Object> react;
    /** Reactive posts with a photo background: its URL. */
    @Nullable
    public final String reactBackground;

    @SuppressWarnings("unchecked")
    private CloudWallpaper(DocumentSnapshot post, @Nullable DocumentSnapshot paid) {
        // Content fields come from the paid document when there is one.
        DocumentSnapshot doc = paid != null && paid.exists() ? paid : post;
        id = post.getId();
        uid = post.getString("uid");
        displayName = post.getString("displayName");
        publicUrl = post.getString("downloadUrl");
        String paidUrl = doc.getString("downloadUrl");
        downloadUrl = paidUrl != null ? paidUrl : publicUrl;
        thumbnailUrl = post.getString("thumbnailUrl");
        forSale = Boolean.TRUE.equals(post.getBoolean("forSale"));
        tier = post.getString("tier");
        locked = forSale && doc == post;
        title = post.getString("title");
        visibility = post.getString("visibility");
        String media = post.getString("mediaType");
        mediaType = media == null ? FirebaseHelper.MEDIA_IMAGE : media;
        Long likes = post.getLong("likesCount");
        likesCount = likes == null ? 0 : likes;
        likedBy = strings(post.get("likedBy"));
        layers = strings(doc.get("layers"));
        float[] m = floats(doc.get("motion"));
        motion = m != null ? m : FourDWallpapers.DEFAULT_MOTION.clone();
        extraMotion = floats(doc.get("extraMotion"));
        Object r = doc.get("react");
        react = r instanceof Map ? (Map<String, Object>) r : null;
        Object bg = react != null ? react.get("background") : null;
        reactBackground = bg instanceof String ? (String) bg : null;
    }

    public static CloudWallpaper from(DocumentSnapshot doc) {
        return new CloudWallpaper(doc, null);
    }

    /** A post for sale with its real content, from the {@code paid} document only its seller and buyers can read. */
    public static CloudWallpaper from(DocumentSnapshot post, @Nullable DocumentSnapshot paid) {
        return new CloudWallpaper(post, paid);
    }

    /** For sale and not bought: only the watermarked preview can be shown. */
    public boolean isLocked() {
        return locked;
    }

    /** Posted by the app's official account, so shown as Featured with a star. */
    public boolean isOfficial() {
        return FirebaseHelper.OFFICIAL_UID.equals(uid);
    }

    public boolean isVideo() {
        return FirebaseHelper.MEDIA_VIDEO.equals(mediaType);
    }

    /**
     * A 3D post with all its layers; one missing layers is shown as a plain picture. A locked one
     * counts too (it belongs in the 3D tab), but only its preview can be shown: check {@link #isLocked()}.
     */
    public boolean isFourD() {
        return FirebaseHelper.MEDIA_FOURD.equals(mediaType) && (locked || layers.size() >= 3);
    }

    /** A reactive post with its characters and settings, or a locked one (see {@link #isFourD()}). */
    public boolean isReact() {
        return FirebaseHelper.MEDIA_REACT.equals(mediaType) && (locked || (!layers.isEmpty() && react != null));
    }

    /** A reactive post's settings (defaults for anything missing). */
    public ReactWallpapers.Spec reactSpec() {
        ReactWallpapers.Spec s = new ReactWallpapers.Spec();
        if (react == null) return s;
        Object style = react.get("style");
        if (style instanceof Number) {
            s.style = Math.max(0, Math.min(ReactWallpapers.STYLES.length - 1, ((Number) style).intValue()));
        }
        Object effects = react.get("effects");
        if (effects instanceof Number) s.effects = ((Number) effects).intValue();
        Object layer = react.get("layer");
        if (layer instanceof Number) s.layer = ReactWallpapers.Spec.clampLayer(((Number) layer).intValue());
        Object squish = react.get("squish");
        if (squish instanceof Number) s.squish = ReactWallpapers.Spec.clampSquish(((Number) squish).doubleValue());
        Object sizes = react.get("sizes");
        float[] out = new float[Math.max(1, Math.min(ReactPhysics.MAX_BODIES, layers.size()))];
        for (int i = 0; i < out.length; i++) {
            Object v = sizes instanceof List && i < ((List<?>) sizes).size() ? ((List<?>) sizes).get(i) : null;
            out[i] = ReactWallpapers.Spec.clampSize(v instanceof Number ? ((Number) v).doubleValue() : 0.45);
        }
        s.sizes = out;
        return s;
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof String) out.add((String) o);
            }
        }
        return out;
    }

    @Nullable
    private static float[] floats(Object raw) {
        if (!(raw instanceof List) || ((List<?>) raw).size() != 2) return null;
        float[] out = new float[2];
        for (int i = 0; i < 2; i++) {
            Object o = ((List<?>) raw).get(i);
            if (!(o instanceof Number)) return null;
            out[i] = ((Number) o).floatValue();
        }
        return out;
    }

    public boolean isLikedBy(String userId) {
        return userId != null && likedBy.contains(userId);
    }
}
