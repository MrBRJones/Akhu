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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
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

/**
 * A wallpaper, motion clip or 3D image card with its badge, title and likes. With [labels] off
 * (the Wallpapers sections) it is just the picture; the preview shows the name and tag.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WallpaperTile(
    card: WallpaperCard,
    selection: GridSelection<WallpaperCard>?,
    @Suppress("UNUSED_PARAMETER") ticks: Int,
    labels: Boolean = true,
    onClick: () -> Unit,
) {
    val selecting = selection?.isActive == true
    val pickable = selecting && selection!!.canSelect(card)
    val likeKey = Likes.keyOf(card)
    val context = LocalContext.current
    // Reactive wallpapers show a drawn still, made (and cached) off the main thread.
    val poster = if (card.isReact) produceState<java.io.File?>(null, card.assetPath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ReactPoster.get(context, card.assetPath!!) }
    }.value else null
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
                if (card.isReact) {
                    if (poster != null) Glide.with(thumb).load(poster).into(thumb) else thumb.setImageDrawable(null)
                } else if (card.isVideo) {
                    // No transform: it would flatten the animated WebP to a still.
                    Glide.with(thumb).load(card.image).into(thumb)
                } else {
                    // Bundled images crop around their subject; the user's own files fall back to centre.
                    val focal = Focal.get(thumb.context, card.assetPath)
                    Glide.with(thumb).load(card.image).transform(FocalCrop(focal[0], focal[1])).into(thumb)
                }
            },
        )
        // Reactive ones play live over their still, as in the preview: the scene moves and the characters float with the tilt.
        if (card.isReact && card.assetPath != null) {
            AndroidView(
                factory = { ReactTileView(it) },
                modifier = Modifier.fillMaxSize(),
                update = { it.setCharacter(card.assetPath!!) },
            )
        } else if (card.post?.isReact == true && card.post?.isLocked == false) {
            AndroidView(
                factory = { ReactTileView(it) },
                modifier = Modifier.fillMaxSize(),
                update = { it.setPost(card.post!!) },
            )
        }
        // Videos loop their first few seconds over the thumbnail, once it's ready to play.
        val loop = produceState<String?>(null, card) { value = TileVideos.source(context, card) }.value
        if (loop != null) {
            androidx.compose.runtime.key(loop) {
                AndroidView(
                    factory = { CropVideoView(it, loop).apply { setLoopMs(TileVideos.LOOP_MS); play() } },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.release() },
                )
            }
        }
        // Live-tilt 3D render over the still thumbnail (Android 13+ only).
        if (card.isFourD && FourDTileView.isSupported()) {
            AndroidView(
                factory = { FourDTileView(it) },
                modifier = Modifier.fillMaxSize(),
                update = { it.setAsset(card.assetPath) },
            )
        }
        if (labels) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp).background(Scrim))
            card.badge?.let { Badge(card) }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                if (card.title.isNotEmpty()) {
                    Text(
                        card.title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (card.likes >= 0) {
                    Text(stringResource(R.string.likes_short, card.likes + Likes.delta(likeKey)), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // Multi-select: greys out cards that can't be picked, and the tick circle.
        if (selecting && !pickable) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        if (pickable) SelectTick(selection!!.isSelected(card), Modifier.align(Alignment.TopEnd))
        if (!selecting) LikedMark(likeKey, Modifier.align(Alignment.TopEnd))
        LikeTapLayer(likeKey, selecting, onClick = { if (selection == null || !selection.onTap(card)) onClick() },
            onLongClick = { selection?.onLongPress(card) })
    }
}

/** Every wallpaper bundled with the app (2D, 3D and Motion) is Featured. */
private fun isFeaturedPick(card: WallpaperCard) = "Featured" == card.badge

/** The card's tag as shown: the price, Hot, Featured, Motion, 3D, or its own badge text. */
internal fun badgeText(context: android.content.Context, card: WallpaperCard): String = when {
    card.post?.forSale == true && card.post?.tier != null -> Market.price(card.post!!.tier!!)
    "Hot" == card.badge -> context.getString(R.string.badge_hot)
    isFeaturedPick(card) -> context.getString(R.string.badge_featured)
    card.isVideo || "Motion" == card.badge -> context.getString(R.string.badge_motion)
    card.isFourD || "4D" == card.badge -> context.getString(R.string.badge_fourd)
    card.isReact || "React" == card.badge -> context.getString(R.string.badge_react)
    else -> card.badge ?: ""
}

