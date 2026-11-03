package com.MrBRos.akhu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Makes or edits a reactive wallpaper. Each picture (up to [ReactPhysics.MAX_BODIES]) is cut out
 * automatically (one that is already transparent is used as it is), and the user can brush the
 * cut themselves (erase / restore, or redo the automatic cut). Then they pick the scene (or a
 * photo of theirs), each image's size and any effects on a live, tiltable preview, and post it or
 * just save it.
 */
class ReactEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PHOTO = "photo"
        private const val EXTRA_CHARACTER = "character"
        private const val EXTRA_NAME = "name"
        private const val TAB_IMAGES = 0
        private const val TAB_SCENE = 1
        private const val TAB_LAYERS = 2
        private const val TAB_EFFECTS = 3
        /** cache/ folder for this editor's picks, apart from Create's (whose picture must survive). */
        private const val PICKED_DIR = "picked_react"
        private const val MAX_VIDEO_BYTES = 100L * 1024 * 1024

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
    private var cutoutView: CutoutView? = null
    /** Set when editing a saved one. */
    private var existing: String? = null

    /** The pictures with their masks, and each one's current cut-out (what the preview shows). */
    private val pieces = mutableListOf<ReactWallpapers.Piece>()
    private val cuts = mutableStateListOf<Bitmap>()
    private val sizes = mutableStateListOf<Float>()
    private var selected by mutableIntStateOf(0)
    private var tab by mutableIntStateOf(TAB_IMAGES)
    private var style by mutableIntStateOf(ReactWallpapers.STYLE_POOL)
    private var effects by mutableIntStateOf(0)
    private var layer by mutableIntStateOf(0)
    private var photo by mutableStateOf<File?>(null)
    private var working by mutableStateOf(true)
    /** A picked background (photo or video) is being copied and checked: "Loading…" at the bottom. */
    private var loadingMedia by mutableStateOf(false)
    private var status by mutableIntStateOf(R.string.react_cutting)

    /** The cut-out screen: open for [selected] while true; [maskBefore] puts it back on cancel. */
    private var cutting by mutableStateOf(false)
    private var maskBefore: ByteArray? = null
    private var tool by mutableIntStateOf(CutoutView.TOOL_ERASE)
    private var brush by mutableFloatStateOf(20f)
    private var canUndo by mutableStateOf(false)

    private val pickBackground = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@registerForActivityResult
        val video = contentResolver.getType(uri)?.startsWith("video/") == true
        loadingMedia = true
        io.execute {
            val file = if (video) copyPickedVideo(uri) else copyPicked(uri)
            runOnUiThread {
                loadingMedia = false
                if (file == null) return@runOnUiThread
                photo = file
                style = ReactWallpapers.STYLE_PHOTO
                pushSpec()
            }
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@registerForActivityResult
        working = true
        status = R.string.react_cutting
        io.execute {
            val file = copyPicked(uri)
            val made = file?.let { makePiece(it) }
            runOnUiThread {
                working = false
                status = R.string.react_editor_hint
                if (made == null || pieces.size >= ReactPhysics.MAX_BODIES) return@runOnUiThread
                pieces.add(made.first)
                cuts.add(made.second)
                sizes.add(0.35f)
                selected = pieces.size - 1
                pushPreview()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Callers check Premium before opening this; saving checks again.
        existing = intent.getStringExtra(EXTRA_CHARACTER)
        setContent {
            AkhuTheme {
                EditorScreen()
                if (cutting) CutoutScreen()
            }
        }
        load()
    }

    private fun load() {
        val app = applicationContext
        val saved = existing
        val source = intent.getStringExtra(EXTRA_PHOTO)
        io.execute {
            val loaded = mutableListOf<Pair<ReactWallpapers.Piece, Bitmap>>()
            var spec: ReactWallpapers.Spec? = null
            if (saved != null) {
                spec = ReactWallpapers.spec(app, saved)
                for (p in ReactWallpapers.loadPieces(app, saved, spec)) {
                    val c = p.cut()
                    if (c != null) loaded.add(p to c)
                }
            } else if (source != null) {
                makePiece(File(source))?.let { loaded.add(it) }
            }
            runOnUiThread {
                if (loaded.isEmpty()) {
                    // makePiece already said why when it could.
                    if (saved != null) Toast.makeText(this, R.string.pick_failed, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                spec?.let {
                    style = it.style
                    effects = it.effects
                    layer = it.layer
                    photo = it.photo?.let { p -> File(p) }
                }
                for ((i, pair) in loaded.withIndex()) {
                    pieces.add(pair.first)
                    cuts.add(pair.second)
                    sizes.add(spec?.sizes?.getOrNull(i) ?: 0.5f)
                }
                working = false
                status = R.string.react_editor_hint
                pushPreview()
            }
        }
    }

    /** Copies a picked picture into the cache; null (after saying why) if it can't be used. */
    private fun copyPicked(uri: android.net.Uri): File? = try {
        ImagePickerHelper.copyToCache(this, uri, PICKED_DIR, false)
    } catch (e: NsfwDetector.Rejected) {
        runOnUiThread { Toast.makeText(this, R.string.nsfw_photo_rejected, Toast.LENGTH_LONG).show() }
        null
    } catch (e: java.io.IOException) {
        runOnUiThread { Toast.makeText(this, R.string.pick_failed, Toast.LENGTH_SHORT).show() }
        null
    }

    /** Copies a picked video for the background into the cache; null (after saying why) if it can't be used. */
    private fun copyPickedVideo(uri: android.net.Uri): File? {
        val size = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
        if (size > MAX_VIDEO_BYTES) {
            runOnUiThread { Toast.makeText(this, R.string.video_too_big, Toast.LENGTH_LONG).show() }
            return null
        }
        val out = File(File(cacheDir, PICKED_DIR).apply { mkdirs() }, "bg_" + System.currentTimeMillis() + ".mp4")
        return try {
            (contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open $uri")).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            if (NsfwDetector.isVideoNsfw(this, out)) {
                out.delete()
                runOnUiThread { Toast.makeText(this, R.string.nsfw_rejected, Toast.LENGTH_LONG).show() }
                null
            } else {
                out
            }
        } catch (e: java.io.IOException) {
            out.delete()
            runOnUiThread { Toast.makeText(this, R.string.video_pick_failed, Toast.LENGTH_SHORT).show() }
            null
        }
    }

    /** The background picker: photos and videos. */
    private fun backgroundPickIntent(): Intent =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }

    /**
     * A picture as a piece with its automatic cut, and that cut-out; null (after saying why) when
     * it can't be read or nothing could be cut out. A picture that is already mostly transparent
     * keeps its own cut. Blocking.
     */
    private fun makePiece(file: File): Pair<ReactWallpapers.Piece, Bitmap>? {
        var error = R.string.fourd_failed
        try {
            val decoded = ImagePickerHelper.decodeSampled(file, ReactWallpapers.CHARACTER_DIM)
            if (decoded == null) {
                error = R.string.pick_failed
            } else {
                val b = if (decoded.config == Bitmap.Config.ARGB_8888) decoded
                else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
                val piece = if (isCutOut(b)) ReactWallpapers.Piece.ofCutout(b)
                else ReactWallpapers.Piece(b, FourDMaker.subjectMask(applicationContext, b))
                val cut = piece.cut()
                if (cut != null) return piece to cut
                error = R.string.fourd_no_subject
            }
        } catch (e: FourDMaker.MakeException) {
            error = e.messageRes
        }
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_LONG).show() }
        return null
    }

    /** True for a picture that is already a cut-out (a tenth or more of it transparent). */
    private fun isCutOut(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val step = maxOf(1, maxOf(b.width, b.height) / 96)
        var clear = 0
        var total = 0
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            total++
            if (b.getPixel(x, y) ushr 24 < 16) clear++
        }
        return clear >= total / 10
    }

    private fun spec() = ReactWallpapers.Spec().also {
        it.style = if (style == ReactWallpapers.STYLE_PHOTO && photo == null) ReactWallpapers.STYLE_POOL else style
        it.sizes = sizes.toFloatArray()
        it.effects = effects
        it.layer = layer
        it.photo = photo?.absolutePath
    }

    private fun pushSpec() {
        view?.setSpec(spec())
    }

    /** The images changed: the preview takes them all again. */
    private fun pushPreview() {
        view?.setPreview(cuts.toList(), spec())
    }

    private fun removeSelected() {
        if (pieces.size <= 1) return
        val i = selected
        pieces.removeAt(i)
        cuts.removeAt(i)
        sizes.removeAt(i)
        selected = minOf(i, pieces.size - 1)
        pushPreview()
    }

    // --- Cut-out screen ---------------------------------------------------------------------

    private fun openCutout() {
        val p = pieces.getOrNull(selected) ?: return
        maskBefore = p.mask.clone()
        tool = CutoutView.TOOL_ERASE
        canUndo = false
        cutting = true
    }

    private fun closeCutout(keep: Boolean) {
        val i = selected
        val p = pieces.getOrNull(i)
        if (p == null) {
            cutting = false
            return
        }
        if (!keep) {
            maskBefore?.let { p.mask = it }
            cutting = false
            return
        }
        working = true
        status = R.string.react_saving
        io.execute {
            val c = p.cut()
            runOnUiThread {
                working = false
                status = R.string.react_editor_hint
                if (c == null) {
                    Toast.makeText(this, R.string.react_cut_empty, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                cutting = false
                cuts[i] = c
                pushPreview()
            }
        }
    }

    private fun autoCut() {
        val p = pieces.getOrNull(selected) ?: return
        working = true
        status = R.string.react_auto_running
        io.execute {
            val mask = try {
                FourDMaker.subjectMask(applicationContext, p.source)
            } catch (e: FourDMaker.MakeException) {
                runOnUiThread { Toast.makeText(this, e.messageRes, Toast.LENGTH_LONG).show() }
                null
            }
            runOnUiThread {
                working = false
                status = R.string.react_editor_hint
                if (mask != null) cutoutView?.setMask(mask)
            }
        }
    }

    // --- Post / save ------------------------------------------------------------------------

    /**
     * The Post button: asks for the post's details, saves, then uploads it so it shows in
     * everyone's React tab (when public). The dialog's Save keeps it without posting.
     */
    private fun post() {
        if (pieces.isEmpty() || working) return
        if (!PremiumGate.check(this, R.string.react_premium_message)) return
        PostDialog.show(this, intent.getStringExtra(EXTRA_NAME), { title, isPublic, category ->
            if (canPost()) save { path -> upload(path, title, isPublic, category) }
        }, { save(null) })
    }

    /** True when signed in to a working cloud; otherwise says why and offers sign-in. */
    private fun canPost(): Boolean {
        if (!FirebaseHelper.isAvailable(this)) {
            Toast.makeText(this, R.string.cloud_disabled, Toast.LENGTH_LONG).show()
            return false
        }
        if (FirebaseHelper.currentUser(this) == null) {
            Toast.makeText(this, R.string.sign_in_required, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AuthActivity::class.java))
            return false
        }
        return true
    }

    private fun upload(path: String, title: String, isPublic: Boolean, category: String?) {
        // The upload outlives this screen, so it reports with the app context.
        val app = applicationContext
        Toast.makeText(app, R.string.uploading, Toast.LENGTH_SHORT).show()
        FirebaseHelper.uploadReact(app, path, title, isPublic, category, object : FirebaseHelper.UploadCallback {
            override fun onProgress(percent: Int) {}

            override fun onSuccess(docId: String) {
                Posts.link(app, path, FirebaseHelper.COLLECTION, docId)
                Toast.makeText(app, R.string.upload_done, Toast.LENGTH_SHORT).show()
            }

            override fun onError(e: Exception) {
                Toast.makeText(app, app.getString(R.string.upload_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
            }
        })
    }

    /** Saves, hands the saved path to [then] (if any) and closes. */
    private fun save(then: ((String) -> Unit)?) {
        if (pieces.isEmpty()) return
        working = true
        status = R.string.react_saving
        val spec = spec()
        val bg = photo
        val saved = existing
        val list = pieces.toList()
        val app = applicationContext
        io.execute {
            val path = try {
                ReactWallpapers.save(app, saved, list, spec, bg)
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
                then?.invoke(path)
                if (path == saved) {
                    setResult(Activity.RESULT_OK)
                } else {
                    // New, or a copy of a built-in or downloaded one: show it among the user's own.
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
        // Saved ones keep their own copies, so this editor's picks can go.
        if (isFinishing) {
            val dir = File(cacheDir, PICKED_DIR)
            Thread({ dir.deleteRecursively() }, "ReactPickedCleanup").start()
        }
        super.onDestroy()
    }

    // --- UI ---------------------------------------------------------------------------------

    @Composable
    private fun EditorScreen() {
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    ReactView(ctx).also {
                        view = it
                        // Keep the images out from under the top bar and the controls.
                        it.setInsets(0.12f, 0.34f)
                        if (cuts.isNotEmpty()) it.setPreview(cuts.toList(), spec())
                        it.onResume()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Top: close, title, post.
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
                Button(onClick = ::post, enabled = !working && pieces.isNotEmpty()) {
                    Text(stringResource(R.string.post))
                }
            }
            // Bottom: images / scene / effects.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000), Color(0xEE000000))))
                    .navigationBarsPadding()
                    .padding(top = Space.xl, bottom = Space.m),
            ) {
                if (loadingMedia) LoadingPill(Modifier.align(Alignment.CenterHorizontally).padding(bottom = Space.s))
                Text(
                    stringResource(
                        when (tab) {
                            TAB_EFFECTS -> R.string.react_effects_hint
                            TAB_LAYERS -> R.string.react_layers_hint
                            else -> status
                        }
                    ),
                    Modifier.fillMaxWidth().padding(horizontal = Space.screen),
                    color = Color(0xDDFFFFFF), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = Space.screen, vertical = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    // In TAB_* order.
                    for ((i, name) in listOf(R.string.react_images, R.string.react_scene, R.string.react_layers,
                        R.string.react_effects).withIndex()) {
                        FilterChip(
                            selected = tab == i, onClick = { tab = i },
                            label = { Text(stringResource(name)) },
                            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                        )
                    }
                }
                when (tab) {
                    TAB_IMAGES -> ImagesPanel()
                    TAB_SCENE -> ScenePanel()
                    TAB_LAYERS -> LayersPanel()
                    else -> EffectsPanel()
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

    /** A small "Loading…" with a spinner, for a picked photo or video that takes a moment. */
    @Composable
    private fun LoadingPill(modifier: Modifier = Modifier) {
        Row(
            modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xCC000000))
                .padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            Text(stringResource(R.string.loading), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }

    /** Each image as a thumbnail (tap to select), an add button, then the selected one's tools and size. */
    @Composable
    private fun ImagesPanel() {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((i, c) in cuts.withIndex()) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x33FFFFFF))
                        .border(BorderStroke(if (i == selected) 3.dp else 1.dp, if (i == selected) Color.White else Color(0x55FFFFFF)), RoundedCornerShape(12.dp))
                        .clickable { selected = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        c.asImageBitmap(), stringResource(R.string.react_image_n, i + 1),
                        Modifier.size(48.dp), contentScale = ContentScale.Fit,
                    )
                }
            }
            if (cuts.size < ReactPhysics.MAX_BODIES) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, Color(0x88FFFFFF)), RoundedCornerShape(12.dp))
                        .clickable { pickImage.launch(ImagePickerHelper.createPickIntent()) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_add_photo), stringResource(R.string.react_add_image_desc), tint = Color.White)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = ::openCutout, enabled = !working && cuts.isNotEmpty()) {
                Text(stringResource(R.string.react_cut_out), color = Color.White)
            }
            if (cuts.size > 1) {
                TextButton(onClick = ::removeSelected, enabled = !working) {
                    Text(stringResource(R.string.react_remove), color = Color(0xFFFF8A80))
                }
            }
            Text(
                stringResource(R.string.react_max_images, ReactPhysics.MAX_BODIES), Modifier.weight(1f),
                color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End,
            )
        }
        if (selected < sizes.size) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.screen), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.react_size), color = Color.White, style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = sizes[selected], onValueChange = { sizes[selected] = it; pushSpec() }, valueRange = 0.15f..0.85f,
                    modifier = Modifier.weight(1f).padding(start = Space.m),
                )
            }
        }
    }

    @Composable
    private fun ScenePanel() {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            for (i in ReactWallpapers.STYLE_ORDER) {
                StyleChip(i, stringResource(ReactWallpapers.STYLE_NAMES[i]), selected = i == style) {
                    if (i == ReactWallpapers.STYLE_PHOTO) {
                        pickBackground.launch(backgroundPickIntent())
                    } else {
                        style = i
                        pushSpec()
                    }
                }
            }
        }
    }

    /** The touch layer, one of ten: water ripples or something in their place. */
    @Composable
    private fun LayersPanel() {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            for ((i, name) in ReactWallpapers.LAYER_NAMES.withIndex()) {
                FilterChip(
                    selected = layer == i,
                    onClick = {
                        layer = i
                        pushSpec()
                        // A ripple in the middle shows it straight away.
                        view?.let { v -> v.ripple(v.width / 2f, v.height * 0.45f) }
                    },
                    label = { Text(stringResource(name)) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                )
            }
        }
    }

    @Composable
    private fun EffectsPanel() {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            for ((i, bit) in ReactWallpapers.EFFECTS.withIndex()) {
                val on = effects and bit != 0
                FilterChip(
                    selected = on,
                    onClick = {
                        effects = effects xor bit
                        pushSpec()
                    },
                    label = { Text(stringResource(ReactWallpapers.EFFECT_NAMES[i])) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                )
            }
        }
    }

    /** Full screen over the editor: the selected image with erase / restore brushes, auto and undo. */
    @Composable
    private fun CutoutScreen() {
        BackHandler { closeCutout(false) }
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize().background(Color(0xFF16181D))) {
            AndroidView(
                factory = { ctx ->
                    CutoutView(ctx).also { v ->
                        cutoutView = v
                        pieces.getOrNull(selected)?.let { v.setPiece(it) }
                        v.setTool(tool)
                        v.setBrushRadius(with(density) { brush.dp.toPx() })
                        v.setListener { canUndo = v.canUndo() }
                    }
                },
                update = { v ->
                    v.setTool(tool)
                    v.setBrushRadius(with(density) { brush.dp.toPx() })
                },
                onRelease = { cutoutView = null },
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp, bottom = 190.dp),
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.s, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { closeCutout(false) }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close), tint = Color.White)
                }
                Text(
                    stringResource(R.string.react_cut_title), Modifier.weight(1f).padding(horizontal = Space.m),
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { cutoutView?.undo(); canUndo = cutoutView?.canUndo() == true }, enabled = canUndo) {
                    Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.undo),
                        tint = if (canUndo) Color.White else Color(0x55FFFFFF))
                }
                Button(onClick = { closeCutout(true) }, enabled = !working) {
                    Text(stringResource(R.string.done))
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = Space.m),
            ) {
                Text(
                    stringResource(R.string.react_cut_hint), Modifier.fillMaxWidth().padding(horizontal = Space.screen),
                    color = Color(0xBBFFFFFF), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    FilterChip(
                        selected = tool == CutoutView.TOOL_ERASE, onClick = { tool = CutoutView.TOOL_ERASE },
                        label = { Text(stringResource(R.string.react_erase)) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                    )
                    FilterChip(
                        selected = tool == CutoutView.TOOL_RESTORE, onClick = { tool = CutoutView.TOOL_RESTORE },
                        label = { Text(stringResource(R.string.react_restore)) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                    )
                    FilterChip(
                        selected = false, onClick = ::autoCut, enabled = !working,
                        label = { Text(stringResource(R.string.react_auto)) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.screen), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.react_brush), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = brush, onValueChange = { brush = it }, valueRange = 6f..60f,
                        modifier = Modifier.weight(1f).padding(start = Space.m),
                    )
                }
            }
            if (working) {
                Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(status), Modifier.padding(top = Space.m), color = Color.White)
                    }
                }
            }
        }
    }

    /** A round swatch of the scene (or a photo icon) with its name under it. */
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
