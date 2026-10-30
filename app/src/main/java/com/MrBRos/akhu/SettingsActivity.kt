package com.MrBRos.akhu

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Settings screen opened from the gear at the top right: dark mode, app colors and About. Drawn with Compose. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the switch is first used the app follows the phone, so show whatever is on screen now.
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val colors = App.appColors(this)
        setContent {
            AkhuTheme {
                SettingsScreen(
                    dark = dark,
                    colors = colors,
                    version = packageManager.getPackageInfo(packageName, 0).versionName ?: "",
                    supportEmail = getString(R.string.support_email),
                    onBack = ::finish,
                    onDarkChange = { checked ->
                        App.setNightMode(this, if (checked) AppCompatDelegate.MODE_NIGHT_YES
                        else AppCompatDelegate.MODE_NIGHT_NO)
                    },
                    onPick = ::pickColor,
                    onSetColors = ::setColors,
                    onContact = ::contact,
                )
            }
        }
    }

    /** Opens the creation window's color picker for the main (0), middle (1) or second (2) color. */
    private fun pickColor(which: Int, main: Int, mid: Int, second: Int) {
        if (!App.canChangeAppColor()) {
            Toast.makeText(this, R.string.app_color_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        when (which) {
            0 -> ColorPickerSheet.show(this, R.string.app_color_main, main) { setColors(it, mid, second) }
            1 -> ColorPickerSheet.show(this, R.string.app_color_mid, mid) { setColors(main, it, second) }
            else -> ColorPickerSheet.show(this, R.string.app_color_second, second) { setColors(main, mid, it) }
        }
    }

    private fun setColors(main: Int?, mid: Int?, second: Int?) {
        App.setAppColors(this, main, mid, second)
        recreate()
    }

    private fun contact() {
        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + getString(R.string.support_email)))
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
        try {
            startActivity(mail)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.support_email), Toast.LENGTH_LONG).show()
        }
    }
}

/** A one-tap color set; a null [mid] is an even blend of the other two. */
private class Preset(val main: Int, val mid: Int?, val second: Int, val name: Int) {
    val colors get() = intArrayOf(main, mid ?: ColorUtils.blendARGB(main, second, 0.5f), second)
}

/** The first is the built-in classic purple, pink and gold. */
private val PRESETS = listOf(
    Preset(App.CLASSIC_MAIN, App.CLASSIC_MID, App.CLASSIC_SECOND, R.string.color_classic),
    Preset(0xFF00A88F.toInt(), null, 0xFF2E8FD6.toInt(), R.string.color_teal),
    Preset(0xFFE0457B.toInt(), null, 0xFFFF8A3D.toInt(), R.string.color_pink),
    Preset(0xFF7B5CD6.toInt(), null, 0xFFE0457B.toInt(), R.string.color_purple),
    Preset(0xFFF07A1A.toInt(), null, 0xFFE0457B.toInt(), R.string.color_orange),
    Preset(0xFF3E9B3A.toInt(), null, 0xFF00A88F.toInt(), R.string.color_green),
    Preset(0xFFD93A3A.toInt(), null, 0xFF8E44FF.toInt(), R.string.color_red),
    Preset(0xFFB8860B.toInt(), 0xFFE6B422.toInt(), 0xFFF5D27A.toInt(), R.string.color_gold),
)


@Composable
private fun SettingsScreen(
    dark: Boolean,
    colors: IntArray?,
    version: String,
    supportEmail: String,
    onBack: () -> Unit,
    onDarkChange: (Boolean) -> Unit,
    onPick: (which: Int, main: Int, mid: Int, second: Int) -> Unit,
    onSetColors: (Int?, Int?, Int?) -> Unit,
    onContact: () -> Unit,
) {
    val main = colors?.get(0) ?: App.CLASSIC_MAIN
    val mid = colors?.get(1) ?: App.CLASSIC_MID
    val second = colors?.get(2) ?: App.CLASSIC_SECOND
    var checked by remember { mutableStateOf(dark) }
    var sheet by remember { mutableStateOf<Int?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        AkhuTopBar(stringResource(R.string.settings), onBack)

        GroupLabel(stringResource(R.string.settings_appearance))
        SettingRow(
            stringResource(R.string.dark_mode), stringResource(R.string.dark_mode_sub),
            onClick = { checked = !checked; onDarkChange(checked) },
        ) {
            Switch(checked = checked, onCheckedChange = { checked = it; onDarkChange(it) })
        }

        // App colors: the main color rebuilds the app's M3 color set; main, middle and second blend
        // in the banner gradient and accents. Each swatch opens the creation window's color picker.
        SettingRow(stringResource(R.string.app_color), stringResource(R.string.app_color_sub)) {
            Swatch(intArrayOf(main), false, stringResource(R.string.app_color_main), 32) { onPick(0, main, mid, second) }
            Spacer(Modifier.width(Space.s))
            Swatch(intArrayOf(mid), false, stringResource(R.string.app_color_mid), 32) { onPick(1, main, mid, second) }
            Spacer(Modifier.width(Space.s))
            Swatch(intArrayOf(second), false, stringResource(R.string.app_color_second), 32) { onPick(2, main, mid, second) }
        }

        // One-tap color sets, scrolling sideways; the current one gets a thick ring. Classic
        // clears the custom colors.
        if (App.canChangeAppColor()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Space.screen, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PRESETS.forEachIndexed { i, p ->
                    val classic = i == 0
                    val selected = if (classic) colors == null else colors?.contentEquals(p.colors) == true
                    Swatch(p.colors, selected, stringResource(p.name), 40) {
                        if (classic) onSetColors(null, null, null) else onSetColors(p.main, p.mid, p.second)
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(top = Space.l), color = MaterialTheme.colorScheme.outlineVariant)
        GroupLabel(stringResource(R.string.settings_about))
        SettingRow(stringResource(R.string.privacy_policy), onClick = { sheet = R.string.privacy_text }) { Chevron() }
        SettingRow(stringResource(R.string.licenses), onClick = { sheet = R.string.licenses_text }) { Chevron() }
        if (supportEmail.isNotEmpty()) {
            SettingRow(stringResource(R.string.contact_us), supportEmail, onClick = onContact) { Chevron() }
        }
        SettingRow(stringResource(R.string.version), version)
        Spacer(Modifier.height(Space.xl))
    }

    sheet?.let { text ->
        AlertDialog(
            onDismissRequest = { sheet = null },
            title = {
                Text(stringResource(if (text == R.string.privacy_text) R.string.privacy_policy else R.string.licenses))
            },
            text = {
                Text(
                    stringResource(text), Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { sheet = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

/** One Settings row: title, optional grey line under it, and [trailing] controls at the end. */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.screen, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Space.l)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle, Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun Chevron() {
    Icon(
        painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Round swatch of one color, or a main-middle-second gradient for a preset. 48dp tap area. */
@Composable
private fun Swatch(colors: IntArray, selected: Boolean, label: String, sizeDp: Int, onClick: () -> Unit) {
    val fill = if (colors.size == 1) SolidColor(Color(colors[0])) else Brush.linearGradient(colors.map { Color(it) })
    Box(
        Modifier
            .size(maxOf(48, sizeDp).dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(sizeDp.dp)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape
                )
                .padding(if (selected) 4.dp else 1.dp)
                .background(fill, CircleShape)
        )
    }
}
