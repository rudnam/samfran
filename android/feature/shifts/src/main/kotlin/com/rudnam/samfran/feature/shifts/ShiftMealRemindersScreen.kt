package com.rudnam.samfran.feature.shifts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.designsystem.component.CardTitle
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.PageTitle
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.component.Skeleton
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.MEAL_DAY_KEYS
import com.rudnam.samfran.core.model.MealKind
import com.rudnam.samfran.core.model.MealRule
import com.rudnam.samfran.core.model.MealRuleMode
import com.rudnam.samfran.core.model.defaultMealReminders
import com.rudnam.samfran.core.model.nextDayMeals
import com.rudnam.samfran.core.ui.SubPage
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.feature.shifts.navigation.ShiftsNavigation

@Composable
internal fun ShiftMealRemindersDestination(
    navigation: ShiftsNavigation,
    viewModel: ShiftMealRemindersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.setEnabled(true)
    }
    ShiftMealRemindersScreen(
        state = state,
        actions = viewModel,
        onToggle = { on ->
            val allowed = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (on && !allowed) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.setEnabled(on)
        },
        onBack = navigation::back,
    )
}

@Composable
fun ShiftMealRemindersScreen(
    state: ShiftMealRemindersUiState,
    actions: ShiftMealRemindersActions,
    onToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val colors = SamFranTheme.colors
    val defaults = remember { defaultMealReminders() }
    SubPage(onBack = onBack) {
        PageTitle(stringResource(R.string.meal_reminders))
        SamFranCard {
            CardTitle(stringResource(R.string.meal_this_device))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { onToggle(!state.enabledOnPhone) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.meal_reminders), style = MaterialTheme.typography.bodyLarge)
                    Hint(stringResource(R.string.meal_reminders_phone_hint))
                }
                Switch(
                    checked = state.enabledOnPhone,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accentInk),
                )
            }
            if (state.blocked) {
                ErrorText(stringResource(R.string.meal_allow_notifications))
            }
        }

        if (state.settings == null) {
            state.problem?.problem()?.let { ErrorText(it) } ?: Skeleton(rows = 4)
            return@SubPage
        }

        Hint(stringResource(R.string.meal_schedule_hint))
        for (shiftKey in MEAL_DAY_KEYS) {
            val dayMeals = state.meals[shiftKey] ?: continue
            val nextDays = nextDayMeals(shiftKey, dayMeals)
            val defaultDay = defaults[shiftKey]
            SamFranCard(spacing = 6) {
                CardTitle(shiftTitle(shiftKey))
                MealKind.entries.forEachIndexed { idx, meal ->
                    if (idx > 0) HorizontalDivider(color = colors.divider)
                    MealRuleRow(
                        meal = meal,
                        rule = dayMeals[meal],
                        defaultTime = defaultDay?.get(meal)?.time ?: "12:00",
                        isNextDay = meal in nextDays,
                        onChange = { actions.setRule(shiftKey, meal, it) },
                    )
                }
            }
        }

        state.problem?.problem()?.let { ErrorText(it) }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(stringResource(R.string.meal_reset_defaults), actions::resetDefaults, enabled = !state.busy)
            Hint(if (state.saved) stringResource(R.string.saved) else "", Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.save), actions::save, enabled = !state.busy)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealRuleRow(
    meal: MealKind,
    rule: MealRule,
    defaultTime: String,
    isNextDay: Boolean,
    onChange: (MealRule) -> Unit,
) {
    val colors = SamFranTheme.colors
    var timeOpen by remember { mutableStateOf(false) }
    val mealName = mealTitle(meal)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(mealName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        if (rule.mode == MealRuleMode.Fixed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isNextDay) {
                    Text(
                        stringResource(R.string.meal_next_day),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, colors.divider, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                ChipButton(rule.time) { timeOpen = true }
                IconButton(onClick = { onChange(MealRule(MealRuleMode.Off)) }) {
                    LineIcon(
                        SamFranIcons.close,
                        stringResource(R.string.meal_turn_off, mealName),
                        tint = colors.muted,
                        size = 18.dp,
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.meal_mode_off), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                LinkButton(stringResource(R.string.meal_set_time), onClick = {
                    onChange(MealRule(MealRuleMode.Fixed, time = defaultTime))
                })
            }
        }
    }

    if (timeOpen) {
        val parts = rule.time.split(':')
        val initHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 12
        val initMinute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val picker = rememberTimePickerState(initialHour = initHour, initialMinute = initMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timeOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        timeOpen = false
                        onChange(MealRule(MealRuleMode.Fixed, time = "%02d:%02d".format(picker.hour, picker.minute)))
                    },
                ) {
                    Text(stringResource(R.string.done))
                }
            },
            dismissButton = {
                TextButton(onClick = { timeOpen = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = { TimePicker(state = picker) },
        )
    }
}

@Composable
private fun ChipButton(label: String, onClick: () -> Unit) {
    val colors = SamFranTheme.colors
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.divider, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun shiftTitle(key: String): String = when (key) {
    "Early" -> stringResource(R.string.meal_shift_early_label)
    "Middle" -> stringResource(R.string.meal_shift_middle_label)
    "Late" -> stringResource(R.string.meal_shift_late_label)
    "Night" -> stringResource(R.string.meal_shift_night_label)
    else -> stringResource(R.string.meal_shift_off_label)
}

@Composable
private fun mealTitle(meal: MealKind): String = when (meal) {
    MealKind.Breakfast -> stringResource(R.string.meal_breakfast)
    MealKind.Lunch -> stringResource(R.string.meal_lunch)
    MealKind.Dinner -> stringResource(R.string.meal_dinner)
}
