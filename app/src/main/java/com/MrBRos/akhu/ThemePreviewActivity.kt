package com.MrBRos.akhu

import androidx.annotation.DrawableRes
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.util.ArrayDeque

/**
 * VibeWalls-style theme preview: swipeable phone mockups of every saved theme with a Home/Lock
 * toggle, and One-Tap Setup, which adds the themed icons to the home screen and sets the wallpaper.
 * Drawn with Compose.
 *
 * Each new icon is a pinned shortcut, and Android asks the user to confirm each one, so they are
 * requested one at a time: the next goes out when the launcher reports the last one added, or when
 * this screen resumes after the confirmation was dismissed.
 */
class ThemePreviewActivity : AppCompatActivity() {

    private val themes = mutableStateListOf<CustomTheme>()
    private val main = Handler(Looper.getMainLooper())
    private var lock by mutableStateOf(false)
    private var page by mutableIntStateOf(0)
    /** A page the pager should jump to (after loading), or null. */
    private var jumpTo by mutableStateOf<Int?>(null)

    // One-Tap Setup state.
    private val pinQueue = ArrayDeque<CustomTheme.App>()
    private var setupTheme: CustomTheme? = null
    private var setupWallpaper = false
    /** Widget kinds still to place, after the icons. */
    private val widgetQueue = ArrayDeque<Class<out ThemedWidgetProvider>>()
    private var widgetTotal = 0
    private var pinSeq = 0
    private var pinPending = false
    private var pausedDuringPin = false
    private var pinTotal = 0
    private var setupBusy by mutableStateOf(false)
    /** The "Adding icon n of N" note, shown with a Stop button while icons are being added. */
    private var progress by mutableStateOf<String?>(null)

    private val pinnedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            pinDone(intent.getIntExtra(EXTRA_SEQ, -1))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, pinnedReceiver, IntentFilter(ACTION_PINNED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        // Full screen: the mock's own clock stands in for the status bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            AkhuTheme {
                PreviewScreen(
                    themes = themes,
                    page = page,
                    jumpTo = jumpTo,
                    onJumped = { jumpTo = null },
                    onPage = { page = it },
                    lock = lock,
                    onLock = { lock = it },
                    setupBusy = setupBusy,
                    progress = progress,
                    onBack = ::finish,
                    onEdit = {
                        current()?.let { startActivity(Intent(this, ThemeEditorActivity::class.java).putExtra(EXTRA_ID, it.id)) }
                    },
                    onDelete = ::confirmDelete,
                    onShare = ::share,
                    onSetup = ::askSetup,
                    onStop = {
                        pinQueue.clear()
                        widgetQueue.clear()
                        pinPending = false
                        finishSetup(pinTotal)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadThemes()
        if (pinPending && pausedDuringPin) {
            // The confirmation was answered (added or cancelled); move on either way.
            val seq = pinSeq
            main.postDelayed({ pinDone(seq) }, 400)
        }
    }

    override fun onPause() {
        if (pinPending) pausedDuringPin = true
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(pinnedReceiver)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun loadThemes() {
        val app = applicationContext
        val wanted = current()?.id ?: intent.getStringExtra(EXTRA_ID)
        Thread({
            val ids = intent.getStringArrayListExtra(EXTRA_IDS)
            val list = ids?.mapNotNull { ThemeStore.load(app, it) } ?: ThemeStore.list(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (list.isEmpty()) {
                    finish()
                    return@runOnUiThread
                }
                themes.clear()
                themes.addAll(list)
                val index = list.indexOfFirst { it.id == wanted }.coerceAtLeast(0)
                page = index
                jumpTo = index
            }
        }, "ThemePreviewLoad").start()
    }

    private fun current(): CustomTheme? = themes.getOrNull(page)

    /** Shares a picture of the theme as it looks on screen. */
    private fun share(mock: View) {
        if (mock.width == 0 || mock.height == 0) return
        val bmp = Bitmap.createBitmap(mock.width, mock.height, Bitmap.Config.ARGB_8888)
        mock.draw(Canvas(bmp))
        val app = applicationContext
        Thread({
            val file = File(File(app.cacheDir, "picked"), "theme_share.jpg")
            val ok = try {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                true
            } catch (e: IOException) {
                false
            } finally {
                bmp.recycle()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) startActivity(ImageExporter.createShareIntent(this, file))
                else Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
        }, "ThemeShare").start()
    }

    private fun confirmDelete() {
        val t = current() ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_theme_title, t.name))
            .setMessage(R.string.delete_theme_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                ThemeStore.delete(this, t)
                loadThemes()
            }
            .show()
    }

    // ---- One-Tap Setup ----

    private fun askSetup() {
        val t = current() ?: return
        if (pinPending) return
        // Icons and wallpaper are ticked; of the widgets only the clock, when the theme has one.
        val items = listOf(getString(R.string.setup_icons, t.apps.size), getString(R.string.setup_wallpaper)) +
            WIDGET_KINDS.map { getString(R.string.setup_widget_named, getString(it.first)) }
        val checked = BooleanArray(items.size) { i ->
            i < 2 || (WIDGET_KINDS[i - 2].second == ThemeClockWidgetProvider::class.java && t.widget != CustomTheme.WIDGET_NONE)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.one_tap_setup)
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ ->
                startSetup(t, checked[0], checked[1], WIDGET_KINDS.filterIndexed { i, _ -> checked[i + 2] }.map { it.second })
            }
            .show()
    }

