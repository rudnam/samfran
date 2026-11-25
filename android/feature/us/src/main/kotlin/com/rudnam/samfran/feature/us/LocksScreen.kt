package com.rudnam.samfran.feature.us

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.rudnam.samfran.core.model.AppLock
import com.rudnam.samfran.core.model.InstalledApp
import com.rudnam.samfran.core.model.LockMinutes
import com.rudnam.samfran.core.model.LockRequest
import com.rudnam.samfran.core.model.LockRequestKind
import com.rudnam.samfran.core.model.LockRequestStatus
import com.rudnam.samfran.core.model.initialOf
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.SubPage
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.feature.us.navigation.UsNavigation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** "3:04 PM" (15:04): when an opening ends or a request expires. */
fun timeOfDay(at: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    at.atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

@Composable
fun minutesText(minutes: Int): String = stringResource(R.string.minutes_short, minutes)

@Composable
internal fun LocksDestination(navigation: UsNavigation, viewModel: LocksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.resumed() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resumed() }
    LocksScreen(
        state,
        viewModel,
        onBack = navigation::back,
        onLockApp = navigation::lockApp,
        onNotifications = {
            val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!allowed) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
    )
}

/**
 * Locked apps: what the phone must allow first, the other person's requests
 * waiting for an answer, your locked apps, theirs, and the latest requests.
 */
@Composable
fun LocksScreen(
    state: LocksUiState,
    actions: LocksActions,
    onBack: () -> Unit,
    onLockApp: () -> Unit,
    onNotifications: () -> Unit,
) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val page = state.page
    val partner = page?.partner.orEmpty()
    SubPage(onBack = onBack, title = stringResource(R.string.locks)) {
        PageTitle(stringResource(R.string.locks))
        state.problem?.problem()?.let { ErrorText(it) }
        if (page == null) {
            Hint(stringResource(R.string.loading))
            return@SubPage
        }
        if (partner.isEmpty()) {
            Hint(stringResource(R.string.locks_need_partner))
        } else {
            Hint(stringResource(R.string.locks_intro, partner))
        }

        if (partner.isNotEmpty() && (!state.setup.ready || !state.setup.notifications)) {
            SamFranCard {
                CardTitle(stringResource(R.string.setup_title))
                Hint(stringResource(R.string.setup_hint))
                SetupRow(stringResource(R.string.setup_usage), stringResource(R.string.setup_usage_hint), state.setup.usageAccess, actions::openUsageAccess)
                SetupRow(stringResource(R.string.setup_overlay), stringResource(R.string.setup_overlay_hint), state.setup.overlay, actions::openOverlay)
                SetupRow(stringResource(R.string.setup_notifications), stringResource(R.string.setup_notifications_hint), state.setup.notifications, onNotifications)
            }
        }

        val waiting = page.waitingForMe
        if (waiting.isNotEmpty()) {
            SamFranCard(color = colors.highlight) {
                CardTitle(stringResource(R.string.waiting_for_you))
                waiting.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = colors.divider)
                    Answer(r, state, actions)
                }
            }
        }

        SamFranCard {
            CardTitle(stringResource(R.string.your_apps))
            val mine = page.mine
            mine.forEach { lock ->
                val pending = page.latestFor(lock)?.takeIf { it.pending }
                SettingRow(
                    lock.label,
                    detail = when {
                        pending?.kind == LockRequestKind.Remove -> stringResource(R.string.status_waiting, partner)
                        lock.isOpen(state.now) -> stringResource(R.string.status_open_until, timeOfDay(lock.openUntil!!, locale))
                        pending != null -> stringResource(R.string.status_waiting, partner)
                        else -> stringResource(R.string.status_locked)
                    },
                    trailing = {
                        if (pending != null) {
                            LinkButton(stringResource(R.string.take_back), { actions.cancel(pending.id) }, enabled = !state.busy, quiet = true)
                        } else {
                            LinkButton(stringResource(R.string.unlock), { actions.confirmUnlock(lock) }, enabled = !state.busy, quiet = true)
                        }
                    },
                )
            }
            if (partner.isNotEmpty()) {
                SecondaryButton(stringResource(R.string.lock_an_app), onLockApp, icon = SamFranIcons.lock, enabled = state.setup.ready)
            }
        }

        if (partner.isNotEmpty()) {
            SamFranCard {
                CardTitle(stringResource(R.string.their_apps, partner))
                val theirs = page.theirs
                if (theirs.isEmpty()) Hint(stringResource(R.string.their_none))
                theirs.forEach { lock ->
                    SettingRow(
                        lock.label,
                        detail = if (lock.isOpen(state.now)) stringResource(R.string.status_open_until, timeOfDay(lock.openUntil!!, locale)) else stringResource(R.string.status_locked),
                    )
                }
            }
        }

        val past = page.requests.filter { !(it.pending && !it.mine) }.take(10)
        if (past.isNotEmpty()) {
            SamFranCard {
                CardTitle(stringResource(R.string.history))
                Column {
                    past.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = colors.divider)
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(describe(r), style = MaterialTheme.typography.bodyMedium)
                            val quote = listOf(r.message, r.reply).filter { it.isNotBlank() }.joinToString("  ") { "“$it”" }
                            if (quote.isNotEmpty()) Hint(quote)
                            Hint(
                                buildList {
                                    if (r.kind != LockRequestKind.Override) add(stateWord(r.status))
                                    add(Dates.moment(r.createdAt, locale))
                                }.joinToString(" · "),
                            )
                        }
                    }
                }
            }
        }
    }

    state.unlocking?.let { lock ->
        AlertDialog(
            onDismissRequest = { actions.confirmUnlock(null) },
            text = { Text(stringResource(R.string.unlock_confirm, partner, lock.label)) },
            confirmButton = { LinkButton(stringResource(R.string.ask), actions::askUnlock, enabled = !state.busy) },
            dismissButton = { LinkButton(stringResource(R.string.cancel), { actions.confirmUnlock(null) }, quiet = true) },
        )
    }
}

