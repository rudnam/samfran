package com.rudnam.samfran.feature.shifts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
        onOpen = { url ->
            // In Chrome itself, not the Calendar app (which can't add a calendar
            // from a link) nor a Custom Tab, so Desktop site is in its menu.
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(Intent(view).setPackage("com.android.chrome"))
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(view)
                } catch (_: ActivityNotFoundException) {
                }
            }
        },
    )
}

/** Google Calendar's "add this calendar" page, with the link filled in (as googleCalendarUrl on the web). */
internal fun googleCalendarUrl(feedUrl: String): String =
    "https://calendar.google.com/calendar/r?cid=" + Uri.encode(feedUrl.replaceFirst(Regex("^https?:"), "webcal:"))

/**
 * Putting the shifts in Google Calendar (web/src/features/shifts/ShiftCalendarLinkPage.tsx):
 * three steps. Google Calendar's phone app can't add a calendar from a link, so it's added on Google
 * Calendar's website, which on a phone needs Chrome's Desktop site.
 */
@Composable
fun ShiftCalendarLinkScreen(
    state: ShiftSettingsUiState,
    actions: ShiftSettingsActions,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpen: (String) -> Unit = {},
) {
    val colors = SamFranTheme.colors
    SubPage(onBack = onBack) {
        PageTitle(stringResource(R.string.add_to_google_calendar))
        if (state.settings == null) {
            state.problem?.problem()?.let { ErrorText(it) } ?: Skeleton(rows = 3)
            return@SubPage
        }
        val who = state.settings.personName
        val name = if (who.isNotBlank()) stringResource(R.string.whose_shifts, who) else stringResource(R.string.shifts)
        if (state.feedUrl.isEmpty()) {
            SamFranCard {
                PrimaryButton(stringResource(R.string.turn_on_link), { actions.feed(true) }, Modifier.fillMaxWidth(), enabled = !state.busy)
                state.problem?.problem()?.let { ErrorText(it) }
                if (state.done == SettingsDone.LinkOff) Hint(stringResource(R.string.link_off))
            }
            return@SubPage
        }
        var copied by rememberSaveable(state.feedUrl) { mutableStateOf(false) }
        SamFranCard(spacing = 16) {
            Step(1, stringResource(R.string.step_open_title)) {
                PrimaryButton(stringResource(R.string.open_google_calendar), { onOpen(googleCalendarUrl(state.feedUrl)) })
                Hint(stringResource(R.string.desktop_site_hint))
            }
            Step(2, stringResource(R.string.step_tap_add))
            Step(3, stringResource(R.string.step_send_other)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.share), { onShare(state.feedUrl) }, icon = SamFranIcons.share)
                    SecondaryButton(stringResource(if (copied) R.string.copied else R.string.copy), {
                        onCopy(state.feedUrl)
                        copied = true
                    }, icon = SamFranIcons.copy)
                }
            }
            Hint(stringResource(R.string.sync_hint, name))
        }
        SamFranCard(Modifier.fillMaxWidth()) {
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
private fun Step(n: Int, title: String, content: @Composable () -> Unit = {}) {
    val colors = SamFranTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).background(colors.highlight, CircleShape), contentAlignment = Alignment.Center) {
            Text(n.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = colors.accentInk)
        }
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
