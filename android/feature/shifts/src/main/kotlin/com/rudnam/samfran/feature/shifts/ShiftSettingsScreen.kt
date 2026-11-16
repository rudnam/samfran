package com.rudnam.samfran.feature.shifts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.rudnam.samfran.core.ui.SubPage
import com.rudnam.samfran.core.ui.copyText
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.core.ui.shareText
import com.rudnam.samfran.feature.shifts.navigation.ShiftsNavigation

@Composable
internal fun ShiftSettingsDestination(navigation: ShiftsNavigation, viewModel: ShiftSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ShiftSettingsScreen(
        state,
        viewModel,
        onBack = navigation::back,
        onCopy = { context.copyText(it) },
        onShare = { context.shareText(it) },
    )
}

/**
 * Whose shifts, how to find their row on the sheet, how long each letter's
 * shift is, and the calendar link (web/src/features/shifts/ShiftSettingsPage.tsx).
 */
@Composable
fun ShiftSettingsScreen(
    state: ShiftSettingsUiState,
    actions: ShiftSettingsActions,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val colors = SamFranTheme.colors
    SubPage(onBack = onBack) {
        PageTitle(stringResource(R.string.shift_settings))
        if (state.settings == null) {
            state.problem?.problem()?.let { ErrorText(it) } ?: Skeleton(rows = 3)
            return@SubPage
        }
        SamFranCard(spacing = 14) {
            OutlinedTextField(
                value = state.person,
                onValueChange = actions::setPerson,
                label = { Text(stringResource(R.string.whose)) },
                placeholder = { Text("Sam") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.onSheet,
                onValueChange = actions::setOnSheet,
                label = { Text(stringResource(R.string.name_on_sheet)) },
                placeholder = { Text("オトル サマンタ") },
                supportingText = { Text(stringResource(R.string.name_on_sheet_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shift_lengths), style = MaterialTheme.typography.labelLarge)
                Hint(stringResource(R.string.shift_lengths_hint))
                state.lengths.forEachIndexed { i, (letter, hours) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = letter,
                            onValueChange = { actions.setLength(i, it, hours) },
                            label = { Text(stringResource(R.string.letter)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.width(88.dp),
                        )
                        OutlinedTextField(
                            value = hours,
                            onValueChange = { actions.setLength(i, letter, it) },
                            label = { Text(stringResource(R.string.hours_for, letter.ifBlank { "?" })) },
                            suffix = { Text(stringResource(R.string.h)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { actions.removeLetter(i) }) { LineIcon(SamFranIcons.close, stringResource(R.string.remove_letter, letter), tint = colors.muted) }
                    }
                }
                LinkButton("+ " + stringResource(R.string.letter), actions::addLetter)
            }
            ClosingDaysField(state.closing, actions)
            state.problem?.problem()?.let { ErrorText(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(if (state.done == SettingsDone.Saved) stringResource(R.string.saved) else "", Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.save), actions::save, enabled = !state.busy)
            }
        }

        SamFranCard {
            CardTitle(stringResource(R.string.in_your_calendar))
            Text(stringResource(R.string.calendar_intro), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            if (state.feedUrl.isNotEmpty()) {
                var copied by rememberSaveable(state.feedUrl) { mutableStateOf(false) }
                OutlinedTextField(
                    value = state.feedUrl,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.calendar_link)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(if (copied) R.string.copied else R.string.copy), {
                        onCopy(state.feedUrl)
                        copied = true
                    }, icon = SamFranIcons.copy)
                    SecondaryButton(stringResource(R.string.share), { onShare(state.feedUrl) }, icon = SamFranIcons.share)
                }
                Hint(stringResource(R.string.calendar_link_private))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(stringResource(R.string.new_link), { actions.feed(true) }, enabled = !state.busy)
                    LinkButton(stringResource(R.string.turn_off), { actions.feed(false) }, enabled = !state.busy, quiet = true)
                }
            } else {
                PrimaryButton(stringResource(R.string.turn_on_link), { actions.feed(true) }, enabled = !state.busy)
            }
            when (state.done) {
                SettingsDone.NewLink -> Hint(stringResource(R.string.made_new_link))
                SettingsDone.LinkOff -> Hint(stringResource(R.string.link_off))
                else -> {}
            }
        }

    }
}

/** 締め申請通知: the days of the month the hours are sent in, each picked from a menu (31 is the last day). */
@Composable
private fun ClosingDaysField(days: List<Int>, actions: ShiftSettingsActions) {
    val colors = SamFranTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.hours_due), style = MaterialTheme.typography.labelLarge)
        Hint(stringResource(R.string.closing_days_hint))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            days.forEachIndexed { i, day ->
                var open by rememberSaveable { mutableStateOf(false) }
                val name = closingDayName(day)
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, colors.divider, RoundedCornerShape(12.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Text(
                            name,
                            Modifier
                                .clickable(onClickLabel = stringResource(R.string.closing_day_n, i + 1)) { open = true }
                                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            for (n in 1..31) {
                                DropdownMenuItem(text = { Text(closingDayName(n)) }, onClick = { actions.setClosing(i, n); open = false })
                            }
                        }
                    }
                    IconButton(onClick = { actions.removeClosing(i) }) { LineIcon(SamFranIcons.close, stringResource(R.string.remove_closing_day, name), tint = colors.muted) }
                }
            }
            if (days.size < 10) LinkButton("+ " + stringResource(R.string.day), actions::addClosing)
        }
    }
}

/** 15日 / 15th, or the last day for 31. */
@Composable
private fun closingDayName(n: Int): String = if (n >= 31) stringResource(R.string.last_day) else stringResource(R.string.day_of_month, n, if (currentLocale.language == "en") ordinalSuffix(n) else "")

private fun ordinalSuffix(n: Int): String = when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}
