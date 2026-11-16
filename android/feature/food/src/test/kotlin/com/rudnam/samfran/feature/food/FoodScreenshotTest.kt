package com.rudnam.samfran.feature.food

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.RestaurantFields
import com.rudnam.samfran.core.testing.Samples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The Food pages at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class FoodScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 16)

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { SamFranTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/food-$name.png")
    }

    private val list = object : FoodListActions {
        override fun setTab(tab: FoodTab) {}
        override fun setQuery(query: String) {}
        override fun refresh() {}
    }
    private val place = object : PlaceActions {
        override fun rate(stars: Int) {}
        override fun setVisitOn(date: LocalDate) {}
        override fun setVisitNote(note: String) {}
        override fun logVisit() {}
        override fun removeVisit(id: Long) {}
        override fun undoRemove() {}
        override fun commitRemove() {}
        override fun delete() {}
    }
    private val form = object : PlaceFormActions {
        override fun set(fields: RestaurantFields) {}
        override fun setBeen(been: Boolean) {}
        override fun setVisitedOn(date: LocalDate) {}
        override fun setStars(stars: Int) {}
        override fun takeLink(text: String) {}
        override fun setName(name: String) {}
        override fun pick(place: com.rudnam.samfran.core.model.FoundPlace) {}
        override fun unpick() {}
        override fun save() {}
    }

    private val been = FoodListUiState(all = Samples.restaurants, space = Samples.space, today = today)

    @Test
    fun beenThere() = shot("list") { FoodListScreen(been, list, {}, {}) }

    @Test
    fun beenThereDark() = shot("list-dark", dark = true) { FoodListScreen(been, list, {}, {}) }

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun beenThereJapanese() = shot("list-ja") { FoodListScreen(been, list, {}, {}) }

    @Test
    fun wantToGo() = shot("want") { FoodListScreen(been.copy(tab = FoodTab.Want), list, {}, {}) }

    @Test
    fun searchFindsAVisitNote() = shot("search") { FoodListScreen(been.copy(query = "miso"), list, {}, {}) }

    @Test
    fun empty() = shot("empty") { FoodListScreen(FoodListUiState(all = emptyList(), today = today), list, {}, {}) }

    @Test
    fun offline() = shot("offline") { FoodListScreen(been.copy(problem = Outcome.Offline), list, {}, {}) }

    private val hanabi = PlaceUiState(place = Samples.restaurants.first(), loaded = true, space = Samples.space, visitOn = today, today = today)

    @Test
    fun placePage() = shot("place") { PlaceScreen(hanabi, place, {}, {}, {}) }

    @Test
    fun placePageDark() = shot("place-dark", dark = true) { PlaceScreen(hanabi, place, {}, {}, {}) }

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun placePageJapanese() = shot("place-ja") { PlaceScreen(hanabi, place, {}, {}, {}) }

    @Test
    fun placeNotVisited() = shot("place-want") {
        PlaceScreen(PlaceUiState(place = Samples.restaurants.first { !it.been }.copy(visits = emptyList()), loaded = true, space = Samples.space, today = today), place, {}, {}, {})
    }

    @Test
    fun addPlace() = shot("add") { PlaceFormScreen(PlaceFormUiState(today = today, visitedOn = today, stars = 4), form, {}, {}) }

    @Test
    fun addBySearching() = shot("add-search") {
        PlaceFormScreen(
            PlaceFormUiState(fields = RestaurantFields(name = "すき"), query = "すき", found = Samples.places, saved = Samples.restaurants, today = today),
            form, {}, {},
        )
    }

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun addPickedJapanese() = shot("add-picked-ja") {
        PlaceFormScreen(
            PlaceFormUiState(fields = RestaurantFields(name = "すき家 静岡駅南店", area = "静岡市駿河区"), picked = Samples.places.first(), today = today, visitedOn = today),
            form, {}, {},
        )
    }

    @Test
    fun addFromMaps() = shot("add-shared") {
        PlaceFormScreen(
            PlaceFormUiState(fields = RestaurantFields("Hanabi Grill", mapUrl = "https://maps.app.goo.gl/AbC123"), been = false, fromShare = true, today = today),
            form, {}, {},
        )
    }

    @Test
    fun editPlace() = shot("edit") {
        val r = Samples.restaurants.first()
        PlaceFormScreen(PlaceFormUiState(id = r.id, fields = RestaurantFields(r.name, r.area, r.cuisine, r.mapUrl, r.notes), today = today), form, {}, {})
    }
}
