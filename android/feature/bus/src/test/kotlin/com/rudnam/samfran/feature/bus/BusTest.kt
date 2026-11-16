package com.rudnam.samfran.feature.bus

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.TripStatus
import com.rudnam.samfran.core.testing.FakeBusAlarm
import com.rudnam.samfran.core.testing.FakeBusRepository
import com.rudnam.samfran.core.testing.FakeFoodRepository
import com.rudnam.samfran.core.testing.FakeReminders
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.core.testing.Samples
import com.rudnam.samfran.feature.bus.navigation.BusRoute
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
        override fun test() {}
        override fun setPrompts(on: Boolean) {}
        override fun edit() {}
        override fun cancelEdit() {}
        override fun setName(name: String) {}
        override fun pick(stop: FoundStop) {}
        override fun setLink(link: String) {}
        override fun useLink() {}
        override fun useHere() {}
        override fun setMeters(meters: Int) {}
        override fun saveStop() {}
    }

    private val ready = BusUiState(stop = Samples.stop, prompts = true)

    private fun screen(state: BusUiState): @Composable () -> Unit = { BusScreen(state, actions, {}, {}, {}, {}) }

    @Test
    fun ready() = shot("ready", content = screen(ready))

    @Test
    fun readyDark() = shot("ready-dark", dark = true, content = screen(ready))

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun readyJapanese() = shot("ready-ja", content = screen(ready))

    @Test
    fun onTheBus() = shot("trip", content = screen(ready.copy(trip = TripStatus(running = true, distance = 2340f))))

    @Test
    fun buzzing() = shot("buzzing", content = screen(ready.copy(trip = TripStatus(running = true, distance = 640f, buzzing = true))))

    @Test
    fun choosingTheStop() = shot("choose", content = screen(
        BusUiState(stop = BusStop(set = false), editing = true, form = StopForm(name = "葵西", query = "葵西", found = Samples.found.take(2))),
    ))

    @Test
    fun stopFound() = shot("found", content = screen(
        ready.copy(editing = true, form = StopForm(name = "葵西", position = 34.97 to 138.38, meters = 600)),
    ))
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BusViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val bus = FakeBusRepository()
    private val alarm = FakeBusAlarm()
    private val reminders = FakeReminders()

    private fun vm(start: Boolean = false) = BusViewModel(SavedStateHandle(BusRoute(start)), bus, FakeFoodRepository(), alarm, reminders)

    @Test
    fun `a trip starts and stops`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.state.value.ready)
        vm.startTrip()
        assertTrue(vm.state.value.trip.running)
        vm.stopTrip()
        assertFalse(vm.state.value.trip.running)
        vm.test()
        assertEquals(listOf("葵西"), alarm.tests)
    }

    @Test
    fun `no stop yet opens the form; searching, picking and saving choose one`() = runTest {
        bus.stopFlow.value = BusStop(set = false)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.state.value.editing)
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
        assertFalse(vm.state.value.editing)
        assertEquals(BusStop(true, "葵西", 34.9756, 138.3829, 600), bus.stopFlow.value)
        assertEquals(1, reminders.syncs)
    }

    @Test
    fun `where I am now, and the question at the end of a shift`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.edit()
        alarm.here = null
        vm.useHere()
        assertEquals(StopProblem.NoHere, vm.state.value.form.problem)
        vm.setPrompts(true)
        assertTrue(vm.state.value.prompts)
        reminders.allowed = false
        vm.setPrompts(true)
        assertTrue(vm.state.value.promptsBlocked)
    }
}
