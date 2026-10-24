package com.MrBRos.akhu;

/**
 * Wallpaper categories, stored lowercase in the Firestore {@code tags} array. The first
 * {@link #FEATURED_COUNT} also have bundled wallpapers under assets/featured/&lt;id&gt;/.
 */
public final class Categories {

    private Categories() {
    }

    public static final int FEATURED_COUNT = 3;

    public static final String[] IDS = {
            "zelda", "onepiece", "mountains",
            "abstract", "anime", "nature", "dark", "aesthetic", "space"};
    public static final String[] LABELS = {
            "Zelda 🗡️", "One Piece 🏴‍☠️", "Mountains 🏔️",
            "Abstract ✨", "Anime 😍", "Nature 🌿", "Dark 🖤", "Aesthetic 🌸", "Space 🚀"};
}
