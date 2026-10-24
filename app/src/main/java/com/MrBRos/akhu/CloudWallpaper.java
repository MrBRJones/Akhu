package com.MrBRos.akhu;

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
    /** {@link FirebaseHelper#MEDIA_VIDEO} or {@link FirebaseHelper#MEDIA_IMAGE}; older docs have none and are images. */
    public final String mediaType;
    public final long likesCount;
    public final List<String> likedBy;

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
        List<String> liked = new ArrayList<>();
        Object raw = doc.get("likedBy");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof String) liked.add((String) o);
            }
        }
        likedBy = liked;
    }

    public static CloudWallpaper from(DocumentSnapshot doc) {
        return new CloudWallpaper(doc);
    }

    public boolean isVideo() {
        return FirebaseHelper.MEDIA_VIDEO.equals(mediaType);
    }

    public boolean isLikedBy(String userId) {
        return userId != null && likedBy.contains(userId);
    }
}
