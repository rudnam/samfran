package com.rudnam.samfran.feature.bus

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.samfran.core.data.BusAlarm
import com.rudnam.samfran.core.data.BusRepository
import com.rudnam.samfran.core.data.FoodRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.TripStatus
import com.rudnam.samfran.core.model.stopNameFromMaps
import com.rudnam.samfran.feature.bus.navigation.BusRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Choosing the stop: by name from the search, a Google Maps link, or where the phone is. */
data class StopForm(
    val name: String = "",
    /** What's being searched for: the name as typed, until a stop is picked. */
    val query: String = "",
    val found: List<FoundStop>? = null,
    val searchFailed: Boolean = false,
    val position: Pair<Double, Double>? = null,
    val link: String = "",
    /** The position came from OpenStreetMap, by the name in the link. */
    val byName: Boolean = false,
    val meters: Int = 800,
    val busy: Boolean = false,
    val problem: StopProblem? = null,
)

/** What went wrong while choosing the stop. */
sealed interface StopProblem {
    data class Server(val outcome: Outcome<Any>) : StopProblem
    /** The link doesn't say where the stop is. */
    data object NoPosition : StopProblem
    /** The phone can't tell where it is. */
    data object NoHere : StopProblem
}

data class BusUiState(
    val stop: BusStop? = null,
    val trip: TripStatus = TripStatus(),
    val editing: Boolean = false,
    val form: StopForm = StopForm(),
    val prompts: Boolean = false,
    /** Notifications are off for the app: say how to allow them. */
    val promptsBlocked: Boolean = false,
    val problem: Outcome<Any>? = null,
    val locationDenied: Boolean = false,
    /** Opened from "Start bus alarm?": the trip starts once the stop is known. */
    val autoStart: Boolean = false,
) {
    val ready: Boolean get() = stop?.set == true
}

interface BusActions {
    /** Starts a trip; the screen has asked for location (and notifications) first. */
    fun startTrip()
    fun locationDenied()
    fun stopTrip()
    fun stopBuzz()
    fun test()
    fun setPrompts(on: Boolean)
    fun edit()
    fun cancelEdit()
    fun setName(name: String)
    fun pick(stop: FoundStop)
    fun setLink(link: String)
    fun useLink()
    fun useHere()
    fun setMeters(meters: Int)
    fun saveStop()
}

@HiltViewModel
class BusViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val bus: BusRepository,
    private val food: FoodRepository,
    private val alarm: BusAlarm,
    private val reminders: Reminders,
) : ViewModel(), BusActions {
    private val local = MutableStateFlow(BusUiState(autoStart = savedState.toRoute<BusRoute>().start))

    val state: StateFlow<BusUiState> = combine(local, bus.stop, alarm.status, reminders.shiftPrompts) { s, stop, trip, prompts ->
        s.copy(stop = stop ?: s.stop, trip = trip, prompts = prompts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            when (val r = bus.refresh()) {
                is Outcome.Done -> local.update {
                    // No stop yet: the form, to choose one.
                    if (!r.value.set) it.copy(editing = true, form = formFor(r.value)) else it
                }
                else -> local.update { it.copy(problem = r) }
            }
        }
    }

    private fun formFor(stop: BusStop) = StopForm(
        name = stop.name,
        position = if (stop.set) stop.lat to stop.lng else null,
        meters = stop.alertMeters.takeIf { it > 0 } ?: 800,
    )

    /** The trip asked for by "Start bus alarm?" has been handled (started, or not possible). */
    fun autoStartHandled() = local.update { it.copy(autoStart = false) }

    override fun startTrip() {
        val stop = state.value.stop?.takeIf { it.set } ?: return
        if (state.value.trip.running) return
        local.update { it.copy(locationDenied = false, problem = null) }
        alarm.start(stop)
    }

    override fun locationDenied() = local.update { it.copy(locationDenied = true) }

    override fun stopTrip() = alarm.stop()

    override fun stopBuzz() = alarm.stopBuzz()

    override fun test() = alarm.test(state.value.stop?.name.orEmpty())

    override fun setPrompts(on: Boolean) {
        local.update { it.copy(promptsBlocked = false) }
        viewModelScope.launch {
            val now = reminders.setShiftPrompts(on)
            local.update { it.copy(promptsBlocked = on && !now) }
        }
    }

    override fun edit() = local.update { it.copy(editing = true, form = formFor(state.value.stop ?: BusStop(set = false))) }

    override fun cancelEdit() = local.update { it.copy(editing = false) }

    private fun form(f: (StopForm) -> StopForm) = local.update { it.copy(form = f(it.form)) }

    private var search: Job? = null

    override fun setName(name: String) {
        val n = name.take(80)
        form { it.copy(name = n, query = n) }
        // A quarter second after the last key, two characters or more.
        search?.cancel()
        if (n.trim().codePointCount(0, n.trim().length) < 2) {
            form { it.copy(found = null, searchFailed = false) }
            return
        }
        search = viewModelScope.launch {
            delay(250)
            val r = bus.search(n, local.value.form.position)
            form {
                when (r) {
                    is Outcome.Done -> it.copy(found = r.value, searchFailed = false)
                    else -> it.copy(searchFailed = true)
                }
            }
        }
    }

    override fun pick(stop: FoundStop) {
        search?.cancel()
        form { it.copy(name = stop.name, position = stop.lat to stop.lng, byName = false, query = "", found = null, problem = null) }
    }

    override fun setLink(link: String) = form { it.copy(link = link) }

    override fun useLink() {
        val link = local.value.form.link.trim()
        if (link.isEmpty() || local.value.form.busy) return
        form { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val r = food.lookup(link, position = true)
            form { f ->
                when {
                    r !is Outcome.Done -> f.copy(busy = false, problem = StopProblem.Server(r))
                    r.value.lat == null || r.value.lng == null -> f.copy(busy = false, problem = StopProblem.NoPosition)
                    else -> f.copy(
                        busy = false,
                        position = r.value.lat!! to r.value.lng!!,
                        byName = r.value.foundByName,
                        name = f.name.ifBlank { stopNameFromMaps(r.value.name) },
                    )
                }
            }
        }
    }

    override fun useHere() {
        form { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val here = alarm.here()
            form { if (here == null) it.copy(busy = false, problem = StopProblem.NoHere) else it.copy(busy = false, position = here, byName = false) }
        }
    }

    override fun setMeters(meters: Int) = form { it.copy(meters = meters) }

    override fun saveStop() {
        val f = local.value.form
        // Saving while choosing from the list takes the first match.
        if (f.query.isNotEmpty() && !f.found.isNullOrEmpty()) {
            pick(f.found.first())
            return
        }
        val pos = f.position ?: return
        form { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            when (val r = bus.save(f.name, pos.first, pos.second, f.meters)) {
                is Outcome.Done -> {
                    local.update { it.copy(editing = false, form = it.form.copy(busy = false)) }
                    reminders.sync(now = true) // the question names the stop
                }
                else -> form { it.copy(busy = false, problem = StopProblem.Server(r)) }
            }
        }
    }
}
