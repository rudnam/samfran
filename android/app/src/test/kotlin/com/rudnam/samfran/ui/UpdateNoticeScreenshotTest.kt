package com.rudnam.samfran.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.AppUpdate
import com.rudnam.samfran.core.model.ChangelogEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The update banner with changelog items, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdateNoticeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val sampleUpdate = AppUpdate(
        versionCode = 42,
        name = "2.0.42",
        url = "https://example.com/download/samfran.apk",
        changelog = listOf(
            ChangelogEntry(
                versionCode = 42,
                versionName = "2.0.42",
                en = listOf("Show what's new when an update is ready", "Faster ride alarm start"),
                ja = listOf("アップデート時に変更内容を表示するようにしました", "乗り過ごしアラームの起動を高速化しました"),
            ),
            ChangelogEntry(
                versionCode = 41,
                versionName = "2.0.41",
                en = listOf("Keep shift sheet photo sharper"),
                ja = listOf("シフト表の写真の画質を改善しました"),
            ),
        ),
    )

    private fun shot(name: String, update: AppUpdate, dark: Boolean = false, expand: Boolean = false) {
        compose.setContent {
            SamFranTheme(darkTheme = dark) {
                Box(Modifier.padding(16.dp)) {
                    UpdateNotice(update = update, onLater = {}, onUpdate = {})
                }
            }
        }
        if (expand) {
            compose.onNodeWithText("Show all").performClick()
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/update-notice-$name.png")
    }

    @Test
    fun light() = shot("light", sampleUpdate)

    @Test
    fun dark() = shot("dark", sampleUpdate, dark = true)

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun japanese() = shot("ja", sampleUpdate)

    @Test
    fun expandedLongList() = shot(
        "expanded",
        sampleUpdate.copy(
            changelog = sampleUpdate.changelog + ChangelogEntry(
                versionCode = 40,
                versionName = "2.0.40",
                en = listOf("Better place search", "Smoother dark mode transitions"),
            ),
        ),
        expand = true,
    )
}
