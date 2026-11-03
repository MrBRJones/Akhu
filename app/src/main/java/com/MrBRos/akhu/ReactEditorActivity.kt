package com.MrBRos.akhu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Makes or edits a reactive wallpaper: the character is cut out of the picked photo (a picture
 * that is already transparent is used as it is), then the user picks the water (or a photo of
 * theirs) and the character's size on a live, tiltable preview, and saves. Premium only.
 */
class ReactEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PHOTO = "photo"
        private const val EXTRA_CHARACTER = "character"
        private const val EXTRA_NAME = "name"

        /** A new one from a photo the user picked in Create. */
        @JvmStatic
        fun create(context: Context, photo: File, name: String?): Intent =
            Intent(context, ReactEditorActivity::class.java)
                .putExtra(EXTRA_PHOTO, photo.absolutePath)
                .putExtra(EXTRA_NAME, name)

        /** Changes one the user saved before (by its character path). */
        @JvmStatic
        fun intent(context: Context, character: String): Intent =
            Intent(context, ReactEditorActivity::class.java).putExtra(EXTRA_CHARACTER, character)
    }

    private val io = Executors.newSingleThreadExecutor()
    private var view: ReactView? = null
    private var character: Bitmap? = null
    /** Set when editing a saved one. */
    private var existing: String? = null

    private var style by mutableIntStateOf(ReactWallpapers.STYLE_POOL)
    private var size by mutableFloatStateOf(0.5f)
    private var photo by mutableStateOf<File?>(null)
    private var working by mutableStateOf(true)
    private var status by mutableIntStateOf(R.string.react_cutting)

    private val pickBackground = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@registerForActivityResult
        io.execute {
            val file = try {
                ImagePickerHelper.copyToCache(this, uri)
            } catch (e: NsfwDetector.Rejected) {
                runOnUiThread { Toast.makeText(this, R.string.nsfw_photo_rejected, Toast.LENGTH_LONG).show() }
                return@execute
            } catch (e: java.io.IOException) {
                runOnUiThread { Toast.makeText(this, R.string.pick_failed, Toast.LENGTH_SHORT).show() }
                return@execute
            }
            runOnUiThread {
                photo = file
                style = ReactWallpapers.STYLE_PHOTO
                pushSpec()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Callers check Premium before opening this; Save checks again.
        existing = intent.getStringExtra(EXTRA_CHARACTER)
        setContent {
            AkhuTheme {
                EditorScreen()
            }
        }
        load()
    }

    private fun load() {
        val app = applicationContext
        val saved = existing
        val source = intent.getStringExtra(EXTRA_PHOTO)
        io.execute {
            var made: Bitmap? = null
            var error = 0
            try {
                if (saved != null) {
                    val spec = ReactWallpapers.spec(app, saved)
                    runOnUiThread {
                        style = spec.style
                        size = spec.size
                        photo = spec.photo?.let { File(it) }
                    }
                    made = ReactWallpapers.loadCharacter(app, saved)
                } else if (source != null) {
                    made = alreadyCutOut(File(source)) ?: FourDMaker.cutout(app, File(source))
                }
            } catch (e: FourDMaker.MakeException) {
                error = e.messageRes
            }
            val result = made
            val failed = if (error != 0) error else R.string.fourd_failed
            runOnUiThread {
                if (result == null) {
                    Toast.makeText(this, failed, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                character = result
                working = false
                status = R.string.react_editor_hint
                view?.setPreview(result, spec())
            }
        }
    }

    /** A picture that is already a cut-out (mostly transparent), trimmed; null for an ordinary photo. */
    private fun alreadyCutOut(file: File): Bitmap? {
        val b = ImagePickerHelper.decodeSampled(file, ReactWallpapers.CHARACTER_DIM) ?: return null
        if (!b.hasAlpha()) {
            b.recycle()
            return null
        }
        val w = b.width
        val h = b.height
        val step = maxOf(1, maxOf(w, h) / 96)
        var clear = 0
        var total = 0
        for (y in 0 until h step step) for (x in 0 until w step step) {
            total++
            if (b.getPixel(x, y) ushr 24 < 16) clear++
        }
        if (clear < total / 10) {
            b.recycle()
            return null
        }
        val trimmed = ReactWallpapers.trim(b)
        if (trimmed !== b) b.recycle()
        return trimmed
    }

    private fun spec() = ReactWallpapers.Spec().also {
        it.style = if (style == ReactWallpapers.STYLE_PHOTO && photo == null) ReactWallpapers.STYLE_POOL else style
        it.size = size
        it.photo = photo?.absolutePath
    }

    private fun pushSpec() {
        view?.setSpec(spec())
    }

    private fun save() {
        val c = character ?: return
        if (!PremiumGate.check(this, R.string.react_premium_message)) return
        working = true
        status = R.string.react_saving
        val spec = spec()
        val bg = photo
        val saved = existing
        val app = applicationContext
        io.execute {
            val path = try {
                if (saved != null) {
                    ReactWallpapers.update(saved, spec, bg)
                    ReactPoster.forget(app, saved)
                    saved
                } else {
                    ReactWallpapers.save(app, c, spec, bg)
                }
            } catch (e: java.io.IOException) {
                null
            }
            runOnUiThread {
                working = false
                status = R.string.react_editor_hint
                if (path == null) {
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Toast.makeText(this, R.string.react_saved, Toast.LENGTH_SHORT).show()
                if (saved != null) {
                    setResult(Activity.RESULT_OK)
                } else {
                    // Show it full screen among the user's own, ready to set.
                    val mine = ReactWallpapers.user(this)
                    startActivity(PreviewActivity.intent(this, ArrayList(mine.map { PreviewActivity.REACT_PREFIX + it }),
                        maxOf(0, mine.indexOf(path))))
                }
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.onResume()
    }

    override fun onPause() {
        view?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun EditorScreen() {
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    ReactView(ctx).also {
                        view = it
                        // Keep the character out from under the top bar and the style row.
                        it.setInsets(0.09f, 0.26f)
                        character?.let { c -> it.setPreview(c, spec()) }
                        it.onResume()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Top: back, title, save.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.s, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::finish, Modifier.background(Color(0x66000000), CircleShape)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close), tint = Color.White)
                }
                Text(
                    stringResource(R.string.react_editor_title), Modifier.weight(1f).padding(horizontal = Space.m),
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = ::save, enabled = !working && character != null) {
                    Text(stringResource(R.string.save))
                }
            }
            // Bottom: water styles and size.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .navigationBarsPadding()
                    .padding(top = Space.xl, bottom = Space.m),
            ) {
                Text(
                    stringResource(status), Modifier.fillMaxWidth().padding(horizontal = Space.screen),
                    color = Color(0xDDFFFFFF), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    for (i in ReactWallpapers.STYLE_ORDER) {
                        StyleChip(i, stringResource(ReactWallpapers.STYLE_NAMES[i]), selected = i == style) {
                            if (i == ReactWallpapers.STYLE_PHOTO) {
                                pickBackground.launch(ImagePickerHelper.createPickIntent())
                            } else {
                                style = i
                                pushSpec()
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.screen), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.react_size), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = size, onValueChange = { size = it; pushSpec() }, valueRange = 0.25f..0.85f,
                        modifier = Modifier.weight(1f).padding(start = Space.m),
                    )
                }
            }
            if (working) {
                Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = colors.primary)
                        Text(stringResource(status), Modifier.padding(top = Space.m), color = Color.White)
                    }
                }
            }
        }
    }

    /** A round swatch of the water (or a photo icon) with its name under it. */
    @Composable
    private fun StyleChip(index: Int, name: String, selected: Boolean, onClick: () -> Unit) {
        val s = ReactWallpapers.STYLES[index]
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == ReactWallpapers.STYLE_PHOTO) Brush.linearGradient(listOf(Color(0xFF444A55), Color(0xFF22262E)))
                        else Brush.verticalGradient(listOf(Color(0xFF000000 or s[0].toLong()), Color(0xFF000000 or s[1].toLong())))
                    )
                    .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0x55FFFFFF)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (index == ReactWallpapers.STYLE_PHOTO) {
                    Icon(painterResource(R.drawable.ic_add_photo), null, Modifier.size(22.dp), tint = Color.White)
                }
            }
            Text(
                name, Modifier.padding(top = Space.xs), color = if (selected) Color.White else Color(0xBBFFFFFF),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