    private fun startSetup(theme: CustomTheme, icons: Boolean, wallpaper: Boolean, widgets: List<Class<out ThemedWidgetProvider>>) {
        val t = theme.copy()
        setupTheme = t
        setupWallpaper = wallpaper
        pinQueue.clear()
        widgetQueue.clear()
        if (widgets.isNotEmpty() && !AppWidgetManager.getInstance(this).isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.pin_unsupported_widget, Toast.LENGTH_LONG).show()
        } else {
            widgetQueue.addAll(widgets)
        }
        widgetTotal = widgetQueue.size
        pinTotal = 0
        if (!icons) {
            if (widgetQueue.isEmpty()) finishSetup(0) else {
                setupBusy = true
                progress = ""
                pinNext()
            }
            return
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, R.string.pin_unsupported_icons, Toast.LENGTH_LONG).show()
            finishSetup(0)
            return
        }
        setupBusy = true
        val app = applicationContext
        Thread({
            val missing = ThemeShortcuts.updatePinned(app, t)
            // Warm the icon cache so each request goes out without rendering on the main thread.
            for (a in missing) ThemeShortcuts.build(app, t, a)
            val restyled = t.apps.size - missing.size
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                pinQueue.addAll(missing)
                pinTotal = missing.size
                if (restyled > 0) Toast.makeText(this, getString(R.string.icons_restyled, restyled), Toast.LENGTH_SHORT).show()
                if (pinTotal > 0 || widgetTotal > 0) progress = ""
                pinNext()
            }
        }, "ThemeShortcuts").start()
    }

    /** Asks for the next icon, then the next widget; each waits for the launcher's answer. */
    private fun pinNext() {
        val app = pinQueue.poll()
        if (app == null) {
            val kind = widgetQueue.poll()
            val t = setupTheme
            if (kind == null || t == null) {
                finishSetup(pinTotal)
                return
            }
            val seq = ++pinSeq
            pinPending = true
            pausedDuringPin = false
            val label = getString(WIDGET_KINDS.first { it.second == kind }.first)
            progress = getString(R.string.adding_widget, widgetTotal - widgetQueue.size, widgetTotal, label)
            if (!ThemedWidgetProvider.requestPin(this, kind, t, seq)) pinDone(seq)
            return
        }
        val seq = ++pinSeq
        pinPending = true
        pausedDuringPin = false
        if (progress != null) progress = getString(R.string.adding_icon, pinTotal - pinQueue.size, pinTotal, app.label)
        val cb = Intent(ACTION_PINNED).setPackage(packageName).putExtra(EXTRA_SEQ, seq)
        val pi = PendingIntent.getBroadcast(this, seq, cb, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            ShortcutManagerCompat.requestPinShortcut(this, ThemeShortcuts.build(this, setupTheme, app), pi.intentSender)
        } catch (e: RuntimeException) {
            pinDone(seq)
        }
    }

    private fun pinDone(seq: Int) {
        if (!pinPending || seq != pinSeq) return
        pinPending = false
        main.postDelayed(::pinNext, 300)
    }

    private fun finishSetup(asked: Int) {
        progress = null
        setupBusy = false
        if (asked > 0) Toast.makeText(this, R.string.icons_done, Toast.LENGTH_SHORT).show()
        val t = setupTheme
        // Widgets placed from the launcher's widget list follow the theme set up last.
        if (t != null) ThemedWidgetProvider.setLastTheme(this, t.id)
        if (!setupWallpaper || t == null) return
        // Wallpaper last: a wallpaper change can recolour and restart the app on some phones.
        setupWallpaper = false
        val app = applicationContext
        Toast.makeText(this, R.string.setting_wallpaper, Toast.LENGTH_SHORT).show()
        Thread({
            val bmp = ThemeStore.decodeWallpaper(app, t, 4096)
            val msg = try {
                if (bmp == null) throw IOException("no wallpaper")
                WallpaperHelper.setStaticWallpaper(app, bmp)
                R.string.wallpaper_set
            } catch (e: IOException) {
                R.string.wallpaper_set_failed
            } catch (e: RuntimeException) {
                R.string.wallpaper_set_failed
            } finally {
                bmp?.recycle()
            }
            main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
        }, "ThemeWallpaper").start()
    }

    companion object {
        const val EXTRA_ID = "theme_id"
        /** The ids to swipe through, in order; without it, the user's own themes. */
        const val EXTRA_IDS = "theme_ids"

        @JvmStatic
        fun intent(context: Context, ids: ArrayList<String>, id: String): Intent =
            Intent(context, ThemePreviewActivity::class.java).putExtra(EXTRA_ID, id).putStringArrayListExtra(EXTRA_IDS, ids)
        /** A shortcut or widget asked for by One-Tap Setup was placed (or the launcher answered). */
        const val ACTION_PINNED = "com.MrBRos.akhu.THEME_ICON_PINNED"
        private const val EXTRA_SEQ = "seq"
    }
}

