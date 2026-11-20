package com.rudnam.samfran.feature.bus

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.BusStops
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.StopKind
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.TripStatus
import com.rudnam.samfran.core.testing.FakeBusAlarm
import com.rudnam.samfran.core.testing.FakeBusRepository
import com.rudnam.samfran.core.testing.FakeFoodRepository
import com.rudnam.samfran.core.testing.FakeReminders
import com.rudnam.samfran.core.testing.FakeShiftsRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.core.testing.Samples
import com.rudnam.samfran.feature.bus.navigation.BusRoute
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The Bus tab at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class BusScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { SamFranTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/bus-$name.png")
    }

    private val actions = object : BusActions {
        override fun startTrip() {}
        override fun locationDenied() {}
        override fun stopTrip() {}
        override fun stopBuzz() {}
        override fun practice() {}
        override fun endPractice() {}
        override fun openSettings() {}
        override fun back() {}
        override fun setSound(on: Boolean) {}
        override fun hearSound() {}
        override fun pickStop(id: Long) {}
        override fun endChoosing() {}
        override fun edit(id: Long) {}
        override fun add() {}
        override fun openWhere() {}
        override fun openSheet(sheet: StopSheet) {}
        override fun closeSheet() {}
        override fun setMeters(meters: Int) {}
        override fun setBuzz(buzz: Buzz) {}
        override fun setShifts(kinds: List<String>) {}
        override fun toggleAsk() {}
        override fun addStop() {}
        override fun removeStop() {}
        override fun setQuery(query: String) {}
        override fun pick(stop: FoundStop) {}
        override fun setOtherWays(open: Boolean) {}
        override fun setLink(link: String) {}
        override fun useLink() {}
        override fun useHere() {}
        override fun setPlaceName(name: String) {}
        override fun setPlaceKind(kind: StopKind) {}
        override fun usePlace() {}
    }

    /** One stop, as before there could be several. */
    private val ready = BusUiState(stops = Samples.oneStop)

    /** Three stops, and a late shift today: its stop is picked. */
    private val several = BusUiState(stops = Samples.stops, shift = Samples.day("2026-11-17", "13F"))

    private val none = BusUiState(stops = BusStops())

    /** A new stop, picked from the search: its page, before "Add stop". */
    private val draft = StopDraft("新林", 35.0102, 137.0581, StopKind.Bus)

    // The map is Google's page in a web view, which doesn't draw on the JVM: a stand-in of its size.
    private fun screen(state: BusUiState): @Composable () -> Unit = {
        BusScreen(state, actions, {}, {}, {}, {}, {}, map = { _, _, _, h ->
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxWidth().height(h).background(SamFranTheme.colors.sunken),
            )
        })
    }

    @Test
    fun ready() = shot("ready", content = screen(ready))

    @Test
    fun readyDark() = shot("ready-dark", dark = true, content = screen(several))

    @Test
    fun severalStops() = shot("stops", content = screen(several))

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun severalStopsJapanese() = shot("stops-ja", content = screen(several))

    @Test
    fun pickedForTheRide() = shot("stops-picked", content = screen(several.copy(picked = 3)))

    // From "Other stop" on the question at the end of a shift.
    @Test
    fun choosingFromTheQuestion() = shot("stops-choose", content = screen(several.copy(choosing = true, picked = 2)))

    // Settings: the stops (one just added), and the sound.
    @Test
    fun settings() = shot("settings", content = screen(several.copy(settings = true, added = 3)))

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun settingsJapanese() = shot("settings-ja", content = screen(several.copy(settings = true, stops = Samples.stops.copy(soundAfter = 0))))

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun readyJapanese() = shot("ready-ja", content = screen(ready))

    // To a station: "I'm on the train", and a train on the line.
    @Test
    fun readyForTheTrain() = shot("train", content = screen(several.copy(picked = 2)))

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun readyForTheTrainJapanese() = shot("train-ja", content = screen(several.copy(picked = 2)))

    @Test
    fun onTheBus() = shot("trip", content = screen(ready.copy(trip = TripStatus(running = true, distance = 2340f), from = 4200f)))

    @Test
    fun onTheTrain() = shot("trip-train", content = screen(several.copy(picked = 2, trip = TripStatus(running = true, distance = 5200f), from = 9000f)))

    @Test
    fun buzzing() = shot("buzzing", content = screen(ready.copy(trip = TripStatus(running = true, distance = 640f, buzzing = true))))

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun buzzingJapanese() = shot("buzzing-ja", content = screen(ready.copy(trip = TripStatus(running = true, distance = 640f, buzzing = true))))

    @Test
    fun practiceRide() = shot("practice", content = screen(ready.copy(practice = Practice.Riding(0.4f))))

    @Test
    fun practiceBuzz() = shot("practice-buzz", content = screen(ready.copy(practice = Practice.Buzzing)))

    @Test
    fun practiceDone() = shot("practice-done", content = screen(ready.copy(practice = Practice.Done)))

    // The first time: how it works, and where.
    @Test
    fun firstVisit() = shot("setup", content = screen(none))

    @Test
    fun searching() = shot("search", content = screen(
        several.copy(settings = true, editing = BusUiState.NEW, search = WhereSearch(query = "静岡", found = Samples.found)),
    ))

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun otherWays() = shot("other-ways", content = screen(
        none.copy(search = WhereSearch(otherWays = true, link = "https://maps.app.goo.gl/x", place = Picked("上島", 34.71, 137.72, StopKind.Train))),
    ))

    // A saved stop's page: a row for each setting.
    @Test
    fun stopPage() = shot("stop", content = screen(several.copy(settings = true, editing = 2)))

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun stopPageJapanese() = shot("stop-ja", content = screen(several.copy(settings = true, editing = 1)))

    // A new one, picked from the search: "Add stop".
    @Test
    fun newStop() = shot("new", content = screen(several.copy(settings = true, editing = BusUiState.NEW, draft = draft)))

    // Each row opens its choices from the bottom.
    @Test
    fun distanceSheet() = shot("sheet-distance", content = screen(several.copy(settings = true, editing = 2, sheet = StopSheet.Distance)))

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun buzzSheetJapanese() = shot("sheet-buzz-ja", content = screen(several.copy(settings = true, editing = 2, sheet = StopSheet.Buzz)))

    @Test
    fun shiftsSheet() = shot("sheet-shifts", content = screen(several.copy(settings = true, editing = 1, sheet = StopSheet.Shifts)))

    // Asking, with notifications off for the app.
    @Test
    fun askBlocked() = shot("ask-blocked", content = screen(several.copy(settings = true, editing = BusUiState.NEW, draft = draft.copy(ask = true), askBlocked = true)))
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BusViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val bus = FakeBusRepository()
    private val alarm = FakeBusAlarm()
    private val reminders = FakeReminders()
    private val shifts = FakeShiftsRepository()
    // Long before the sample shifts: none is ending.
    private val clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun vm(route: BusRoute = BusRoute()) =
        BusViewModel(SavedStateHandle(route), bus, FakeFoodRepository(), alarm, reminders, shifts, clock)

    @Test
    fun `a trip starts and stops, and is the one used last`() = runTest {
        bus.stopsFlow.value = Samples.stops.copy(lastId = 3)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.state.value.ready)
        // No shift: the one used last.
        assertEquals("実家", vm.state.value.stop?.name)
        vm.startTrip()
        assertTrue(vm.state.value.trip.running)
        assertEquals(listOf(3L), bus.used)
        vm.stopTrip()
        assertFalse(vm.state.value.trip.running)
        // The sound: on (after a minute) to start with; switched off, and heard.
        assertEquals(60, vm.state.value.stops?.soundAfter)
        vm.setSound(false)
        assertEquals(0, bus.stopsFlow.value?.soundAfter)
        vm.setSound(true)
        assertEquals(60, bus.stopsFlow.value?.soundAfter)
        vm.hearSound()
        assertEquals(1, alarm.heard)
    }

    @Test
    fun `the shift picks the stop, and another can be picked for the ride`() = runTest {
        shifts.home = Samples.day("2026-11-17", "7F") // early
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals("静岡駅", vm.state.value.stop?.name)
        assertTrue(vm.state.value.byShift)
        vm.pickStop(3)
        assertEquals("実家", vm.state.value.stop?.name)
        assertFalse(vm.state.value.byShift)
        vm.startTrip()
        assertEquals(listOf(3L), bus.used)
    }

    @Test
    fun `from the question, its stop starts, or another is chosen first`() = runTest {
        val vm = vm(BusRoute(start = true, stop = 2))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.state.value.autoStart)
        assertEquals("静岡駅", vm.state.value.stop?.name)

        val other = vm(BusRoute(pick = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { other.state.collect {} }
        assertTrue(other.state.value.choosing)
        other.pickStop(3)
        other.startTrip()
        assertFalse(other.state.value.choosing)
        assertEquals(listOf(3L), bus.used)
    }

    @Test
    fun `the first stop: the search, its page, then Add stop`() = runTest {
        bus.stopsFlow.value = BusStops()
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.state.value.searching)
        vm.setQuery("葵")
        advanceTimeBy(300)
        assertEquals(null, vm.state.value.search.found) // one character: not yet
        vm.setQuery("葵西")
        advanceTimeBy(300)
        assertEquals(2, vm.state.value.search.found?.size)
        vm.pick(vm.state.value.search.found!!.first())
        // Its page, filled in; nothing saved yet.
        assertTrue(vm.state.value.stopPage)
        assertEquals(StopDraft("葵西", 34.9756, 138.3829, StopKind.Bus), vm.state.value.draft)
        assertEquals(emptyList<BusStop>(), bus.stopsFlow.value!!.stops)
        vm.setMeters(600)
        vm.addStop()
        assertFalse(vm.state.value.isEditing)
        val saved = bus.stopsFlow.value!!.stops.single()
        assertEquals(listOf("葵西", "600", "Strong"), listOf(saved.name, saved.alertMeters.toString(), saved.buzz.name))
        assertEquals(saved, vm.state.value.stop)
        assertEquals(1, reminders.syncs)
    }

    @Test
    fun `adding a station for some shifts takes them from the others, and a stop can be removed`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.openSettings()
        vm.add()
        assertTrue(vm.state.value.searching)
        vm.setQuery("静岡")
        advanceTimeBy(300)
        vm.pick(vm.state.value.search.found!!.single { it.train })
        // A station: a train's distance.
        assertEquals(StopKind.Train to 2000, vm.state.value.draft!!.let { it.kind to it.meters })
        vm.setShifts(listOf("Late", "Early"))
        assertEquals(listOf("Early", "Late"), vm.state.value.draft!!.shifts) // in the order of the day
        // Back goes to the search, and forward again.
        vm.back()
        assertTrue(vm.state.value.searching)
        vm.pick(Samples.found.single { it.train })
        vm.setShifts(listOf("Early", "Late"))
        vm.addStop()
        val all = bus.stopsFlow.value!!
        assertEquals(4, all.stops.size)
        assertEquals(emptyList<String>(), all.byId(1)?.shifts)
        assertEquals(listOf("Middle"), all.byId(2)?.shifts)
        // Back in Settings, the new one marked.
        assertTrue(vm.state.value.settings)
        assertFalse(vm.state.value.isEditing)
        assertEquals(4L, vm.state.value.added)

        vm.edit(4)
        assertEquals(listOf("Early", "Late"), vm.state.value.pageStop?.shifts)
        vm.removeStop()
        assertEquals(3, bus.stopsFlow.value!!.stops.size)
        assertFalse(vm.state.value.isEditing)
    }

    @Test
    fun `a saved stop saves each change, and moves with the search`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.openSettings()
        vm.edit(3)
        vm.openSheet(StopSheet.Buzz)
        vm.setBuzz(Buzz.Strongest)
        assertEquals(null, vm.state.value.sheet) // a pick closes it
        assertEquals(Buzz.Strongest, bus.stopsFlow.value!!.byId(3)!!.buzz)
        assertEquals(Buzz.Strong, bus.stopsFlow.value!!.byId(1)!!.buzz) // only that stop's
        vm.setMeters(1500)
        assertEquals(1500, bus.stopsFlow.value!!.byId(3)!!.alertMeters)
        vm.toggleAsk()
        assertTrue(bus.stopsFlow.value!!.byId(3)!!.ask)
        assertFalse(vm.state.value.askBlocked)
        // Where: the search, and the stop moves to what's picked (a station now, with a station's distance).
        vm.openWhere()
        assertTrue(vm.state.value.searching)
        vm.setQuery("静岡")
        advanceTimeBy(300)
        vm.pick(vm.state.value.search.found!!.single { it.train })
        assertTrue(vm.state.value.stopPage)
        val moved = bus.stopsFlow.value!!.byId(3)!!
        assertEquals(listOf("静岡", "train", "1500", "Strongest"), listOf(moved.name, moved.kind.api, moved.alertMeters.toString(), moved.buzz.name))
        // A sheet closes first; then back to Settings, where it was opened from.
        vm.openSheet(StopSheet.Distance)
        vm.back()
        assertEquals(null, vm.state.value.sheet)
        assertTrue(vm.state.value.isEditing)
        vm.back()
        assertFalse(vm.state.value.isEditing)
        assertTrue(vm.state.value.settings)
        // Notifications off for the app: switching a question on says so.
        reminders.allowed = false
        vm.edit(1)
        vm.toggleAsk() // it was on: off
        assertFalse(vm.state.value.askBlocked)
        vm.toggleAsk()
        assertTrue(vm.state.value.askBlocked)
        vm.back()
        vm.back()
        assertFalse(vm.state.value.settings)
    }

    @Test
    fun `try it now rides for six seconds, then really buzzes the stop's way, and Stop ends it`() = runTest {
        bus.stopsFlow.value = Samples.stops.copy(lastId = 2)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.practice()
        assertTrue(vm.state.value.practice is Practice.Riding)
        advanceTimeBy(BusViewModel.PRACTICE_MS + 50)
        assertEquals(Practice.Buzzing, vm.state.value.practice)
        assertEquals(listOf("静岡駅"), alarm.tests)
        assertEquals(Buzz.Strongest, alarm.testBuzz) // the stop's
        vm.stopBuzz()
        assertEquals(Practice.Done, vm.state.value.practice)
        vm.endPractice()
        assertEquals(null, vm.state.value.practice)
    }

    @Test
    fun `where I am now, then its name and kind`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.add()
        alarm.here = null
        vm.useHere()
        assertEquals(StopProblem.NoHere, vm.state.value.search.problem)
        alarm.here = 34.97 to 138.38
        vm.setQuery("職場前")
        vm.useHere()
        assertEquals("職場前", vm.state.value.search.place?.name)
        vm.setPlaceKind(StopKind.Train)
        vm.usePlace()
        assertEquals(StopDraft("職場前", 34.97, 138.38, StopKind.Train), vm.state.value.draft)
    }
}
