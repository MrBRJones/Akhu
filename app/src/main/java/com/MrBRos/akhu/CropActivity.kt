package com.MrBRos.akhu

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crops a picture before it is made into a wallpaper: drag the box, pick Phone (the screen's
 * shape), Free or Square, then Done. Returns the cropped copy's path as [EXTRA_PATH].
 */
class CropActivity : AppCompatActivity() {

    private var bitmap by mutableStateOf<Bitmap?>(null)
    private var shape by mutableIntStateOf(0)
    private var busy by mutableStateOf(false)
    private var cropView: CropImageView? = null
    private lateinit var source: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = File(intent.getStringExtra(EXTRA_PATH) ?: return finish())
        Thread({
            val b = ImagePickerHelper.decodeSampled(source, PREVIEW_DIM)
            runOnUiThread { if (b == null) finish() else bitmap = b }
        }, "CropLoad").start()
        setContent {
            AkhuTheme {
                Column(Modifier.fillMaxSize()) {
                    AkhuTopBar(stringResource(R.string.crop), ::finish) {
                        TextButton(onClick = ::done, enabled = !busy && bitmap != null) {
                            Text(stringResource(R.string.done))
                        }
                    }
                    PillToggle(
                        listOf(stringResource(R.string.crop_phone), stringResource(R.string.crop_free), stringResource(R.string.crop_square)),
                        shape, { shape = it; cropView?.setAspect(aspectFor(it)) },
                        Modifier.padding(horizontal = Space.screen).align(Alignment.CenterHorizontally),
                    )
                    AndroidView(
                        factory = { CropImageView(it, null).also { v -> cropView = v } },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = Space.m),
                        update = { v ->
                            val b = bitmap
                            if (b != null && v.tag !== b) {
                                v.tag = b
                                v.setBitmap(b)
                                v.setAspect(aspectFor(shape))
                            }
                        },
                    )
                }
            }
        }
    }

    /** Phone is this screen's own width / height, so the crop fills it exactly. */
    private fun aspectFor(i: Int): Float {
        val m = resources.displayMetrics
        return when (i) {
            0 -> minOf(m.widthPixels, m.heightPixels).toFloat() / maxOf(m.widthPixels, m.heightPixels)
            2 -> 1f
            else -> 0f
        }
    }

    /** Cuts the box out of the full-size picture, off the main thread, and hands it back. */
    private fun done() {
        val crop = cropView?.crop ?: return
        busy = true
        val app = applicationContext
        Thread({
            var out: File? = null
            val full = ImagePickerHelper.decodeSampled(source, FULL_DIM)
            if (full != null) {
                val x = (crop.left * full.width).toInt().coerceIn(0, full.width - 1)
                val y = (crop.top * full.height).toInt().coerceIn(0, full.height - 1)
                val w = ((crop.right - crop.left) * full.width).toInt().coerceIn(1, full.width - x)
                val h = ((crop.bottom - crop.top) * full.height).toInt().coerceIn(1, full.height - y)
                val cut = Bitmap.createBitmap(full, x, y, w, h)
                val dir = File(app.cacheDir, "cropped").apply { mkdirs() }
                val file = File(dir, "crop_" + System.currentTimeMillis() + ".jpg")
                try {
                    FileOutputStream(file).use { cut.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    out = file
                } catch (e: IOException) {
                    file.delete()
                }
                if (cut != full) cut.recycle()
                full.recycle()
            }
            runOnUiThread {
                busy = false
                if (out == null) {
                    Toast.makeText(app, R.string.save_failed, Toast.LENGTH_SHORT).show()
                } else {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, out.absolutePath))
                    finish()
                }
            }
        }, "Crop").start()
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val PREVIEW_DIM = 2048
        private const val FULL_DIM = 4096

        @JvmStatic
        fun intent(context: Context, path: String): Intent =
            Intent(context, CropActivity::class.java).putExtra(EXTRA_PATH, path)
    }
}
