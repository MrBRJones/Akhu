package com.MrBRos.akhu

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.google.android.material.color.MaterialColors
import java.io.File
import kotlin.math.abs

/**
 * The Create tab's screen, drawn with Compose. [CreateFragment] keeps all the work (picking,
 * filters, 3D cut-outs, motion) and drives the screen through [CreateUi].
 */
class CreateUi {
    /** CreateFragment.MODE_* */
    var mode by mutableIntStateOf(0)
    var name by mutableStateOf("")
    @get:StringRes
    var status by mutableIntStateOf(R.string.pick_first)
    /** The 2D/3D picture, or null to show the picker prompt. */
    var picture by mutableStateOf<Bitmap?>(null)
    /** The MOTION video or GIF, or null. */
    var motion by mutableStateOf<File?>(null)
    var busy by mutableStateOf(false)

    interface Actions {
        fun onPreviewTap()
        fun onCreate()
        fun onMode(mode: Int)
        fun onTheme()
        fun onName(name: String)
    }

    companion object {
        /** The mode strip, left to right; THEME is last and opens its own editor. */
        @JvmField val ORDER = intArrayOf(2, 0, 1)

        @JvmStatic
        fun view(container: android.content.Context, ui: CreateUi, actions: Actions): ComposeView =
            ComposeView(container).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { AkhuTheme { CreateScreen(ui, actions) } }
            }
    }
}

private const val MODE_FOURD = 0
private const val MODE_MOTION = 1
private const val MODE_2D = 2

@Composable
private fun CreateScreen(ui: CreateUi, actions: CreateUi.Actions) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    // Same colour language as before: pink for 2D, the second app colour for 3D, the main one for MOTION.
    val accent = when (ui.mode) {
        MODE_MOTION -> MaterialTheme.colorScheme.primary
        MODE_2D -> Color(context.getColor(R.color.brand_2d))
        else -> Color(MaterialColors.getColor(context, R.attr.akhuBrandSecondary, 0))
    }
    val swipe = with(LocalDensity.current) { 60.dp.toPx() }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.screen)
            // Left/right swipes step through the modes in strip order; THEME stays tap-only.
            .pointerInput(ui.busy) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onHorizontalDrag = { _, d -> dx += d },
                    onDragEnd = {
                        if (ui.busy || abs(dx) < swipe) return@detectHorizontalDragGestures
                        val at = CreateUi.ORDER.indexOf(ui.mode)
                        val next = at + if (dx < 0) 1 else -1
                        if (next in CreateUi.ORDER.indices) actions.onMode(CreateUi.ORDER[next])
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreviewCard(ui, Modifier.weight(1f).padding(top = Space.xs)) { actions.onPreviewTap() }
        OutlinedTextField(
            value = ui.name,
            onValueChange = { ui.name = it.take(40); actions.onName(ui.name) },
            label = { Text(stringResource(R.string.creation_name)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(top = Space.l),
        )
        Text(
            stringResource(ui.status), Modifier.fillMaxWidth().padding(top = Space.s),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = actions::onCreate, enabled = !ui.busy, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(top = Space.m).heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.create_action), style = MaterialTheme.typography.titleMedium) }
        val modes = listOf(MODE_2D, MODE_FOURD, MODE_MOTION)
        PillToggle(
            listOf(
                stringResource(R.string.mode_2d), stringResource(R.string.mode_fourd),
                stringResource(R.string.mode_motion), stringResource(R.string.mode_theme),
            ),
            modes.indexOf(ui.mode),
            { i -> if (i < modes.size) actions.onMode(modes[i]) else actions.onTheme() },
            Modifier.padding(top = Space.m, bottom = Space.m),
        )
    }
}

/** The big rounded preview: the picked picture or clip, or a prompt to pick one. */
@Composable
private fun PreviewCard(ui: CreateUi, modifier: Modifier, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceContainer)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        val picture = ui.picture
        val motion = ui.motion
        when {
            ui.mode == MODE_MOTION && motion != null -> AndroidView(
                // Glide animates a GIF and shows a still frame for a video.
                factory = { android.widget.ImageView(it).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
                modifier = Modifier.fillMaxSize(),
                update = { Glide.with(it).load(motion).fitCenter().into(it) },
            )
            ui.mode != MODE_MOTION && picture != null -> Image(
                remember(picture) { picture.asImageBitmap() }, stringResource(R.string.preview),
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_add_photo), null, Modifier.size(32.dp), tint = colors.onSurface)
                }
                Text(
                    stringResource(if (ui.mode == MODE_MOTION) R.string.upload_video_gif else R.string.tap_to_pick),
                    Modifier.padding(top = Space.m), color = colors.onSurface, style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
