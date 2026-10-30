package com.MrBRos.akhu

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
