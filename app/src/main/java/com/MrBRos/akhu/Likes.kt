package com.MrBRos.akhu

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * What the user has liked, by key: a [PreviewActivity] source string for bundled and saved
 * wallpapers, "cloud:<docId>" for public wallpapers and "theme:<id>" for themes. Kept on the phone
 * so likes work signed out; likes on public posts also go to Firestore so their counts move.
 * Compose reads [isLiked] as state, so hearts redraw when anything is liked.
 */
object Likes {

    private const val PREFS = "likes"
    private const val KEY = "liked"

    private val liked = mutableStateMapOf<String, Boolean>()
    /** Count change since each public post was loaded (+1 liked here, -1 unliked). */
    private val delta = mutableStateMapOf<String, Int>()
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null)?.forEach { liked[it] = true }
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, liked.filterValues { it }.keys.toHashSet()).apply()
    }

    @JvmStatic
    fun isLiked(context: Context, key: String): Boolean {
        load(context)
        return liked[key] == true
    }

    /** How far a public post's like count has moved since it was loaded. */
    @JvmStatic
    fun delta(key: String): Int = delta[key] ?: 0

    /** Double tap: likes, never unlikes (like Instagram). Returns true when it was a new like. */
    @JvmStatic
    fun like(context: Context, key: String): Boolean {
        if (isLiked(context, key)) return false
        set(context, key, true)
        return true
    }

    @JvmStatic
    fun toggle(context: Context, key: String) = set(context, key, !isLiked(context, key))

    private fun set(context: Context, key: String, on: Boolean) {
        load(context)
        liked[key] = on
        persist(context)
        val cloud = cloudTarget(key) ?: return
        delta[key] = (delta[key] ?: 0) + if (on) 1 else -1
        val user = FirebaseHelper.currentUser(context) ?: return
        FirebaseHelper.setLiked(cloud.first, cloud.second, user.uid, on)
    }

    /** Matches the phone's record to what Firestore says, when a public post is loaded. */
    @JvmStatic
    fun sync(context: Context, key: String, likedThere: Boolean) {
        load(context)
        delta.remove(key)
        if ((liked[key] == true) == likedThere) return
        liked[key] = likedThere
        persist(context)
    }

    /** (collection, docId) for a public post's key, or null for things only on this phone. */
    private fun cloudTarget(key: String): Pair<String, String>? = when {
        key.startsWith(CLOUD) -> FirebaseHelper.COLLECTION to key.removePrefix(CLOUD)
        key.startsWith(THEME + ThemeStore.CLOUD_PREFIX) ->
            FirebaseHelper.THEMES to key.removePrefix(THEME + ThemeStore.CLOUD_PREFIX)
        else -> null
    }

    const val CLOUD = "cloud:"
    const val THEME = "theme:"

    @JvmStatic
    fun keyOf(card: WallpaperCard): String = when {
        card.isCloud -> CLOUD + card.cloudId
        card.isVideo && card.assetPath != null -> PreviewActivity.VIDEO_PREFIX + card.assetPath
        card.isFourD -> PreviewActivity.FOURD_PREFIX + card.assetPath
        card.isFeatured -> PreviewActivity.ASSET_PREFIX + card.assetPath
        else -> card.localPath ?: card.title
    }

    @JvmStatic
    fun keyOf(theme: CustomTheme): String = THEME + theme.id
}
