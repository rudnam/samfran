package com.rudnam.samfran.feature.food

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.samfran.core.data.BusRepository
import com.rudnam.samfran.core.data.FoodRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.model.FoundPlace
import com.rudnam.samfran.core.model.PlaceCategory
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.RestaurantFields
import com.rudnam.samfran.core.model.named
import com.rudnam.samfran.core.model.Shared
import com.rudnam.samfran.feature.food.navigation.PlaceFormRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** A category picked by hand stays; until then, a place picked from the search sets it. */
    val categoryPicked: Boolean = false,
    val lookingUp: Boolean = false,
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    /** Opened with a place shared from Google Maps. */
    val fromShare: Boolean = false,
    /** Saved: the place's page opens in this form's place. */
    val savedId: Long? = null,
    val today: LocalDate = LocalDate.now(),
    /** Searching by name, when adding: what's typed, until a place is picked. */
    val query: String = "",
    val found: List<FoundPlace>? = null,
    val searchFailed: Boolean = false,
    /** The place picked from the search: saved with a Maps link to that branch. */
    val picked: FoundPlace? = null,
    /** What's already on the list, to say so instead of adding it twice. */
    val saved: List<Restaurant> = emptyList(),
) {
    /** A place on the list with the name typed. */
    val duplicate: Restaurant? get() = if (editing) null else saved.named(fields?.name.orEmpty())

    val editing: Boolean get() = id != null
    val ready: Boolean get() = fields?.name?.isNotBlank() == true && !busy
}

interface PlaceFormActions {
    fun set(fields: RestaurantFields)
    fun setBeen(been: Boolean)
    fun setVisitedOn(date: LocalDate)
    fun setStars(stars: Int)
    fun setCategory(category: PlaceCategory)
    /** A link however it came (typed and left, pasted, the Paste button): its name is looked up. */
    fun takeLink(text: String)
    /** The name as typed: it's also searched for, when adding. */
    fun setName(name: String)
    /** [food] is what the Kind field says for its kind, in the person's language (foodFor); empty for none. A place Google suggested gets its own from its details. */
    fun pick(place: FoundPlace, food: String)
    /** "Not this one": forget the picked place, keep the name. */
    fun unpick()
    fun save()
}

@HiltViewModel
class PlaceFormViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val food: FoodRepository,
    private val bus: BusRepository,
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
                _state.update {
                    it.copy(fields = RestaurantFields(name = r.name, area = r.area, cuisine = r.cuisine, category = r.category, mapUrl = r.mapUrl, notes = r.notes))
                }
            }
        }
        if (route.id == null) {
            viewModelScope.launch { food.restaurants.collect { list -> _state.update { it.copy(saved = list.orEmpty()) } } }
            // Near the bus stop first, if there is one: likely the right town.
            viewModelScope.launch { near = bus.stop.first()?.takeIf { it.set }?.let { it.lat to it.lng } }
        }
        // Shared from Maps with only a link: ask the server for the name.
        _state.value.fields?.let { f -> if (route.shared != null && f.name.isBlank() && f.mapUrl.isNotBlank()) nameFromLink(f.mapUrl) }
    }

    override fun set(fields: RestaurantFields) = _state.update { it.copy(fields = fields) }

    private var near: Pair<Double, Double>? = null
    private var search: Job? = null
    /** One Google session from typing to picking; a new one after each pick. */
    private var session = java.util.UUID.randomUUID().toString()

    override fun setName(name: String) {
        val f = _state.value.fields ?: return
        val n = name.take(200)
        _state.update { it.copy(fields = f.copy(name = n), query = if (it.editing) "" else n) }
        if (_state.value.editing) return
        // A quarter second after the last key, two characters or more.
        search?.cancel()
        if (n.trim().codePointCount(0, n.trim().length) < 2) {
            _state.update { it.copy(found = null, searchFailed = false) }
            return
        }
        search = viewModelScope.launch {
            delay(250)
            val r = food.search(n, near, session)
            _state.update { if (r is Outcome.Done) it.copy(found = r.value, searchFailed = false) else it.copy(searchFailed = true) }
        }
    }

    override fun pick(place: FoundPlace, food: String) {
        search?.cancel()
        if (place.placeId.isBlank()) {
            fill(place, food)
            return
        }
        // Google's suggestion has only a name: ask for the rest.
        _state.update { s -> s.copy(fields = s.fields?.copy(name = place.name), picked = null, query = "", found = null, lookingUp = true) }
        val asked = session
        session = java.util.UUID.randomUUID().toString()
        viewModelScope.launch {
            when (val r = this@PlaceFormViewModel.food.place(place.placeId, asked)) {
                is Outcome.Done -> {
                    _state.update { it.copy(lookingUp = false) }
                    fill(r.value, r.value.food)
                }
                else -> _state.update { it.copy(lookingUp = false, problem = r) }
            }
        }
    }

    /** Fills in the form from a place with its details. Area and Kind only when empty, the category unless picked by hand: what they chose stays. */
    private fun fill(place: FoundPlace, food: String) = _state.update { s ->
        val f = s.fields ?: return@update s
        val category = place.category?.takeUnless { s.categoryPicked } ?: f.category
        val fields = f.copy(name = place.name, area = f.area.ifBlank { place.area }, cuisine = f.cuisine.ifBlank { food }, category = category)
        s.copy(fields = fields, picked = place, query = "", found = null)
    }

    override fun unpick() = _state.update { it.copy(picked = null) }

    override fun setBeen(been: Boolean) = _state.update { it.copy(been = been) }

    override fun setVisitedOn(date: LocalDate) = _state.update { it.copy(visitedOn = minOf(date, it.today)) }

    override fun setStars(stars: Int) = _state.update { it.copy(stars = stars) }

    override fun setCategory(category: PlaceCategory) =
        _state.update { s -> s.copy(fields = s.fields?.copy(category = category), categoryPicked = true) }

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
            // A picked place without a link of its own opens on that branch in Maps.
            val withLink = if (fields.mapUrl.isBlank() && s.picked != null) fields.copy(mapUrl = s.picked.mapUrl) else fields
            // Where the picked place is (a Google suggestion has it only after its details).
            val placed = s.picked?.takeIf { it.lat != 0.0 || it.lng != 0.0 }?.let { withLink.copy(lat = it.lat, lng = it.lng) } ?: withLink
            val result = if (s.id != null) food.update(s.id, fields) else food.create(placed, s.visitedOn.takeIf { s.been }, s.stars)
            _state.update {
                when (result) {
                    is Outcome.Done -> it.copy(busy = false, savedId = result.value.id)
                    else -> it.copy(busy = false, problem = result)
                }
            }
        }
    }
}
