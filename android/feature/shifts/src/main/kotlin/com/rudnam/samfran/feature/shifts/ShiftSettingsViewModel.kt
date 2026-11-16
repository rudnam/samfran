package com.rudnam.samfran.feature.shifts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.model.ShiftSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What just happened, said under the form or the calendar link. */
enum class SettingsDone { Saved, NewLink, LinkOff }

data class ShiftSettingsUiState(
    val settings: ShiftSettings? = null,
    val person: String = "",
    val onSheet: String = "",
    /** Letter and hours, as typed. */
    val lengths: List<Pair<String, String>> = emptyList(),
    val busy: Boolean = false,
    val done: SettingsDone? = null,
    val problem: Outcome<Any>? = null,
    /** The calendar link's full address, while it's on. */
    val feedUrl: String = "",
)

interface ShiftSettingsActions {
    fun setPerson(name: String)
    fun setOnSheet(name: String)
    fun setLength(index: Int, letter: String, hours: String)
    fun addLetter()
    fun removeLetter(index: Int)
    fun save()
    fun feed(on: Boolean)
}

@HiltViewModel
class ShiftSettingsViewModel @Inject constructor(
    private val shifts: ShiftsRepository,
) : ViewModel(), ShiftSettingsActions {
    private val _state = MutableStateFlow(ShiftSettingsUiState())
    val state: StateFlow<ShiftSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            shifts.settings.first()?.let(::take)
            when (val r = shifts.refreshSettings()) {
                is Outcome.Done -> take(r.value)
                else -> if (_state.value.settings == null) _state.update { it.copy(problem = r) }
            }
        }
    }

    private fun take(s: ShiftSettings) = _state.update {
        it.copy(
            settings = s,
            person = s.personName,
            onSheet = s.nameOnSheet,
            lengths = s.lengths.entries.sortedBy { e -> e.key }.map { (k, v) -> k to hours(v) },
            feedUrl = if (s.feedPath.isNotEmpty()) shifts.address(s.feedPath) else "",
        )
    }

    private fun hours(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    override fun setPerson(name: String) = _state.update { it.copy(person = name.take(40), done = null) }

    override fun setOnSheet(name: String) = _state.update { it.copy(onSheet = name.take(80), done = null) }

    override fun setLength(index: Int, letter: String, hours: String) = _state.update {
        it.copy(lengths = it.lengths.mapIndexed { i, l -> if (i == index) letter.take(1).uppercase() to hours else l }, done = null)
    }

    override fun addLetter() = _state.update { it.copy(lengths = it.lengths + ("" to "8")) }

    override fun removeLetter(index: Int) = _state.update { it.copy(lengths = it.lengths.filterIndexed { i, _ -> i != index }) }

    override fun save() {
        val s = _state.value
        val table = s.lengths.filter { it.first.isNotBlank() }.associate { (k, v) -> k.trim().uppercase() to (v.replace(',', '.').toDoubleOrNull() ?: 0.0) }
        run(SettingsDone.Saved) { shifts.saveSettings(s.person, s.onSheet, table) }
    }

    override fun feed(on: Boolean) = run(if (on && _state.value.feedUrl.isNotEmpty()) SettingsDone.NewLink else if (on) null else SettingsDone.LinkOff) { shifts.feed(on) }

    private fun run(done: SettingsDone?, block: suspend () -> Outcome<ShiftSettings>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, problem = null, done = null) }
        viewModelScope.launch {
            val r = block()
            if (r is Outcome.Done) take(r.value)
            _state.update { it.copy(busy = false, done = done.takeIf { r is Outcome.Done }, problem = r.takeIf { o -> o !is Outcome.Done }) }
        }
    }
}
