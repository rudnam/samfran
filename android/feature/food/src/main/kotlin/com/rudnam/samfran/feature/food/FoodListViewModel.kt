package com.rudnam.samfran.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.samfran.core.data.FoodRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.SpaceRepository
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import com.rudnam.samfran.core.model.FoodLists
import com.rudnam.samfran.core.model.PlaceCategory
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.Space
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class FoodTab { Been, Want }

data class FoodListUiState(
    /** Null until the first answer (from the phone's copy or the server). */
    val all: List<Restaurant>? = null,
    val tab: FoodTab = FoodTab.Been,
    val query: String = "",
    /** The category chosen to show; null for all. Remembered on the phone. */
    val filter: PlaceCategory? = null,
    val space: Space? = null,
    val refreshing: Boolean = false,
    /** The last refresh didn't work (offline, say); what's shown is the phone's copy. */
    val problem: Outcome<Unit>? = null,
    val today: LocalDate = LocalDate.now(),
) {
    private val lists: FoodLists get() = FoodLists.of(all.orEmpty())
    val been: List<Restaurant> get() = lists.been
    val want: List<Restaurant> get() = lists.want

    /** The tab's places, in any category. */
    val onTab: List<Restaurant> get() = if (tab == FoodTab.Been) been else want

    /** The category chips show once there's more than one category to tell apart. */
    val filterable: Boolean
        get() {
            val inUse = all.orEmpty().map { it.category }.toSet()
            return inUse.size > 1 || (filter != null && inUse.isNotEmpty())
        }

    /** The category the list is filtered by: none while the chips are hidden. */
    val activeFilter: PlaceCategory? get() = filter.takeIf { filterable }

    /** How many of the tab's places are in each category. */
    val counts: Map<PlaceCategory, Int> get() = onTab.groupingBy { it.category }.eachCount()

    /** The chips: every category the tab has a place in, and the one chosen. */
    val chips: List<PlaceCategory> get() = PlaceCategory.entries.filter { (counts[it] ?: 0) > 0 || it == activeFilter }

    /** The tab's places in the chosen category that match the search. */
    val shown: List<Restaurant>
        get() = onTab.filter { (activeFilter == null || it.category == activeFilter) && it.matches(query) }

    /** Places last visited this month. */
    val thisMonth: Int get() = been.count { it.lastVisit?.let(YearMonth::from) == YearMonth.from(today) }

    /** The search shows once there are enough places to search. */
    val searchable: Boolean get() = all.orEmpty().size > 4
}

interface FoodListActions {
    fun setTab(tab: FoodTab)
    fun setQuery(query: String)
    fun setFilter(category: PlaceCategory?)
    fun refresh()
}

@HiltViewModel
class FoodListViewModel @Inject constructor(
    private val food: FoodRepository,
    private val spaces: SpaceRepository,
    private val preferences: UserPreferencesRepository,
    private val clock: Clock,
) : ViewModel(), FoodListActions {
    private val local = MutableStateFlow(FoodListUiState(today = LocalDate.now(clock)))

    val state: StateFlow<FoodListUiState> = combine(
        local,
        food.restaurants,
        spaces.space,
        preferences.preferences.map { it.placesCategory },
    ) { s, all, space, filter ->
        s.copy(all = all ?: s.all, space = space, filter = filter.takeIf { it.isNotEmpty() }?.let(PlaceCategory::of))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        refresh(quietly = true)
    }

    override fun setTab(tab: FoodTab) = local.update { it.copy(tab = tab) }

    override fun setQuery(query: String) = local.update { it.copy(query = query) }

    override fun setFilter(category: PlaceCategory?) {
        viewModelScope.launch { preferences.setPlacesCategory(category?.key.orEmpty()) }
    }

    override fun refresh() = refresh(quietly = false)

    private fun refresh(quietly: Boolean) {
        local.update { it.copy(refreshing = !quietly) }
        viewModelScope.launch {
            val result = food.refresh()
            spaces.refresh()
            local.update { it.copy(refreshing = false, problem = result.takeIf { r -> r !is Outcome.Done }) }
        }
    }
}
