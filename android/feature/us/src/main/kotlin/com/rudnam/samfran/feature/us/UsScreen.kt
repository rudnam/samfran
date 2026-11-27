package com.rudnam.samfran.feature.us

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.datastore.Appearance
import com.rudnam.samfran.core.designsystem.component.CardTitle
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.PageTitle
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.component.SettingRow
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Tone
import com.rudnam.samfran.core.ui.Avatar
import com.rudnam.samfran.core.ui.Choice
import com.rudnam.samfran.core.ui.ChoicePicker
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.LanguagePicker
import com.rudnam.samfran.core.ui.PendingAvatar
import com.rudnam.samfran.core.ui.TabPage
import com.rudnam.samfran.core.ui.copyText
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.core.ui.shareText
import com.rudnam.samfran.feature.us.navigation.UsNavigation

@Composable
internal fun UsDestination(navigation: UsNavigation, viewModel: UsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askForAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.setAlerts(true) }
    val join = stringResource(R.string.join_me)
    UsScreen(
        state,
        viewModel,
        onAlerts = { on ->
            val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (on && !allowed) askForAlerts.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.setAlerts(on)
        },
        onCopy = { context.copyText(it) },
        onShare = { context.shareText("$join\n$it", "SamFran") },
        onLeaveDemo = navigation::leaveDemo,
        onLocks = navigation::openLocks,
        languagePicker = { LanguagePicker() },
    )
}

/** The two of you (web/src/features/us/UsPage.tsx): names, inviting your partner, recent changes, and this phone. */
@Composable
fun UsScreen(
    state: UsUiState,
    actions: UsActions,
    onAlerts: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onLeaveDemo: () -> Unit,
    onLocks: () -> Unit = {},
    languagePicker: @Composable () -> Unit = {},
) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val demo = state.me?.demo == true
    TabPage {
        item(key = "head") { PageTitle(stringResource(R.string.us)) }
        state.problem?.let { p -> item(key = "problem") { p.problem()?.let { ErrorText(it) } } }

        item(key = "people") {
            SamFranCard {
                CardTitle(stringResource(R.string.two_of_you))
                val space = state.space
                if (space == null) {
                    Hint(stringResource(R.string.loading))
                } else {
                    space.members.forEachIndexed { i, m ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(m.name, if (i == 1) Tone.B else Tone.A, large = true)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.name) }
                                        if (m.me) withStyle(SpanStyle(color = colors.muted)) { append(" · " + stringResource(R.string.you_lower)) }
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Hint(m.email)
                            }
                        }
                    }
                    space.invites.forEach { inv ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PendingAvatar()
                            Column(Modifier.weight(1f)) {
                                Text(inv.email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Hint(stringResource(R.string.invited))
                            }
                            LinkButton(stringResource(R.string.cancel), { actions.uninvite(inv.email) }, enabled = !state.busy, quiet = true)
                        }
                    }
                    if (space.hasRoom && !demo) InviteLink(state, actions, onCopy, onShare)
                }
            }
        }

        item(key = "name") {
            SamFranCard {
                CardTitle(stringResource(R.string.your_name))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.name.orEmpty(),
                        onValueChange = actions::setName,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { actions.saveName() }),
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(stringResource(R.string.save), actions::saveName, enabled = !state.busy && state.space != null && state.name != state.myName)
                }
                if (state.nameSaved) Hint(stringResource(R.string.saved))
            }
        }

        item(key = "changes") {
            SamFranCard {
                CardTitle(stringResource(R.string.recent_changes))
                val shown = state.activity.orEmpty().map { it to describe(it) }.filter { it.second.isNotEmpty() }
                if (state.activity != null && shown.isEmpty()) Hint(stringResource(R.string.nothing_yet))
                Column {
                    (if (state.showAll) shown else shown.take(8)).forEachIndexed { i, (e, text) ->
                        if (i > 0) HorizontalDivider(color = colors.divider)
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val isNew = state.seenAtOpen != null && !e.me && e.id > state.seenAtOpen
                            Text(
                                buildAnnotatedString {
                                    if (isNew) withStyle(SpanStyle(color = colors.accentInk, fontWeight = FontWeight.Bold)) { append(stringResource(R.string.new_mark) + "  ") }
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(if (e.me) stringResource(R.string.you) else e.who.ifBlank { stringResource(R.string.someone) }) }
                                    append(" ")
                                    append(text)
                                    if (e.viaClaude) withStyle(SpanStyle(color = colors.muted)) { append(" · " + stringResource(R.string.via_claude)) }
                                },
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Hint(Dates.moment(e.at, locale))
                        }
                    }
                }
                if (!state.showAll && shown.size > 8) LinkButton(stringResource(R.string.show_more), actions::showAll)
            }
        }

        item(key = "device") {
            SamFranCard {
                CardTitle(stringResource(R.string.this_device))
                if (!demo) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onAlerts(!state.alerts) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.tell_me), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(state.alerts, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.accentInk))
                    }
                    if (state.alertsBlocked) ErrorText(stringResource(R.string.allow_notifications))
                }
                if (!demo) {
                    SettingRow(
                        stringResource(R.string.locks),
                        detail = state.lockedHere?.let { n ->
                            if (n == 0) stringResource(R.string.locks_none, state.space?.partner?.name ?: stringResource(R.string.someone)) else stringResource(R.string.locks_count, n)
                        },
                        onClick = onLocks,
                    ) { LineIcon(SamFranIcons.lock, null, tint = colors.muted, size = 20.dp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.language), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    languagePicker()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.appearance), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    ChoicePicker(
                        listOf(
                            Choice(Appearance.System, stringResource(R.string.auto)),
                            Choice(Appearance.Light, stringResource(R.string.light)),
                            Choice(Appearance.Dark, stringResource(R.string.dark)),
                        ),
                        selected = state.appearance,
                        onSelect = actions::setAppearance,
                        description = stringResource(R.string.appearance),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint(if (demo) stringResource(R.string.demo) else state.me?.email.orEmpty(), Modifier.weight(1f))
                    if (demo) {
                        SecondaryButton(stringResource(R.string.end_demo), onLeaveDemo)
                    } else {
                        SecondaryButton(stringResource(R.string.sign_out), actions::signOut)
                    }
                }
            }
        }
    }
}

