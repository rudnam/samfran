package com.rudnam.samfran.feature.shifts

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.testing.Samples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth

/** The Shifts pages at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ShiftsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 16)

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { SamFranTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shifts-$name.png")
    }

    private val actions = object : ShiftsActions {
        override fun showMonth(month: YearMonth) {}
        override fun setList(list: Boolean) {}
        override fun pick(date: LocalDate) {}
        override fun readPhoto(uri: Uri) {}
        override fun refresh() {}
    }
    private val editor = object : ShiftEditorActions {
        override fun setCode(date: LocalDate, code: String) {}
        override fun focus(date: LocalDate?) {}
        override fun chip(code: String) {}
        override fun save() {}
    }
    private val settings = object : ShiftSettingsActions {
        override fun setPerson(name: String) {}
        override fun setOnSheet(name: String) {}
        override fun setLength(index: Int, letter: String, hours: String) {}
        override fun addLetter() {}
        override fun removeLetter(index: Int) {}
        override fun setClosing(index: Int, day: Int) {}
        override fun addClosing() {}
        override fun removeClosing(index: Int) {}
        override fun save() {}
        override fun feed(on: Boolean) {}
    }

    private val month = ShiftsUiState(
        data = Samples.november,
        next = Samples.november.days.first { it.date == today },
        picked = today,
        today = today,
    )

    @Test
    fun calendar() = shot("calendar") { ShiftsScreen(month, actions, {}, {}, {}, {}) }

    @Test
    fun calendarDark() = shot("calendar-dark", dark = true) { ShiftsScreen(month, actions, {}, {}, {}, {}) }

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun calendarJapanese() = shot("calendar-ja") { ShiftsScreen(month, actions, {}, {}, {}, {}) }

    /** Sam's phone (1080x2400, 420dpi, default text size), where the squares' kanji used to be cut off at the bottom. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun calendarSamsPhone() = shot("calendar-sams-phone") { ShiftsScreen(month, actions, {}, {}, {}, {}) }

    @Test
    fun list() = shot("list") { ShiftsScreen(month.copy(asList = true), actions, {}, {}, {}, {}) }

    @Test
    fun reading() = shot("reading") { ShiftsScreen(month.copy(reading = true), actions, {}, {}, {}, {}) }

    @Test
    fun emptyMonth() = shot("empty") {
        ShiftsScreen(ShiftsUiState(data = Samples.november.copy(month = YearMonth.of(2027, 1), days = emptyList()), today = today), actions, {}, {}, {}, {})
    }

    private val read = Samples.november.copy(nameAsRead = "オトル サマンタ", notes = "Day 25 was hard to read: 13Z or 13F?")

    @Test
    fun checkingAPhoto() = shot("check") {
        ShiftEditorScreen(
            ShiftEditorUiState(read.month, base = read, codes = read.days.associate { it.date to it.code }, focused = LocalDate.of(2026, 11, 3)),
            editor, {},
        )
    }

    @Test
    fun checkingAPhotoDark() = shot("check-dark", dark = true) {
        ShiftEditorScreen(ShiftEditorUiState(read.month, base = read, codes = read.days.associate { it.date to it.code }), editor, {})
    }

    private val settingsState = ShiftSettingsUiState(
        settings = Samples.settings.copy(feedPath = "/calendar/abc123.ics"),
        person = "Sam",
        onSheet = "オトル サマンタ",
        lengths = listOf("D" to "8", "F" to "9"),
        closing = listOf(15, 31),
        feedUrl = "https://samfran.rudnam.com/calendar/abc123.ics",
    )

    @Test
    fun shiftSettings() = shot("settings") { ShiftSettingsScreen(settingsState, settings, {}, {}, {}) }

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun shiftSettingsJapanese() = shot("settings-ja") { ShiftSettingsScreen(settingsState, settings, {}, {}, {}) }
}
