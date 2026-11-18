package com.rudnam.samfran.feature.shifts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.model.ShiftCodes
import com.rudnam.samfran.core.model.ShiftCoworker
import com.rudnam.samfran.core.model.ShiftKind
import com.rudnam.samfran.core.model.ShiftMonth
import com.rudnam.samfran.feature.shifts.navigation.ShiftEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** Someone at work around a day, in the editor. [read] is the name as the photo reader gave it, to learn from a fix. */
data class PersonRow(val key: Long, val name: String, val code: String, val dayOffset: Int = 0, val read: String? = null) {
    val coworker: ShiftCoworker get() = ShiftCoworker(name.trim(), ShiftCodes.normalize(code), dayOffset)
}

data class ShiftEditorUiState(
    val month: YearMonth,
    /** The month as saved, or as the photo reader found it; null while it loads. */
    val base: ShiftMonth? = null,
    val codes: Map<LocalDate, String> = emptyMap(),
    /** The day whose code the chips fill in. */
    val focused: LocalDate? = null,
    /** Everyone at work around each day, as read or saved, and as fixed here. */
    val people: Map<LocalDate, List<PersonRow>> = emptyMap(),
    /** The day whose people are open, and who was listed first when it opened (so nobody jumps away while edited). */
    val openDay: LocalDate? = null,
    val shown: Set<Long> = emptySet(),
    /** 締め申請通知 this month, as dates in it; null until known. Sent only once [closingChanged]. */
    val closing: List<Int>? = null,
    val closingChanged: Boolean = false,
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    val saved: Boolean = false,
) {
    val dates: List<LocalDate> get() = (1..month.lengthOfMonth()).map { month.atDay(it) }

    /** Checking what the photo reader found, rather than fixing the saved month by hand. */
    val fromPhoto: Boolean get() = base?.fromPhoto == true

    private val lengths: Map<String, Double> get() = base?.settings?.lengths.orEmpty()

    /** The day's shift, in hours from its start; null for a day off or an unknown length. */
    fun hours(date: LocalDate): Pair<Double, Double>? = ShiftCodes.hoursAround(codes[date].orEmpty(), 0, lengths)

    /** Whether someone is there during the day's shift (a shift without hours: on its exact code). */
    fun isThere(date: LocalDate, p: PersonRow): Boolean {
        if (p.name.isBlank()) return false
        val h = hours(date) ?: return p.dayOffset == 0 && ShiftCodes.normalize(p.code) == ShiftCodes.normalize(codes[date].orEmpty())
        return ShiftCodes.hoursAround(p.code, p.dayOffset, lengths)?.let { ShiftCodes.overlaps(it, h) } == true
    }

    /** Those there during the day's shift, as the code now is. */
    fun there(date: LocalDate): List<PersonRow> = people[date].orEmpty().filter { isThere(date, it) }

    /** Whether a day is a shift (so it can have people). */
    fun working(date: LocalDate): Boolean = meaning(date)?.kind == ShiftKind.Work

    /** Names fixed while checking a scan, as read to corrected, so the next scan gets them right. */
    fun corrections(): Map<String, String> = buildMap {
        for (p in people.values.flatten()) {
            val to = p.name.trim()
            if (p.read != null && to.isNotEmpty() && to != p.read) put(p.read, to)
        }
    }

    /** The usual closing days as they fall in this month. */
    val usualClosing: List<Int> get() = base?.usualClosing.orEmpty()

    /** This month's closing days are the usual ones. */
    val closingIsUsual: Boolean get() = closing == usualClosing

    fun meaning(date: LocalDate): ShiftCodes.Meaning? =
        codes[date]?.takeIf { it.isNotBlank() }?.let { ShiftCodes.explain(it, base?.settings?.lengths.orEmpty()) }
}

interface ShiftEditorActions {
    fun setCode(date: LocalDate, code: String)
    fun focus(date: LocalDate?)
    /** A common code's chip: fills the focused day and moves to the next. */
    fun chip(code: String)
    /** Opens a day's people, or closes them (null). */
    fun openPeople(date: LocalDate?)
    fun setPersonName(key: Long, name: String)
    fun setPersonCode(key: Long, code: String)
    /** Which day their cell is on: the same day, then the day before (a night shift), then the next day. */
    fun nextPersonDay(key: Long)
    fun removePerson(key: Long)
    /** Adds someone to the open day, on its own code. */
    fun addPerson()
    /** This month's closing day [index] becomes [day] (a date in the month). */
    fun setClosing(index: Int, day: Int)
    fun addClosing()
    fun removeClosing(index: Int)
    /** Back to the usual days from the settings. */
    fun usualClosing()
    fun save()
}