/** The widgets One-Tap Setup can add, in the order it offers them. */
private val WIDGET_KINDS: List<Pair<Int, Class<out ThemedWidgetProvider>>> = listOf(
    R.string.widget_clock_name to ThemeClockWidgetProvider::class.java,
    R.string.widget_music to MusicWidgetProvider::class.java,
    R.string.widget_weather to WeatherWidgetProvider::class.java,
    R.string.widget_event to EventWidgetProvider::class.java,
    R.string.widget_battery to BatteryWidgetProvider::class.java,
    R.string.widget_apps to AppsWidgetProvider::class.java,
    R.string.widget_note to NoteWidgetProvider::class.java,
    R.string.widget_countdown to CountdownWidgetProvider::class.java,
)

@Composable
private fun PreviewScreen(
    themes: List<CustomTheme>,
    page: Int,
    jumpTo: Int?,
    onJumped: () -> Unit,
    onPage: (Int) -> Unit,
    lock: Boolean,
    onLock: (Boolean) -> Unit,
    setupBusy: Boolean,
    progress: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: (View) -> Unit,
    onSetup: () -> Unit,
    onStop: () -> Unit,
) {
    val pager = rememberPagerState { themes.size }
    LaunchedEffect(jumpTo, themes.size) {
        val target = jumpTo ?: return@LaunchedEffect
        if (target < themes.size) {
            pager.scrollToPage(target)
            onJumped()
        }
    }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect(onPage) }
    val current = themes.getOrNull(page)
    val context = LocalContext.current
    /** The full-screen mock on the current page, which Share draws. */
    val shown = remember { HashMap<String, View>() }

    // Full screen like the wallpaper previews: each theme fills the screen as it would look on the
    // phone, with back and the counter on top, the rail on the right and the name, Home / Lock
    // and One-Tap Setup at the bottom. Tap hides them; double tap likes.
    var chrome by remember { mutableStateOf(true) }
    val chromeAlpha by animateFloatAsState(if (chrome) 1f else 0f, tween(180), label = "chrome")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { themes[it].id },
        ) { i ->
            var burst by remember { mutableIntStateOf(0) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { HomeMockView(it) },
                    modifier = Modifier.fillMaxSize(),
                    update = { v ->
                        v.setTheme(themes[i])
                        v.setLock(lock)
                        shown[themes[i].id] = v
                    },
                )
                Box(
                    Modifier.matchParentSize().pointerInput(themes[i].id) {
                        detectTapGestures(
                            onTap = { chrome = !chrome },
                            onDoubleTap = {
                                Likes.like(context, Likes.keyOf(themes[i]))
                                burst++
                            },
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) { HeartBurst(burst, 96.dp) }
            }
        }
        if (chromeAlpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = chromeAlpha }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                    .padding(start = 12.dp, end = 12.dp, top = 36.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(R.drawable.ic_arrow_back, stringResource(R.string.back), onBack)
                Spacer(Modifier.weight(1f))
                if (themes.size > 1) {
                    Text(
                        stringResource(R.string.preview_counter, page + 1, themes.size),
                        Modifier.background(Color(0x66000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White, style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (current != null) {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 200.dp).width(64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AndroidView(
                        factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_launcher_round) } },
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    val key = Likes.keyOf(current)
                    val liked = Likes.isLiked(context, key)
                    RailAction(
                        if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline,
                        stringResource(if (liked) R.string.liked else R.string.like),
                    ) { Likes.toggle(context, key) }
                    // Built-in and public themes can be copied into your own (edit) but not deleted.
                    RailAction(R.drawable.ic_edit, stringResource(R.string.action_edit), onEdit)
                    RailAction(R.drawable.ic_share, stringResource(R.string.share)) { shown[current.id]?.let(onShare) }
                    if (!ThemeStore.isReadOnly(current)) {
                        RailAction(R.drawable.ic_delete, stringResource(R.string.delete), onDelete)
                    }
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                    .padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 36.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        current?.name ?: "", Modifier.weight(1f).padding(end = Space.m),
                        color = Color.White, style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    HomeLockToggle(lock, onLock, onDark = true)
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(stringResource(R.string.one_tap_setup), onSetup, enabled = !setupBusy)
            }
        }
        if (progress != null) {
            Snackbar(
                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                action = { TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) } },
            ) { Text(progress) }
        }
    }
}

