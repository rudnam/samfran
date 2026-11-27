package com.rudnam.samfran.feature.shifts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.model.DayMeals
import com.rudnam.samfran.core.model.MealKind
import com.rudnam.samfran.core.model.MealRule
import com.rudnam.samfran.core.model.ShiftSettings
import com.rudnam.samfran.core.model.defaultMealReminders
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShiftMealRemindersUiState(
    val settings: ShiftSettings? = null,
    val meals: Map<String, DayMeals> = defaultMealReminders(),
    val enabledOnPhone: Boolean = false,
    val blocked: Boolean = false,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val problem: Outcome<Any>? = null,
)

interface ShiftMealRemindersActions {
    fun setEnabled(on: Boolean)
    fun setRule(shiftKey: String, meal: MealKind, rule: MealRule)
    fun resetDefaults()
    fun save()
}

@HiltViewModel
class ShiftMealRemindersViewModel @Inject constructor(
    private val shifts: ShiftsRepository,
    private val reminders: Reminders,
) : ViewModel(), ShiftMealRemindersActions {
    private val local = MutableStateFlow(ShiftMealRemindersUiState())

    val state: StateFlow<ShiftMealRemindersUiState> = combine(local, reminders.mealReminders) { s, on ->
        s.copy(enabledOnPhone = on)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            shifts.settings.first()?.let(::take)
            when (val r = shifts.refreshSettings()) {
                is Outcome.Done -> take(r.value)
                else -> if (local.value.settings == null) local.update { it.copy(problem = r) }
            }
        }
    }

    private fun take(s: ShiftSettings) = local.update {
        it.copy(settings = s, meals = s.mealReminders)
    }

    override fun setEnabled(on: Boolean) {
        local.update { it.copy(blocked = false) }
        viewModelScope.launch {
            val now = reminders.setMealReminders(on)
            local.update { it.copy(blocked = on && !now) }
        }
    }

    override fun setRule(shiftKey: String, meal: MealKind, rule: MealRule) {
        local.update { s ->
            val current = s.meals[shiftKey] ?: return@update s
            s.copy(
                meals = s.meals + (shiftKey to current.with(meal, rule)),
                saved = false,
                problem = null,
            )
        }
    }

    override fun resetDefaults() {
        local.update { it.copy(meals = defaultMealReminders(), saved = false, problem = null) }
    }

    override fun save() {
        val current = local.value.settings ?: return
        if (local.value.busy) return
        val mealsToSave = local.value.meals
        local.update { it.copy(busy = true, saved = false, problem = null) }
        viewModelScope.launch {
            val r = shifts.saveSettings(
                current.personName,
                current.nameOnSheet,
                current.lengths,
                current.closingDays,
                mealsToSave,
            )
            if (r is Outcome.Done) {
                take(r.value)
                reminders.sync(now = true)
            }
            local.update {
                it.copy(
                    busy = false,
                    saved = r is Outcome.Done,
                    problem = r.takeIf { o -> o !is Outcome.Done },
                )
            }
        }
    }
}
