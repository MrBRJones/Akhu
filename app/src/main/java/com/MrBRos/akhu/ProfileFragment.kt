package com.MrBRos.akhu

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import java.io.File
import java.util.function.Supplier

/** Profile tab: who is signed in, then everything saved on this phone in 2D, 3D, Motion and Themes tabs. Drawn with Compose. */
class ProfileFragment : Fragment() {

    private var name by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var accountLabel by mutableIntStateOf(R.string.sign_in)
    private var signedIn by mutableStateOf(false)
    private var tab by mutableIntStateOf(selectedTab)
    private var loaded by mutableStateOf(false)
    private var ticks by mutableIntStateOf(0)

    private val twoD = mutableStateListOf<WallpaperCard>()
    private val threeD = mutableStateListOf<WallpaperCard>()
    private val motion = mutableStateListOf<WallpaperCard>()
    private val themes = mutableStateListOf<CustomTheme>()

    // Long-press any card to start selecting; Share and Delete then work on all the ticked ones.
    private val select2d by lazy { selectionOf(twoD, CardActions.saved2d(::loadSaved)) }
    private val select3d by lazy { selectionOf(threeD, CardActions.user3d(::loadSaved)) }
    private val selectMotion by lazy { selectionOf(motion, CardActions.savedMotion(::loadSaved)) }
    private val selectThemes by lazy {
        GridSelection(this, Runnable { ticks++ }, Supplier<List<CustomTheme>> { themes },
            ThemeSelectActions(requireContext(), ::loadSaved))
    }

