package com.MrBRos.akhu

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide

/*
 * Compose versions of the grid cards (item_wallpaper_card.xml and the old item_theme_card.xml): same
 * 16dp corners, 9:16 shape, bottom scrim and multi-select tick. Pictures still come from the
 * existing Views (Glide ImageView, FourDTileView, HomeMockView) hosted with AndroidView.
 */

// The tiles take `ticks`, a counter bumped whenever a GridSelection changes. GridSelection isn't
// Compose state, so the changed parameter is what redraws the ticks.

private val CardShape = RoundedCornerShape(16.dp)
private val Scrim = Brush.verticalGradient(listOf(Color(0x00000000), Color(0xA6000000)))

/** Rows of [columns] cards, each with the 6dp margin the XML grids used, inset by [inset] at the sides. */
fun <T> LazyListScope.cardRows(
    items: List<T>,
    columns: Int,
    inset: Dp = 0.dp,
    key: (T) -> String,
    card: @Composable (T) -> Unit,
) {
    val rows = items.chunked(columns)
    items(rows.size, key = { i -> key(rows[i][0]) }) { i ->
        Row(Modifier.fillMaxWidth().padding(horizontal = inset)) {
            for (item in rows[i]) Box(Modifier.weight(1f).padding(6.dp)) { card(item) }
            repeat(columns - rows[i].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A wallpaper, motion clip or 3D image card with its badge, title and likes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WallpaperTile(
    card: WallpaperCard,
    selection: GridSelection<WallpaperCard>?,
    @Suppress("UNUSED_PARAMETER") ticks: Int,
    onClick: () -> Unit,
) {
    val selecting = selection?.isActive == true
    val pickable = selecting && selection!!.canSelect(card)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
            modifier = Modifier.fillMaxSize(),
            update = { thumb ->
                if (card.isVideo) {
                    // No transform: it would flatten the animated WebP to a still.
                    Glide.with(thumb).load(card.image).into(thumb)
                } else {
                    // Bundled images crop around their subject; the user's own files fall back to centre.
                    val focal = Focal.get(thumb.context, card.assetPath)
                    Glide.with(thumb).load(card.image).transform(FocalCrop(focal[0], focal[1])).into(thumb)
                }
            },
        )
        // Live-tilt 3D render over the still thumbnail (Android 13+ only).
        if (card.isFourD && FourDTileView.isSupported()) {
            AndroidView(
                factory = { FourDTileView(it) },
                modifier = Modifier.fillMaxSize(),
                update = { it.setAsset(card.assetPath) },
            )
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp).background(Scrim))
        card.badge?.let { Badge(card) }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
            if (card.title.isNotEmpty()) {
                Text(
                    card.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (card.likes >= 0) {
                Text(stringResource(R.string.likes_short, card.likes), color = Color(0xE6FFFFFF), fontSize = 11.sp)
            }
        }
        // Multi-select: greys out cards that can't be picked, and the tick circle.
        if (selecting && !pickable) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        if (pickable) SelectTick(selection!!.isSelected(card), Modifier.align(Alignment.TopEnd))
        TapLayer(onClick = { if (selection == null || !selection.onTap(card)) onClick() },
            onLongClick = { selection?.onLongPress(card) })
    }
}

@Composable
private fun Badge(card: WallpaperCard) {
    val hot = "Hot" == card.badge
    val featured = card.isFeatured
    val text = when {
        hot -> stringResource(R.string.badge_hot)
        card.isVideo || "Motion" == card.badge -> stringResource(R.string.badge_motion)
        card.isFourD -> stringResource(R.string.badge_fourd)
        featured -> stringResource(R.string.badge_featured)
        else -> card.badge ?: ""
    }
    val shape = RoundedCornerShape(10.dp)
    val bg = if (hot || featured) Modifier.background(
        Brush.horizontalGradient(listOf(Color(0xFFFF8A00), Color(0xFFFF3D3D))), shape
    ) else Modifier.background(Color(0x99000000), shape)
    Text(
        text, Modifier.padding(8.dp).then(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
    )
}

/** A saved home screen theme: the pretend phone screen with its name along the bottom. */
@Composable
internal fun ThemeTile(
    theme: CustomTheme,
    selection: GridSelection<CustomTheme>,
    @Suppress("UNUSED_PARAMETER") ticks: Int,
    wallpaperMax: Int,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AndroidView(
            factory = { HomeMockView(it).apply { setWallpaperMax(wallpaperMax) } },
            modifier = Modifier.fillMaxSize(),
            update = { it.setTheme(theme) },
        )
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp).background(Scrim))
        Text(
            theme.name, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (selection.isActive) SelectTick(selection.isSelected(theme), Modifier.align(Alignment.TopEnd))
        TapLayer(onClick = { if (!selection.onTap(theme)) onClick() },
            onLongClick = { selection.onLongPress(theme) })
    }
}

@Composable
private fun SelectTick(selected: Boolean, modifier: Modifier) {
    Image(
        painterResource(if (selected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline),
        contentDescription = null,
        modifier = modifier.padding(8.dp).size(26.dp),
    )
}

/** Sits on top of the hosted Views so taps and long-presses reach the card, not the picture. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TapLayer(onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = onLongClick))
}

/** The grey note shown in place of an empty grid. */
@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
