package com.MrBRos.akhu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The app's own profile: every wallpaper and theme that comes with Akhu, in 2D, 3D, Motion, React
 * and Themes pages. Built in, so it works offline and needs no account.
 */
class AkhuProfileActivity : AppCompatActivity() {

    private var page by mutableIntStateOf(0)
    private val twoD = ArrayList<WallpaperCard>()
    private val threeD = ArrayList<WallpaperCard>()
    private val motion = ArrayList<WallpaperCard>()
    private val react = ArrayList<WallpaperCard>()
    private val themes = mutableStateListOf<CustomTheme>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = intent.getIntExtra(EXTRA_PAGE, 0)
        twoD.addAll(FeaturedWallpapers.list(this, null).map { WallpaperCard.featured(it) })
        threeD.addAll(FourDWallpapers.list(this).filter { !FourDWallpapers.isUser(it) }.map { WallpaperCard.fourD(it) })
        motion.addAll(MotionWallpapers.list(this).map { WallpaperCard.motion(it) })
        react.addAll(ReactWallpapers.bundled(this).map { WallpaperCard.react(it) })
        val app = applicationContext
        Thread({
            val list = ThemeStore.builtins(app)
            runOnUiThread { themes.addAll(list) }
        }, "AkhuThemes").start()
        setContent {
            AkhuTheme {
                AkhuProfileScreen(
                    page, { page = it }, listOf(twoD, threeD, motion, react), themes,
                    onBack = ::finish, onOpen = ::open,
                    onOpenTheme = { t -> startActivity(ThemePreviewActivity.intent(this, ArrayList(themes.map { it.id }), t.id)) },
                )
            }
        }
    }

    private fun open(card: WallpaperCard) {
        val items = when {
            card.isFourD -> threeD
            card.isVideo -> motion
            card.isReact -> react
            else -> twoD
        }
        val prefix = when {
            card.isFourD -> PreviewActivity.FOURD_PREFIX
            card.isVideo -> PreviewActivity.VIDEO_PREFIX
            card.isReact -> PreviewActivity.REACT_PREFIX
            else -> PreviewActivity.ASSET_PREFIX
        }
        startActivity(PreviewActivity.intent(this, ArrayList(items.map { prefix + it.assetPath }), items.indexOf(card),
            ArrayList(items.map { previewLabel(this, it) })))
    }

    companion object {
        private const val EXTRA_PAGE = "page"
        const val PAGE_3D = 1
        const val PAGE_MOTION = 2
        const val PAGE_REACT = 3
        const val PAGE_THEMES = 4

        @JvmStatic
        @JvmOverloads
        fun intent(context: Context, page: Int = 0): Intent =
            Intent(context, AkhuProfileActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}

@Composable
private fun AkhuProfileScreen(
    page: Int,
    onPage: (Int) -> Unit,
    wallpapers: List<List<WallpaperCard>>,
    themes: List<CustomTheme>,
    onBack: () -> Unit,
    onOpen: (WallpaperCard) -> Unit,
    onOpenTheme: (CustomTheme) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val columns = maxOf(3, LocalConfiguration.current.screenWidthDp / 150)
    val total = wallpapers.sumOf { it.size } + themes.size
    Column(Modifier.fillMaxSize()) {
        AkhuTopBar(stringResource(R.string.akhu_handle), onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = Space.screen).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AndroidView(
                            factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_launcher_round) } },
                            modifier = Modifier.size(86.dp),
                        )
                        Column(Modifier.padding(start = Space.l).weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.app_name), color = colors.onSurface, style = MaterialTheme.typography.titleLarge)
                                Icon(
                                    painterResource(R.drawable.ic_verified), stringResource(R.string.akhu_verified),
                                    Modifier.padding(start = Space.xs).size(20.dp), tint = colors.primary,
                                )
                            }
                            Row(Modifier.padding(top = Space.s)) {
                                Column {
                                    Text(total.toString(), color = colors.onSurface, style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.posts), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.akhu_bio), Modifier.padding(top = Space.m), color = colors.onSurface,
                        style = MaterialTheme.typography.bodyMedium)
                    PillToggle(
                        listOf(stringResource(R.string.section_2d), stringResource(R.string.section_3d),
                            stringResource(R.string.nav_live), stringResource(R.string.section_react), stringResource(R.string.nav_themes)),
                        page, onPage, Modifier.padding(top = Space.l),
                    )
                }
            }
            item(key = "gap") { androidx.compose.foundation.layout.Spacer(Modifier.height(Space.m)) }
            if (page == AkhuProfileActivity.PAGE_THEMES) {
                cardRows(themes, columns, inset = 2.dp, key = { it.id }) { t ->
                    ThemeTile(t, null, 0, wallpaperMax = 640, labels = false) { onOpenTheme(t) }
                }
            } else {
                cardRows(wallpapers[page], columns, inset = 2.dp, key = { "$page:" + cardKey(it) }) { card ->
                    WallpaperTile(card, null, 0, labels = false) { onOpen(card) }
                }
            }
        }
    }
}
