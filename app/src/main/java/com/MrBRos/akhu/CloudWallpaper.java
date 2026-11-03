package com.MrBRos.akhu;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A Firestore {@code wallpapers/{docId}} document. */
public class CloudWallpaper {

    public final String id;
    public final String uid;
    public final String displayName;
    public final String downloadUrl;
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
    private CloudWallpaper(DocumentSnapshot doc) {
        id = doc.getId();
        uid = doc.getString("uid");
        displayName = doc.getString("displayName");
        downloadUrl = doc.getString("downloadUrl");
        thumbnailUrl = doc.getString("thumbnailUrl");
        title = doc.getString("title");
        visibility = doc.getString("visibility");
        String media = doc.getString("mediaType");
        mediaType = media == null ? FirebaseHelper.MEDIA_IMAGE : media;
        Long likes = doc.getLong("likesCount");
        likesCount = likes == null ? 0 : likes;
        likedBy = strings(doc.get("likedBy"));
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
        return new CloudWallpaper(doc);
    }

    public boolean isVideo() {
        return FirebaseHelper.MEDIA_VIDEO.equals(mediaType);
    }

    /** A 3D post with all its layers; one missing layers is shown as a plain picture. */
    public boolean isFourD() {
        return FirebaseHelper.MEDIA_FOURD.equals(mediaType) && layers.size() >= 3;
    }

    /** A reactive post with its characters and settings; one missing them is shown as a plain picture. */
    public boolean isReact() {
        return FirebaseHelper.MEDIA_REACT.equals(mediaType) && !layers.isEmpty() && react != null;
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
