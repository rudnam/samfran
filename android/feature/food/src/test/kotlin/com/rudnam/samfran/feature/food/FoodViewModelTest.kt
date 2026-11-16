package com.rudnam.samfran.feature.food

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.testing.FakeFoodRepository
import com.rudnam.samfran.core.testing.FakeSpaceRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.feature.food.navigation.PlaceFormRoute
import com.rudnam.samfran.feature.food.navigation.PlaceRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FoodViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"), ZoneId.of("Asia/Tokyo"))
    private val food = FakeFoodRepository()
    private val space = FakeSpaceRepository()

    /** Keeps a StateFlow collected, as the screen does. */
    private fun <T> TestScope.watch(flow: kotlinx.coroutines.flow.StateFlow<T>) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }

    @Test
    fun `the list splits into been there and want to go, and searches both`() = runTest {
        val vm = FoodListViewModel(food, space, clock)
        watch(vm.state)
        val s = vm.state.value
        assertEquals(listOf(12L, 9L, 7L, 5L), s.been.map { it.id })
        assertEquals(listOf(15L, 14L), s.want.map { it.id })
        assertEquals(2, s.thisMonth)
        assertTrue(s.searchable)
        vm.setQuery("miso")
        assertEquals(listOf(9L), vm.state.value.shown.map { it.id })
        vm.setTab(FoodTab.Want)
        vm.setQuery("")
        assertEquals(listOf(15L, 14L), vm.state.value.shown.map { it.id })
    }

    @Test
    fun `offline, the phone's copy stays and says so`() = runTest {
        food.next = Outcome.Offline
        val vm = FoodListViewModel(food, space, clock)
        watch(vm.state)
        assertEquals(Outcome.Offline, vm.state.value.problem)
        assertEquals(6, vm.state.value.all?.size)
    }

    @Test
    fun `logging a visit, rating, and removing a visit with undo`() = runTest {
        val vm = PlaceViewModel(SavedStateHandle(PlaceRoute(12)), food, space, clock, backgroundScope)
        watch(vm.state)
        assertEquals(4, vm.state.value.visitCount)
        vm.setVisitNote("Tongue")
        vm.logVisit()
        assertEquals(5, vm.state.value.visitCount)
        assertEquals("", vm.state.value.visitNote)
        vm.rate(3)
        assertEquals(3, vm.state.value.place?.myStars)

        val visit = vm.state.value.visits.first().id
        vm.removeVisit(visit)
        assertFalse(vm.state.value.visits.any { it.id == visit })
        vm.undoRemove()
        assertTrue(vm.state.value.visits.any { it.id == visit })
        vm.removeVisit(visit)
        vm.commitRemove()
        testScheduler.runCurrent() // the delete runs in the app's scope, past the page
        assertNull(vm.state.value.removing)
        assertFalse(food.list.value!!.first { it.id == 12L }.visits!!.any { it.id == visit })
    }

    @Test
    fun `a visit can't be in the future`() = runTest {
        val vm = PlaceViewModel(SavedStateHandle(PlaceRoute(12)), food, space, clock, backgroundScope)
        vm.setVisitOn(LocalDate.of(2027, 1, 1))
        assertEquals(LocalDate.of(2026, 11, 16), vm.state.first().visitOn)
    }

    @Test
    fun `deleting a place closes its page`() = runTest {
        val vm = PlaceViewModel(SavedStateHandle(PlaceRoute(15)), food, space, clock, backgroundScope)
        watch(vm.state)
        vm.delete()
        assertTrue(vm.state.value.deleted)
        assertFalse(food.list.value!!.any { it.id == 15L })
    }

    @Test
    fun `a place shared from Maps fills the form, and a link alone looks up the name`() = runTest {
        val shared = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute(shared = "Hanabi Grill\nhttps://maps.app.goo.gl/AbC")), food, clock)
        assertEquals("Hanabi Grill", shared.state.value.fields?.name)
        assertEquals("https://maps.app.goo.gl/AbC", shared.state.value.fields?.mapUrl)

        food.lookupName = "Menya Kaze"
        val linkOnly = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute(shared = "https://maps.app.goo.gl/xyz")), food, clock)
        assertEquals("Menya Kaze", linkOnly.state.value.fields?.name)
    }

    @Test
    fun `adding a place you've been to logs the visit and the rating, then opens it`() = runTest {
        val vm = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute()), food, clock)
        assertFalse(vm.state.value.ready)
        vm.set(vm.state.value.fields!!.copy(name = "Gyoza Ou"))
        vm.setStars(5)
        vm.save()
        val id = vm.state.value.savedId!!
        val added = food.list.value!!.first { it.id == id }
        assertEquals(1, added.visitCount)
        assertEquals(5, added.myStars)
    }

    @Test
    fun `want to go adds no visit`() = runTest {
        val vm = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute(want = true)), food, clock)
        vm.set(vm.state.value.fields!!.copy(name = "Later Place"))
        vm.setStars(4) // ignored: no visit, no rating
        vm.save()
        val added = food.list.value!!.first { it.id == vm.state.value.savedId }
        assertEquals(0, added.visitCount)
        assertTrue(added.ratings.isEmpty())
    }
}
