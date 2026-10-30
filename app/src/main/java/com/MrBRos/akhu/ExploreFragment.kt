package com.MrBRos.akhu

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import java.util.Locale
import java.util.function.Supplier

/**
 * 2D tab: banner, category chips, Hot/New sort and the card grid. Drawn with Compose.
 * "Explore" shows public cloud wallpapers; "Mine" shows saved files plus the user's uploads.
 * Without Firebase both modes fall back to the wallpapers saved on this device.
 */
class ExploreFragment : Fragment() {

    private val loaded = ArrayList<WallpaperCard>()
    private val visible = mutableStateListOf<WallpaperCard>()
    private var showMine by mutableStateOf(false)
    private var hot by mutableStateOf(true)
    private var category by mutableStateOf<String?>(null)
    private var search by mutableStateOf("")
    private var searching by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var cloudOff by mutableStateOf(false)
    private var empty by mutableStateOf<Int?>(null)
    private var ticks by mutableIntStateOf(0)
    private var loadGeneration = 0

    // Long-press starts multi-select; only pictures saved on this phone can be picked.
    private val selection: GridSelection<WallpaperCard> by lazy {
        GridSelection(this, Runnable { ticks++ }, Supplier<List<WallpaperCard>> { visible }, CardActions.saved2d(::load))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AkhuTheme {
                    ExploreScreen(
                        cards = visible, ticks = ticks, selection = selection,
                        mine = showMine, hot = hot, category = category,
                        searching = searching, search = search, refreshing = refreshing,
                        cloudOff = cloudOff, empty = empty,
                        onSearch = { search = it; applyFilter() },
                        onBanner = { (requireActivity() as MainActivity).selectTab(R.id.nav_create) },
                        onCategory = { category = it; load() },
                        onSort = { hot = it; load() },
                        onRefresh = ::load,
                        onOpen = ::onCardClick,
                    )
                }
            }
        }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** Switches between Explore (public) and Mine. */
    fun setMine(mine: Boolean) {
        if (showMine == mine) return
        showMine = mine
        if (view != null) load()
    }

    fun toggleSearch() {
        searching = !searching
        if (!searching && search.isNotEmpty()) {
            search = ""
            applyFilter()
        }
    }

    // --- Loading ---------------------------------------------------------------------------

    private fun load() {
        if (view == null) return
        val gen = ++loadGeneration
        val context = requireContext()
        val cloud = FirebaseHelper.isAvailable(context)
        cloudOff = !cloud

        val cards = ArrayList<WallpaperCard>()
        val mine = showMine
        if (!mine) {
            for (asset in FeaturedWallpapers.list(context, category)) cards.add(WallpaperCard.featured(asset))
        }
        if (mine || (!cloud && category == null)) {
            for (path in ImageExporter.getSavedPaths(context)) cards.add(WallpaperCard.local(path))
        }
        if (!cloud) {
            show(gen, cards)
            return
        }

        val query = if (mine) {
            val user = FirebaseHelper.currentUser(context)
            if (user == null) {
                show(gen, cards)
                return
            }
            FirebaseHelper.mineQuery(user.uid)
        } else {
            FirebaseHelper.publicQuery(category, hot)
        }
        refreshing = true
        query.get().addOnCompleteListener(requireActivity()) { t ->
            if (gen != loadGeneration || view == null) return@addOnCompleteListener
            if (!t.isSuccessful) {
                val msg = t.exception?.message ?: ""
                Toast.makeText(context, getString(R.string.gallery_load_failed) + ": " + msg, Toast.LENGTH_LONG).show()
            } else {
                t.result.documents.forEachIndexed { i, doc ->
                    val wp = CloudWallpaper.from(doc)
                    val badge = when {
                        mine -> if (FirebaseHelper.VISIBILITY_PRIVATE == wp.visibility) "Private" else "Public"
                        hot && i < HOT_BADGE_COUNT && wp.likesCount > 0 -> "Hot"
                        else -> null
                    }
                    cards.add(WallpaperCard.cloud(wp, badge))
                }
            }
            show(gen, cards)
        }
    }

    private fun show(gen: Int, cards: List<WallpaperCard>) {
        if (gen != loadGeneration || view == null) return
        refreshing = false
        loaded.clear()
        loaded.addAll(cards)
        applyFilter()
    }

    private fun applyFilter() {
        val context = context ?: return
        val q = search.trim().lowercase(Locale.ROOT)
        visible.clear()
        visible.addAll(loaded.filter { q.isEmpty() || it.title.lowercase(Locale.ROOT).contains(q) })
        selection.retain()
        empty = if (visible.isNotEmpty()) null
        else if (showMine && FirebaseHelper.isAvailable(context) && FirebaseHelper.currentUser(context) == null)
            R.string.mine_empty_signed_out
        else R.string.gallery_empty
    }

    // --- Card actions ----------------------------------------------------------------------

    private fun onCardClick(card: WallpaperCard) {
        if (card.isLocal || card.isFeatured) {
            // Full-screen preview, swipeable across the featured/saved cards currently shown.
            val sources = ArrayList<String>()
            var index = 0
            for (c in visible) {
                if (c.isCloud) continue
                if (c === card) index = sources.size
                sources.add(if (c.isFeatured) PreviewActivity.ASSET_PREFIX + c.assetPath else c.localPath ?: continue)
            }
            startActivity(PreviewActivity.intent(requireContext(), sources, index))
        } else {
            startActivity(Intent(requireContext(), WallpaperDetailActivity::class.java)
                .putExtra(WallpaperDetailActivity.EXTRA_DOC_ID, card.cloudId))
        }
    }

    private companion object {
        const val HOT_BADGE_COUNT = 4
    }
}

