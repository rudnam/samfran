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
import com.rudnam.samfran.core.model.Distances
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.model.ShiftKinds
import com.rudnam.samfran.core.model.SoundAfter
import com.rudnam.samfran.core.model.StopKind
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

/** A stop as its page shows it, before it's added. */
data class StopDraft(
    val name: String,
    val lat: Double,
    val lng: Double,
    val kind: StopKind,
    val meters: Int = Distances.default(kind),
    val buzz: Buzz = Buzz.Strong,
    val shifts: List<String> = emptyList(),
    val ask: Boolean = false,
) {
    fun input() = StopInput(name, lat, lng, meters, shifts, ask, kind, buzz)
}

/** A place picked for a stop: from the search, a link, or where the phone is. */
data class Picked(val name: String, val lat: Double, val lng: Double, val kind: StopKind)

/** Finding where a stop is: by name (and town) from the search; or, folded away, a Google Maps link or where the phone is. */
data class WhereSearch(
    val query: String = "",
    val found: List<FoundStop>? = null,
    val failed: Boolean = false,
    /** "Can't find it? Other ways" is open. */
    val otherWays: Boolean = false,
    val link: String = "",
    /** From a link or where the phone is: the place, with its name and kind to be said. */
    val place: Picked? = null,
    val busy: Boolean = false,
    val problem: StopProblem? = null,
)

/** What went wrong while finding where a stop is. */
sealed interface StopProblem {
    data class Server(val outcome: Outcome<Any>) : StopProblem
    /** The link doesn't say where the stop is. */
    data object NoPosition : StopProblem
    /** The phone can't tell where it is. */
    data object NoHere : StopProblem
}

