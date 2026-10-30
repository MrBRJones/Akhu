package com.MrBRos.akhu

import androidx.annotation.DrawableRes
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
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
import androidx.compose.foundation.layout.PaddingValues
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
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min

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
                    onSetup = ::askSetup,
                    onStop = {
                        pinQueue.clear()
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
            val list = ThemeStore.list(app)
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
        val checked = booleanArrayOf(true, true)
        val items = arrayOf(getString(R.string.setup_icons, t.apps.size), getString(R.string.setup_wallpaper))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.one_tap_setup)
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ -> startSetup(t, checked[0], checked[1]) }
            .show()
    }

    private fun startSetup(theme: CustomTheme, icons: Boolean, wallpaper: Boolean) {
        val t = theme.copy()
        setupTheme = t
        setupWallpaper = wallpaper
        pinQueue.clear()
        if (!icons) {
            finishSetup(0)
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
                if (pinTotal > 0) progress = ""
                pinNext()
            }
        }, "ThemeShortcuts").start()
    }

    private fun pinNext() {
        val app = pinQueue.poll()
        if (app == null) {
            finishSetup(pinTotal)
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
        private const val ACTION_PINNED = "com.MrBRos.akhu.THEME_ICON_PINNED"
        private const val EXTRA_SEQ = "seq"
    }
}

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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Blurred, dimmed copy of the current theme's wallpaper behind everything.
        AndroidView(
            factory = {
                ImageView(it).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
                    }
                    setColorFilter(android.graphics.Color.argb(120, 0, 0, 0))
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { iv ->
                if (current != null) {
                    Glide.with(iv).load(ThemeStore.wallpaper(iv.context, current))
                        .signature(ObjectKey(current.updated)).override(160).centerCrop().into(iv)
                }
            },
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(R.drawable.ic_arrow_back, stringResource(R.string.back), onBack)
                Text(
                    current?.name ?: "", Modifier.weight(1f).padding(horizontal = Space.m),
                    color = Color.White, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                GlassIconButton(R.drawable.ic_edit, stringResource(R.string.action_edit), onEdit)
                Spacer(Modifier.width(Space.s))
                GlassIconButton(R.drawable.ic_delete, stringResource(R.string.delete), onDelete)
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                contentPadding = PaddingValues(horizontal = 48.dp),
                pageSpacing = 12.dp,
                key = { themes[it].id },
            ) { i ->
                val offset = abs(pager.currentPage - i + pager.currentPageOffsetFraction)
                val scale = 0.88f + 0.12f * (1f - min(1f, offset))
                Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
                    PhoneMock(themes[i], lock, corner = 30.dp, pad = 5.dp, elevation = 8.dp, stroke = true)
                }
            }
            HomeLockToggle(
                lock, onLock, Modifier.align(Alignment.CenterHorizontally).padding(top = Space.l),
                onDark = true,
            )
            if (themes.isNotEmpty()) {
                Text(
                    stringResource(R.string.preview_counter, page + 1, themes.size),
                    Modifier.align(Alignment.CenterHorizontally).padding(top = Space.s),
                    color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelMedium,
                )
            }
            PrimaryButton(
                stringResource(R.string.one_tap_setup), onSetup, enabled = !setupBusy,
                modifier = Modifier.padding(start = Space.screen, end = Space.screen, top = Space.m, bottom = Space.l),
            )
        }
        if (progress != null) {
            Snackbar(
                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                action = { TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) } },
            ) { Text(progress) }
        }
    }
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
 * calls [onIconClick] when given.
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
