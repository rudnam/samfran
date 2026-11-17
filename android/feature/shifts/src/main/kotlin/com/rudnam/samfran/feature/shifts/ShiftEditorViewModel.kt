package com.rudnam.samfran.feature.shifts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.model.ShiftCodes
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

data class ShiftEditorUiState(
    val month: YearMonth,
    /** The month as saved, or as the photo reader found it; null while it loads. */
    val base: ShiftMonth? = null,
    val codes: Map<LocalDate, String> = emptyMap(),
    /** The day whose code the chips fill in. */
    val focused: LocalDate? = null,
    /** Who's with you, as typed by hand: the whole line, per day. Typed names stay whatever the code. */
    val typed: Map<LocalDate, String> = emptyMap(),
    /** The day whose names are open to edit; the chips then offer this month's names. */
    val editingWith: LocalDate? = null,
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    val saved: Boolean = false,
) {
    val dates: List<LocalDate> get() = (1..month.lengthOfMonth()).map { month.atDay(it) }

    /** Checking what the photo reader found, rather than fixing the saved month by hand. */
    val fromPhoto: Boolean get() = base?.fromPhoto == true

    /**
     * Who's on the same shift: what was typed, else as read or saved. The read
     * names only stay while the day's code is unchanged (a different code is a
     * different shift); typed names are the user's call and always stay.
     */
    fun withFor(date: LocalDate): List<String> {
        typed[date]?.let { return splitNames(it) }
        val d = base?.days?.firstOrNull { it.date == date } ?: return emptyList()
        return if (d.with.isNotEmpty() && ShiftCodes.normalize(d.code) == ShiftCodes.normalize(codes[date].orEmpty())) d.with else emptyList()
    }

    /** How many others on other shifts are there, as read or saved, while the day's code is unchanged. */
    fun thereCount(date: LocalDate): Int {
        val d = base?.days?.firstOrNull { it.date == date } ?: return 0
        return if (ShiftCodes.normalize(d.code) == ShiftCodes.normalize(codes[date].orEmpty())) d.others.size else 0
    }

    /** The line in a day's names box: as typed, else the names joined. */
    fun withText(date: LocalDate): String = typed[date] ?: withFor(date).joinToString(", ")

    /** The names on this month's other days, the most frequent first: the chips while [date]'s names are open. */
    fun namesBesides(date: LocalDate): List<String> =
        dates.filter { it != date }.flatMap(::withFor).groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /**
     * Names fixed while checking a scan, as read to corrected, so the next
     * scan gets them right. Only where the same number of names went as came,
     * so each pairs with the one it replaced.
     */
    fun corrections(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (d in base?.days.orEmpty()) {
            val now = withFor(d.date)
            val removed = d.with.filter { it !in now }
            val added = now.filter { it !in d.with }
            if (removed.size == added.size) removed.zip(added).forEach { (from, to) -> out[from] = to }
        }
        return out
    }

    fun meaning(date: LocalDate): ShiftCodes.Meaning? =
        codes[date]?.takeIf { it.isNotBlank() }?.let { ShiftCodes.explain(it, base?.settings?.lengths.orEmpty()) }
}

/** Names are separated by commas (a name itself has a space in it). */
internal fun splitNames(text: String): List<String> = text.split(',', '、', '，').map { it.trim() }.filter { it.isNotEmpty() }

interface ShiftEditorActions {
    fun setCode(date: LocalDate, code: String)
    fun focus(date: LocalDate?)
    /** A common code's chip: fills the focused day and moves to the next. */
    fun chip(code: String)
    /** Opens a day's names to edit, or closes them (null). */
    fun editWith(date: LocalDate?)
    fun setWith(date: LocalDate, text: String)
    /** A name's chip: adds it to the open day, or takes it off if it's there. */
    fun toggleName(name: String)
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

    private fun take(m: ShiftMonth) = _state.update {
        it.copy(base = m, codes = m.days.associate { d -> d.date to d.code }, focused = it.focused ?: month.atDay(1))
    }

    override fun setCode(date: LocalDate, code: String) = _state.update { it.copy(codes = it.codes + (date to code.take(12))) }

    // A code box taking the focus closes the names.
    override fun focus(date: LocalDate?) = _state.update { it.copy(focused = date, editingWith = null) }

    override fun editWith(date: LocalDate?) = _state.update { it.copy(editingWith = date, focused = date ?: it.focused) }

    override fun setWith(date: LocalDate, text: String) = _state.update { it.copy(typed = it.typed + (date to text.take(400))) }

    override fun toggleName(name: String) = _state.update { s ->
        val day = s.editingWith ?: return@update s
        val now = s.withFor(day)
        val next = if (name in now) now - name else now + name
        s.copy(typed = s.typed + (day to next.joinToString(", ")))
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
            val with = s.dates.associateWith { s.withFor(it) }.filterValues { it.isNotEmpty() }
            // Who else was there, as read or saved: the server keeps those who overlap the day's shift as saved.
            val others = s.base?.days.orEmpty().associate { d -> d.date to d.others.map { it.coworker } }.filterValues { it.isNotEmpty() }
            val r = shifts.save(month, codes, with, if (s.fromPhoto) s.corrections() else emptyMap(), others)
            if (r is Outcome.Done) reminders.sync(now = true) // the phone asks "Start bus alarm?" at the new times
            _state.update { it.copy(busy = false, saved = r is Outcome.Done, problem = r.takeIf { o -> o !is Outcome.Done }) }
        }
    }
}
