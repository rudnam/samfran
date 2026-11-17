package com.rudnam.samfran.feature.shifts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.Notice
import com.rudnam.samfran.core.designsystem.component.PageTitle
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.Skeleton
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.ShiftCodes
import com.rudnam.samfran.core.model.ShiftKind
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
 * Under a working day is who's with you: tapping it opens the names to edit,
 * and the chips then offer the names on the month's other days instead.
 */
@Composable
fun ShiftEditorScreen(state: ShiftEditorUiState, actions: ShiftEditorActions, onBack: () -> Unit) {
    val colors = SamFranTheme.colors
    // The open day's names, held here while they're edited: the keyboard's
    // text (with what the input method is still composing) must not be
    // replaced by the view model's copy, which arrives a frame later. The
    // view model hears every change; the name chips change it here too.
    val editing = state.editingWith
    var names by remember(editing) { mutableStateOf(atEnd(editing?.let(state::withText).orEmpty())) }
    fun setNames(value: TextFieldValue) {
        names = value
        editing?.let { actions.setWith(it, value.text) }
    }
    val locale = currentLocale
    val month = Dates.month(state.month, locale)
    val requesters = remember(state.month) { state.dates.associateWith { FocusRequester() } }
    // The chips move the focus on: the next day's box follows (not while a day's names are open).
    LaunchedEffect(state.focused) {
        if (state.editingWith == null) state.focused?.let { requesters[it] }?.let { runCatching { it.requestFocus() } }
    }
    SubPage(
        onBack = onBack,
        bottomBar = if (state.base == null) null else ({
            Column {
                HorizontalDivider(color = colors.border)
                if (editing == null) {
                    ChipRow {
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
                } else {
                    val others = state.namesBesides(editing)
                    if (others.isNotEmpty()) {
                        val now = splitNames(names.text)
                        ChipRow {
                            for (name in others) {
                                FilterChip(
                                    selected = name in now,
                                    onClick = { setNames(atEnd(toggleName(names.text, name))) },
                                    label = { Text(name) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.a.bg),
                                )
                            }
                        }
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
                val day = Dates.day(date, locale)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            .onFocusChanged { if (it.isFocused && (state.focused != date || state.editingWith != null)) actions.focus(date) }
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
                    }
                }
                // Who's with you: under the day at full width, where its box opens in place.
                if (state.editingWith == date) {
                    NamesField(names, ::setNames, day, onDone = { actions.editWith(null) })
                } else {
                    val with = state.withFor(date)
                    if (with.isNotEmpty() || meaning?.kind == ShiftKind.Work) WithLine(with, day) { actions.editWith(date) }
                }
            }
        }
        state.problem?.let { ErrorText(problemText(it)) }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Who's with you on a day, or a way to add them; tapping it opens the names to edit. */
@Composable
private fun WithLine(names: List<String>, day: String, onClick: () -> Unit) {
    val colors = SamFranTheme.colors
    val pencil = @Composable { LineIcon(SamFranIcons.edit, null, Modifier.padding(start = 6.dp), tint = colors.muted, size = 14.dp) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 76.dp, bottom = 6.dp)
            .heightIn(min = 32.dp)
            .clickable(onClickLabel = stringResource(R.string.edit_who_with, day), onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (names.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LineIcon(SamFranIcons.add, null, tint = colors.accentInk, size = 14.dp)
                Text(stringResource(R.string.add_who_with), style = MaterialTheme.typography.bodySmall, color = colors.accentInk)
            }
        } else {
            WithNames(names, trailing = pencil)
        }
    }
}

/**
 * A day's names as one line, separated by commas, under the day's row. It
 * takes the focus when it opens; Done (or the check) closes it.
 */
@Composable
private fun NamesField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, day: String, onDone: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val label = stringResource(R.string.who_with_on, day)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.who_with)) },
        supportingText = { Text(stringResource(R.string.who_with_hint)) },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = onDone) { LineIcon(SamFranIcons.check, stringResource(R.string.done), size = 20.dp) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 76.dp, bottom = 8.dp)
            .focusRequester(requester)
            .semantics { contentDescription = label },
    )
}

/** Text with the cursor at its end. */
private fun atEnd(text: String) = TextFieldValue(text, TextRange(text.length))
