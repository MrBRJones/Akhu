package com.MrBRos.akhu;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

/** A Firestore {@code wallpapers/{docId}} document. */
public class CloudWallpaper {

    public final String id;
    public final String uid;
    public final String displayName;
    public final String downloadUrl;
    public final String thumbnailUrl;
    public final String title;
    public final String visibility;
    /** {@link FirebaseHelper#MEDIA_VIDEO}, {@link FirebaseHelper#MEDIA_FOURD} or {@link FirebaseHelper#MEDIA_IMAGE}; older docs have none and are images. */
    public final String mediaType;
    public final long likesCount;
    public final List<String> likedBy;
    /** 3D posts only: layer URLs (far, mid, near, then the optional extra cut-out); empty otherwise. */
    public final List<String> layers;
    /** 3D posts only: {strength, mid factor}, and the extra layer's {factor, angle} or null. */
    public final float[] motion;
    @Nullable
    public final float[] extraMotion;

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