    private fun selectionOf(list: List<WallpaperCard>, actions: GridSelection.Actions<WallpaperCard>) =
        GridSelection(this, Runnable { ticks++ }, Supplier<List<WallpaperCard>> { list }, actions)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AkhuTheme {
                    ProfileScreen(
                        name, subtitle, accountLabel, signedIn,
                        tab = tab,
                        onTab = ::selectTab,
                        onAccount = ::openAccount,
                        onMyUploads = { (requireActivity() as MainActivity).showMine() },
                        pages = listOf(
                            Page(twoD, select2d, R.string.profile_empty_2d) { open(twoD, it, fourD = false) },
                            Page(threeD, select3d, R.string.profile_empty_3d) { open(threeD, it, fourD = true) },
                            // Motion videos reopen in the editor, where they can be trimmed again, downloaded or set.
                            Page(motion, selectMotion, R.string.profile_empty_motion) {
                                startActivity(VideoEditorActivity.intent(requireContext(), File(it.localPath), false, null))
                            },
                        ),
                        themes = themes,
                        selectThemes = selectThemes,
                        onOpenTheme = { t ->
                            startActivity(Intent(requireContext(), ThemePreviewActivity::class.java)
                                .putExtra(ThemePreviewActivity.EXTRA_ID, t.id))
                        },
                        loaded = loaded,
                        ticks = ticks,
                    )
                }
            }
        }

    private fun selectTab(index: Int) {
        for (s in listOf(select2d, select3d, selectMotion, selectThemes)) s.endSelection()
        tab = index
        selectedTab = index
    }

    private fun openAccount() {
        if (!FirebaseHelper.isAvailable(requireContext())) {
            Toast.makeText(requireContext(), R.string.cloud_disabled, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(requireContext(), AuthActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        bindUser()
        loadSaved()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) {
            bindUser()
            loadSaved()
        }
    }

    private fun bindUser() {
        val context = requireContext()
        val user = FirebaseHelper.currentUser(context)
        signedIn = user != null
        if (user != null) {
            name = FirebaseHelper.displayNameOf(user)
            subtitle = user.email ?: ""
            accountLabel = R.string.account
        } else {
            name = getString(R.string.not_signed_in)
            subtitle = getString(if (FirebaseHelper.isAvailable(context)) R.string.sign_in_hint else R.string.cloud_off_short)
            accountLabel = R.string.sign_in
        }
    }

    /** Reads the saved lists off the main thread, then fills each grid (or shows its empty note). */
    private fun loadSaved() {
        if (view == null) return
        val app = requireContext().applicationContext
        Thread({
            val newTwoD = ImageExporter.getSavedPaths(app).map { WallpaperCard.local(it) }
            val newThreeD = FourDWallpapers.list(app).filter { FourDWallpapers.isUser(it) }.map { WallpaperCard.fourD(it) }
            val removed = MotionWallpapers.removeNsfwSaved(app)
            val newMotion = MotionWallpapers.saved(app).map { WallpaperCard.savedMotion(it) }
            val themeList = ThemeStore.list(app)
            activity?.runOnUiThread {
                if (removed > 0) {
                    Toast.makeText(app, app.resources.getQuantityString(R.plurals.nsfw_saved_removed, removed, removed),
                        Toast.LENGTH_LONG).show()
                }
                if (view == null) return@runOnUiThread
                replace(twoD, newTwoD)
                replace(threeD, newThreeD)
                replace(motion, newMotion)
                replace(themes, themeList)
                loaded = true
                for (s in listOf(select2d, select3d, selectMotion, selectThemes)) s.retain()
            }
        }, "ProfileLoad").start()
    }

    private fun <T> replace(list: MutableList<T>, items: List<T>) {
        list.clear()
        list.addAll(items)
    }

    /** Full-screen preview, swipeable across that tab's items. */
    private fun open(list: List<WallpaperCard>, card: WallpaperCard, fourD: Boolean) {
        val sources = ArrayList(list.map { if (fourD) PreviewActivity.FOURD_PREFIX + it.assetPath else it.localPath })
        startActivity(PreviewActivity.intent(requireContext(), sources, list.indexOf(card).coerceAtLeast(0)))
    }

    companion object {
        /** Which tab is open; kept across visits to the Profile tab. */
        private var selectedTab = 0
    }
}

/** One of the 2D / 3D / Motion tabs: its cards, their selection, its empty note and what a tap opens. */
private class Page(
    val cards: List<WallpaperCard>,
    val selection: GridSelection<WallpaperCard>,
    @StringRes val empty: Int,
    val onOpen: (WallpaperCard) -> Unit,
)

private val TABS = listOf(
    R.drawable.ic_nav_wallpaper to R.string.profile_2d,
    R.drawable.ic_cube to R.string.profile_3d,
    R.drawable.ic_nav_motion to R.string.profile_motion,
    R.drawable.ic_nav_themes to R.string.profile_themes,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileScreen(
    name: String,
    subtitle: String,
    @StringRes accountLabel: Int,
    signedIn: Boolean,
    tab: Int,
    onTab: (Int) -> Unit,
    onAccount: () -> Unit,
    onMyUploads: () -> Unit,
    pages: List<Page>,
    themes: List<CustomTheme>,
    selectThemes: GridSelection<CustomTheme>,
    onOpenTheme: (CustomTheme) -> Unit,
    loaded: Boolean,
    ticks: Int,
) {
    val columns = maxOf(3, LocalConfiguration.current.screenWidthDp / 150)
    // Like a TikTok profile: the account card scrolls away and the tab bar sticks to the top.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Space.xs, bottom = Space.xl)) {
        item(key = "account") { AccountCard(name, subtitle, accountLabel, signedIn, onAccount, onMyUploads) }
        stickyHeader(key = "tabs") { ProfileTabs(tab, onTab) }
        item(key = "top") { Spacer(Modifier.height(2.dp)) }
        if (tab < pages.size) {
            val page = pages[tab]
            if (loaded && page.cards.isEmpty()) item(key = "empty$tab") { EmptyState(stringResource(page.empty)) }
            cardRows(page.cards, columns, inset = 2.dp, key = { "$tab:" + (it.localPath ?: it.assetPath ?: it.title) }) { card ->
                WallpaperTile(card, page.selection, ticks) { page.onOpen(card) }
            }
        } else {
            if (loaded && themes.isEmpty()) item(key = "emptyThemes") { EmptyState(stringResource(R.string.profile_empty_themes)) }
            cardRows(themes, columns, inset = 2.dp, key = { "themes:" + it.id }) { t ->
                ThemeTile(t, selectThemes, ticks, wallpaperMax = 480) { onOpenTheme(t) }
            }
        }
    }
}

@Composable
private fun AccountCard(
    name: String,
    subtitle: String,
    @StringRes accountLabel: Int,
    signedIn: Boolean,
    onAccount: () -> Unit,
    onMyUploads: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(horizontal = Space.screen)
            .fillMaxWidth()
            .background(colors.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).background(rememberBannerBrush(), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_person), null, Modifier.size(32.dp), tint = Color.White)
            }
            Column(Modifier.padding(start = Space.l).weight(1f)) {
                Text(
                    name, color = colors.onSurface, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle, Modifier.padding(top = 2.dp), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.padding(top = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = onAccount, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(accountLabel), maxLines = 1)
            }
            if (signedIn) {
                OutlinedButton(onClick = onMyUploads, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.my_uploads), maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileTabs(tab: Int, onTab: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface).padding(top = Space.s)) {
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = colors.surface,
            indicator = {
                TabRowDefaults.PrimaryIndicator(
                    Modifier.tabIndicatorOffset(tab, matchContentSize = true),
                    width = Dp.Unspecified,
                    color = colors.onSurface,
                )
            },
            divider = {},
        ) {
            TABS.forEachIndexed { i, (@DrawableRes icon, @StringRes label) ->
                Tab(
                    selected = i == tab,
                    onClick = { onTab(i) },
                    text = { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) },
                    icon = { Icon(painterResource(icon), null) },
                    selectedContentColor = colors.onSurface,
                    unselectedContentColor = colors.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.surfaceContainer))
    }
}
