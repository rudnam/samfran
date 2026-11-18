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
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.data.StopInput
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.BusStops
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.model.ShiftKinds
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
import java.time.Clock
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
    /** The kinds of shift it's the stop for. */
    val shifts: List<String> = emptyList(),
    /** Ask "Start bus alarm?" when a shift whose stop this is ends. Off for a new stop. */
    val ask: Boolean = false,
    /** It asks, but notifications are off for the app: say how to allow them. */
    val askBlocked: Boolean = false,
    /** The map is showing: once a stop is found, until it's saved; or opened. */
    val showMap: Boolean = false,
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
    val stops: BusStops? = null,
    /** The shift being gone home from, if there is one. */
    val shift: ShiftDay? = null,
    /** A stop picked for this ride, instead of the one for the shift. */
    val picked: Long? = null,
    val trip: TripStatus = TripStatus(),
    /** Where the ride started from, for the progress line: the first distance the phone knew. */
    val from: Float = -1f,
    val practice: Practice? = null,
    /** The stop being changed (its id), [NEW] for adding one, null when not. */
    val editing: Long? = null,
    val form: StopForm = StopForm(),
    /** Saving how it buzzes, from Settings. */
    val savingSettings: Boolean = false,
    /** Settings are open: the stops, and how it buzzes. A stop opened from there goes back there. */
    val settings: Boolean = false,
    val problem: Outcome<Any>? = null,
    val locationDenied: Boolean = false,
    /** Opened from "Start bus alarm?": the trip starts once the stop is known. */
    val autoStart: Boolean = false,
    /** Opened from "Other stop": choosing where to get off, then starting. */
    val choosing: Boolean = false,
) {
    /** Where to get off: the one picked, or the shift's, or the one used last. */
    val stop: BusStop? get() = stops?.let { all -> picked?.let(all::byId) ?: all.pick(shift) }
    /** The stop is the shift's (not picked, nor the one used last). */
    val byShift: Boolean get() = stop != null && picked == null && stop == stops?.forShift(shift)
    val ready: Boolean get() = stops?.stops?.isNotEmpty() == true
    val isEditing: Boolean get() = editing != null
    val buzzing: Boolean get() = trip.buzzing || practice == Practice.Buzzing
    val riding: Boolean get() = trip.running && !trip.buzzing

    companion object {
        /** [editing] for a new stop. */
        const val NEW = 0L
    }
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
    fun openSettings()
    /** Back from Settings to the ride. */
    fun closeSettings()
    /** "See on the map", on a stop's page. */
    fun toggleMap()
    /** How it buzzes, from the settings card: saved straight away. */
    fun setBuzz(buzz: Buzz)
    /** Where to get off, for this ride. */
    fun pickStop(id: Long)
    /** "Other stop" is done with. */
    fun endChoosing()
    fun edit(id: Long)
    fun add()
    fun cancelEdit()
    fun toggleShift(kind: String)
    fun toggleAsk()
    fun removeStop()
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
    private val shifts: ShiftsRepository,
    private val clock: Clock,
) : ViewModel(), BusActions {
    private val local = savedState.toRoute<BusRoute>().let { route ->
        MutableStateFlow(BusUiState(autoStart = route.start, picked = route.stop.takeIf { it > 0 }, choosing = route.pick))
    }

    val state: StateFlow<BusUiState> = combine(local, bus.stops, alarm.status) { s, stops, trip ->
        s.copy(stops = stops ?: s.stops, trip = trip)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            when (val r = bus.refresh()) {
                // No stop yet: the setup, to choose one.
                is Outcome.Done -> local.update { if (r.value.stops.isEmpty()) it.copy(editing = BusUiState.NEW, form = StopForm()) else it }
                else -> local.update { it.copy(problem = r) }
            }
        }
        viewModelScope.launch {
            val home = shifts.homeShift(clock.instant())
            local.update { it.copy(shift = home) }
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
        position = stop.lat to stop.lng,
        meters = stop.alertMeters.takeIf { it > 0 } ?: 800,
        shifts = stop.shifts,
        ask = stop.ask,
    )

    /** The trip asked for by "Start bus alarm?" has been handled (started, or not possible). */
    fun autoStartHandled() = local.update { it.copy(autoStart = false) }

    override fun startTrip() {
        val stop = state.value.stop ?: return
        if (state.value.trip.running) return
        practiceRun?.cancel()
        local.update { it.copy(locationDenied = false, problem = null, practice = null, choosing = false) }
        alarm.start(stop)
        // The one used last, for when no shift says (and for older apps).
        viewModelScope.launch { bus.use(stop.id) }
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
            val stop = state.value.stop
            alarm.test(stop?.name.orEmpty(), state.value.stops?.buzz ?: Buzz.Strong)
        }
    }

    override fun endPractice() {
        practiceRun?.cancel()
        if (local.value.practice == Practice.Buzzing) alarm.stopBuzz()
        local.update { it.copy(practice = null) }
    }

    override fun openSettings() = local.update { it.copy(settings = true) }

    override fun closeSettings() = local.update { it.copy(settings = false) }

    override fun toggleMap() = form { it.copy(showMap = !it.showMap) }


    override fun setBuzz(buzz: Buzz) {
        val all = state.value.stops ?: return
        if (buzz == all.buzz || local.value.savingSettings) return
        local.update { it.copy(savingSettings = true, problem = null) }
        viewModelScope.launch {
            val r = bus.setBuzz(buzz)
            local.update { it.copy(savingSettings = false, problem = r.takeIf { o -> o !is Outcome.Done }) }
        }
    }

    override fun pickStop(id: Long) {
        if (state.value.trip.running) return
        endPractice()
        local.update { it.copy(picked = id) }
    }

    override fun endChoosing() = local.update { it.copy(choosing = false) }

    override fun edit(id: Long) {
        val stop = state.value.stops?.byId(id) ?: return
        local.update { it.copy(editing = id, form = formFor(stop)) }
    }

    override fun add() = local.update { it.copy(editing = BusUiState.NEW, form = StopForm()) }

    override fun cancelEdit() = local.update { it.copy(editing = null) }

    override fun toggleShift(kind: String) = form { f -> f.copy(shifts = if (kind in f.shifts) f.shifts - kind else ShiftKinds.filter { it in f.shifts || it == kind }) }

    override fun toggleAsk() = form { it.copy(ask = !it.ask, askBlocked = !it.ask && !reminders.notificationsAllowed()) }

    override fun removeStop() {
        val id = local.value.editing?.takeIf { it != BusUiState.NEW } ?: return
        form { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            when (val r = bus.remove(id)) {
                is Outcome.Done -> {
                    local.update { it.copy(editing = if (r.value.stops.isEmpty()) BusUiState.NEW else null, form = StopForm(), picked = it.picked?.takeIf { p -> p != id }, settings = it.settings && r.value.stops.isNotEmpty()) }
                    reminders.sync(now = true)
                }
                else -> form { it.copy(busy = false, problem = StopProblem.Server(r)) }
            }
        }
    }

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
        form { it.copy(name = stop.name, position = stop.lat to stop.lng, byName = false, query = "", found = null, problem = null, showMap = true) }
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
                        showMap = true,
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
            form { if (here == null) it.copy(busy = false, problem = StopProblem.NoHere) else it.copy(busy = false, position = here, byName = false, query = "", showMap = true) }
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
        val editing = local.value.editing ?: return
        form { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val input = StopInput(f.name, pos.first, pos.second, f.meters, f.shifts, f.ask)
            val before = state.value.stops?.stops.orEmpty().map { it.id }.toSet()
            val r = if (editing == BusUiState.NEW) bus.add(input) else bus.change(editing, input)
            when (r) {
                is Outcome.Done -> {
                    // A stop just added is the one to look at.
                    val added = r.value.stops.firstOrNull { it.id !in before }?.id
                    local.update { it.copy(editing = null, form = it.form.copy(busy = false), picked = if (editing == BusUiState.NEW && before.isNotEmpty()) added else it.picked) }
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
