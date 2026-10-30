package com.MrBRos.akhu

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.AttrRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    MaterialTheme(colorScheme = scheme, typography = AkhuType, shapes = AkhuShapes, content = content)
}

/** Plus Jakarta Sans, the one typeface (res/font, same as the XML screens). */
@OptIn(ExperimentalTextApi::class)
val AkhuSans = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.plus_jakarta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

/*
 * Five sizes (values/type.xml): 28 screen titles (headlineMedium), 20 section titles (titleLarge),
 * 16 body and row titles (bodyLarge, titleMedium), 14 secondary text and buttons (bodyMedium,
 * titleSmall, labelLarge), 12 captions and tags (bodySmall, labelMedium, labelSmall).
 */
private fun style(size: Int, weight: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = AkhuSans, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

val AkhuType = Typography(
    displayLarge = style(57, 700, 64),
    displayMedium = style(45, 700, 52),
    displaySmall = style(36, 700, 44),
    headlineLarge = style(32, 700, 40),
    headlineMedium = style(28, 700, 36, -0.2),
    headlineSmall = style(24, 700, 32),
    titleLarge = style(20, 700, 28),
    // Letter spacing as Material 3 sets it; without it the smaller sizes look cramped.
    titleMedium = style(16, 600, 24, 0.15),
    titleSmall = style(14, 600, 20, 0.1),
    bodyLarge = style(16, 400, 24, 0.3),
    bodyMedium = style(14, 400, 20, 0.25),
    bodySmall = style(12, 400, 16, 0.3),
    labelLarge = style(14, 600, 20, 0.1),
    labelMedium = style(12, 600, 16, 0.4),
    labelSmall = style(12, 600, 16, 0.4),
)

/** Three corners: 12 small (chips, fields), 16 medium (cards), 28 large (sheets, dialogs). */
val AkhuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Spacing grid: everything lines up on these steps. Screen edges use [screen]. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 16.dp
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
