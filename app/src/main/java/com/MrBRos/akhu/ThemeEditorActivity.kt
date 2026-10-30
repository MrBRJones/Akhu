package com.MrBRos.akhu

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Builds or edits a [CustomTheme]: wallpaper, icon style, shape, colours and the app list, with a
 * live home screen preview. Tapping an icon in the preview gives it a photo, renames it or removes
 * it. Drawn with Compose.
 */
class ThemeEditorActivity : AppCompatActivity() {

    private lateinit var theme: CustomTheme
    private var isNew = false
    private var dirty = false
    /** Copy of the wallpaper as it was when editing began, restored if changes are discarded. */
    private var wallpaperBackup: File? = null
    /** Index of the app whose icon photo is being picked, or -1 when picking the wallpaper. */
    private var pickingFor = -1

    private var name by mutableStateOf("")
    private var lock by mutableStateOf(false)
    /** Bumped whenever [theme] changes, so the screen redraws from it. */
    private var version by mutableIntStateOf(0)

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        result.data?.data?.let(::onPicked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(ThemePreviewActivity.EXTRA_ID)
        val existing = id?.let { ThemeStore.load(this, it) }
        isNew = existing == null
        theme = existing ?: ThemeStore.create(getString(R.string.default_theme_name))
        name = theme.name

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        setContent {
            AkhuTheme {
                EditorScreen(
                    theme = theme,
                    version = version,
                    name = name,
                    onName = { name = it.take(40) },
                    lock = lock,
                    onLock = { lock = it },
                    onBack = ::leave,
                    onIconClick = ::iconOptions,
                    onGallery = { pick(-1) },
                    onSave = ::saveOptions,
                    onStyle = { s ->
                        theme.style = s
                        if (s != CustomTheme.STYLE_ORIGINAL) theme.glyphColor = ThemeIcons.defaultGlyph(s, theme.bgColor)
                        changed()
                    },
                    onShape = { s ->
                        theme.shape = s
                        changed()
                    },
                    onBg = { c ->
                        theme.bgColor = c
                        theme.glyphColor = ThemeIcons.defaultGlyph(theme.style, c)
                        changed()
                    },
                    onGlyph = { c ->
                        theme.glyphColor = c
                        changed()
                    },
                    onCustomColour = { title, current, set -> ColorPickerSheet.show(this, title, current) { set(it) } },
                    onIconImage = ::iconImageOptions,
                    onApps = ::chooseApps,
                )
            }
        }

        if (isNew) startNew() else {
            backupWallpaper()
            refresh()
        }
    }