/** "Name · Tag" for the preview, which shows what the label-less grid leaves out. */
internal fun previewLabel(context: android.content.Context, card: WallpaperCard): String =
    listOf(card.title, badgeText(context, card)).filter { it.isNotEmpty() }.joinToString(" · ")

@Composable
private fun Badge(card: WallpaperCard) {
    val hot = "Hot" == card.badge
    val featured = isFeaturedPick(card)
    val text = badgeText(LocalContext.current, card)
    // Quiet frosted tag; only Hot gets a small coloured dot and Featured a gold star, so the grid stays calm.
    Row(
        Modifier
            .padding(Space.s)
            .background(Color(0x73000000), CircleShape)
            .padding(horizontal = Space.s, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (featured) {
            Text("\u2605", Modifier.padding(end = 4.dp), color = Color(0xFFFFC83D), style = MaterialTheme.typography.labelMedium)
        } else if (hot) {
            Box(Modifier.padding(end = 5.dp).size(6.dp).background(Color(0xFFFF7A3D), CircleShape))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** A saved home screen theme: the pretend phone screen with its name along the bottom. */
@Composable
internal fun ThemeTile(
    theme: CustomTheme,
    selection: GridSelection<CustomTheme>?,
    @Suppress("UNUSED_PARAMETER") ticks: Int,
    wallpaperMax: Int,
    likes: Long = -1,
    labels: Boolean = true,
    onClick: () -> Unit,
) {
    val selecting = selection?.isActive == true
    val likeKey = Likes.keyOf(theme)
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
        if (labels) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp).background(Scrim))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                Text(
                    theme.name, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (likes >= 0) {
                    Text(stringResource(R.string.likes_short, likes + Likes.delta(likeKey)), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (selecting) SelectTick(selection!!.isSelected(theme), Modifier.align(Alignment.TopEnd))
        else LikedMark(likeKey, Modifier.align(Alignment.TopEnd))
        LikeTapLayer(likeKey, selecting, onClick = { if (selection == null || !selection.onTap(theme)) onClick() },
            onLongClick = { selection?.onLongPress(theme) })
    }
}

/** A small white heart in the corner of anything the user has liked. */
@Composable
private fun LikedMark(key: String, modifier: Modifier) {
    if (!Likes.isLiked(LocalContext.current, key)) return
    Icon(
        painterResource(R.drawable.ic_heart), stringResource(R.string.liked),
        // On a frosted dot, like the badges, so it reads on light pictures too.
        modifier.padding(Space.s).size(26.dp).background(Color(0x73000000), CircleShape).padding(5.dp),
        tint = Color.White,
    )
}

/**
 * Sits on top of the hosted Views so taps reach the card, not the picture. A double tap likes the
 * card with a heart that pops up and fades (not while multi-selecting, where taps tick cards).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LikeTapLayer(key: String, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    var burst by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxSize().combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onDoubleClick = if (selecting) null else ({
                Likes.like(context, key)
                burst++
            }),
        ),
        contentAlignment = Alignment.Center,
    ) { HeartBurst(burst, 56.dp) }
}

/** The double-tap heart: grows in with a little overshoot, holds, then fades. Nothing at 0. */
@Composable
fun HeartBurst(trigger: Int, size: Dp) {
    if (trigger == 0) return
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        alpha.snapTo(1f)
        scale.snapTo(0f)
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
        delay(350)
        alpha.animateTo(0f, tween(250))
    }
    Icon(
        painterResource(R.drawable.ic_heart), null,
        Modifier.size(size).graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            this.alpha = alpha.value
        },
        tint = Color.White,
    )
}

@Composable
private fun SelectTick(selected: Boolean, modifier: Modifier) {
    Image(
        painterResource(if (selected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline),
        contentDescription = null,
        modifier = modifier.padding(8.dp).size(26.dp),
    )
}

/** The grey note shown in place of an empty grid. */
@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
