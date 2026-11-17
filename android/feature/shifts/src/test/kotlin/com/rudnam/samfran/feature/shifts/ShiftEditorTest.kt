package com.rudnam.samfran.feature.shifts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.testing.FakeReminders
import com.rudnam.samfran.core.testing.FakeShiftsRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.feature.shifts.navigation.ShiftEditRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Editing who's with you, by tapping and typing as on the phone ("Edit by hand" on a saved month). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ShiftEditorTest {
    @get:Rule(order = 0)
    val main = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val shifts = FakeShiftsRepository()

    @Test
    fun `tapping who's with you opens a box that takes typing and the name chips`() {
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, FakeReminders())
        compose.setContent {
            val state by vm.state.collectAsState()
            SamFranTheme { ShiftEditorScreen(state, vm, onBack = {}) }
        }
        val day = LocalDate.of(2026, 11, 2) // 7F, the first working day with no names yet
        compose.onAllNodesWithText("Who’s with you?").onFirst().performClick()
        assertEquals(day, vm.state.value.editingWith)

        val box = compose.onNode(hasSetTextAction() and hasContentDescription("Who’s with you on", substring = true))
        box.performTextInput("サトウ ハナ")
        compose.onAllNodesWithText("タナカ ユイ").onFirst().performClick() // the chip: this month's most frequent name
        compose.waitForIdle()

        box.assert(hasText("サトウ ハナ, タナカ ユイ"))
        assertEquals(listOf("サトウ ハナ", "タナカ ユイ"), vm.state.value.withFor(day))

        box.performTextInput(", スズキ")
        compose.waitForIdle()
        assertEquals(listOf("サトウ ハナ", "タナカ ユイ", "スズキ"), vm.state.value.withFor(day))
    }
}