@Composable
private fun SetupRow(title: String, detail: String, done: Boolean, onAllow: () -> Unit) {
    SettingRow(title, detail = detail, onClick = if (done) null else onAllow) {
        if (done) {
            LineIcon(SamFranIcons.check, null, tint = SamFranTheme.colors.accentInk)
        } else {
            LinkButton(stringResource(R.string.allow), onAllow)
        }
    }
}

/** One of the other person's requests: what they asked, and Allow or Deny, for as long as asked or another time. */
@Composable
private fun Answer(r: LockRequest, state: LocksUiState, actions: LocksActions) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (r.kind == LockRequestKind.Remove) stringResource(R.string.asks_remove, r.who, r.label) else stringResource(R.string.asks_open, r.who, r.label, minutesText(r.minutes)),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        if (r.message.isNotBlank()) Text(stringResource(R.string.quoted, r.message), style = MaterialTheme.typography.bodyMedium)
        r.expiresAt?.let { Hint(stringResource(R.string.expires_at, timeOfDay(it, locale))) }
        if (r.kind == LockRequestKind.Open) {
            Text(stringResource(R.string.allow_for), style = MaterialTheme.typography.labelLarge)
            val picked = state.allowMinutes[r.id] ?: r.minutes
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (LockMinutes + r.minutes).distinct().sorted().forEach { m ->
                    FilterChip(
                        selected = m == picked,
                        onClick = { actions.setAllowMinutes(r.id, m) },
                        label = { Text(minutesText(m)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.a.bg),
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.replies[r.id].orEmpty(),
            onValueChange = { actions.setReply(r.id, it) },
            label = { Text(stringResource(R.string.reply_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.allow), { actions.answer(r.id, true) }, enabled = !state.busy)
            SecondaryButton(stringResource(R.string.deny), { actions.answer(r.id, false) }, enabled = !state.busy)
        }
    }
}

@Composable
private fun stateWord(s: LockRequestStatus): String = stringResource(
    when (s) {
        LockRequestStatus.Allowed -> R.string.state_allowed
        LockRequestStatus.Denied -> R.string.state_denied
        LockRequestStatus.Pending -> R.string.state_pending
        LockRequestStatus.Cancelled -> R.string.state_cancelled
        LockRequestStatus.Expired -> R.string.state_expired
    },
)

/** A request in words: who asked what. */
@Composable
private fun describe(r: LockRequest): String {
    val m = minutesText(r.minutes)
    return when (r.kind) {
        LockRequestKind.Open -> if (r.mine) stringResource(R.string.history_you_open, r.label, m) else stringResource(R.string.history_they_open, r.who, r.label, m)
        LockRequestKind.Remove -> if (r.mine) stringResource(R.string.history_you_remove, r.label) else stringResource(R.string.history_they_remove, r.who, r.label)
        LockRequestKind.Override -> if (r.mine) stringResource(R.string.history_you_override, r.label, m) else stringResource(R.string.history_they_override, r.who, r.label, m)
    }
}

/** An app's own icon, or its initial on a tile when the phone doesn't have it (or in a test). */
@Composable
fun AppIcon(packageName: String, label: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val icon: ImageBitmap? = remember(packageName) {
        if (preview) {
            null
        } else {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    if (icon != null) {
        Image(icon, null, Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(SamFranTheme.colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            Text(initialOf(label), style = MaterialTheme.typography.titleMedium, color = SamFranTheme.colors.muted)
        }
    }
}

@Composable
internal fun LockAppDestination(navigation: UsNavigation, viewModel: LocksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadApps() }
    LockAppScreen(state, viewModel, onBack = navigation::back, onLocked = navigation::back)
}

/** Choosing an app on this phone to lock. */
@Composable
fun LockAppScreen(state: LocksUiState, actions: LocksActions, onBack: () -> Unit, onLocked: () -> Unit) {
    val colors = SamFranTheme.colors
    SubPage(onBack = onBack, title = stringResource(R.string.lock_an_app)) {
        PageTitle(stringResource(R.string.lock_an_app))
        state.problem?.problem()?.let { ErrorText(it) }
        OutlinedTextField(
            value = state.query,
            onValueChange = actions::setQuery,
            label = { Text(stringResource(R.string.search_apps)) },
            leadingIcon = { LineIcon(SamFranIcons.search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val apps = state.shownApps
        when {
            apps == null -> Hint(stringResource(R.string.loading))
            apps.isEmpty() -> Hint(stringResource(R.string.no_apps))
            else -> SamFranCard(spacing = 0) {
                apps.forEachIndexed { i, app ->
                    if (i > 0) HorizontalDivider(color = colors.divider)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.busy) { actions.lock(app, onLocked) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AppIcon(app.packageName, app.label)
                        Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        LineIcon(SamFranIcons.lock, null, tint = colors.muted, size = 20.dp)
                    }
                }
            }
        }
    }
}
