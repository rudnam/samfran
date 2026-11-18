package com.rudnam.samfran.feature.shifts

import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.rememberSaveable
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.designsystem.component.LinkButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
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
 * Each day is one line; a working day ends in a count of who's there, which
 * opens that day's people (PeoplePage) to fix.
 */
@Composable
fun ShiftEditorScreen(state: ShiftEditorUiState, actions: ShiftEditorActions, onBack: () -> Unit) {
    val open = state.openDay
    if (open != null && state.base != null) {
        PeoplePage(state, open, actions)
        return
    }
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val month = Dates.month(state.month, locale)
    val requesters = remember(state.month) { state.dates.associateWith { FocusRequester() } }
    // The chips move the focus on: the next day's box follows.
    LaunchedEffect(state.focused) {
        state.focused?.let { requesters[it] }?.let { runCatching { it.requestFocus() } }
    }
    SubPage(
        onBack = onBack,
        bottomBar = if (state.base == null) null else ({
            Column {
                HorizontalDivider(color = colors.border)
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
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val codeFor = stringResource(R.string.code_for, day)
                    Text(day, Modifier.width(58.dp), style = MaterialTheme.typography.labelLarge)
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
                            .width(84.dp)
                            .focusRequester(requesters.getValue(date))
                            .onFocusChanged { if (it.isFocused && state.focused != date) actions.focus(date) }
                            .semantics { contentDescription = codeFor },
                        label = null,
                        placeholder = null,
                    )
                    Column(Modifier.weight(1f)) {
                        if (meaning != null) {
                            // The label and its times on one line: "遅番 · 13:00–22:00".
                            Text(
                                buildAnnotatedString {
                                    append(ShiftCodes.labelOf(meaning.label))
                                    meaning.hours?.let { (from, to) ->
                                        withStyle(SpanStyle(color = colors.muted)) { append(" · ${ShiftCodes.clock(from)}–${ShiftCodes.clock(to)}") }
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (meaning.problem != null) Text(stringResource(R.string.unknown_length), style = MaterialTheme.typography.bodySmall, color = colors.danger)
                        }
                    }
                    if (state.working(date)) PeopleCount(state.there(date).size, day) { actions.openPeople(date) }
                }
            }
        }
        state.closing?.let { ClosingCard(it, state, actions) }
        state.problem?.let { ErrorText(problemText(it)) }
    }
}

/**
 * 締め申請通知 for this month: the days its hours are sent in, saved with its
 * shifts. Each is picked from the month's dates; the usual days are in Settings.
 */
@Composable
private fun ClosingCard(days: List<Int>, state: ShiftEditorUiState, actions: ShiftEditorActions) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val month = Dates.month(state.month, locale)
    SamFranCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClosingFlag()
            Text(stringResource(R.string.hours_due), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (!state.closingIsUsual) LinkButton(stringResource(R.string.use_usual_days), actions::usualClosing)
        }
        Hint(stringResource(if (state.closingIsUsual) R.string.closing_usual_hint else R.string.closing_own_hint, month))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            days.forEachIndexed { i, day ->
                var open by remember { mutableStateOf(false) }
                val name = Dates.day(state.month.atDay(day), locale)
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
                            for (date in state.dates) {
                                DropdownMenuItem(text = { Text(Dates.day(date, locale)) }, onClick = { actions.setClosing(i, date.dayOfMonth); open = false })
                            }
                        }
                    }
                    IconButton(onClick = { actions.removeClosing(i) }) { LineIcon(SamFranIcons.close, stringResource(R.string.remove_closing_day, name), tint = colors.muted) }
                }
            }
            if (days.size < 10) LinkButton("+ " + stringResource(R.string.day), actions::addClosing)
        }
        if (days.isEmpty()) Text(stringResource(R.string.no_closing_this_month), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}


/** How many are there during a day's shift ("+" for nobody yet); it opens the day's people. */
@Composable
private fun PeopleCount(count: Int, day: String, onClick: () -> Unit) {
    val colors = SamFranTheme.colors
    val label = stringResource(R.string.whos_there_on, day) + if (count > 0) " ($count)" else ""
    Row(
        Modifier
            .clip(CircleShape)
            .then(if (count > 0) Modifier.background(colors.sunken) else Modifier.border(1.5.dp, colors.border, CircleShape))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        LineIcon(SamFranIcons.us, null, tint = if (count > 0) colors.text else colors.muted, size = 16.dp)
        Text(if (count > 0) "$count" else "+", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (count > 0) colors.text else colors.muted)
    }
}

