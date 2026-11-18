package com.rudnam.samfran.feature.food

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.samfran.core.common.ApplicationScope
import com.rudnam.samfran.core.data.FoodRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.SpaceRepository
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.Space
import com.rudnam.samfran.feature.food.navigation.PlaceRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class PlaceUiState(
    val place: Restaurant? = null,
    /** True once the server (or the phone's copy) has answered, place or none. */
    val loaded: Boolean = false,
    val space: Space? = null,
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    /** The date being picked (Mark as been, or Change), or null when closed. */
    val picking: LocalDate? = null,
    /** "Not been yet" just chosen: shown so, and saved once Undo's time is up. */
    val clearing: Boolean = false,
    /** The place is gone (deleted here): the page closes. */
    val deleted: Boolean = false,
    val today: LocalDate = LocalDate.now(),
) {
    /** When you went, the one date a place shows; null if not yet. */
    val beenOn: LocalDate? get() = if (clearing) null else place?.lastVisit
}

interface PlaceActions {
    fun rate(stars: Int)
    /** Opens the date: today for Mark as been, else when you went. */
    fun startPicking()
    fun setPicking(date: LocalDate)
    fun cancelPicking()
    fun saveBeen()
    /** Shows it as not been yet; [undoNotBeen] brings it back, [commitNotBeen] saves it. */
    fun notBeen()
    fun undoNotBeen()
    fun commitNotBeen()
    fun delete()
}

@HiltViewModel
class PlaceViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val food: FoodRepository,
    spaces: SpaceRepository,
    clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel(), PlaceActions {
    private val id = savedState.toRoute<PlaceRoute>().id
    private val today = LocalDate.now(clock)
    private val local = MutableStateFlow(PlaceUiState(today = today))

    val state: StateFlow<PlaceUiState> = combine(local, food.restaurant(id), spaces.space) { s, place, space ->
        s.copy(place = place ?: s.place.takeIf { s.deleted }, space = space, loaded = s.loaded || place != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            val result = food.refresh(id)
            local.update { it.copy(loaded = true, problem = result.takeIf { r -> r !is Outcome.Done }) }
        }
    }

    private fun change(block: suspend () -> Outcome<Any>, after: (PlaceUiState) -> PlaceUiState = { it }) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val result = block()
            local.update { s ->
                val next = s.copy(busy = false, problem = result.takeIf { it !is Outcome.Done })
                if (result is Outcome.Done) after(next) else next
            }
        }
    }

    override fun rate(stars: Int) = change({ food.rate(id, stars) })

    override fun startPicking() = local.update { it.copy(picking = state.value.beenOn ?: it.today) }

    override fun setPicking(date: LocalDate) = local.update { it.copy(picking = minOf(date, it.today)) }

    override fun cancelPicking() = local.update { it.copy(picking = null) }

    override fun saveBeen() {
        val on = local.value.picking ?: return
        change({ food.setBeen(id, on) }) { it.copy(picking = null) }
    }

    override fun notBeen() = local.update { it.copy(clearing = true, picking = null) }

    override fun undoNotBeen() = local.update { it.copy(clearing = false) }

    override fun commitNotBeen() {
        if (!local.value.clearing) return
        // Outlives the page: leaving it mustn't cancel the change.
        appScope.launch {
            val result = food.clearBeen(id)
            local.update { it.copy(clearing = false, problem = result.takeIf { r -> r !is Outcome.Done }) }
        }
    }

    override fun delete() {
        local.update { it.copy(clearing = false) } // a "not been yet" waiting goes with the place
        change({ food.delete(id) }) { it.copy(deleted = true) }
    }

    override fun onCleared() {
        commitNotBeen()
    }
}
