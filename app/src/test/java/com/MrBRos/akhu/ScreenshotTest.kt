package com.MrBRos.akhu

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper

/**
 * Screenshot diffs of the main tabs on a phone-sized screen, light and dark.
 * `gradlew recordRoborazziDebug` saves the reference images under app/src/test/screenshots;
 * `gradlew verifyRoborazziDebug` fails when a screen changes and writes a *_compare.png diff.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private val tabs = listOf(
        "wallpapers" to R.id.nav_wallpapers,
        "following" to R.id.nav_following,
        "create" to R.id.nav_create,
        "inbox" to R.id.nav_inbox,
        "profile" to R.id.nav_profile,
    )

    @Test
    fun tabsLight() = captureTabs("light")

    @Test
    @Config(qualifiers = "+night")
    fun tabsDark() = captureTabs("dark")

    private fun captureTabs(theme: String) {
        // Real-phone frame rate, so screens that animate forever still let the clock move on.
        ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((name, id) in tabs) {
                scenario.onActivity { it.selectTab(id) }
                // Let the tab switch animation and any posted work finish.
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
                scenario.onActivity {
                    it.window.decorView.captureRoboImage("src/test/screenshots/${theme}_$name.png")
                }
            }
        }
    }
}
