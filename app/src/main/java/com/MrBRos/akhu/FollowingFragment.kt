package com.MrBRos.akhu

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot

/**
 * Following tab: the public posts (2D, 3D, Motion) of everyone the user follows, newest first,
 * then their public themes. Drawn with Compose.
 */
class FollowingFragment : Fragment() {

    private val posts = mutableStateListOf<WallpaperCard>()
    private val themes = mutableStateListOf<CustomTheme>()
    private var refreshing by mutableStateOf(false)
    private var empty by mutableStateOf<Int?>(null)
    private var loadGeneration = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AkhuTheme {
                    FollowingScreen(posts, themes, refreshing, empty, ::load, ::openPost) { t ->
                        startActivity(ThemePreviewActivity.intent(requireContext(), ArrayList(themes.map { it.id }), t.id))
                    }
                }
            }
        }

    override fun onResume() {
        super.onResume()
        if (!isHidden) load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) load()
    }

    private fun load() {
        if (view == null) return
        val gen = ++loadGeneration
        val context = requireContext()
        val user = if (FirebaseHelper.isAvailable(context)) FirebaseHelper.currentUser(context) else null
        if (user == null) {
            show(gen, emptyList(), emptyList(), R.string.following_signed_out)
            return
        }
        refreshing = true
        Social.followingIds(user.uid).addOnCompleteListener(requireActivity()) { t ->
            if (gen != loadGeneration || view == null) return@addOnCompleteListener
            val ids = if (t.isSuccessful) t.result else emptyList()
            if (ids.isEmpty()) {
                show(gen, emptyList(), emptyList(), R.string.following_empty)
                return@addOnCompleteListener
            }
            // "in" takes at most 30 values, so long lists are asked in batches.
            val db = FirebaseFirestore.getInstance()
            val postQueries = ids.chunked(IN_LIMIT).map { batch ->
                db.collection(FirebaseHelper.COLLECTION).whereIn("uid", batch)
                    .whereEqualTo("visibility", FirebaseHelper.VISIBILITY_PUBLIC)
                    .orderBy("createdAt", Query.Direction.DESCENDING).limit(LIMIT).get()
            }
            val themeQueries = ids.chunked(IN_LIMIT).map { batch ->
                db.collection(FirebaseHelper.THEMES).whereIn("uid", batch)
                    .whereEqualTo("visibility", FirebaseHelper.VISIBILITY_PUBLIC).limit(LIMIT).get()
            }
            Tasks.whenAllComplete(postQueries + themeQueries).addOnCompleteListener(requireActivity()) {
                if (gen != loadGeneration || view == null) return@addOnCompleteListener
                val postDocs = newestFirst(postQueries)
                val themeDocs = newestFirst(themeQueries)
                val uid = user.uid
                val cards = postDocs.map { CloudWallpaper.from(it) }.map { wp ->
                    Likes.sync(context, Likes.CLOUD + wp.id, wp.isLikedBy(uid))
                    WallpaperCard.cloud(wp, null)
                }
                val app = context.applicationContext
                Thread({
                    val cloudThemes = themeDocs.mapNotNull { doc ->
                        val json = doc.getString("theme") ?: return@mapNotNull null
                        val url = doc.getString("downloadUrl") ?: return@mapNotNull null
                        ThemeStore.cloud(app, doc.id, json, url)?.also { theme ->
                            doc.getString("title")?.let { theme.name = it }
                            Likes.sync(app, Likes.keyOf(theme), CloudWallpaper.from(doc).isLikedBy(uid))
                        }
                    }
                    activity?.runOnUiThread {
                        show(gen, cards, cloudThemes, if (cards.isEmpty() && cloudThemes.isEmpty()) R.string.following_empty else null)
                    }
                }, "FollowingThemes").start()
            }
        }
    }

    private fun newestFirst(queries: List<Task<QuerySnapshot>>): List<DocumentSnapshot> =
        queries.filter { it.isSuccessful }.flatMap { it.result.documents }
            .sortedByDescending { it.getTimestamp("createdAt")?.seconds ?: 0L }

    private fun show(gen: Int, cards: List<WallpaperCard>, cloudThemes: List<CustomTheme>, emptyText: Int?) {
        if (gen != loadGeneration || view == null) return
        refreshing = false
        posts.clear()
        posts.addAll(cards)
        themes.clear()
        themes.addAll(cloudThemes)
        empty = emptyText
    }

    private fun openPost(card: WallpaperCard) {
        startActivity(WallpaperDetailActivity.intent(requireContext(), card.cloudId, posts.mapNotNull { it.cloudId }))
    }

    private companion object {
        const val IN_LIMIT = 30
        const val LIMIT = 50L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowingScreen(
    posts: List<WallpaperCard>,
    themes: List<CustomTheme>,
    refreshing: Boolean,
    empty: Int?,
    onRefresh: () -> Unit,
    onOpen: (WallpaperCard) -> Unit,
    onOpenTheme: (CustomTheme) -> Unit,
) {
    // Same columns as the other grids.
    val columns = maxOf(3, LocalConfiguration.current.screenWidthDp / 180)
    PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = Space.xs, bottom = Space.xl)) {
            if (empty != null) item(key = "empty") { EmptyState(stringResource(empty)) }
            cardRows(posts, columns, key = ::cardKey) { card ->
                WallpaperTile(card, null, 0, labels = false) { onOpen(card) }
            }
            if (themes.isNotEmpty()) {
                item(key = "themesHeader") {
                    Text(
                        stringResource(R.string.following_themes),
                        Modifier.padding(start = 6.dp, end = 6.dp, top = Space.xl, bottom = Space.s),
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                    )
                }
                cardRows(themes, columns, key = { "theme:" + it.id }) { t ->
                    ThemeTile(t, null, 0, wallpaperMax = 640, labels = false) { onOpenTheme(t) }
                }
            }
        }
    }
}
