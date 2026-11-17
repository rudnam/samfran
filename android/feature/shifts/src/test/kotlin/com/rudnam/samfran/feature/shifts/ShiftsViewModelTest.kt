package com.rudnam.samfran.feature.shifts

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.samfran.core.testing.FakeReminders
import com.rudnam.samfran.core.testing.FakeShiftsRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.core.testing.Samples
import com.rudnam.samfran.feature.shifts.navigation.ShiftEditRoute
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
class ShiftsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val shifts = FakeShiftsRepository()
    private val reminders = FakeReminders()

    @Test
    fun `the editor starts from the saved month, and a chip fills the day and moves on`() = runTest {
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        val s = vm.state.value
        assertEquals(30, s.dates.size)
        assertEquals("7F", s.codes[LocalDate.of(2026, 11, 2)])
        vm.focus(LocalDate.of(2026, 11, 29))
        vm.chip("普")
        vm.chip("13F")
        assertEquals("普", vm.state.value.codes[LocalDate.of(2026, 11, 29)])
        assertEquals("13F", vm.state.value.codes[LocalDate.of(2026, 11, 30)])
        assertEquals(LocalDate.of(2026, 11, 30), vm.state.value.focused) // the last day stays
        assertEquals("Late", vm.state.value.meaning(LocalDate.of(2026, 11, 30))?.label)
    }

    @Test
    fun `saving normalizes the codes and brings the phone's reminders up to date`() = runTest {
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        vm.setCode(LocalDate.of(2026, 11, 1), "１３ｆ")
        vm.save()
        assertTrue(vm.state.value.saved)
        assertEquals("13F", shifts.saved?.get(LocalDate.of(2026, 11, 1)))
        assertEquals(1, reminders.syncs)
    }

    @Test
    fun `who's on the same shift is kept only while the day's code is unchanged`() = runTest {
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        assertEquals(listOf("タナカ ユイ"), vm.state.value.withFor(LocalDate.of(2026, 11, 4)))
        vm.setCode(LocalDate.of(2026, 11, 5), "7F") // was 13F: a different shift
        assertEquals(emptyList<String>(), vm.state.value.withFor(LocalDate.of(2026, 11, 5)))
        vm.save()
        assertEquals(listOf("タナカ ユイ"), shifts.savedWith?.get(LocalDate.of(2026, 11, 4)))
        assertEquals(null, shifts.savedWith?.get(LocalDate.of(2026, 11, 5)))
    }

    @Test
    fun `names typed for a day are saved, and stay when the code changes`() = runTest {
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        val day = LocalDate.of(2026, 11, 2)
        vm.editWith(day)
        assertEquals(day, vm.state.value.editingWith)
        vm.setWith(day, "サトウ ハナ、 タナカ ユイ ,")
        vm.setCode(day, "9F")
        assertEquals(listOf("サトウ ハナ", "タナカ ユイ"), vm.state.value.withFor(day))
        vm.focus(LocalDate.of(2026, 11, 3)) // a code box: the names close
        assertEquals(null, vm.state.value.editingWith)
        vm.save()
        assertEquals(listOf("サトウ ハナ", "タナカ ユイ"), shifts.savedWith?.get(day))
        assertEquals(emptyMap<String, String>(), shifts.savedCorrections) // not checking a scan
    }

    @Test
    fun `a name's chip adds it to the line, and takes it off again`() = runTest {
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        assertEquals("タナカ ユイ", vm.state.value.namesBesides(LocalDate.of(2026, 11, 2)).first()) // the most frequent this month
        assertEquals("スズキ ケン, タナカ ユイ", toggleName(toggleName("", "スズキ ケン"), "タナカ ユイ"))
        assertEquals("タナカ ユイ", toggleName("スズキ ケン、 タナカ ユイ", "スズキ ケン"))
    }

    @Test
    fun `names fixed while checking a scan are sent as corrections`() = runTest {
        shifts.read = Samples.november.copy(nameAsRead = "オトル サマンタ")
        shifts.readPhoto(ByteArray(1))
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11", fromPhoto = true)), shifts, reminders)
        val day = LocalDate.of(2026, 11, 4) // read as タナカ ユイ
        vm.editWith(day)
        vm.setWith(day, "タナカ ユエ")
        vm.save()
        assertEquals(mapOf("タナカ ユイ" to "タナカ ユエ"), shifts.savedCorrections)
    }

    @Test
    fun `a photo's reading opens in the editor to be checked`() = runTest {
        shifts.read = Samples.november.copy(month = YearMonth.of(2026, 12), nameAsRead = "オトル サマンタ")
        shifts.readPhoto(ByteArray(1))
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-12", fromPhoto = true)), shifts, reminders)
        assertTrue(vm.state.value.fromPhoto)
        assertEquals(YearMonth.of(2026, 12), vm.state.value.base?.month)
    }

    @Test
    fun `settings save the letters' lengths, and the calendar link turns on`() = runTest {
        val vm = ShiftSettingsViewModel(shifts)
        assertEquals(listOf("D" to "8", "F" to "9"), vm.state.value.lengths)
        vm.addLetter()
        vm.setLength(2, "z", "7.5")
        vm.save()
        assertEquals(7.5, shifts.settingsFlow.value?.lengths?.get("Z"))
        assertEquals(SettingsDone.Saved, vm.state.value.done)
        vm.feed(true)
        assertEquals("https://samfran.example/calendar/abc123.ics", vm.state.value.feedUrl)
    }
}
