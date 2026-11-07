package com.MrBRos.akhu;

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
    /** The post itself, for cloud items; null for local ones. */
    @Nullable
    public final CloudWallpaper post;

    private WallpaperCard(Object image, String title, long likes, @Nullable String badge,
                          @Nullable String cloudId, @Nullable String localPath, @Nullable String assetPath) {
        this(image, title, likes, badge, cloudId, localPath, assetPath, null);
    }

    private WallpaperCard(Object image, String title, long likes, @Nullable String badge,
                          @Nullable String cloudId, @Nullable String localPath, @Nullable String assetPath,
                          @Nullable CloudWallpaper post) {
        this.post = post;
        this.image = image;
        this.title = title == null ? "" : title;
        this.likes = likes;
        this.badge = badge;
        this.cloudId = cloudId;
        this.localPath = localPath;
        this.assetPath = assetPath;
    }

    public static WallpaperCard cloud(CloudWallpaper wp, @Nullable String badge) {
        if (badge == null && wp.forSale) badge = "Sale";
        if (badge == null && wp.isOfficial()) badge = "Featured";
        return new WallpaperCard(wp.thumbnailUrl, wp.title, wp.likesCount,
                badge == null && wp.isVideo() ? "Motion" : badge == null && wp.isFourD() ? "4D"
                        : badge == null && wp.isReact() ? "React" : badge, wp.id, null, null, wp);
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
                Names.of(assetPath), -1, "Featured", null, null, assetPath);
    }

    /** A bundled looping video; the card shows its small animated WebP preview loop. */
    public static WallpaperCard motion(String videoAsset) {
        return new WallpaperCard(Uri.parse("file:///android_asset/" + MotionWallpapers.previewFor(videoAsset)),
                Names.of(videoAsset), -1, "Featured", null, null, videoAsset);
    }

    /**
     * A bundled 4D depth wallpaper. The static image is the fallback thumbnail; on Android 13+
     * the grid draws a live-tilt {@link FourDTileView} over it.
     */
    public static WallpaperCard fourD(String imageAsset) {
        // The user's own 3D images (file paths) are tagged 3D; only bundled ones are Featured.
        boolean own = FourDWallpapers.isUser(imageAsset);
        return new WallpaperCard(FourDWallpapers.uri(imageAsset),
                own ? "" : Names.of(imageAsset), -1, own ? "4D" : "Featured", null, null, imageAsset);
    }

    /** A reactive wallpaper (bundled or the user's own); the grid draws its poster. */
    public static WallpaperCard react(String character) {
        boolean own = ReactWallpapers.isUser(character);
        return new WallpaperCard(ReactWallpapers.model(character), own ? "" : Names.of(character), -1,
                own ? "React" : "Featured", null, null, character);
    }

    public boolean isReact() {
        return ReactWallpapers.isReact(assetPath);
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
