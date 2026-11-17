package com.rudnam.samfran.feature.shifts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.ui.SubPage
import com.rudnam.samfran.core.ui.copyText
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.core.ui.shareText
import com.rudnam.samfran.feature.shifts.navigation.ShiftsNavigation

@Composable
internal fun ShiftCalendarLinkDestination(navigation: ShiftsNavigation, viewModel: ShiftSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ShiftCalendarLinkScreen(
        state,
        viewModel,
        onBack = navigation::back,
        onCopy = { context.copyText(it) },
        onShare = { context.shareText(it) },
    )
}

/**
 * Putting the shifts in Google Calendar (web/src/features/shifts/ShiftCalendarLinkPage.tsx):
 * the calendar link and the steps. Google Calendar's phone app can't add a calendar from a
 * link, so the link goes to a computer first.
 */
@Composable
fun ShiftCalendarLinkScreen(
    state: ShiftSettingsUiState,
    actions: ShiftSettingsActions,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val colors = SamFranTheme.colors
    SubPage(onBack = onBack) {
        PageTitle(stringResource(R.string.add_to_google_calendar))
        Text(stringResource(R.string.google_calendar_intro), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        if (state.settings == null) {
            state.problem?.problem()?.let { ErrorText(it) } ?: Skeleton(rows = 3)
            return@SubPage
        }
        val who = state.settings.personName
        val name = if (who.isNotBlank()) stringResource(R.string.whose_shifts, who) else stringResource(R.string.shifts)
        if (state.feedUrl.isEmpty()) {
            SamFranCard {
                Text(stringResource(R.string.turn_on_first), style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(stringResource(R.string.turn_on_link), { actions.feed(true) }, Modifier.fillMaxWidth(), enabled = !state.busy)
                state.problem?.problem()?.let { ErrorText(it) }
                if (state.done == SettingsDone.LinkOff) Hint(stringResource(R.string.link_off))
            }
            return@SubPage
        }
        var copied by rememberSaveable(state.feedUrl) { mutableStateOf(false) }
        SamFranCard(spacing = 16) {
            Step(1, stringResource(R.string.step_send_title), stringResource(R.string.step_send_hint)) {
                OutlinedTextField(
                    value = state.feedUrl,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.calendar_link)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.share), { onShare(state.feedUrl) }, icon = SamFranIcons.share)
                    SecondaryButton(stringResource(if (copied) R.string.copied else R.string.copy), {
                        onCopy(state.feedUrl)
                        copied = true
                    }, icon = SamFranIcons.copy)
                }
            }
            Step(2, stringResource(R.string.step_add_title), stringResource(R.string.step_add_hint, name))
            Step(3, stringResource(R.string.step_phone_title), stringResource(R.string.step_phone_hint, name))
            Step(4, stringResource(R.string.step_other_title), stringResource(R.string.step_other_hint))
            Hint(stringResource(R.string.google_checks))
        }
        SamFranCard {
            CardTitle(stringResource(R.string.other_calendar_apps))
            Hint(stringResource(R.string.other_calendar_apps_hint))
        }
        SamFranCard {
            CardTitle(stringResource(R.string.the_link))
            Hint(stringResource(R.string.calendar_link_private))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(stringResource(R.string.new_link), { actions.feed(true) }, enabled = !state.busy)
                LinkButton(stringResource(R.string.turn_off), { actions.feed(false) }, enabled = !state.busy, quiet = true)
            }
            state.problem?.problem()?.let { ErrorText(it) }
            if (state.done == SettingsDone.NewLink) Hint(stringResource(R.string.made_new_link))
        }
    }
}

/** A numbered step: what to do, how, and anything to do it with. */
@Composable
private fun Step(n: Int, title: String, hint: String, content: @Composable () -> Unit = {}) {
    val colors = SamFranTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).background(colors.highlight, CircleShape), contentAlignment = Alignment.Center) {
            Text(n.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = colors.accentInk)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Hint(hint)
            content()
        }
    }
}
