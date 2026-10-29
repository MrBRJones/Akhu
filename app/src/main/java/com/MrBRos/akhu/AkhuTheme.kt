package com.MrBRos.akhu

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.AttrRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

/**
 * Compose MaterialTheme built from the screen's own View theme, so Compose screens get exactly
 * the colors the XML screens use: classic blue by default, or the custom app colors from Settings
 * (applied to the activity's theme by App before it is created).
 */
@Composable
fun AkhuTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(context) { colorSchemeFrom(context) }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun colorSchemeFrom(context: Context): androidx.compose.material3.ColorScheme {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val base = if (dark) darkColorScheme() else lightColorScheme()
    fun c(@AttrRes attr: Int, fallback: Color) =
        Color(MaterialColors.getColor(context, attr, fallback.toArgb()))
    return base.copy(
        primary = c(MR.attr.colorPrimary, base.primary),
        onPrimary = c(MR.attr.colorOnPrimary, base.onPrimary),
        primaryContainer = c(MR.attr.colorPrimaryContainer, base.primaryContainer),
        onPrimaryContainer = c(MR.attr.colorOnPrimaryContainer, base.onPrimaryContainer),
        secondary = c(MR.attr.colorSecondary, base.secondary),
        onSecondary = c(MR.attr.colorOnSecondary, base.onSecondary),
        secondaryContainer = c(MR.attr.colorSecondaryContainer, base.secondaryContainer),
        onSecondaryContainer = c(MR.attr.colorOnSecondaryContainer, base.onSecondaryContainer),
        tertiary = c(MR.attr.colorTertiary, base.tertiary),
        onTertiary = c(MR.attr.colorOnTertiary, base.onTertiary),
        tertiaryContainer = c(MR.attr.colorTertiaryContainer, base.tertiaryContainer),
        onTertiaryContainer = c(MR.attr.colorOnTertiaryContainer, base.onTertiaryContainer),
        // The app's own surface and text colors, as the XML screens use them.
        background = c(R.attr.akhuSurface, base.background),
        surface = c(R.attr.akhuSurface, base.surface),
        onBackground = c(R.attr.akhuTextPrimary, base.onBackground),
        onSurface = c(R.attr.akhuTextPrimary, base.onSurface),
        onSurfaceVariant = c(R.attr.akhuTextSecondary, base.onSurfaceVariant),
        surfaceContainer = c(R.attr.akhuSurfaceMuted, base.surfaceContainer),
        surfaceContainerLowest = c(MR.attr.colorSurfaceContainerLowest, base.surfaceContainerLowest),
        surfaceContainerLow = c(MR.attr.colorSurfaceContainerLow, base.surfaceContainerLow),
        surfaceContainerHigh = c(MR.attr.colorSurfaceContainerHigh, base.surfaceContainerHigh),
        surfaceContainerHighest = c(MR.attr.colorSurfaceContainerHighest, base.surfaceContainerHighest),
        outline = c(MR.attr.colorOutline, base.outline),
        outlineVariant = c(MR.attr.colorOutlineVariant, base.outlineVariant),
        inverseSurface = c(R.attr.akhuInverseSurface, base.inverseSurface),
        inverseOnSurface = c(R.attr.akhuOnInverseSurface, base.inverseOnSurface),
        inversePrimary = c(MR.attr.colorPrimaryInverse, base.inversePrimary),
        error = c(androidx.appcompat.R.attr.colorError, base.error),
    )
}