/** One of a stop's choices, opened from its row. */
enum class StopSheet { Distance, Buzz, Shifts }

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
    /** A stop's page is open: a saved one's id, or [NEW] for one being added. */
    val editing: Long? = null,
    /** The new stop, once its place is picked; until then the search shows. */
    val draft: StopDraft? = null,
    /** The search for where the stop is shows (for a new one, or to move a saved one). */
    val where: Boolean = false,
    val search: WhereSearch = WhereSearch(),
    /** One of the stop's choices is open. */
    val sheet: StopSheet? = null,
    /** Its question is on, but notifications are off for the app: say how to allow them. */
    val askBlocked: Boolean = false,
    /** A change is being saved. */
    val saving: Boolean = false,
    /** Settings are open: the stops, and the sound. A stop opened from there goes back there. */
    val settings: Boolean = false,
    /** The stop just added, marked in Settings. */
    val added: Long? = null,
    val problem: Outcome<Any>? = null,
    val locationDenied: Boolean = false,
    /** Opened from "Start the ride alarm?": the trip starts once the stop is known. */
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
    /** The saved stop whose page is open. */
    val editingStop: BusStop? get() = editing?.takeIf { it != NEW }?.let { stops?.byId(it) }
    /** The stop whose page is open, as its page shows it. */
    val pageStop: StopDraft?
        get() = draft?.takeIf { editing == NEW } ?: editingStop?.let { StopDraft(it.name, it.lat, it.lng, it.kind, it.alertMeters, it.buzz, it.shifts, it.ask) }
    /** The stop's page shows (not the search): a saved one, or a new one picked. */
    val stopPage: Boolean get() = !where && (editingStop != null || (editing == NEW && draft != null))
    /** The search shows: moving a stop, adding one, or the first. */
    val searching: Boolean get() = where || (editing == NEW && draft == null) || (stops != null && !ready && draft == null)
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
    /** "‹": back a page (a sheet closes first). */
    fun back()
    /** The sound when the buzz isn't stopped: on (after a minute) or off. Saved straight away. */
    fun setSound(on: Boolean)
    /** "Hear the sound": a few seconds of it. */
    fun hearSound()
    /** Where to get off, for this ride. */
    fun pickStop(id: Long)
    /** "Other stop" is done with. */
    fun endChoosing()
    /** A saved stop's page. */
    fun edit(id: Long)
    /** "+ Add a stop": the search, then the new stop's page. */
    fun add()
    /** "Where", on a stop's page: the search, to move it. */
    fun openWhere()
    fun openSheet(sheet: StopSheet)
    fun closeSheet()
    fun setMeters(meters: Int)
    fun setBuzz(buzz: Buzz)
    fun setShifts(kinds: List<String>)
    fun toggleAsk()
    /** "Add stop", on a new stop's page. */
    fun addStop()
    fun removeStop()
    fun setQuery(query: String)
    fun pick(stop: FoundStop)
    fun setOtherWays(open: Boolean)
    fun setLink(link: String)
    fun useLink()
    fun useHere()
    fun setPlaceName(name: String)
    fun setPlaceKind(kind: StopKind)
    /** "Use this": the place from a link or where the phone is. */
    fun usePlace()
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
                is Outcome.Done -> {}
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

    /** The trip asked for by "Start the ride alarm?" has been handled (started, or not possible). */
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
            alarm.test(stop?.name.orEmpty(), stop?.buzz ?: Buzz.Strong)
        }
    }

    override fun endPractice() {
        practiceRun?.cancel()
        if (local.value.practice == Practice.Buzzing) alarm.stopBuzz()
        local.update { it.copy(practice = null) }
    }

    override fun openSettings() = local.update { it.copy(settings = true, added = null) }

    override fun back() = local.update { s ->
        when {
            s.sheet != null -> s.copy(sheet = null)
            // From moving a stop, back to its page.
            s.where && s.editing != BusUiState.NEW -> s.copy(where = false)
            // From a new stop's page, back to the search.
            s.editing == BusUiState.NEW && s.draft != null -> s.copy(draft = null, search = WhereSearch())
            s.editing != null -> s.copy(editing = null, where = false, draft = null, askBlocked = false)
            else -> s.copy(settings = false)
        }
    }

    override fun setSound(on: Boolean) {
        val seconds = if (on) SoundAfter.DEFAULT else 0
        if (state.value.stops?.soundAfter == seconds || local.value.saving) return
        local.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            val r = bus.setSoundAfter(seconds)
            local.update { it.copy(saving = false, problem = r.takeIf { o -> o !is Outcome.Done }) }
        }
    }

    override fun hearSound() = alarm.hearSound()

    override fun pickStop(id: Long) {
        if (state.value.trip.running) return
        endPractice()
        local.update { it.copy(picked = id) }
    }

    override fun endChoosing() = local.update { it.copy(choosing = false) }

    override fun edit(id: Long) = local.update { it.copy(editing = id, where = false, draft = null, sheet = null, askBlocked = false, added = null) }

    override fun add() = local.update { it.copy(editing = BusUiState.NEW, where = false, draft = null, search = WhereSearch(), added = null) }

    override fun openWhere() = local.update { it.copy(where = true, search = WhereSearch()) }

    override fun openSheet(sheet: StopSheet) = local.update { it.copy(sheet = sheet) }

    override fun closeSheet() = local.update { it.copy(sheet = null) }

    /**
     * A change to the stop whose page is open: a new one's draft changes;
     * a saved one is saved straight away.
     */
    private fun changeStop(f: (StopDraft) -> StopDraft) {
        val s = local.value
        s.draft?.takeIf { s.editing == BusUiState.NEW }?.let { d ->
            local.update { it.copy(draft = f(d), sheet = null) }
            return
        }
        val stop = state.value.editingStop ?: return
        val changed = f(state.value.pageStop ?: return)
        local.update { it.copy(sheet = null, saving = true, problem = null) }
        viewModelScope.launch {
            val r = bus.change(stop.id, changed.input())
            local.update { it.copy(saving = false, problem = r.takeIf { o -> o !is Outcome.Done }) }
            if (r is Outcome.Done) reminders.sync(now = true) // the question names the stop
        }
    }

    override fun setMeters(meters: Int) = changeStop { it.copy(meters = meters) }

    override fun setBuzz(buzz: Buzz) = changeStop { it.copy(buzz = buzz) }

    override fun setShifts(kinds: List<String>) = changeStop { d -> d.copy(shifts = ShiftKinds.filter { it in kinds }) }

    override fun toggleAsk() {
        val on = !(state.value.pageStop?.ask ?: return)
        local.update { it.copy(askBlocked = on && !reminders.notificationsAllowed()) }
        changeStop { it.copy(ask = on) }
    }

    override fun addStop() {
        val d = local.value.draft ?: return
        if (local.value.saving) return
        val first = !state.value.ready
        val before = state.value.stops?.stops.orEmpty().map { it.id }.toSet()
        local.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            when (val r = bus.add(d.input())) {
                is Outcome.Done -> {
                    val id = r.value.stops.firstOrNull { it.id !in before }?.id
                    // The first goes to the ride; another back to Settings, marked.
                    local.update { it.copy(saving = false, editing = null, draft = null, where = false, added = if (first) null else id, settings = it.settings && !first) }
                    reminders.sync(now = true)
                }
                else -> local.update { it.copy(saving = false, problem = r) }
            }
        }
    }

    override fun removeStop() {
        val id = state.value.editingStop?.id ?: return
        local.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            when (val r = bus.remove(id)) {
                is Outcome.Done -> {
                    local.update { it.copy(saving = false, editing = null, picked = it.picked?.takeIf { p -> p != id }, settings = it.settings && r.value.stops.isNotEmpty()) }
                    reminders.sync(now = true)
                }
                else -> local.update { it.copy(saving = false, problem = r) }
            }
        }
    }

    private fun search(f: (WhereSearch) -> WhereSearch) = local.update { it.copy(search = f(it.search)) }

    private var searching: Job? = null

    override fun setQuery(query: String) {
        val q = query.take(80)
        search { it.copy(query = q) }
        // A quarter second after the last key, two characters or more.
        searching?.cancel()
        if (q.trim().codePointCount(0, q.trim().length) < 2) {
            search { it.copy(found = null, failed = false) }
            return
        }
        searching = viewModelScope.launch {
            delay(250)
            val near = state.value.editingStop?.let { it.lat to it.lng } ?: state.value.stops?.stops?.firstOrNull()?.let { it.lat to it.lng }
            val r = bus.search(q, near)
            search {
                when (r) {
                    is Outcome.Done -> it.copy(found = r.value, failed = false)
                    else -> it.copy(failed = true)
                }
            }
        }
    }

    override fun pick(stop: FoundStop) {
        searching?.cancel()
        place(Picked(stop.name, stop.lat, stop.lng, stop.kind))
    }

    /** The place for the stop is chosen: a new one's page, filled in; or a saved one moved there. */
    private fun place(p: Picked) {
        if (local.value.editing == BusUiState.NEW || !state.value.ready) {
            local.update { it.copy(editing = BusUiState.NEW, where = false, draft = StopDraft(p.name, p.lat, p.lng, p.kind)) }
            return
        }
        local.update { it.copy(where = false) }
        changeStop { d ->
            d.copy(name = p.name, lat = p.lat, lng = p.lng, kind = p.kind, meters = if (d.meters in Distances.choices(p.kind)) d.meters else Distances.default(p.kind))
        }
    }

    override fun setOtherWays(open: Boolean) = search { it.copy(otherWays = open) }

    override fun setLink(link: String) = search { it.copy(link = link) }

    override fun useLink() {
        val link = local.value.search.link.trim()
        if (link.isEmpty() || local.value.search.busy) return
        search { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val r = food.lookup(link, position = true)
            search { f ->
                when {
                    r !is Outcome.Done -> f.copy(busy = false, problem = StopProblem.Server(r))
                    r.value.lat == null || r.value.lng == null -> f.copy(busy = false, problem = StopProblem.NoPosition)
                    else -> f.copy(busy = false, place = Picked(stopNameFromMaps(r.value.name).ifBlank { f.query }, r.value.lat!!, r.value.lng!!, StopKind.Bus))
                }
            }
        }
    }

    override fun useHere() {
        search { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val here = alarm.here()
            search { if (here == null) it.copy(busy = false, problem = StopProblem.NoHere) else it.copy(busy = false, place = Picked(it.query, here.first, here.second, StopKind.Bus)) }
        }
    }

    override fun setPlaceName(name: String) = search { it.copy(place = it.place?.copy(name = name.take(80))) }

    override fun setPlaceKind(kind: StopKind) = search { it.copy(place = it.place?.copy(kind = kind)) }

    override fun usePlace() {
        val p = local.value.search.place ?: return
        place(p.copy(name = p.name.trim()))
    }

    companion object {
        /** How long the pretend ride of a practice run takes, before it buzzes. */
        const val PRACTICE_MS = 6000L
        private const val TICK_MS = 100L
    }
}
