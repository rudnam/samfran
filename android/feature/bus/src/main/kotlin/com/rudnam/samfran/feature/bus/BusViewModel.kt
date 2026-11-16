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

/** Choosing the stop: by name from the search, or (folded away) a Google Maps link, or where the phone is. */
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
    /** "Can't find it? Other ways" is open. */
    val otherWays: Boolean = false,
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

/** "Try it now": a pretend ride of a few seconds that ends the way a real one does, with the phone really buzzing. */
sealed interface Practice {
    /** [left]: 1 to 0, through the pretend ride. */
    data class Riding(val left: Float) : Practice
    data object Buzzing : Practice
    data object Done : Practice
}

data class BusUiState(
    val stop: BusStop? = null,
    val trip: TripStatus = TripStatus(),
    /** Where the ride started from, for the progress line: the first distance the phone knew. */
    val from: Float = -1f,
    val practice: Practice? = null,
    val editing: Boolean = false,
    val form: StopForm = StopForm(),
    /** Saving a new buzz distance from the settings card. */
    val savingDistance: Boolean = false,
    val prompts: Boolean = false,
    /** Notifications are off for the app: say how to allow them. */
    val promptsBlocked: Boolean = false,
    val problem: Outcome<Any>? = null,
    val locationDenied: Boolean = false,
    /** Opened from "Start bus alarm?": the trip starts once the stop is known. */
    val autoStart: Boolean = false,
) {
    val ready: Boolean get() = stop?.set == true
    val buzzing: Boolean get() = trip.buzzing || practice == Practice.Buzzing
    val riding: Boolean get() = trip.running && !trip.buzzing
}

interface BusActions {
    /** Starts a trip; the screen has asked for location (and notifications) first. */
    fun startTrip()
    fun locationDenied()
    fun stopTrip()
    /** "Stop" while it buzzes, on a ride or in practice. */
    fun stopBuzz()
    fun practice()
    fun endPractice()
    fun setPrompts(on: Boolean)
    /** A buzz distance from the settings card: saved straight away. */
    fun setDistance(meters: Int)
    fun edit()
    fun cancelEdit()
    fun setName(name: String)
    fun pick(stop: FoundStop)
    fun setOtherWays(open: Boolean)
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
                // No stop yet: the setup, to choose one.
                is Outcome.Done -> local.update { if (!r.value.set) it.copy(editing = true, form = formFor(r.value)) else it }
                else -> local.update { it.copy(problem = r) }
            }
        }
        viewModelScope.launch {
            var sawBuzz = false
            alarm.status.collect { trip ->
                local.update { s ->
                    // The ride's start, for the progress line.
                    val from = if (!trip.running) -1f else if (s.from < 0 && trip.distance >= 0) trip.distance else s.from
                    // In practice, "Stop" on the notification ends it too.
                    val practice = if (s.practice == Practice.Buzzing) {
                        if (trip.buzzing) sawBuzz = true
                        if (sawBuzz && !trip.buzzing) Practice.Done.also { sawBuzz = false } else s.practice
                    } else {
                        s.practice
                    }
                    s.copy(from = from, practice = practice)
                }
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
        practiceRun?.cancel()
        local.update { it.copy(locationDenied = false, problem = null, practice = null) }
        alarm.start(stop)
    }

    override fun locationDenied() = local.update { it.copy(locationDenied = true) }

    override fun stopTrip() = alarm.stop()

    override fun stopBuzz() {
        if (local.value.practice == Practice.Buzzing) local.update { it.copy(practice = Practice.Done) }
        alarm.stopBuzz()
    }

    private var practiceRun: Job? = null

    override fun practice() {
        practiceRun?.cancel()
        local.update { it.copy(problem = null, practice = Practice.Riding(1f)) }
        practiceRun = viewModelScope.launch {
            val steps = PRACTICE_MS / TICK_MS
            for (i in 1..steps) {
                delay(TICK_MS)
                local.update { it.copy(practice = Practice.Riding(1f - i.toFloat() / steps)) }
            }
            local.update { it.copy(practice = Practice.Buzzing) }
            alarm.test(state.value.stop?.name.orEmpty())
        }
    }

    override fun endPractice() {
        practiceRun?.cancel()
        if (local.value.practice == Practice.Buzzing) alarm.stopBuzz()
        local.update { it.copy(practice = null) }
    }

    override fun setPrompts(on: Boolean) {
        local.update { it.copy(promptsBlocked = false) }
        viewModelScope.launch {
            val now = reminders.setShiftPrompts(on)
            local.update { it.copy(promptsBlocked = on && !now) }
        }
    }

    override fun setDistance(meters: Int) {
        val stop = state.value.stop?.takeIf { it.set } ?: return
        if (meters == stop.alertMeters || local.value.savingDistance) return
        local.update { it.copy(savingDistance = true, problem = null) }
        viewModelScope.launch {
            val r = bus.save(stop.name, stop.lat, stop.lng, meters)
            local.update { it.copy(savingDistance = false, problem = r.takeIf { o -> o !is Outcome.Done }) }
            if (r is Outcome.Done) reminders.sync(now = true)
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

    override fun setOtherWays(open: Boolean) = form { it.copy(otherWays = open) }

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
                        query = "",
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
            form { if (here == null) it.copy(busy = false, problem = StopProblem.NoHere) else it.copy(busy = false, position = here, byName = false, query = "") }
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

    companion object {
        /** How long the pretend ride of a practice run takes, before it buzzes. */
        const val PRACTICE_MS = 6000L
        private const val TICK_MS = 100L
    }
}