    /** A new theme starts from a featured wallpaper and everyday apps so the preview isn't blank. */
    private fun startNew() {
        val app = applicationContext
        Thread({
            val apps = AppCatalog.defaults(app, 12)
            val featured = FeaturedWallpapers.list(app, null)
            val src = try {
                if (featured.isNotEmpty()) FeaturedWallpapers.copyToCache(app, featured[0]) else null
            } catch (e: IOException) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                theme.apps.addAll(apps)
                if (src != null) setWallpaperFrom(src, true) else refresh()
            }
        }, "ThemeNew").start()
    }

    private fun backupWallpaper() {
        val wp = ThemeStore.wallpaper(this, theme)
        if (!wp.exists()) return
        val backup = File(cacheDir, "theme_wp_backup.jpg")
        wallpaperBackup = try {
            wp.copyTo(backup, overwrite = true)
        } catch (e: IOException) {
            null
        }
    }

    private fun changed() {
        dirty = true
        refresh()
    }

    private fun refresh() {
        version++
    }

    /** The preset closest to the wallpaper's main colour, so a new theme matches it without extra swatches. */
    private fun nearestPreset(c: Int): Int = PRESETS.minBy { p ->
        val dr = (AColor.red(p) - AColor.red(c)).toLong()
        val dg = (AColor.green(p) - AColor.green(c)).toLong()
        val db = (AColor.blue(p) - AColor.blue(c)).toLong()
        2 * dr * dr + 4 * dg * dg + 3 * db * db
    }

    // ---- Wallpaper ----

    private fun pick(forApp: Int) {
        pickingFor = forApp
        pickLauncher.launch(ImagePickerHelper.createPickIntent())
    }

    private fun onPicked(uri: Uri) {
        val app = applicationContext
        val target = pickingFor
        Thread({
            try {
                val file = ImagePickerHelper.copyToCache(app, uri)
                if (target < 0) {
                    runOnUiThread { setWallpaperFrom(file, false) }
                    return@Thread
                }
                val square = ThemeStore.squarePhoto(file) ?: throw IOException("decode")
                val photo = ThemeStore.savePhoto(app, theme, square)
                square.recycle()
                runOnUiThread {
                    if (target < theme.apps.size) theme.apps[target].photo = photo
                    changed()
                }
            } catch (e: NsfwDetector.Rejected) {
                runOnUiThread { Toast.makeText(app, R.string.nsfw_photo_rejected, Toast.LENGTH_LONG).show() }
            } catch (e: IOException) {
                runOnUiThread { Toast.makeText(app, R.string.pick_failed, Toast.LENGTH_SHORT).show() }
            }
        }, "ThemePick").start()
    }

    /** Stores [src] as the wallpaper; a fresh theme also takes its colours from it. */
    private fun setWallpaperFrom(src: File, recolour: Boolean) {
        val app = applicationContext
        val t = theme
        Thread({
            try {
                ThemeStore.setWallpaper(app, t, src)
                val wp = ThemeStore.decodeWallpaper(app, t, 256)
                val pal = if (wp != null) ThemeStore.palette(wp, 1) else emptyList()
                wp?.recycle()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if ((recolour || isNew) && pal.isNotEmpty()) {
                        theme.bgColor = nearestPreset(pal[0])
                        theme.glyphColor = ThemeIcons.defaultGlyph(theme.style, theme.bgColor)
                    }
                    dirty = dirty or !recolour
                    refresh()
                }
            } catch (e: IOException) {
                runOnUiThread { Toast.makeText(app, R.string.pick_failed, Toast.LENGTH_SHORT).show() }
            }
        }, "ThemeWallpaper").start()
    }

    // ---- Apps & icons ----

    private fun chooseApps() {
        val app = applicationContext
        Thread({
            val all = AppCatalog.all(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val labels = all.map { it.label }.toTypedArray()
                val checked = BooleanArray(all.size) { AppCatalog.contains(theme.apps, all[it]) }
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.choose_apps_max, MAX_APPS))
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> applyAppChoice(all, checked) }
                    .show()
            }
        }, "ThemeApps").start()
    }

    private fun applyAppChoice(all: List<CustomTheme.App>, checked: BooleanArray) {
        // Keep the existing order (and photos/names) of apps that stay; new ones go at the end.
        var kept = theme.apps.filter { a ->
            all.indices.any { i -> checked[i] && a.pkg == all[i].pkg && a.activity == all[i].activity }
        }.toMutableList()
        for (i in all.indices) if (checked[i] && !AppCatalog.contains(kept, all[i])) kept.add(all[i])
        if (kept.size > MAX_APPS) {
            Toast.makeText(this, getString(R.string.too_many_apps, MAX_APPS), Toast.LENGTH_LONG).show()
            kept = kept.subList(0, MAX_APPS).toMutableList()
        }
        theme.apps.clear()
        theme.apps.addAll(kept)
        changed()
    }

    /** Icon images row tap: upload an image for that app's icon, or change or remove the one it has. */
    private fun iconImageOptions(index: Int) {
        val app = theme.apps.getOrNull(index) ?: return
        if (app.photo == null) {
            pick(index)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setItems(arrayOf(getString(R.string.icon_image_change), getString(R.string.icon_image_remove))) { _, which ->
                if (which == 0) pick(index) else {
                    app.photo = null
                    changed()
                }
            }
            .show()
    }

    private fun iconOptions(index: Int) {
        val app = theme.apps.getOrNull(index) ?: return
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        items += getString(R.string.icon_use_photo)
        actions += { pick(index) }
        if (app.photo != null) {
            items += getString(R.string.icon_use_themed)
            actions += {
                app.photo = null
                changed()
            }
        }
        items += getString(R.string.icon_rename)
        actions += { rename(app) }
        if (index > 0) {
            items += getString(R.string.icon_move_first)
            actions += {
                theme.apps.removeAt(index)
                theme.apps.add(0, app)
                changed()
            }
        }
        items += getString(R.string.icon_remove)
        actions += {
            theme.apps.removeAt(index)
            changed()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun rename(app: CustomTheme.App) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(app.label)
            setSelectAllOnFocus(true)
        }
        val pad = Math.round(20 * resources.displayMetrics.density)
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.icon_rename)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val s = input.text.toString().trim()
                if (s.isNotEmpty()) {
                    app.label = s
                    changed()
                }
            }
            .show()
    }

    // ---- Save / leave ----

    /** The one Save button: keep the theme on the phone, or keep it and also post its wallpaper. */
    private fun saveOptions() {
        if (!canSave()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_theme_title)
            .setItems(arrayOf<CharSequence>(getString(R.string.save_only), getString(R.string.save_and_post))) { _, which ->
                if (which == 0) save() else saveAndPost()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun canSave(): Boolean {
        if (theme.apps.isEmpty()) {
            Toast.makeText(this, R.string.theme_needs_apps, Toast.LENGTH_SHORT).show()
            return false
        }
        if (!ThemeStore.wallpaper(this, theme).exists()) {
            Toast.makeText(this, R.string.theme_needs_wallpaper, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun typedName() = name.trim()

    private fun save() {
        if (store()) done()
    }

    /** Saves the theme, then sends its background picture to the cloud like a 2D post. */
    private fun saveAndPost() {
        if (!FirebaseHelper.isAvailable(this)) {
            Toast.makeText(this, R.string.cloud_disabled, Toast.LENGTH_LONG).show()
            return
        }
        if (FirebaseHelper.currentUser(this) == null) {
            Toast.makeText(this, R.string.sign_in_required, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AuthActivity::class.java))
            return
        }
        val typed = typedName()
        PostDialog.show(this, typed.ifEmpty { null }) { title, isPublic, category ->
            if (!store()) return@show
            // The upload outlives this screen, so it reports with the app context.
            val app = applicationContext
            Toast.makeText(app, R.string.uploading, Toast.LENGTH_SHORT).show()
            FirebaseHelper.uploadWallpaper(app, ThemeStore.wallpaper(this, theme), title, isPublic, category,
                object : FirebaseHelper.UploadCallback {
                    override fun onProgress(percent: Int) {}

                    override fun onSuccess(docId: String) {
                        Toast.makeText(app, R.string.upload_done, Toast.LENGTH_SHORT).show()
                    }

                    override fun onError(e: Exception) {
                        Toast.makeText(app, app.getString(R.string.upload_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
                    }
                })
            done()
        }
    }

    /** Writes the theme; false (with a message) if that failed. */
    private fun store(): Boolean {
        theme.name = typedName().ifEmpty { getString(R.string.default_theme_name) }
        try {
            ThemeStore.save(this, theme)
        } catch (e: IOException) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            return false
        }
        Toast.makeText(this, R.string.theme_saved, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun done() {
        if (isNew) {
            startActivity(Intent(this, ThemePreviewActivity::class.java).putExtra(ThemePreviewActivity.EXTRA_ID, theme.id))
        }
        finish()
    }

    private fun leave() {
        if (!dirty && !isNew && typedName() == theme.name) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_theme_title)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.discard) { _, _ ->
                discard()
                finish()
            }
            .show()
    }

    private fun discard() {
        if (isNew) {
            ThemeStore.delete(this, theme)
            return
        }
        wallpaperBackup?.takeIf { it.exists() }?.let {
            try {
                it.copyTo(ThemeStore.wallpaper(this, theme), overwrite = true)
            } catch (ignored: IOException) {
            }
        }
        // Photos picked during this edit aren't used by the saved theme.
        ThemeStore.load(this, theme.id)?.let { ThemeStore.prunePhotos(this, it) }
    }

    companion object {
        private const val MAX_APPS = 24

        @JvmField
        val PRESETS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF16181D.toInt(), 0xFF2F6BFF.toInt(), 0xFF8E44FF.toInt(), 0xFFFF5E8A.toInt(),
            0xFFFF8A00.toInt(), 0xFFFFD43B.toInt(), 0xFF2BC48A.toInt(), 0xFF00B8D9.toInt(), 0xFF7A4B2A.toInt(),
        )
    }
}

// Display order; each entry carries its CustomTheme.STYLE_* / SHAPE_* value, so saved themes are unaffected.
private val STYLES = listOf(
    R.string.style_original to CustomTheme.STYLE_ORIGINAL, R.string.style_themed to CustomTheme.STYLE_THEMED,
    R.string.style_glass to CustomTheme.STYLE_GLASS, R.string.style_gradient to CustomTheme.STYLE_GRADIENT,
    R.string.style_outline to CustomTheme.STYLE_OUTLINE, R.string.style_neon to CustomTheme.STYLE_NEON,
    R.string.style_pastel to CustomTheme.STYLE_PASTEL, R.string.style_minimal to CustomTheme.STYLE_MINIMAL,
    R.string.style_raised to CustomTheme.STYLE_RAISED, R.string.style_sticker to CustomTheme.STYLE_STICKER,
)
private val SHAPES = listOf(
    R.string.shape_circle to CustomTheme.SHAPE_CIRCLE, R.string.shape_squircle to CustomTheme.SHAPE_SQUIRCLE,
    R.string.shape_rounded to CustomTheme.SHAPE_ROUNDED, R.string.shape_teardrop to CustomTheme.SHAPE_TEARDROP,
    R.string.shape_square to CustomTheme.SHAPE_SQUARE, R.string.shape_hexagon to CustomTheme.SHAPE_HEXAGON,
    R.string.shape_leaf to CustomTheme.SHAPE_LEAF, R.string.shape_flower to CustomTheme.SHAPE_FLOWER,
    R.string.shape_cookie to CustomTheme.SHAPE_COOKIE, R.string.shape_shield to CustomTheme.SHAPE_SHIELD,
)

@Composable
private fun EditorScreen(
    theme: CustomTheme,
    version: Int,
    name: String,
    onName: (String) -> Unit,
    lock: Boolean,
    onLock: (Boolean) -> Unit,
    onBack: () -> Unit,
    onIconClick: (Int) -> Unit,
    onGallery: () -> Unit,
    onSave: () -> Unit,
    onStyle: (Int) -> Unit,
    onShape: (Int) -> Unit,
    onBg: (Int) -> Unit,
    onGlyph: (Int) -> Unit,
    onCustomColour: (title: Int, current: Int, set: (Int) -> Unit) -> Unit,
    onIconImage: (Int) -> Unit,
    onApps: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val original = theme.style == CustomTheme.STYLE_ORIGINAL
    // Minimal has no background, so only the symbol colour applies.
    val showBg = !original && theme.style != CustomTheme.STYLE_MINIMAL
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), tint = colors.onSurface)
            }
            Text(
                stringResource(R.string.theme_creation), Modifier.padding(start = 8.dp),
                color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            // Live preview: tap an icon to give it a photo, rename or remove it.
            PhoneMock(
                theme, lock, corner = 26.dp, pad = 4.dp, elevation = 4.dp, stroke = false,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp).height(400.dp),
                onIconClick = onIconClick,
            )
            HomeLockToggle(lock, onLock, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            Text(
                stringResource(R.string.tap_icon_hint), Modifier.fillMaxWidth().padding(top = 4.dp),
                color = colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.theme_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            SectionHeader(R.string.wallpaper_header)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                FilledTonalButton(onClick = onGallery, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(stringResource(R.string.from_gallery))
                }
                // Save, or save and post: a menu asks which.
                Button(onClick = onSave, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(stringResource(R.string.save))
                }
            }

            // Icon style and shape: drop-down menus, side by side.
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Dropdown(R.string.icon_style, STYLES, theme.style, onStyle, Modifier.weight(1f).padding(end = 6.dp))
                Dropdown(R.string.icon_shape, SHAPES, theme.shape, onShape, Modifier.weight(1f).padding(start = 6.dp))
            }

            if (showBg) {
                SectionHeader(R.string.icon_colour)
                Swatches(ThemeEditorActivity.PRESETS.toList(), theme.bgColor, onBg) {
                    onCustomColour(R.string.icon_colour, theme.bgColor, onBg)
                }
            }
            if (!original) {
                SectionHeader(R.string.symbol_colour)
                val glyph = remember { (listOf(0xFFFFFFFF.toInt(), 0xFF16181D.toInt()) + ThemeEditorActivity.PRESETS.toList()).distinct() }
                Swatches(glyph, theme.glyphColor, onGlyph) {
                    onCustomColour(R.string.symbol_colour, theme.glyphColor, onGlyph)
                }
            }

            // One tile per app: tap to upload an image for that app's icon.
            SectionHeader(R.string.icon_images)
            Text(
                stringResource(R.string.icon_images_hint), Modifier.padding(bottom = 8.dp),
                color = colors.onSurfaceVariant, fontSize = 12.sp,
            )
            IconImageRow(theme, version, onIconImage)

            SectionHeader(R.string.apps_header_count, theme.apps.size)
            OutlinedButton(
                onClick = onApps, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp),
            ) { Text(stringResource(R.string.choose_apps)) }
        }
    }
}