/** Stable key for a card in any grid. */
internal fun cardKey(c: WallpaperCard): String = c.cloudId ?: c.localPath ?: c.assetPath ?: c.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreScreen(
    cards: List<WallpaperCard>,
    ticks: Int,
    selection: GridSelection<WallpaperCard>,
    mine: Boolean,
    hot: Boolean,
    category: String?,
    searching: Boolean,
    search: String,
    refreshing: Boolean,
    cloudOff: Boolean,
    empty: Int?,
    onSearch: (String) -> Unit,
    onBanner: () -> Unit,
    onCategory: (String?) -> Unit,
    onSort: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (WallpaperCard) -> Unit,
) {
    // Same columns as the Motion and Themes grids.
    val columns = maxOf(3, LocalConfiguration.current.screenWidthDp / 180)
    PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = Space.xs, bottom = Space.xl)) {
            if (searching) {
                item(key = "search") {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(
                        value = search, onValueChange = onSearch, singleLine = true, shape = CircleShape,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = Space.m).focusRequester(focus),
                    )
                }
            }
            if (!mine) {
                item(key = "banner") {
                    GradientBanner(
                        stringResource(R.string.banner_title), stringResource(R.string.banner_subtitle),
                        R.drawable.ic_sparkle, onBanner, Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
            if (cloudOff) {
                item(key = "cloudOff") {
                    Text(
                        stringResource(R.string.cloud_off_note),
                        Modifier
                            .padding(start = 6.dp, end = 6.dp, top = Space.m)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                            .padding(Space.m),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!mine) {
                item(key = "chips") {
                    val all = listOf<Pair<String?, String>>(null to stringResource(R.string.chip_all)) +
                            Categories.IDS.indices.map { Categories.IDS[it] to Categories.LABELS[it] }
                    LazyRow(
                        Modifier.padding(top = Space.l),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        items(all, key = { it.first ?: "all" }) { (id, label) ->
                            CategoryPill(label, category == id) { onCategory(id) }
                        }
                    }
                }
            }
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = Space.xl, bottom = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (mine) R.string.section_mine else R.string.section_popular),
                        Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!mine) {
                        PillToggle(
                            listOf(stringResource(R.string.sort_hot), stringResource(R.string.sort_new)),
                            if (hot) 0 else 1, { onSort(it == 0) },
                        )
                    }
                }
            }
            if (empty != null) item(key = "empty") { EmptyState(stringResource(empty)) }
            cardRows(cards, columns, key = ::cardKey) { card ->
                WallpaperTile(card, selection, ticks) { onOpen(card) }
            }
        }
    }
}
