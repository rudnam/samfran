package com.rudnam.samfran.feature.food

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.samfran.core.data.FoodRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.model.RestaurantFields
import com.rudnam.samfran.core.model.Shared
import com.rudnam.samfran.feature.food.navigation.PlaceFormRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class PlaceFormUiState(
    /** The place being edited; null when adding one. */
    val id: Long? = null,
    /** Null while an edited place loads. */
    val fields: RestaurantFields? = RestaurantFields(),
    /** Adding: been there (logs a first visit) or want to go. */
    val been: Boolean = true,
    val visitedOn: LocalDate = LocalDate.now(),
    val stars: Int = 0,
    val lookingUp: Boolean = false,
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    /** Opened with a place shared from Google Maps. */
    val fromShare: Boolean = false,
    /** Saved: the place's page opens in this form's place. */
    val savedId: Long? = null,
    val today: LocalDate = LocalDate.now(),
) {
    val editing: Boolean get() = id != null
    val ready: Boolean get() = fields?.name?.isNotBlank() == true && !busy
}

interface PlaceFormActions {
    fun set(fields: RestaurantFields)
    fun setBeen(been: Boolean)
    fun setVisitedOn(date: LocalDate)
    fun setStars(stars: Int)
    /** A link however it came (typed and left, pasted, the Paste button): its name is looked up. */
    fun takeLink(text: String)
    fun save()
}

@HiltViewModel
class PlaceFormViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val food: FoodRepository,
    clock: Clock,
) : ViewModel(), PlaceFormActions {
    private val route = savedState.toRoute<PlaceFormRoute>()
    private val today = LocalDate.now(clock)

    private val _state = MutableStateFlow(
        PlaceFormUiState(
            id = route.id,
            fields = if (route.id != null) null else route.shared?.let { Shared.parse(it) }?.let { RestaurantFields(name = it.name, mapUrl = it.url) } ?: RestaurantFields(),
            been = !route.want,
            visitedOn = today,
            today = today,
            fromShare = route.shared != null,
        ),
    )
    val state: StateFlow<PlaceFormUiState> = _state.asStateFlow()

    init {
        route.id?.let { id ->
            viewModelScope.launch {
                val r = food.restaurant(id).first { it != null } ?: return@launch
                _state.update { it.copy(fields = RestaurantFields(r.name, r.area, r.cuisine, r.mapUrl, r.notes)) }
            }
        }
        // Shared from Maps with only a link: ask the server for the name.
        _state.value.fields?.let { f -> if (route.shared != null && f.name.isBlank() && f.mapUrl.isNotBlank()) nameFromLink(f.mapUrl) }
    }

    override fun set(fields: RestaurantFields) = _state.update { it.copy(fields = fields) }

    override fun setBeen(been: Boolean) = _state.update { it.copy(been = been) }

    override fun setVisitedOn(date: LocalDate) = _state.update { it.copy(visitedOn = minOf(date, it.today)) }

    override fun setStars(stars: Int) = _state.update { it.copy(stars = stars) }

    override fun takeLink(text: String) {
        val f = _state.value.fields ?: return
        val shared = Shared.parse(text)
        if (shared.url.isNotEmpty() && shared.url != text.trim()) {
            // "Name\nhttps://maps…" goes into both fields.
            _state.update { it.copy(fields = f.copy(mapUrl = shared.url, name = f.name.ifBlank { shared.name })) }
            if (f.name.isBlank() && shared.name.isBlank()) nameFromLink(shared.url)
            return
        }
        val url = text.trim()
        _state.update { it.copy(fields = f.copy(mapUrl = url)) }
        nameFromLink(url)
    }

    /** Fills in the name from a Maps link while it's still empty. */
    private fun nameFromLink(url: String) {
        if (!Shared.isMapsLink(url) || _state.value.fields?.name?.isNotBlank() == true) return
        _state.update { it.copy(lookingUp = true) }
        viewModelScope.launch {
            val name = (food.lookup(url) as? Outcome.Done)?.value?.name.orEmpty()
            _state.update { s ->
                val f = s.fields
                s.copy(lookingUp = false, fields = if (f != null && f.name.isBlank() && name.isNotEmpty()) f.copy(name = name) else f)
            }
        }
    }

    override fun save() {
        val s = _state.value
        val fields = s.fields ?: return
        if (!s.ready) return
        _state.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val result = if (s.id != null) food.update(s.id, fields) else food.create(fields, s.visitedOn.takeIf { s.been }, s.stars)
            _state.update {
                when (result) {
                    is Outcome.Done -> it.copy(busy = false, savedId = result.value.id)
                    else -> it.copy(busy = false, problem = result)
                }
            }
        }
    }
}
