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
    fun `a month's own closing days are saved with its shifts, and only once changed`() = runTest {
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        assertEquals(listOf(15, 30), vm.state.value.closing) // the usual 15th and last day, in November
        assertTrue(vm.state.value.closingIsUsual)
        vm.save()
        assertEquals(null, shifts.savedClosing) // untouched: left as it is

        val again = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        again.setClosing(0, 20)
        again.addClosing()
        assertEquals(listOf(1, 20, 30), again.state.value.closing)
        assertTrue(!again.state.value.closingIsUsual)
        again.removeClosing(0)
        again.save()
        assertEquals(listOf(20, 30), shifts.savedClosing)

        val back = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        assertEquals(listOf(20, 30), back.state.value.closing)
        back.usualClosing()
        back.save()
        assertEquals(listOf(15, 30), shifts.savedClosing)
    }

    @Test
    fun `a corrected code changes who's there, and nobody is lost`() = runTest {
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        val day = LocalDate.of(2026, 11, 4) // 13F: 13:00–22:00
        fun there() = vm.state.value.there(day).map { it.name }
        assertEquals(listOf("タナカ ユイ", "ヤマダ ハナ", "サトウ ミオ"), there())
        vm.setCode(day, "7F") // 7:00–16:00: the one on 5D is there now, and the night before's 22F still isn't
        assertEquals(listOf("タナカ ユイ", "ヤマダ ハナ", "コバヤシ リク"), there())
        vm.setCode(day, "13F")
        assertEquals(listOf("タナカ ユイ", "ヤマダ ハナ", "サトウ ミオ"), there())
        vm.setCode(day, "7F")
        vm.save()
        // Everyone is saved, there or not: the server works out who's shown.
        assertEquals(5, shifts.savedPeople?.get(day)?.size)
        assertEquals(emptyMap<String, String>(), shifts.savedCorrections) // not checking a scan
    }

    @Test
    fun `anyone's name or code can be fixed, someone added, and someone taken off`() = runTest {
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11")), shifts, reminders)
        val day = LocalDate.of(2026, 11, 4)
        vm.openPeople(day)
        assertEquals(day, vm.state.value.openDay)
        val rows = vm.state.value.people.getValue(day)
        assertEquals(3, vm.state.value.shown.size) // those there, listed first
        vm.setPersonName(rows[1].key, "ヤマダ ハル") // someone on another shift
        vm.setPersonCode(rows[3].key, "１３ｆ") // the one on 5D, now there
        vm.removePerson(rows[4].key)
        vm.addPerson()
        val added = vm.state.value.people.getValue(day).last()
        assertEquals("13F", added.code) // on the day's own code
        assertTrue(added.key in vm.state.value.shown)
        vm.setPersonName(added.key, "サトウ ハナ")
        assertEquals(listOf("タナカ ユイ", "ヤマダ ハル", "サトウ ミオ", "コバヤシ リク", "サトウ ハナ"), vm.state.value.there(day).map { it.name })
        vm.openPeople(null)
        vm.save()
        val saved = shifts.savedPeople?.get(day).orEmpty()
        assertEquals(listOf("タナカ ユイ", "ヤマダ ハル", "サトウ ミオ", "コバヤシ リク", "サトウ ハナ"), saved.map { it.name })
        assertEquals("13F", saved[3].code)
    }

    @Test
    fun `names fixed while checking a scan are sent as corrections`() = runTest {
        shifts.read = Samples.november.copy(nameAsRead = "オトル サマンタ")
        shifts.readPhoto(ByteArray(1))
        val vm = ShiftEditorViewModel(SavedStateHandle(ShiftEditRoute("2026-11", fromPhoto = true)), shifts, reminders)
        val day = LocalDate.of(2026, 11, 4) // read as タナカ ユイ and ヤマダ ハナ
        vm.openPeople(day)
        val rows = vm.state.value.people.getValue(day)
        vm.setPersonName(rows[0].key, "タナカ ユエ")
        vm.setPersonName(rows[1].key, "ヤマダ ハル")
        vm.save()
        assertEquals(mapOf("タナカ ユイ" to "タナカ ユエ", "ヤマダ ハナ" to "ヤマダ ハル"), shifts.savedCorrections)
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
