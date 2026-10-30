package com.MrBRos.akhu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors

/**
 * Themes tab, laid out like the 2D tab: the Theme Creation banner, a Hot/New sort, then the nine
 * built-in themes and every theme users have posted publicly. The user's own themes live in
 * Profile, so deleting one there never changes this tab. Drawn with Compose.
 */
class ThemesFragment : Fragment() {

    private val themes = mutableStateListOf<CustomTheme>()
    /** Like counts of the public themes, by theme id. */
    private val likes = mutableStateMapOf<String, Long>()
    private var hot by mutableStateOf(true)
    private var refreshing by mutableStateOf(false)
    private var loadGeneration = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AkhuTheme {
                    ThemesScreen(
                        themes = themes,
                        likes = likes,
                        hot = hot,
                        refreshing = refreshing,
                        onSort = { hot = it; reload() },
                        onRefresh = ::reload,
                        onCreate = { createTheme(requireContext()) },
                        onOpen = { t ->
                            startActivity(ThemePreviewActivity.intent(requireContext(), ArrayList(themes.map { it.id }), t.id))
                        },
                    )
                }
            }
        }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) reload()
    }

    private fun reload() {
        if (view == null) return
        val gen = ++loadGeneration
        val app = requireContext().applicationContext
        Thread({
            val builtins = ThemeStore.builtins(app)
            activity?.runOnUiThread {
                if (gen != loadGeneration || view == null) return@runOnUiThread
                if (themes.isEmpty()) themes.addAll(builtins)
                loadPublic(gen, builtins)
            }
        }, "ThemesLoad").start()
    }

    /** Adds the public themes after the built-in ones, downloading any not seen before. */
    private fun loadPublic(gen: Int, builtins: List<CustomTheme>) {
        val context = requireContext()
        if (!FirebaseHelper.isAvailable(context)) {
            show(gen, builtins, emptyList())
            return
        }
        refreshing = true
        val uid = FirebaseHelper.currentUser(context)?.uid
        FirebaseHelper.publicThemesQuery(hot).get().addOnCompleteListener(requireActivity()) { t ->
            if (gen != loadGeneration || view == null) return@addOnCompleteListener
            val docs = if (t.isSuccessful) t.result.documents else emptyList()
            val app = context.applicationContext
            Thread({
                val cloud = ArrayList<Pair<CustomTheme, Long>>()
                for (doc in docs) {
                    val json = doc.getString("theme") ?: continue
                    val url = doc.getString("downloadUrl") ?: continue
                    val theme = ThemeStore.cloud(app, doc.id, json, url) ?: continue
                    doc.getString("title")?.let { theme.name = it }
                    val wp = CloudWallpaper.from(doc)
                    Likes.sync(app, Likes.keyOf(theme), wp.isLikedBy(uid))
                    cloud.add(theme to wp.likesCount)
                }
                activity?.runOnUiThread { show(gen, builtins, cloud) }
            }, "ThemesCloud").start()
        }
    }

    private fun show(gen: Int, builtins: List<CustomTheme>, cloud: List<Pair<CustomTheme, Long>>) {
        if (gen != loadGeneration || view == null) return
        refreshing = false
        likes.clear()
        cloud.forEach { (t, n) -> likes[t.id] = n }
        // Hot puts the most liked public themes first; New keeps the built-ins on top.
        val list = if (hot) cloud.filter { it.second > 0 }.map { it.first } + builtins + cloud.filter { it.second <= 0 }.map { it.first }
        else builtins + cloud.map { it.first }
        themes.clear()
        themes.addAll(list)
    }

    companion object {
        /** Opens the editor on a new theme. */
        @JvmStatic
        fun createTheme(context: Context) {
            context.startActivity(Intent(context, ThemeEditorActivity::class.java))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemesScreen(
    themes: List<CustomTheme>,
    likes: Map<String, Long>,
    hot: Boolean,
    refreshing: Boolean,
    onSort: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (CustomTheme) -> Unit,
) {
    // Same columns as the 2D and Motion grids.
    val columns = maxOf(3, LocalConfiguration.current.screenWidthDp / 180)
    PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = Space.xs, bottom = Space.xl),
        ) {
            item(key = "banner") {
                GradientBanner(
                    stringResource(R.string.theme_creation), stringResource(R.string.theme_creation_sub), R.drawable.ic_plus,
                    onCreate, Modifier.padding(horizontal = 6.dp),
                )
            }
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = Space.xl, bottom = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.section_popular), Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                    )
                    PillToggle(
                        listOf(stringResource(R.string.sort_hot), stringResource(R.string.sort_new)),
                        if (hot) 0 else 1, { onSort(it == 0) },
                    )
                }
            }
            cardRows(themes, columns, key = { it.id }) { t ->
                ThemeTile(t, null, 0, wallpaperMax = 640, likes = likes[t.id] ?: -1) { onOpen(t) }
            }
        }
    }
}
