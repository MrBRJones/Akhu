package com.MrBRos.akhu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.google.android.material.color.MaterialColors
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Shared pieces every Compose screen is built from, so headers, sections and buttons look the
 * same everywhere. Sizes come from AkhuTheme's type scale and the Space grid.
 */

/** Top bar of a screen opened over the tabs: back arrow, title, then [actions] at the end. */
@Composable
fun AkhuTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = if (onBack != null) Space.xs else Space.screen, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), tint = contentColor)
            }
            Spacer(Modifier.width(Space.xs))
        }
        Text(
            title, Modifier.weight(1f), color = contentColor, style = MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** A titled block of a screen: 16sp semibold title with an optional grey line under it. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(top = Space.xl, bottom = Space.s)) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle, Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Small coloured heading over a group of rows, as in Settings. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(start = Space.screen, end = Space.screen, top = Space.xl, bottom = Space.s),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
    )
}

/** The screen's main action: full width, 56dp, fully rounded. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick, enabled = enabled, shape = CircleShape,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = Space.xl),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Centred grey note shown where a list has nothing in it yet. */
@Composable
fun EmptyState(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (body != null) {
                Text(
                    body, Modifier.padding(top = Space.xs), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** Gradient banner at the top of a tab (app colors): title, subtitle and a round icon on the right. */
@Composable
fun GradientBanner(
    title: String,
    subtitle: String,
    @androidx.annotation.DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(rememberBannerBrush())
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = Space.xl, end = 88.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(
                subtitle, Modifier.padding(top = 2.dp), color = Color(0xE6FFFFFF),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = Space.xl)
                .size(48.dp)
                .background(Color(0x33FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = Color.White)
        }
    }
}

/** Two or more options in a rounded track, the chosen one raised: Hot / New, Videos / 3D motion. */
@Composable
fun PillToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .background(colors.surfaceContainer, CircleShape)
            .padding(3.dp)
            .selectableGroup()
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (on) colors.surface else Color.Transparent)
                    .selectable(on, role = Role.Tab) { onSelect(i) }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = Space.l),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge,
                    color = if (on) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** A category filter chip: dark fill when chosen, soft grey otherwise. */
@Composable
fun CategoryPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) colors.onSurface else colors.surfaceContainer)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.surface else colors.onSurface,
        )
    }
}

/** The tab gradient (attrs akhuGradient*): classic purple-pink-gold, or the custom app colors. */
@Composable
fun rememberBannerBrush(): Brush {
    val context = LocalContext.current
    return remember(context) {
        fun c(attr: Int) = Color(MaterialColors.getColor(context, attr, 0))
        Brush.horizontalGradient(
            listOf(c(R.attr.akhuGradientStart), c(R.attr.akhuGradientCenter), c(R.attr.akhuGradientEnd), c(R.attr.akhuGradientTail))
        )
    }
}

/**
 * A round profile picture: [image] is a File, URL or null for the gradient person icon.
 * [version] changes when the same file is replaced, so the new picture shows.
 */
@Composable
fun Avatar(image: Any?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, version: Long = 0) {
    Box(
        modifier.size(size).clip(CircleShape).background(rememberBannerBrush()),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            Icon(painterResource(R.drawable.ic_person), null, Modifier.size(size / 2), tint = Color.White)
        } else {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { android.widget.ImageView(it).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } },
                modifier = Modifier.matchParentSize(),
                update = { iv ->
                    com.bumptech.glide.Glide.with(iv).load(image)
                        .signature(com.bumptech.glide.signature.ObjectKey(version)).circleCrop().into(iv)
                },
            )
        }
    }
}
