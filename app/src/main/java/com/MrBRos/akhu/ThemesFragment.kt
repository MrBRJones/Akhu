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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import java.util.function.Supplier

/** Themes tab: the Theme Creation banner over a grid of the saved home screen themes. Drawn with Compose. */
class ThemesFragment : Fragment() {

    private val themes = mutableStateListOf<CustomTheme>()
    private var loaded by mutableStateOf(false)
    private var ticks by mutableIntStateOf(0)

    // Long-press a theme to start selecting; Share and Delete then work on all the ticked ones.
    private val selection: GridSelection<CustomTheme> by lazy {
        GridSelection(this, Runnable { ticks++ }, Supplier<List<CustomTheme>> { themes },
            ThemeSelectActions(requireContext(), ::reload))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AkhuTheme {
                    ThemesScreen(
                        themes = themes,
                        loaded = loaded,
                        ticks = ticks,
                        selection = selection,
                        onCreate = { createTheme(requireContext()) },
                        onOpen = { t ->
                            startActivity(Intent(requireContext(), ThemePreviewActivity::class.java)
                                .putExtra(ThemePreviewActivity.EXTRA_ID, t.id))
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
        val app = requireContext().applicationContext
        Thread({
            val list = ThemeStore.list(app)
            activity?.runOnUiThread {
                if (view == null) return@runOnUiThread
                themes.clear()
                themes.addAll(list)
                loaded = true
                selection.retain()
            }
        }, "ThemesLoad").start()
    }

    companion object {
        /** Opens the editor on a new theme. */
        @JvmStatic
        fun createTheme(context: Context) {
            context.startActivity(Intent(context, ThemeEditorActivity::class.java))
        }
    }
}

@Composable
private fun ThemesScreen(
    themes: List<CustomTheme>,
    loaded: Boolean,
    ticks: Int,
    selection: GridSelection<CustomTheme>,
    onCreate: () -> Unit,
    onOpen: (CustomTheme) -> Unit,
) {
    // Same columns as the 2D and Motion grids.
    val columns = maxOf(3, LocalConfiguration.current.screenWidthDp / 180)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 16.dp),
    ) {
        item(key = "banner") { CreationBanner(onCreate) }
        item(key = "label") {
            Text(
                stringResource(R.string.my_themes),
                Modifier.padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        cardRows(themes, columns, key = { it.id }) { t ->
            ThemeTile(t, selection, ticks, wallpaperMax = 640) { onOpen(t) }
        }
        if (loaded && themes.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.themes_empty),
                    Modifier
                        .padding(6.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Theme Creation" banner in the app's gradient: opens the editor for a new theme. */
@Composable
private fun CreationBanner(onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(rememberBannerBrush())
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 18.dp)) {
            Text(stringResource(R.string.theme_creation), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.theme_creation_sub), Modifier.padding(top = 2.dp),
                color = Color(0xE6FFFFFF), fontSize = 13.sp,
            )
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 18.dp)
                .size(52.dp)
                .background(Color(0x66000000), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("＋", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The banner gradient (bg_banner.xml): classic blue-purple-pink, or the custom app colors. */
@Composable
fun rememberBannerBrush(): Brush {
    val context = LocalContext.current
    return remember(context) {
        fun c(attr: Int) = Color(MaterialColors.getColor(context, attr, 0))
        Brush.horizontalGradient(listOf(c(R.attr.akhuGradientStart), c(R.attr.akhuGradientCenter), c(R.attr.akhuGradientEnd)))
    }
}
