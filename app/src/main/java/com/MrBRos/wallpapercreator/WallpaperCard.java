package com.MrBRos.wallpapercreator;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;

/** One tile in the wallpaper grid: either a cloud document or a locally saved file. */
public class WallpaperCard {

    /** Glide model: a download URL for cloud items, a File for local ones. */
    public final Object image;
    public final String title;
    public final long likes;
    @Nullable
    public final String badge;
    @Nullable
    public final String cloudId;
    @Nullable
    public final String localPath;
    @Nullable
    public final String assetPath;

    private WallpaperCard(Object image, String title, long likes, @Nullable String badge,
                          @Nullable String cloudId, @Nullable String localPath, @Nullable String assetPath) {
        this.image = image;
        this.title = title == null ? "" : title;
        this.likes = likes;
        this.badge = badge;
        this.cloudId = cloudId;
        this.localPath = localPath;
        this.assetPath = assetPath;
    }

    public static WallpaperCard cloud(CloudWallpaper wp, @Nullable String badge) {
        return new WallpaperCard(wp.thumbnailUrl, wp.title, wp.likesCount,
                badge == null && wp.isVideo() ? "Motion" : badge, wp.id, null, null);
    }

    public static WallpaperCard local(String path) {
        return new WallpaperCard(new File(path), "", -1, "On device", null, path, null);
    }

    /** A motion video the user made and downloaded; Glide shows a still frame of it. */
    public static WallpaperCard savedMotion(String path) {
        return new WallpaperCard(new File(path), "", -1, "Motion", null, path, null);
    }

    public static WallpaperCard featured(String assetPath) {
        return new WallpaperCard(Uri.parse("file:///android_asset/" + assetPath),
                FeaturedWallpapers.labelFor(assetPath), -1, "Featured", null, null, assetPath);
    }

    /** A bundled looping video; the card shows its small animated WebP preview loop. */
    public static WallpaperCard motion(String videoAsset) {
        return new WallpaperCard(Uri.parse("file:///android_asset/" + MotionWallpapers.previewFor(videoAsset)),
                "", -1, "Motion", null, null, videoAsset);
    }

    /**
     * A bundled 4D depth wallpaper. The static image is the fallback thumbnail; on Android 13+
     * the grid draws a live-tilt {@link FourDTileView} over it.
     */
    public static WallpaperCard fourD(String imageAsset) {
        return new WallpaperCard(FourDWallpapers.uri(imageAsset),
                "", -1, "4D", null, null, imageAsset);
    }

    public boolean isFourD() {
        return FourDWallpapers.isFourD(assetPath);
    }

    public boolean isVideo() {
        return assetPath != null && assetPath.endsWith(".mp4");
    }

    public boolean isLocal() {
        return localPath != null;
    }

    public boolean isFeatured() {
        return assetPath != null;
    }

    public boolean isCloud() {
        return cloudId != null;
    }
}
