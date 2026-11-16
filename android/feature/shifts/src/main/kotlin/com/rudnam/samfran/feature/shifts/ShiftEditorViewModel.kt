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
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    val saved: Boolean = false,
) {
    val dates: List<LocalDate> get() = (1..month.lengthOfMonth()).map { month.atDay(it) }

    /** Checking what the photo reader found, rather than fixing the saved month by hand. */
    val fromPhoto: Boolean get() = base?.fromPhoto == true

    fun meaning(date: LocalDate): ShiftCodes.Meaning? =
        codes[date]?.takeIf { it.isNotBlank() }?.let { ShiftCodes.explain(it, base?.settings?.lengths.orEmpty()) }
}

interface ShiftEditorActions {
    fun setCode(date: LocalDate, code: String)
    fun focus(date: LocalDate?)
    /** A common code's chip: fills the focused day and moves to the next. */
    fun chip(code: String)
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

    override fun focus(date: LocalDate?) = _state.update { it.copy(focused = date) }

    override fun chip(code: String) = _state.update { s ->
        val day = s.focused ?: return@update s
        val next = day.plusDays(1).takeIf { YearMonth.from(it) == month }
        s.copy(codes = s.codes + (day to code), focused = next ?: day)
    }

    override fun save() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val codes = _state.value.codes.mapValues { ShiftCodes.normalize(it.value) }
            val r = shifts.save(month, codes)
            if (r is Outcome.Done) reminders.sync(now = true) // the phone asks "Start bus alarm?" at the new times
            _state.update { it.copy(busy = false, saved = r is Outcome.Done, problem = r.takeIf { o -> o !is Outcome.Done }) }
        }
    }
}
