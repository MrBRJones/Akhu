package com.MrBRos.akhu

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings screen opened from the gear at the top right: dark mode and app colors. Drawn with Compose. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Before the switch is first used the app follows the phone, so show whatever is on screen now.
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val colors = App.appColors(this)
        setContent {
            AkhuTheme {
                SettingsScreen(
                    dark = dark,
                    colors = colors,
                    onDarkChange = { checked ->
                        App.setNightMode(this, if (checked) AppCompatDelegate.MODE_NIGHT_YES
                        else AppCompatDelegate.MODE_NIGHT_NO)
                    },
                    onPick = ::pickColor,
                    onSetColors = ::setColors,
                )
            }
        }
    }

    /** Opens the creation window's color picker for the main (0) or second (1) color. */
    private fun pickColor(which: Int, main: Int, second: Int) {
        if (!App.canChangeAppColor()) {
            Toast.makeText(this, R.string.app_color_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        if (which == 0) {
            ColorPickerSheet.show(this, R.string.app_color_main, main) { setColors(it, second) }
        } else {
            ColorPickerSheet.show(this, R.string.app_color_second, second) { setColors(main, it) }
        }
    }

    private fun setColors(main: Int?, second: Int?) {
        App.setAppColors(this, main, second)
        recreate()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

/** {main, second} for each preset; the first is the built-in classic blue. */
private val PRESETS = listOf(
    App.CLASSIC_MAIN to App.CLASSIC_SECOND,
    0xFF00A88F.toInt() to 0xFF2E8FD6.toInt(),
    0xFFE0457B.toInt() to 0xFFFF8A3D.toInt(),
    0xFF7B5CD6.toInt() to 0xFFE0457B.toInt(),
    0xFFF07A1A.toInt() to 0xFFE0457B.toInt(),
    0xFF3E9B3A.toInt() to 0xFF00A88F.toInt(),
    0xFFD93A3A.toInt() to 0xFF8E44FF.toInt(),
)
private val PRESET_NAMES = listOf(
    R.string.color_classic, R.string.color_teal, R.string.color_pink, R.string.color_purple,
    R.string.color_orange, R.string.color_green, R.string.color_red,
)

@Composable
private fun SettingsScreen(
    dark: Boolean,
    colors: IntArray?,
    onDarkChange: (Boolean) -> Unit,
    onPick: (which: Int, main: Int, second: Int) -> Unit,
    onSetColors: (Int?, Int?) -> Unit,
) {
    val main = colors?.get(0) ?: App.CLASSIC_MAIN
    val second = colors?.get(1) ?: App.CLASSIC_SECOND
    var checked by remember { mutableStateOf(dark) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            RowLabel(stringResource(R.string.dark_mode), Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = { checked = it; onDarkChange(it) })
        }

        // App colors: the main color rebuilds the app's M3 color set, the second joins it in the
        // banner gradient and accents. Each swatch opens the creation window's color picker.
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable { onPick(0, main, second) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RowLabel(stringResource(R.string.app_color), Modifier.weight(1f))
            if (colors != null) {
                TextButton(onClick = { onSetColors(null, null) }) { Text(stringResource(R.string.reset)) }
                Spacer(Modifier.width(8.dp))
            }
            Swatch(main, null, false, stringResource(R.string.app_color_main), 32) { onPick(0, main, second) }
            Spacer(Modifier.width(12.dp))
            Swatch(second, null, false, stringResource(R.string.app_color_second), 32) { onPick(1, main, second) }
        }

        // One-tap color pairs; the current one gets a thick ring. Classic blue clears the custom colors.
        if (App.canChangeAppColor()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PRESETS.forEachIndexed { i, (a, b) ->
                    val classic = i == 0
                    val selected = if (classic) colors == null else colors?.contentEquals(intArrayOf(a, b)) == true
                    Swatch(a, b, selected, stringResource(PRESET_NAMES[i]), 36) {
                        if (classic) onSetColors(null, null) else onSetColors(a, b)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowLabel(text: String, modifier: Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
}

/** Round swatch of one color, or a main-to-second gradient for a preset pair. */
@Composable
private fun Swatch(color: Int, second: Int?, selected: Boolean, label: String, sizeDp: Int, onClick: () -> Unit) {
    val fill = if (second == null) Brush.linearGradient(listOf(Color(color), Color(color)))
    else Brush.linearGradient(listOf(Color(color), Color(second)))
    Box(
        Modifier
            .size(sizeDp.dp)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                CircleShape
            )
            .padding(if (selected) 3.dp else 1.dp)
            .background(fill, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
    )
}
