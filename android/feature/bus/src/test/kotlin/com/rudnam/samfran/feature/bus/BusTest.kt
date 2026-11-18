package com.rudnam.samfran.feature.bus

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.testing.invoke
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.BusStops
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.TripStatus
import com.rudnam.samfran.core.testing.FakeBusAlarm
import com.rudnam.samfran.core.testing.FakeBusRepository
import com.rudnam.samfran.core.testing.FakeFoodRepository
import com.rudnam.samfran.core.testing.FakeShiftsRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.core.testing.Samples
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
        override fun setBuzz(buzz: Buzz) {}
        override fun pickStop(id: Long) {}
        override fun edit(id: Long) {}
        override fun add() {}
        override fun cancelEdit() {}
        override fun toggleShift(kind: String) {}
        override fun removeStop() {}
        override fun setName(name: String) {}
        override fun pick(stop: FoundStop) {}
        override fun setOtherWays(open: Boolean) {}
        override fun setLink(link: String) {}
        override fun useLink() {}
        override fun useHere() {}
        override fun setMeters(meters: Int) {}
        override fun saveStop() {}
    }

    /** One stop, as before there could be several. */
    private val ready = BusUiState(stops = Samples.oneStop)

    /** Three stops, and a late shift today: its stop is picked. */
    private val several = BusUiState(stops = Samples.stops, shift = Samples.day("2026-11-17", "13F"))

    private val none = BusUiState(stops = BusStops(), editing = BusUiState.NEW)

    // The map is Google's page in a web view, which doesn't draw on the JVM: a stand-in of its size.
    private fun screen(state: BusUiState): @Composable () -> Unit = {
        BusScreen(state, actions, {}, {}, {}, {}, map = { _, _, _ ->
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxWidth().height(280.dp).background(SamFranTheme.colors.sunken),
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

    // A tall screen, to see the whole of Alarm settings: the stops and how it buzzes.
    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun settings() = shot("settings", content = screen(several.copy(stops = Samples.stops.copy(buzz = Buzz.Strongest))))

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun readyJapanese() = shot("ready-ja", content = screen(ready))

    @Test
    fun onTheBus() = shot("trip", content = screen(ready.copy(trip = TripStatus(running = true, distance = 2340f), from = 4200f)))

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

    @Test
    fun firstVisit() = shot("setup", content = screen(none))

    @Test
    fun choosingTheStop() = shot("choose", content = screen(
        none.copy(form = StopForm(name = "葵西", query = "葵西", found = Samples.found.take(2))),
    ))

    @Test
    fun otherWays() = shot("other-ways", content = screen(none.copy(form = StopForm(otherWays = true))))

    // Changing a stop, with the shifts it's for: early is another stop's now.
    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun stopFound() = shot("found", content = screen(
        several.copy(editing = 1, form = StopForm(name = "葵西", position = 34.97 to 138.38, meters = 600, shifts = listOf("Early", "Late"))),
    ))

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun addingAStop() = shot("add", content = screen(
        several.copy(editing = BusUiState.NEW, form = StopForm(name = "実家", position = 34.95 to 138.40)),
    ))
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BusViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val bus = FakeBusRepository()
    private val alarm = FakeBusAlarm()
    private val shifts = FakeShiftsRepository()
    // Long before the sample shifts: none is ending.
    private val clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun vm() =
        BusViewModel(bus, FakeFoodRepository(), alarm, shifts, clock)

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
        vm.setBuzz(Buzz.Strongest)
        assertEquals(Buzz.Strongest, bus.stopsFlow.value?.buzz)
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
    fun `no stop yet opens the form, and searching, picking and saving choose one`() = runTest {
        bus.stopsFlow.value = BusStops()
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(BusUiState.NEW, vm.state.value.editing)
        vm.setName("葵")
        advanceTimeBy(300)
        assertEquals(null, vm.state.value.form.found) // one character: not yet
        vm.setName("葵西")
        advanceTimeBy(300)
        assertEquals(2, vm.state.value.form.found?.size)
        vm.saveStop() // with matches showing, saving takes the first
        assertEquals(34.9756 to 138.3829, vm.state.value.form.position)
        vm.setMeters(600)
        vm.saveStop()
        assertFalse(vm.state.value.isEditing)
        val saved = bus.stopsFlow.value!!.stops.single()
        assertEquals(listOf("葵西", "600"), listOf(saved.name, saved.alertMeters.toString()))
        assertEquals(saved, vm.state.value.stop)
    }

    @Test
    fun `adding a stop for some shifts takes them from the others, and a stop can be removed`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.add()
        vm.setName("職場前")
        advanceTimeBy(300)
        vm.useHere()
        vm.toggleShift("Late")
        vm.toggleShift("Early")
        assertEquals(listOf("Early", "Late"), vm.state.value.form.shifts) // in the order of the day
        vm.saveStop()
        val all = bus.stopsFlow.value!!
        assertEquals(4, all.stops.size)
        assertEquals(emptyList<String>(), all.byId(1)?.shifts)
        assertEquals(listOf("Middle"), all.byId(2)?.shifts)
        // The one just added is the one to look at.
        assertEquals("職場前", vm.state.value.stop?.name)

        vm.edit(4)
        assertEquals(listOf("Early", "Late"), vm.state.value.form.shifts)
        vm.removeStop()
        assertEquals(3, bus.stopsFlow.value!!.stops.size)
        assertFalse(vm.state.value.isEditing)
        assertEquals("葵西", vm.state.value.stop?.name) // the one used last
    }

    @Test
    fun `try it now rides for six seconds, then really buzzes, and Stop ends it`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.practice()
        assertTrue(vm.state.value.practice is Practice.Riding)
        advanceTimeBy(BusViewModel.PRACTICE_MS + 50)
        assertEquals(Practice.Buzzing, vm.state.value.practice)
        assertEquals(listOf("葵西"), alarm.tests)
        assertEquals(Buzz.Strong, alarm.testBuzz) // the person's
        vm.stopBuzz()
        assertEquals(Practice.Done, vm.state.value.practice)
        vm.endPractice()
        assertEquals(null, vm.state.value.practice)
    }

    @Test
    fun `where I am now`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.edit(1)
        alarm.here = null
        vm.useHere()
        assertEquals(StopProblem.NoHere, vm.state.value.form.problem)
    }
}
