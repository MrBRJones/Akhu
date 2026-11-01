package com.MrBRos.akhu;

/**
 * Wallpaper categories, stored lowercase in the Firestore {@code tags} array. The first
 * {@link #FEATURED_COUNT} also have bundled wallpapers under assets/featured/&lt;id&gt;/.
 */
public final class Categories {

    private Categories() {
    }

    public static final int FEATURED_COUNT = 4;

    public static final String[] IDS = {
            "art", "space", "nature", "egypt",
            "mountains", "abstract", "anime", "dark", "aesthetic"};
    public static final String[] LABELS = {
            "Art 🎨", "Space 🚀", "Nature 🌿", "Egypt 🏺",
            "Mountains 🏔️", "Abstract ✨", "Anime 😍", "Dark 🖤", "Aesthetic 🌸"};
}