@HiltViewModel
class ShiftEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val shifts: ShiftsRepository,
    private val reminders: Reminders,
) : ViewModel(), ShiftEditorActions {
    private val route = savedState.toRoute<ShiftEditRoute>()
    private val month = YearMonth.parse(route.month)
    private val _state = MutableStateFlow(ShiftEditorUiState(month))
    val state: StateFlow<ShiftEditorUiState> = _state.asStateFlow()

    init {
        val read = if (route.fromPhoto) shifts.takePendingRead(month) else null
        if (read != null) {
            take(read)
            // A photo read doesn't carry the month's closing days; the saved month does.
            viewModelScope.launch {
                val saved = (shifts.refresh(month) as? Outcome.Done)?.value ?: shifts.month(month).first()
                _state.update { it.copy(closing = it.closing ?: (saved ?: read).closing) }
            }
        } else {
            viewModelScope.launch {
                when (val r = shifts.refresh(month)) {
                    is Outcome.Done -> take(r.value)
                    // Offline: the phone's copy, if it has one.
                    else -> shifts.month(month).first().let { if (it != null) take(it) else _state.update { s -> s.copy(problem = r) } }
                }
            }
        }
    }

    private var nextKey = 1L

    private fun take(m: ShiftMonth) = _state.update {
        it.copy(
            base = m,
            codes = m.days.associate { d -> d.date to d.code },
            people = m.days.associate { d ->
                d.date to d.people.map { p -> PersonRow(nextKey++, p.name, p.code, p.dayOffset, read = p.name.takeIf { m.fromPhoto }) }
            },
            focused = it.focused ?: month.atDay(1),
            closing = if (m.fromPhoto) it.closing else m.closing,
        )
    }

    private fun closing(change: (List<Int>) -> List<Int>) = _state.update {
        it.copy(closing = change(it.closing.orEmpty()).distinct().sorted(), closingChanged = true)
    }

    override fun setClosing(index: Int, day: Int) = closing { c -> c.mapIndexed { i, d -> if (i == index) day else d } }

    override fun addClosing() = closing { c -> c + ((1..month.lengthOfMonth()).firstOrNull { it !in c } ?: return@closing c) }

    override fun removeClosing(index: Int) = closing { c -> c.filterIndexed { i, _ -> i != index } }

    override fun usualClosing() = closing { _state.value.usualClosing }

    override fun setCode(date: LocalDate, code: String) = _state.update { it.copy(codes = it.codes + (date to code.take(12))) }

    override fun focus(date: LocalDate?) = _state.update { it.copy(focused = date) }

    override fun openPeople(date: LocalDate?) = _state.update { s ->
        s.copy(openDay = date, shown = date?.let { d -> s.there(d).map { it.key }.toSet() }.orEmpty())
    }

    /** Changes someone on the open day. */
    private fun person(key: Long, change: (PersonRow) -> PersonRow?) = _state.update { s ->
        val day = s.openDay ?: return@update s
        s.copy(people = s.people + (day to s.people[day].orEmpty().mapNotNull { if (it.key == key) change(it) else it }))
    }

    override fun setPersonName(key: Long, name: String) = person(key) { it.copy(name = name.take(40)) }

    override fun setPersonCode(key: Long, code: String) = person(key) { it.copy(code = code.take(12)) }

    override fun nextPersonDay(key: Long) = person(key) { it.copy(dayOffset = if (it.dayOffset == 0) -1 else if (it.dayOffset < 0) 1 else 0) }

    override fun removePerson(key: Long) = person(key) { null }

    override fun addPerson() = _state.update { s ->
        val day = s.openDay ?: return@update s
        val added = PersonRow(nextKey++, "", ShiftCodes.normalize(s.codes[day].orEmpty()))
        s.copy(people = s.people + (day to s.people[day].orEmpty() + added), shown = s.shown + added.key)
    }

    override fun chip(code: String) = _state.update { s ->
        val day = s.focused ?: return@update s
        val next = day.plusDays(1).takeIf { YearMonth.from(it) == month }
        s.copy(codes = s.codes + (day to code), focused = next ?: day)
    }

    override fun save() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val s = _state.value
            val codes = s.codes.mapValues { ShiftCodes.normalize(it.value) }
            // Everyone, there during the shift or not: the server works out who's shown.
            val people = s.people.mapValues { (_, rows) -> rows.filter { it.name.isNotBlank() }.map { it.coworker } }.filterValues { it.isNotEmpty() }
            val r = shifts.save(month, codes, people, if (s.fromPhoto) s.corrections() else emptyMap(), s.closing.takeIf { s.closingChanged })
            if (r is Outcome.Done) reminders.sync(now = true) // the phone asks "Start bus alarm?" at the new times
            _state.update { it.copy(busy = false, saved = r is Outcome.Done, problem = r.takeIf { o -> o !is Outcome.Done }) }
        }
    }
}
