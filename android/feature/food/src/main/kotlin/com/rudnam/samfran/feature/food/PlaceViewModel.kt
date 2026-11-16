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
    val visitOn: LocalDate = LocalDate.now(),
    val visitNote: String = "",
    /** A visit just removed: hidden, and deleted for real once Undo's time is up. */
    val removing: Long? = null,
    /** The place is gone (deleted here): the page closes. */
    val deleted: Boolean = false,
    val today: LocalDate = LocalDate.now(),
) {
    val visits get() = place?.visits.orEmpty().filter { it.id != removing }
    val visitCount get() = (place?.visitCount ?: 0) - if (removing != null) 1 else 0
}

interface PlaceActions {
    fun rate(stars: Int)
    fun setVisitOn(date: LocalDate)
    fun setVisitNote(note: String)
    fun logVisit()
    /** Hides the visit; [undoRemove] brings it back, [commitRemove] deletes it. */
    fun removeVisit(id: Long)
    fun undoRemove()
    fun commitRemove()
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
    private val local = MutableStateFlow(PlaceUiState(visitOn = today, today = today))

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

    override fun setVisitOn(date: LocalDate) = local.update { it.copy(visitOn = minOf(date, it.today)) }

    override fun setVisitNote(note: String) = local.update { it.copy(visitNote = note.take(1000)) }

    override fun logVisit() {
        val s = local.value
        change({ food.addVisit(id, s.visitOn, s.visitNote) }) { it.copy(visitNote = "", visitOn = it.today) }
    }

    override fun removeVisit(id: Long) {
        commitRemove() // an earlier one waiting goes now
        local.update { it.copy(removing = id) }
    }

    override fun undoRemove() = local.update { it.copy(removing = null) }

    override fun commitRemove() {
        val visit = local.value.removing ?: return
        // Outlives the page: leaving it mustn't cancel the delete.
        appScope.launch {
            val result = food.deleteVisit(id, visit)
            local.update { it.copy(removing = null, problem = result.takeIf { r -> r !is Outcome.Done }) }
        }
    }

    override fun delete() {
        local.update { it.copy(removing = null) } // a visit waiting to be removed goes with the place
        change({ food.delete(id) }) { it.copy(deleted = true) }
    }

    override fun onCleared() {
        commitRemove()
    }
}