@Composable
private fun SectionHeader(@StringRes text: Int, vararg args: Any) {
    Text(
        stringResource(text, *args),
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    @StringRes label: Int,
    options: List<Pair<Int, Int>>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.second == selected }?.first
    ExposedDropdownMenuBox(expanded, { expanded = it }, modifier) {
        OutlinedTextField(
            value = current?.let { stringResource(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            for ((text, value) in options) {
                DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = {
                    expanded = false
                    onPick(value)
                })
            }
        }
    }
}

/**
 * A row of colour dots: a rainbow dot first that opens the full colour picker, then the presets.
 * A colour picked with the custom picker joins the row so it shows as selected, and the row
 * scrolls to keep the selected dot in view.
 */
@Composable
private fun Swatches(presets: List<Int>, selected: Int, onPick: (Int) -> Unit, onCustom: () -> Unit) {
    val sel = selected or 0xFF000000.toInt()
    val colours = if (presets.any { (it or 0xFF000000.toInt()) == sel }) presets else listOf(sel) + presets
    val state = rememberLazyListState()
    val selIndex = colours.indexOfFirst { (it or 0xFF000000.toInt()) == sel } + 1
    LaunchedEffect(selIndex) {
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        if (selIndex !in visible.dropLast(1)) state.animateScrollToItem(maxOf(0, selIndex - 1))
    }
    val customLabel = stringResource(R.string.custom_colour)
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .size(40.dp)
                    .background(
                        Brush.sweepGradient(
                            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                        ),
                        CircleShape,
                    )
                    .border(1.dp, Color(0x33000000), CircleShape)
                    .clickable(onClick = onCustom)
                    .semantics { contentDescription = customLabel }
            )
        }
        itemsIndexed(colours) { _, c ->
            val isSel = (c or 0xFF000000.toInt()) == sel
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .size(40.dp)
                    .background(Color(c), CircleShape)
                    .border(
                        if (isSel) 3.dp else 1.dp,
                        if (isSel) MaterialTheme.colorScheme.primary else Color(0x33000000),
                        CircleShape,
                    )
                    .clickable { onPick(c) }
                    .semantics { contentDescription = String.format("#%06X", c and 0xFFFFFF) }
            )
        }
    }
}

/** Each app's icon as it looks now, rendered off the main thread. */
@Composable
private fun IconImageRow(theme: CustomTheme, version: Int, onTap: (Int) -> Unit) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(theme.apps.toList(), key = { i, a -> "$version:$i:${a.pkg}/${a.activity}" }) { i, app ->
            val icon by produceState<Bitmap?>(null, version, app) {
                value = withContext(Dispatchers.Default) { ThemeIcons.render(context, theme, app, iconPx) }
            }
            Column(
                Modifier.width(72.dp).clickable { onTap(i) }.semantics { contentDescription = app.label },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(56.dp)) {
                    icon?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                }
                Text(
                    app.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
