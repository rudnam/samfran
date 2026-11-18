package com.rudnam.samfran.feature.food

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.model.PlaceCategory
import com.rudnam.samfran.core.testing.FakeBusAlarm
import com.rudnam.samfran.core.testing.FakeBusRepository
import com.rudnam.samfran.core.testing.FakeFoodRepository
import com.rudnam.samfran.core.testing.FakeSpaceRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.core.testing.TestPreferences
import com.rudnam.samfran.feature.food.navigation.PlaceFormRoute
import com.rudnam.samfran.feature.food.navigation.PlaceRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
    private val bus = FakeBusRepository()
    private val preferences = TestPreferences.repository()
    private val alarm = FakeBusAlarm()

    /** Keeps a StateFlow collected, as the screen does. */
    private fun <T> TestScope.watch(flow: kotlinx.coroutines.flow.StateFlow<T>) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }

    @Test
    fun `the list splits into been there and want to go, and searches both`() = runTest {
        val vm = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(vm.state)
        val s = vm.state.value
        assertEquals(listOf(12L, 9L, 20L, 7L, 5L), s.been.map { it.id })
        assertEquals(listOf(23L, 22L, 21L, 15L, 14L), s.want.map { it.id })
        assertEquals(2, s.thisMonth)
        assertTrue(s.searchable)
        vm.setQuery("ramen")
        assertEquals(listOf(9L), vm.state.value.shown.map { it.id })
        vm.setQuery("miso") // only in an old visit's note, which the app no longer shows
        assertEquals(emptyList<Long>(), vm.state.value.shown.map { it.id })
        vm.setTab(FoodTab.Want)
        vm.setQuery("")
        assertEquals(listOf(23L, 22L, 21L, 15L, 14L), vm.state.value.shown.map { it.id })
    }

    @Test
    fun `the list filters by category, and the phone remembers which`() = runTest {
        val vm = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(vm.state)
        vm.setTab(FoodTab.Want)
        val s = vm.state.value
        assertTrue(s.filterable)
        assertEquals(listOf(PlaceCategory.Eat, PlaceCategory.Nature, PlaceCategory.Culture, PlaceCategory.Relax), s.chips)
        assertEquals(2, s.counts[PlaceCategory.Eat])
        vm.setFilter(PlaceCategory.Nature)
        assertEquals(listOf(22L), vm.state.value.shown.map { it.id })
        // Been there, the same filter.
        vm.setTab(FoodTab.Been)
        assertEquals(listOf(20L), vm.state.value.shown.map { it.id })
        // Opened again later, it's still on Nature.
        val later = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(later.state)
        assertEquals(PlaceCategory.Nature, later.state.value.activeFilter)
        later.setFilter(null)
        assertEquals(5, later.state.value.shown.size)
    }

    @Test
    fun `nearby sorts by distance from the phone, and is remembered`() = runTest {
        val vm = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(vm.state)
        vm.setTab(FoodTab.Want)
        alarm.here = 34.975 to 138.385 // by Shizuoka Station
        vm.setOrder(PlaceOrder.Nearby)
        // Nearest first; those without a position last, newest first.
        assertEquals(listOf(22L, 21L, 15L, 23L, 14L), vm.state.value.shown.map { it.id })
        assertEquals(Locating.No, vm.state.value.locating)
        // Opened again later: still nearby.
        val later = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(later.state)
        assertTrue(later.state.value.nearby)
    }

    @Test
    fun `top rated puts the best first and the unrated last, A to Z by name, both remembered`() = runTest {
        val vm = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(vm.state)
        // Hanabi and Menya both average 4.5: they keep their newest-first order.
        vm.setOrder(PlaceOrder.TopRated)
        assertEquals(listOf(12L, 9L, 20L, 5L, 7L), vm.state.value.shown.map { it.id })
        vm.setTab(FoodTab.Want) // nobody's rated these: newest first
        assertEquals(listOf(23L, 22L, 21L, 15L, 14L), vm.state.value.shown.map { it.id })
        vm.setTab(FoodTab.Been)
        vm.setOrder(PlaceOrder.Name)
        assertEquals(listOf("Cafe Lune", "Hanabi Grill", "Menya Kaze", "Sumpu Castle Park", "Trattoria Sole"), vm.state.value.shown.map { it.name })
        val later = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(later.state)
        assertEquals(PlaceOrder.Name, later.state.value.order)
    }

    @Test
    fun `nearby without the phone's location stays newest first, and says so`() = runTest {
        alarm.here = null
        val vm = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(vm.state)
        vm.setTab(FoodTab.Want)
        vm.setOrder(PlaceOrder.Nearby)
        assertEquals(Locating.Failed, vm.state.value.locating)
        assertEquals(listOf(23L, 22L, 21L, 15L, 14L), vm.state.value.shown.map { it.id })
    }

    @Test
    fun `with only one category there's nothing to filter, but a remembered filter can still be undone`() = runTest {
        food.list.value = food.list.value!!.filter { it.category == PlaceCategory.Eat }
        val vm = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(vm.state)
        assertFalse(vm.state.value.filterable)
        assertEquals(4, vm.state.value.shown.size)

        // Nature, remembered from before the parks were deleted: its chip stays, with nothing in it, so it can be switched off.
        preferences.setPlacesCategory("nature")
        assertTrue(vm.state.value.filterable)
        assertEquals(listOf(PlaceCategory.Eat, PlaceCategory.Nature), vm.state.value.chips)
        assertTrue(vm.state.value.shown.isEmpty())
        vm.setFilter(null)
        assertFalse(vm.state.value.filterable)
        assertNull(vm.state.value.activeFilter)
        assertEquals(4, vm.state.value.shown.size)
    }

    @Test
    fun `offline, the phone's copy stays and says so`() = runTest {
        food.next = Outcome.Offline
        val vm = FoodListViewModel(food, space, preferences, alarm, clock)
        watch(vm.state)
        assertEquals(Outcome.Offline, vm.state.value.problem)
        assertEquals(10, vm.state.value.all?.size)
    }

    @Test
    fun `changing when you went, rating, and not been yet with undo`() = runTest {
        val vm = PlaceViewModel(SavedStateHandle(PlaceRoute(12)), food, space, clock, backgroundScope)
        watch(vm.state)
        assertEquals(LocalDate.of(2026, 11, 14), vm.state.value.beenOn)
        // Change opens on the date it has; going again moves that date, it adds no visit.
        vm.startPicking()
        assertEquals(LocalDate.of(2026, 11, 14), vm.state.value.picking)
        vm.setPicking(LocalDate.of(2026, 11, 15))
        vm.saveBeen()
        assertNull(vm.state.value.picking)
        assertEquals(LocalDate.of(2026, 11, 15), vm.state.value.beenOn)
        assertEquals(4, vm.state.value.place?.visitCount)
        vm.rate(3)
        assertEquals(3, vm.state.value.place?.myStars)

        vm.notBeen()
        assertNull(vm.state.value.beenOn)
        vm.undoNotBeen()
        assertEquals(LocalDate.of(2026, 11, 15), vm.state.value.beenOn)
        vm.notBeen()
        vm.commitNotBeen()
        testScheduler.runCurrent() // it runs in the app's scope, past the page
        assertFalse(vm.state.value.clearing)
        assertFalse(food.list.value!!.first { it.id == 12L }.been)
    }

    @Test
    fun `mark as been asks when, today unless changed, and not in the future`() = runTest {
        val vm = PlaceViewModel(SavedStateHandle(PlaceRoute(15)), food, space, clock, backgroundScope)
        watch(vm.state)
        assertNull(vm.state.value.beenOn)
        vm.startPicking()
        assertEquals(LocalDate.of(2026, 11, 16), vm.state.value.picking)
        vm.setPicking(LocalDate.of(2027, 1, 1))
        assertEquals(LocalDate.of(2026, 11, 16), vm.state.value.picking)
        vm.cancelPicking()
        assertNull(vm.state.value.picking)
        vm.startPicking()
        vm.saveBeen()
        assertEquals(LocalDate.of(2026, 11, 16), vm.state.value.beenOn)
        assertEquals(1, vm.state.value.place?.visitCount)
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
        val shared = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute(shared = "Hanabi Grill\nhttps://maps.app.goo.gl/AbC")), food, bus, clock)
        assertEquals("Hanabi Grill", shared.state.value.fields?.name)
        assertEquals("https://maps.app.goo.gl/AbC", shared.state.value.fields?.mapUrl)

        food.lookupName = "Menya Kaze"
        val linkOnly = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute(shared = "https://maps.app.goo.gl/xyz")), food, bus, clock)
        assertEquals("Menya Kaze", linkOnly.state.value.fields?.name)
    }

    @Test
    fun `adding a place you've been to logs the visit and the rating, then opens it`() = runTest {
        val vm = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute()), food, bus, clock)
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
        val vm = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute(want = true)), food, bus, clock)
        vm.set(vm.state.value.fields!!.copy(name = "Later Place"))
        vm.setStars(4) // ignored: no visit, no rating
        vm.save()
        val added = food.list.value!!.first { it.id == vm.state.value.savedId }
        assertEquals(0, added.visitCount)
        assertTrue(added.ratings.isEmpty())
    }

    @Test
    fun `typing a name searches, picking fills in the area and food and links to that branch, and a place on the list says so`() = runTest {
        val vm = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute()), food, bus, clock)
        vm.setName("すき")
        advanceTimeBy(300)
        assertEquals(2, vm.state.value.found?.size)
        vm.pick(vm.state.value.found!!.first(), "Fast food")
        assertEquals("すき家 静岡駅南店", vm.state.value.fields?.name)
        assertEquals("静岡市駿河区", vm.state.value.fields?.area)
        assertEquals("Fast food", vm.state.value.fields?.cuisine)
        assertEquals("", vm.state.value.query)
        vm.save()
        val added = food.list.value!!.first { it.id == vm.state.value.savedId }
        assertEquals("https://www.google.com/maps/search/%E3%81%99%E3%81%8D%E5%AE%B6%20%E9%9D%99%E5%B2%A1%E9%A7%85%E5%8D%97%E5%BA%97/@34.968,138.389,17z", added.mapUrl)

        // What they typed in Kind stays when they pick a place.
        val typed = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute()), food, bus, clock)
        typed.set(com.rudnam.samfran.core.model.RestaurantFields(cuisine = "Gyudon"))
        typed.pick(com.rudnam.samfran.core.testing.Samples.places[1], "Fast food")
        assertEquals("Gyudon", typed.state.value.fields?.cuisine)

        val again = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute()), food, bus, clock)
        again.setName("hanabi grill ")
        assertEquals(12L, again.state.value.duplicate?.id)
    }

    @Test
    fun `a picked place sets the category, unless one was picked by hand, and it's saved`() = runTest {
        val park = com.rudnam.samfran.core.testing.Samples.places.first { it.category == PlaceCategory.Nature }
        val vm = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute(want = true)), food, bus, clock)
        assertEquals(PlaceCategory.Eat, vm.state.value.fields?.category)
        vm.pick(park, "Park")
        assertEquals(PlaceCategory.Nature, vm.state.value.fields?.category)
        vm.save()
        assertEquals(PlaceCategory.Nature, food.list.value!!.first { it.id == vm.state.value.savedId }.category)

        val byHand = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute(want = true)), food, bus, clock)
        byHand.setCategory(PlaceCategory.Fun)
        byHand.pick(park, "Park")
        assertEquals(PlaceCategory.Fun, byHand.state.value.fields?.category)

        // Editing keeps the category, and can change it.
        val edit = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute(id = 21)), food, bus, clock)
        edit.state.first { it.fields != null }
        assertEquals(PlaceCategory.Culture, edit.state.value.fields?.category)
        edit.setCategory(PlaceCategory.Nature)
        edit.save()
        assertEquals(PlaceCategory.Nature, food.list.value!!.first { it.id == 21L }.category)
    }

    @Test
    fun `picking a place Google suggested asks for its details, in the session the search was in`() = runTest {
        food.places = listOf(com.rudnam.samfran.core.model.FoundPlace("石松 JR浜松駅店", "", "静岡県浜松市中央区砂山町", "chinese_restaurant", 0.0, 0.0, placeId = "ChIJ1"))
        food.details = mapOf(
            "ChIJ1" to com.rudnam.samfran.core.model.FoundPlace(
                "石松 JR浜松駅店", "浜松市 中央区", "砂山町", "chinese_restaurant", 34.7036, 137.7352,
                placeId = "ChIJ1", food = "中華料理店", googleMapUrl = "https://maps.google.com/?cid=123",
            ),
        )
        val vm = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute()), food, bus, clock)
        vm.setName("石松")
        advanceTimeBy(300)
        vm.pick(vm.state.value.found!!.single(), "")
        assertEquals("浜松市 中央区", vm.state.value.fields?.area)
        assertEquals("中華料理店", vm.state.value.fields?.cuisine)
        assertFalse(vm.state.value.lookingUp)
        assertEquals(2, food.sessions.size)
        assertEquals(food.sessions[0], food.sessions[1])
        vm.save()
        val added = food.list.value!!.first { it.id == vm.state.value.savedId }
        assertEquals("https://maps.google.com/?cid=123", added.mapUrl)

        // Details that don't come: the name stays, and it says why.
        val failing = PlaceFormViewModel(SavedStateHandle(PlaceFormRoute()), food, bus, clock)
        failing.pick(com.rudnam.samfran.core.model.FoundPlace("どこか", "", "", "restaurant", 0.0, 0.0, placeId = "ChIJunknown"), "")
        assertEquals("どこか", failing.state.value.fields?.name)
        assertTrue(failing.state.value.problem is Outcome.Refused)
    }
}