/** An icon with its label under it, on the right-hand rail of a full-screen preview. */
@Composable
private fun RailAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).background(Color(0x66000000), CircleShape)) {
        Icon(painterResource(icon), label, Modifier.size(26.dp), tint = Color.White)
    }
    Text(
        label, Modifier.padding(top = 2.dp, bottom = 12.dp), color = Color.White,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold,
            shadow = Shadow(Color(0x99000000), blurRadius = 6f),
        ),
    )
}

/** Round frosted icon button for the dark full-screen previews. */
@Composable
internal fun GlassIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).background(Color(0x40000000), CircleShape)) {
        Icon(painterResource(icon), label, Modifier.size(22.dp), tint = Color.White)
    }
}

/**
 * A theme on a pretend phone, kept at a phone's 9:19.5 shape and as large as fits. Tapping an icon
 * calls [onIconClick] when given. [version] must change whenever [theme] is changed in place, or
 * the same theme object would never be redrawn.
 */
@Composable
internal fun PhoneMock(
    theme: CustomTheme,
    lock: Boolean,
    corner: Dp,
    pad: Dp,
    elevation: Dp,
    stroke: Boolean,
    modifier: Modifier = Modifier,
    version: Int = 0,
    onIconClick: ((Int) -> Unit)? = null,
) {
    val outer = RoundedCornerShape(corner)
    Box(
        modifier
            .aspectRatio(9f / 19.5f, matchHeightConstraintsFirst = true)
            .shadow(elevation, outer)
            .background(Color(0xFF111111), outer)
            .then(if (stroke) Modifier.border(BorderStroke(2.dp, Color(0xFF2A2D33)), outer) else Modifier)
            .padding(pad)
            .clip(RoundedCornerShape(corner - pad))
    ) {
        AndroidView(
            factory = { HomeMockView(it) },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.tag = version // read so a new version reruns this block
                v.setTheme(theme)
                v.setLock(lock)
                v.setOnIconClickListener(onIconClick?.let { cb -> HomeMockView.OnIconClickListener { cb(it) } })
            },
        )
    }
}

/** Home / Lock switch under a phone preview; on the dark preview screen it uses glassy colours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeLockToggle(lock: Boolean, onLock: (Boolean) -> Unit, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val colors = if (onDark) SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primary,
        activeContentColor = Color.White,
        activeBorderColor = Color(0x80FFFFFF),
        inactiveContainerColor = Color(0x33FFFFFF),
        inactiveContentColor = Color(0xCCFFFFFF),
        inactiveBorderColor = Color(0x80FFFFFF),
    ) else SegmentedButtonDefaults.colors()
    SingleChoiceSegmentedButtonRow(modifier) {
        listOf(R.string.theme_home to false, R.string.theme_lock to true).forEachIndexed { i, (label, isLock) ->
            SegmentedButton(
                selected = lock == isLock,
                onClick = { onLock(isLock) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                colors = colors,
                icon = {},
            ) { Text(stringResource(label)) }
        }
    }
}
