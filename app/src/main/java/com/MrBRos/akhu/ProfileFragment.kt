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
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.mutableLongStateOf
import java.io.File
import java.util.function.Supplier

/** Profile tab: who is signed in, then everything saved on this phone in 2D, 3D, Motion and Themes tabs. Drawn with Compose. */
class ProfileFragment : Fragment() {

    private var header by mutableStateOf(Header())
    private var avatarVersion by mutableLongStateOf(0L)

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        result.data?.data?.let { uri ->
            EditProfileActivity.pickAvatar(requireActivity(), uri) {
                avatarVersion = System.currentTimeMillis()
                if (view != null) bindUser()
            }
        }
    }
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
                        header = header,
                        avatarVersion = avatarVersion,
                        tab = tab,
                        onTab = ::selectTab,
                        actions = HeaderActions(
                            onPicture = { pickLauncher.launch(ImagePickerHelper.createPickIntent()) },
                            onEdit = { startActivity(Intent(requireContext(), EditProfileActivity::class.java)) },
                            onShare = ::shareProfile,
                            onAccount = ::openAccount,
                            onPosts = { (requireActivity() as MainActivity).showMine() },
                        ),
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

    /** Fills the header from the profile saved here, then refreshes it and the counts from the cloud. */
    private fun bindUser() {
        val context = requireContext()
        val user = FirebaseHelper.currentUser(context)
        val p = Social.local(context)
        header = header.copy(
            profile = p,
            avatar = Social.avatarFile(context) ?: p.photoUrl,
            signedIn = user != null,
            fallbackName = if (user != null) FirebaseHelper.displayNameOf(user) else getString(R.string.not_signed_in),
            hint = when {
                user != null -> null
                FirebaseHelper.isAvailable(context) -> getString(R.string.sign_in_hint)
                else -> getString(R.string.cloud_off_short)
            },
            counts = if (user == null) null else header.counts,
        )
        if (user == null) return
        Social.refreshOwn(context)?.addOnSuccessListener(requireActivity()) {
            if (view != null) header = header.copy(profile = Social.local(context),
                avatar = Social.avatarFile(context) ?: Social.local(context).photoUrl)
        }
        Social.counts(user.uid, mine = true).addOnSuccessListener(requireActivity()) {
            if (view != null) header = header.copy(counts = it)
        }
    }

    private fun shareProfile() {
        val h = header
        val who = h.profile.username.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: h.displayName
        val text = getString(R.string.share_profile_text, who)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            getString(R.string.share_profile)))
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
    header: Header,
    avatarVersion: Long,
    tab: Int,
    onTab: (Int) -> Unit,
    actions: HeaderActions,
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
        item(key = "account") { ProfileHeader(header, avatarVersion, actions) }
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

/** What the profile header shows. [counts] are posts, followers, following; null until loaded or signed out. */
internal data class Header(
    val profile: Profile = Profile(),
    val avatar: Any? = null,
    val signedIn: Boolean = false,
    val fallbackName: String = "",
    val hint: String? = null,
    val counts: LongArray? = null,
) {
    val displayName get() = profile.name.ifEmpty { fallbackName }
}

internal class HeaderActions(
    val onPicture: () -> Unit,
    val onEdit: () -> Unit,
    val onShare: () -> Unit,
    val onAccount: () -> Unit,
    val onPosts: () -> Unit,
)

/**
 * Instagram-style header: the picture (tap to change it) beside the name and posts / followers /
 * following, then @username, pronouns and bio, then Edit profile, Share profile and the account
 * button (sign in or out).
 */
@Composable
private fun ProfileHeader(h: Header, avatarVersion: Long, actions: HeaderActions) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = Space.screen).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(onClickLabel = stringResource(R.string.change_picture), onClick = actions.onPicture)) {
                Avatar(h.avatar, 86.dp, version = avatarVersion)
                Box(
                    Modifier.align(Alignment.BottomEnd).size(26.dp)
                        .background(colors.surface, CircleShape).padding(2.dp)
                        .background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(16.dp), tint = colors.onPrimary) }
            }
            Column(Modifier.padding(start = Space.l).weight(1f)) {
                Text(
                    h.displayName, color = colors.onSurface, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = Space.s).fillMaxWidth()) {
                    Stat(h.counts?.get(0), R.string.posts, Modifier.weight(1f), actions.onPosts)
                    Stat(h.counts?.get(1), R.string.followers, Modifier.weight(1f))
                    Stat(h.counts?.get(2), R.string.following, Modifier.weight(1f))
                }
            }
        }
        val p = h.profile
        if (p.username.isNotEmpty() || p.pronouns.isNotEmpty()) {
            Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
                if (p.username.isNotEmpty()) {
                    Text("@" + p.username, color = colors.onSurface, style = MaterialTheme.typography.titleSmall)
                }
                if (p.pronouns.isNotEmpty()) {
                    Text(
                        p.pronouns, Modifier.padding(start = Space.s), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (p.bio.isNotEmpty()) {
            Text(p.bio, Modifier.padding(top = Space.xs), color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        h.hint?.let {
            Text(it, Modifier.padding(top = Space.s), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.padding(top = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            HeaderButton(stringResource(R.string.edit_profile), actions.onEdit, Modifier.weight(1f))
            HeaderButton(stringResource(R.string.share_profile), actions.onShare, Modifier.weight(1f))
            if (h.signedIn) {
                FilledTonalIconButton(
                    onClick = actions.onAccount, modifier = Modifier.size(40.dp), shape = MaterialTheme.shapes.small,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colors.surfaceContainer),
                ) { Icon(painterResource(R.drawable.ic_person), stringResource(R.string.account), Modifier.size(20.dp)) }
            }
        }
        if (!h.signedIn) {
            PrimaryButton(stringResource(R.string.sign_in), actions.onAccount, Modifier.padding(top = Space.s))
        }
    }
}

@Composable
private fun Stat(count: Long?, label: Int, modifier: Modifier, onClick: (() -> Unit)? = null) {
    Column(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Text(count?.toString() ?: "–", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The grey Edit / Share buttons, as on Instagram. */
@Composable
private fun HeaderButton(text: String, onClick: () -> Unit, modifier: Modifier) {
    FilledTonalButton(
        onClick = onClick, modifier = modifier.height(40.dp), shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = Space.s),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}