/** Inviting your partner: a link to send them, which works once, for seven days. */
@Composable
private fun InviteLink(state: UsUiState, actions: UsActions, onCopy: (String) -> Unit, onShare: (String) -> Unit) {
    val locale = currentLocale
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.invite_partner), style = MaterialTheme.typography.labelLarge)
        val made = state.invite
        val open = state.space?.inviteLinkExpires
        when {
            made != null -> {
                OutlinedTextField(value = made.url, onValueChange = {}, readOnly = true, singleLine = true, label = { Text(stringResource(R.string.invite_link)) }, modifier = Modifier.fillMaxWidth())
                var copied by rememberSaveable(made.url) { mutableStateOf(false) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(R.string.share), { onShare(made.url) })
                    SecondaryButton(stringResource(if (copied) R.string.copied else R.string.copy), {
                        onCopy(made.url)
                        copied = true
                    })
                    LinkButton(stringResource(R.string.cancel_link), actions::cancelInviteLink, enabled = !state.busy, quiet = true)
                }
                Hint(stringResource(R.string.invite_made_hint, Dates.medium(made.expires, locale)))
            }
            open != null -> {
                Hint(stringResource(R.string.invite_open_hint, Dates.medium(open, locale)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(stringResource(R.string.make_new_link), actions::createInviteLink, enabled = !state.busy)
                    LinkButton(stringResource(R.string.cancel_link), actions::cancelInviteLink, enabled = !state.busy, quiet = true)
                }
            }
            else -> {
                PrimaryButton(stringResource(R.string.create_invite_link), actions::createInviteLink, enabled = !state.busy)
            }
        }
    }
}
