package com.rudnam.samfran.feature.shifts

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
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
        override fun editWith(date: LocalDate?) {}
        override fun setWith(date: LocalDate, text: String) {}
        override fun toggleName(name: String) {}
        override fun setClosing(index: Int, day: Int) {}
        override fun addClosing() {}
        override fun removeClosing(index: Int) {}
        override fun usualClosing() {}
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

    /** This month's own 締め申請通知 days, at the bottom of the editor. */
    @Test
    fun closingDaysForTheMonth() {
        compose.setContent {
            SamFranTheme {
                ShiftEditorScreen(
                    ShiftEditorUiState(read.month, base = read.copy(nameAsRead = null), codes = read.days.associate { it.date to it.code }, closing = listOf(20, 30), closingChanged = true),
                    editor, {},
                )
            }
        }
        compose.onNodeWithText("+ Day").performScrollTo()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shifts-edit-closing.png")
    }

    @Test
    fun checkingAPhotoDark() = shot("check-dark", dark = true) {
        ShiftEditorScreen(ShiftEditorUiState(read.month, base = read, codes = read.days.associate { it.date to it.code }), editor, {})
    }

    /** A day's names open to edit, with the month's other names as chips. */
    @Test
    fun editingWhoIsWith() = shot("check-names") {
        ShiftEditorScreen(
            ShiftEditorUiState(
                read.month, base = read, codes = read.days.associate { it.date to it.code },
                focused = LocalDate.of(2026, 11, 2), editingWith = LocalDate.of(2026, 11, 2),
                typed = mapOf(LocalDate.of(2026, 11, 2) to "タナカ ユイ, サトウ"),
            ),
            editor, {},
        )
    }

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun editingWhoIsWithJapanese() = shot("check-names-ja") {
        ShiftEditorScreen(
            ShiftEditorUiState(
                read.month, base = read, codes = read.days.associate { it.date to it.code },
                focused = LocalDate.of(2026, 11, 2), editingWith = LocalDate.of(2026, 11, 2),
            ),
            editor, {},
        )
    }

    /** A busy month: several people on a shift, shown and open to edit. */
    private val crowded = read.copy(
        days = read.days.map {
            when (it.date.dayOfMonth) {
                2 -> it.copy(with = listOf("タナカ ユイ", "スズキ ケン", "ヤマモト アオイ", "サトウ ハナ", "ナカムラ ショウタロウ"))
                3 -> it.copy(with = listOf("スズキ ケン", "ヤマモト アオイ", "イトウ メグミ", "コバヤシ ダイスケ"))
                else -> it
            }
        },
    )

    @Test
    fun severalCoworkers() = shot("check-many") {
        ShiftEditorScreen(ShiftEditorUiState(crowded.month, base = crowded, codes = crowded.days.associate { it.date to it.code }), editor, {})
    }

    @Test
    fun editingSeveralCoworkers() = shot("check-many-names") {
        ShiftEditorScreen(
            ShiftEditorUiState(
                crowded.month, base = crowded, codes = crowded.days.associate { it.date to it.code },
                focused = LocalDate.of(2026, 11, 2), editingWith = LocalDate.of(2026, 11, 2),
            ),
            editor, {},
        )
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
