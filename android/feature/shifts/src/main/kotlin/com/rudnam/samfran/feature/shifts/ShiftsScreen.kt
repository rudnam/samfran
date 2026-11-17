package com.rudnam.samfran.feature.shifts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.designsystem.component.CardTitle
import com.rudnam.samfran.core.designsystem.component.CodeChip
import com.rudnam.samfran.core.designsystem.component.Dot
import com.rudnam.samfran.core.designsystem.component.EmptyState
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.Eyebrow
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.PageTitle
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.component.Segmented
import com.rudnam.samfran.core.designsystem.component.Skeleton
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.TabPage
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.feature.shifts.navigation.ShiftEditRoute
import com.rudnam.samfran.feature.shifts.navigation.ShiftCalendarLinkRoute
import com.rudnam.samfran.feature.shifts.navigation.ShiftSettingsRoute
import com.rudnam.samfran.feature.shifts.navigation.ShiftsNavigation

@Composable
internal fun ShiftsDestination(navigation: ShiftsNavigation, viewModel: ShiftsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val read by viewModel.readMonth.collectAsStateWithLifecycle()
    LaunchedEffect(read) {
        read?.let {
            viewModel.readOpened()
            navigation.navigate(ShiftEditRoute(it.toString(), fromPhoto = true))
        }
    }
    ShiftsScreen(
        state,
        viewModel,
        onSettings = { navigation.navigate(ShiftSettingsRoute) },
        onEdit = { state.shown?.let { navigation.navigate(ShiftEditRoute(it.toString())) } },
        onTakePhoto = { viewModel.askPhoto(SheetPhotos.Source.Camera) },
        onChoosePhoto = { viewModel.askPhoto(SheetPhotos.Source.Picker) },
        onCalendar = { navigation.navigate(ShiftCalendarLinkRoute) },
    )
}

/** Sam's shifts (web/src/features/shifts/ShiftsPage.tsx): the month, the next shift, reading a photo of the sheet, and fixing days by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftsScreen(
    state: ShiftsUiState,
    actions: ShiftsActions,
    onSettings: () -> Unit,
    onEdit: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    onCalendar: () -> Unit = {},
) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val data = state.data
    PullToRefreshBox(isRefreshing = false, onRefresh = { actions.refresh() }) {
        TabPage {
            item(key = "head") {
                Row(verticalAlignment = Alignment.Top) {
                    val who = data?.settings?.personName.orEmpty()
                    PageTitle(
                        if (who.isNotBlank()) stringResource(R.string.whose_shifts, who) else stringResource(R.string.shifts),
                        Modifier.weight(1f),
                    )
                    IconButton(onClick = onSettings) { LineIcon(SamFranIcons.settings, stringResource(R.string.settings)) }
                }
            }
            state.next?.let { next ->
                item(key = "next") {
                    SamFranCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Dot(next.tone.dot(), size = 12)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Eyebrow(stringResource(R.string.next_shift))
                                Text(
                                    listOf(Dates.day(next.date, locale), next.workplaceLabel, next.times).filter(String::isNotEmpty).joinToString(" · "),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                WithNames(next.with)
                            }
                            CodeChip(next.code)
                        }
                    }
                }
            }
            state.problem?.let { p -> item(key = "problem") { ErrorText(problemText(p)) } }
            item(key = "month") {
                SamFranCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = state.shown
                        IconButton(onClick = { shown?.let { actions.showMonth(it.minusMonths(1)) } }, enabled = shown != null) {
                            LineIcon(SamFranIcons.chevronLeft, stringResource(R.string.previous_month))
                        }
                        Text(shown?.let { Dates.month(it, locale) }.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { shown?.let { actions.showMonth(it.plusMonths(1)) } }, enabled = shown != null) {
                            LineIcon(SamFranIcons.chevronRight, stringResource(R.string.next_month))
                        }
                    }
                    Segmented(
                        listOf(stringResource(R.string.calendar), stringResource(R.string.list)),
                        selected = if (state.asList) 1 else 0,
                        onSelect = { actions.setList(it == 1) },
                    )
                    when {
                        data == null -> Skeleton(rows = 4)
                        data.days.isEmpty() -> EmptyState(stringResource(R.string.no_shifts), stringResource(R.string.no_shifts_hint))
                        state.asList -> Column {
                            data.days.forEachIndexed { i, d ->
                                if (i > 0) HorizontalDivider(color = colors.divider)
                                DayLine(d.date, d, Modifier.padding(vertical = 8.dp))
                            }
                        }
                        else -> ShiftCalendar(data.month, data.days, state.today, state.picked, actions::pick, data.settings.closingDays)
                    }
                }
            }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The two ways to fill in a month, one above the other at full width.
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.canRead) {
                            var menu by remember { mutableStateOf(false) }
                            Box(Modifier.fillMaxWidth()) {
                                PrimaryButton(
                                    stringResource(if (state.reading) R.string.reading_sheet else R.string.read_photo),
                                    { menu = true },
                                    Modifier.fillMaxWidth(),
                                    enabled = !state.reading && !state.needsName,
                                )
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.take_photo)) },
                                        leadingIcon = { LineIcon(SamFranIcons.camera, null) },
                                        onClick = {
                                            menu = false
                                            onTakePhoto()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.choose_photo)) },
                                        leadingIcon = { LineIcon(SamFranIcons.list, null) },
                                        onClick = {
                                            menu = false
                                            onChoosePhoto()
                                        },
                                    )
                                }
                            }
                        }
                        SecondaryButton(stringResource(R.string.edit_by_hand), onEdit, Modifier.fillMaxWidth(), enabled = state.shown != null, icon = SamFranIcons.edit)
                        SecondaryButton(stringResource(R.string.add_to_google_calendar), onCalendar, Modifier.fillMaxWidth(), icon = SamFranIcons.calendar)
                    }
                    if (state.reading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accentInk)
                            Hint(stringResource(R.string.reading_hint))
                        }
                    }
                    if (state.needsName && state.canRead) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Hint(stringResource(R.string.needs_name), Modifier.weight(1f))
                            LinkButton(stringResource(R.string.settings), onSettings)
                        }
                    }
                }
            }
        }
    }
}

/** A problem with the month or the photo, in words. */
@Composable
internal fun problemText(p: Outcome<Any>): String =
    if (p is Outcome.Refused && p.message == ShiftsViewModel.PHOTO_UNREADABLE) stringResource(R.string.photo_unreadable) else p.problem().orEmpty()

