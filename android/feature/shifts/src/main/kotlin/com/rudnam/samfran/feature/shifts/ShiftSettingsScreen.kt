package com.rudnam.samfran.feature.shifts

import androidx.compose.foundation.layout.Arrangement
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
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.SubPage
import com.rudnam.samfran.core.ui.copyText
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.core.ui.serverText
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
 * shift is, and the calendar feed (web/src/features/shifts/ShiftSettingsPage.tsx).
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

        state.calendar?.takeIf { it.available }?.let { cal ->
            SamFranCard {
                CardTitle(stringResource(R.string.google_calendar))
                Text(stringResource(R.string.google_calendar_intro), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                if (cal.connected) {
                    Text(
                        stringResource(R.string.connected) + (cal.syncedAt?.let { " · " + stringResource(R.string.updated_when, Dates.moment(it, currentLocale)) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (cal.error.isNotBlank()) ErrorText(serverText(cal.error))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton(stringResource(if (state.calendarBusy) R.string.updating else R.string.update_now), actions::syncCalendar, enabled = !state.calendarBusy)
                        LinkButton(stringResource(R.string.disconnect), actions::disconnectCalendar, enabled = !state.calendarBusy, quiet = true)
                    }
                    Hint(stringResource(R.string.disconnect_hint))
                } else {
                    // Google doesn't allow connecting a calendar from inside an app.
                    Hint(stringResource(R.string.connect_in_browser))
                }
                state.calendarProblem?.problem()?.let { ErrorText(it) }
            }
        }
    }
}
