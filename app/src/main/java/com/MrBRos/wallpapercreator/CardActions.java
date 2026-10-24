package com.MrBRos.wallpapercreator;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;

/** Multi-select rules for each kind of card the user can pick: their saved 2D pictures, 3D images and motion videos. */
final class CardActions {

    private CardActions() {
    }

    /** Saved 2D pictures (cards with a local file); bundled and cloud cards can't be picked. */
    static GridSelection.Actions<WallpaperCard> saved2d(Runnable reload) {
        return new Base(reload) {
            @Override
            public String key(WallpaperCard card) {
                return String.valueOf(card.localPath);
            }

            @Override
            public boolean canSelect(WallpaperCard card) {
                return card.isLocal();
            }

            @Nullable
            @Override
            public File file(WallpaperCard card) {
                return card.localPath == null ? null : new File(card.localPath);
            }

            @Override
            public void delete(Context app, WallpaperCard card) {
                ImageExporter.removeSaved(app, card.localPath);
            }
        };
    }

    /** The user's own 3D images; Share sends the picture they were made from. */
    static GridSelection.Actions<WallpaperCard> user3d(Runnable reload) {
        return new Base(reload) {
            @Override
            public String key(WallpaperCard card) {
                return String.valueOf(card.assetPath);
            }

            @Override
            public boolean canSelect(WallpaperCard card) {
                return card.isFourD() && FourDWallpapers.isUser(card.assetPath);
            }

            @Nullable
            @Override
            public File file(WallpaperCard card) {
                return new File(card.assetPath);
            }

            @Override
            public void delete(Context app, WallpaperCard card) {
                FourDWallpapers.delete(app, card.assetPath);
            }
        };
    }

    /** Motion videos the user saved from the editor. */
    static GridSelection.Actions<WallpaperCard> savedMotion(Runnable reload) {
        return new Base(reload) {
            @Override
            public String key(WallpaperCard card) {
                return String.valueOf(card.localPath);
            }

            @Override
            public boolean canSelect(WallpaperCard card) {
                return card.isLocal();
            }

            @Nullable
            @Override
            public File file(WallpaperCard card) {
                return new File(card.localPath);
            }

            @Override
            public void delete(Context app, WallpaperCard card) {
                //noinspection ResultOfMethodCallIgnored
                new File(card.localPath).delete();
            }
        };
    }

    private abstract static class Base implements GridSelection.Actions<WallpaperCard> {
        private final Runnable reload;

        Base(Runnable reload) {
            this.reload = reload;
        }

        @Override
        public void reload() {
            reload.run();
        }
    }
}