/**
 * Who's at work around one day (web: PeopleSheet.tsx): everyone read off the
 * sheet or added, each with the code in their cell, all of it fixable. Those
 * there during the shift come first; the rest of that day's people wait,
 * folded away, in case the day's code is corrected.
 */
@Composable
private fun PeoplePage(state: ShiftEditorUiState, date: LocalDate, actions: ShiftEditorActions) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val close = { actions.openPeople(null) }
    BackHandler(onBack = close)
    val rows = state.people[date].orEmpty()
    val shown = rows.filter { it.key in state.shown }
    val rest = rows.filter { it.key !in state.shown }
    var restOpen by rememberSaveable(date) { mutableStateOf(false) }
    SubPage(
        onBack = close,
        bottomBar = {
            Column {
                HorizontalDivider(color = colors.border)
                PrimaryButton(stringResource(R.string.done), close, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
        },
    ) {
        PageTitle(stringResource(R.string.whos_there_on, Dates.day(date, locale)))
        val hours = state.hours(date)
        Hint(
            listOfNotNull(
                ShiftCodes.normalize(state.codes[date].orEmpty()),
                hours?.let { (from, to) -> "${ShiftCodes.clock(from)}–${ShiftCodes.clock(to)}" },
                stringResource(R.string.names_as_on_sheet),
            ).joinToString(" · "),
        )
        SamFranCard(spacing = 8) {
            if (shown.isEmpty()) Hint(stringResource(R.string.nobody_yet))
            shown.forEach { PersonLine(state, date, it, rows.indexOf(it), actions) }
            SecondaryButton(stringResource(R.string.add_someone), actions::addPerson, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (rest.isNotEmpty()) {
            LinkButton(
                if (restOpen) stringResource(R.string.hide_not_there) else stringResource(R.string.n_more_not_there, rest.size),
                { restOpen = !restOpen },
            )
            if (restOpen) {
                SamFranCard(spacing = 8) { rest.forEach { PersonLine(state, date, it, rows.indexOf(it), actions) } }
            }
        }
    }
}

/** One person: their name and code to fix, when they're there, and a way to take them off. */
@Composable
private fun PersonLine(state: ShiftEditorUiState, date: LocalDate, p: PersonRow, index: Int, actions: ShiftEditorActions) {
    val colors = SamFranTheme.colors
    val there = p.name.isBlank() || state.isThere(date, p)
    val label = p.name.trim().ifEmpty { stringResource(R.string.person_n, index + 1) }
    val nameLabel = stringResource(R.string.name_of_person, index + 1)
    val codeLabel = stringResource(R.string.code_of, label)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = p.name,
            onValueChange = { actions.setPersonName(p.key, it) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.their_name)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (there) colors.text else colors.muted),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f).semantics { contentDescription = nameLabel },
        )
        OutlinedTextField(
            value = p.code,
            onValueChange = { actions.setPersonCode(p.key, it) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
            modifier = Modifier.width(72.dp).semantics { contentDescription = codeLabel },
        )
        // Which day their cell is on: tapping it goes the same day, the day before, the next day.
        val dayWord = stringResource(if (p.dayOffset < 0) R.string.the_day_before else if (p.dayOffset > 0) R.string.the_next_day else R.string.the_same_day)
        val dayLabel = stringResource(R.string.which_day_works, label, dayWord)
        Column(
            Modifier
                .width(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { actions.nextPersonDay(p.key) }
                .semantics(mergeDescendants = true) { contentDescription = dayLabel }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            val h = ShiftCodes.hoursAround(p.code, p.dayOffset, state.base?.settings?.lengths.orEmpty())
            Text(h?.let { (from, to) -> "${ShiftCodes.clock(from)}–${ShiftCodes.clock(to)}" } ?: "–", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(
                dayWord,
                style = MaterialTheme.typography.labelSmall,
                color = if (p.dayOffset == 0) colors.muted else colors.accentInk,
                fontWeight = if (p.dayOffset == 0) null else FontWeight.Bold,
                maxLines = 1,
            )
        }
        IconButton(onClick = { actions.removePerson(p.key) }, modifier = Modifier.size(32.dp)) {
            LineIcon(SamFranIcons.close, stringResource(R.string.remove_person, label), tint = colors.muted, size = 18.dp)
        }
    }
}
