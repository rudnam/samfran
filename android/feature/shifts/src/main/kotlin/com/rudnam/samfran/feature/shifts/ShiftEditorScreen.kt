package com.rudnam.samfran.feature.shifts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.Notice
import com.rudnam.samfran.core.designsystem.component.PageTitle
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.Skeleton
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.ShiftCodes
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.SubPage
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.feature.shifts.navigation.ShiftsNavigation
import com.rudnam.samfran.feature.shifts.navigation.ShiftsRoute

@Composable
internal fun ShiftEditorDestination(navigation: ShiftsNavigation, viewModel: ShiftEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) navigation.replace(ShiftsRoute(state.month.toString())) }
    ShiftEditorScreen(state, viewModel, onBack = navigation::back)
}

/**
 * Checking a month before it's saved (web/src/features/shifts/ShiftEditor.tsx):
 * what the photo reader found, or the saved month to fix by hand. Every day
 * has a box for its code and shows what the code means as it's typed. The
 * common codes are one tap away: a chip fills the focused day and moves on.
 */
@Composable
fun ShiftEditorScreen(state: ShiftEditorUiState, actions: ShiftEditorActions, onBack: () -> Unit) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val month = Dates.month(state.month, locale)
    val requesters = remember(state.month) { state.dates.associateWith { FocusRequester() } }
    // The chips move the focus on: the next day's box follows.
    LaunchedEffect(state.focused) { state.focused?.let { requesters[it] }?.let { runCatching { it.requestFocus() } } }
    SubPage(
        onBack = onBack,
        bottomBar = if (state.base == null) null else ({
            Column {
                HorizontalDivider(color = colors.border)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (code in ShiftCodes.common) {
                        val meaning = ShiftCodes.explain(code, state.base.settings.lengths)
                        FilterChip(
                            selected = state.focused?.let { state.codes[it] } == code,
                            onClick = { actions.chip(code) },
                            label = { Text(code, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.a.bg),
                            modifier = Modifier.semantics { contentDescription = "$code, ${ShiftCodes.labelOf(meaning.label)}" },
                        )
                    }
                }
                PrimaryButton(
                    stringResource(R.string.save_month, month),
                    actions::save,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    enabled = !state.busy,
                )
            }
        }),
    ) {
        val base = state.base
        PageTitle(if (state.fromPhoto) stringResource(R.string.check_shifts) else month)
        if (base == null) {
            state.problem?.let { ErrorText(problemText(it)) } ?: Skeleton(rows = 6)
            return@SubPage
        }
        if (state.fromPhoto) {
            Notice {
                Text(
                    stringResource(R.string.read_row, base.nameAsRead?.ifBlank { null } ?: base.settings.nameOnSheet, month),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (base.notes.isNotBlank()) Text(base.notes, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        SamFranCard(spacing = 0) {
            state.dates.forEachIndexed { i, date ->
                if (i > 0) HorizontalDivider(color = colors.divider)
                val meaning = state.meaning(date)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val day = Dates.day(date, locale)
                    val codeFor = stringResource(R.string.code_for, day)
                    Text(day, Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = state.codes[date].orEmpty(),
                        onValueChange = { actions.setCode(date, it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = {
                            date.plusDays(1).takeIf { it.month == date.month }?.let(actions::focus)
                        }),
                        modifier = Modifier
                            .width(96.dp)
                            .focusRequester(requesters.getValue(date))
                            .onFocusChanged { if (it.isFocused && state.focused != date) actions.focus(date) }
                            .semantics { contentDescription = codeFor },
                        label = null,
                        placeholder = null,
                    )
                    Column(Modifier.weight(1f)) {
                        if (meaning != null) {
                            Text(ShiftCodes.labelOf(meaning.label), style = MaterialTheme.typography.bodyMedium)
                            meaning.hours?.let { (from, to) ->
                                Text("${ShiftCodes.clock(from)}–${ShiftCodes.clock(to)}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                            }
                            if (meaning.problem != null) Text(stringResource(R.string.unknown_length), style = MaterialTheme.typography.bodySmall, color = colors.danger)
                        }
                        WithNames(state.withFor(date))
                    }
                }
            }
        }
        state.problem?.let { ErrorText(problemText(it)) }
    }
}
