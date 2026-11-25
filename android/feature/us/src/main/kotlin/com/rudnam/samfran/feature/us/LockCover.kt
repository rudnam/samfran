package com.rudnam.samfran.feature.us

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.samfran.core.data.LocksRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.AppLock
import com.rudnam.samfran.core.model.LockMinutes
import com.rudnam.samfran.core.model.LockRequest
import com.rudnam.samfran.core.model.LockRequestKind
import com.rudnam.samfran.core.model.LockRequestStatus
import com.rudnam.samfran.core.ui.MaxContentWidth
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.core.ui.problem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class LockCoverUiState(
    val packageName: String = "",
    /** The app's name, from the lock (or the phone, before the lock is known). */
    val label: String = "",
    val lock: AppLock? = null,
    val partner: String = "",
    val minutes: Int = 15,
    val message: String = "",
    /** The request being waited on or just answered; null while asking. */
    val request: LockRequest? = null,
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    val confirmEmergency: Boolean = false,
)

interface LockCoverActions {
    fun setMinutes(minutes: Int)
    fun setMessage(message: String)
    fun ask()
    fun takeBack()
    fun askAgain()
    fun confirmEmergency(show: Boolean)
    fun openWithoutAsking()
    /** Allowed: open the app. */
    fun open()
}

/**
 * The screen over a locked app (the app's LockActivity): ask the other
 * person to open it, for how long and why, then wait for the answer (it
 * comes by push, and this asks after it too); or open it now without
 * asking, which the other person is told about.
 */
@HiltViewModel
class LockCoverViewModel @Inject constructor(
    private val locks: LocksRepository,
    private val clock: Clock,
) : ViewModel(), LockCoverActions {
    private val local = MutableStateFlow(LockCoverUiState())
    private var waiting: Job? = null
    private val opens = Channel<String>(Channel.CONFLATED)

    /** The app to open now: it's allowed. */
    val toOpen: Flow<String> = opens.receiveAsFlow()

    val state: StateFlow<LockCoverUiState> = combine(local, locks.page) { s, page ->
        val lock = page?.lockOf(s.packageName)
        // A request's answer that came by push is in the page before this asks after it.
        val request = s.request?.let { r -> page?.requests?.firstOrNull { it.id == r.id } ?: r }
        s.copy(lock = lock, label = lock?.label ?: s.label, partner = page?.partner ?: s.partner, request = request)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    /** The cover is shown for [packageName] (again, perhaps for another app). */
    fun show(packageName: String, label: String) {
        if (packageName == local.value.packageName && local.value.request?.pending == true) return
        waiting?.cancel()
        local.value = LockCoverUiState(packageName = packageName, label = label)
        viewModelScope.launch {
            val r = locks.refresh()
            val page = (r as? Outcome.Done)?.value ?: return@launch
            val lock = page.lockOf(packageName)
            if (lock == null || lock.isOpen(clock.instant())) {
                opens.trySend(packageName) // not locked (any more), or open: nothing to cover
                return@launch
            }
            // Asked before and still waiting: carry on waiting.
            page.latestFor(lock)?.takeIf { it.pending && it.kind == LockRequestKind.Open }?.let { pending ->
                local.update { it.copy(request = pending, minutes = pending.minutes, message = pending.message) }
                waitFor(pending.id)
            }
        }
    }

    override fun setMinutes(minutes: Int) = local.update { it.copy(minutes = minutes) }

    override fun setMessage(message: String) = local.update { it.copy(message = message.take(200)) }

    override fun ask() {
        val s = state.value
        val lock = s.lock ?: return
        if (s.busy) return
        local.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val r = locks.ask(lock.id, LockRequestKind.Open, s.minutes, s.message)
            local.update { it.copy(busy = false, problem = r.takeIf { it !is Outcome.Done }, request = (r as? Outcome.Done)?.value ?: it.request) }
            if (r is Outcome.Done) waitFor(r.value.id)
        }
    }

    /** Asks after the request every few seconds while it waits (the push usually comes first). */
    private fun waitFor(id: Long) {
        waiting?.cancel()
        waiting = viewModelScope.launch {
            while (true) {
                val current = state.value.request
                if (current != null && current.id == id && !current.pending) break
                delay(POLL_MS)
                val r = locks.request(id)
                if (r is Outcome.Done) local.update { s -> if (s.request?.id == id) s.copy(request = r.value) else s }
            }
            val done = state.value.request ?: return@launch
            if (done.status == LockRequestStatus.Allowed) {
                locks.refresh() // the watcher reads the opening from the phone's copy
            }
        }
    }

    override fun takeBack() {
        val r = state.value.request ?: return
        waiting?.cancel()
        viewModelScope.launch {
            val out = locks.cancel(r.id)
            local.update { it.copy(request = (out as? Outcome.Done)?.value ?: it.request, problem = out.takeIf { o -> o !is Outcome.Done }) }
        }
    }

    override fun askAgain() = local.update { it.copy(request = null, problem = null) }

    override fun confirmEmergency(show: Boolean) = local.update { it.copy(confirmEmergency = show) }

    override fun openWithoutAsking() {
        val s = state.value
        val lock = s.lock ?: return
        local.update { it.copy(busy = true, problem = null, confirmEmergency = false) }
        viewModelScope.launch {
            val r = locks.openWithoutAsking(lock.id, s.minutes, s.message)
            local.update { it.copy(busy = false, problem = r.takeIf { it !is Outcome.Done }) }
            if (r is Outcome.Done) opens.trySend(s.packageName)
        }
    }

    override fun open() {
        opens.trySend(state.value.packageName)
    }

    companion object {
        var POLL_MS = 4_000L
    }
}

/** The cover over a locked app. */
@Composable
fun LockCover(state: LockCoverUiState, actions: LockCoverActions, onClose: () -> Unit) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val partner = state.partner.ifEmpty { "…" }
    val r = state.request
    CompositionLocalProvider(LocalContentColor provides colors.text) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    AppIcon(state.packageName, state.label, size = 72.dp)
                    Box(Modifier.size(28.dp).background(colors.accent, MaterialTheme.shapes.extraLarge), contentAlignment = Alignment.Center) {
                        LineIcon(SamFranIcons.lock, null, tint = colors.accentText, size = 18.dp)
                    }
                }
                Text(stringResource(R.string.cover_locked, state.label), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                state.problem?.problem()?.let { ErrorText(it) }

                when {
                    r == null -> {
                        CenteredHint(stringResource(R.string.cover_ask_hint, partner))
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.cover_how_long), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            LockMinutes.forEach { m ->
                                FilterChip(
                                    selected = m == state.minutes,
                                    onClick = { actions.setMinutes(m) },
                                    label = { Text(minutesText(m)) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.a.bg),
                                )
                            }
                        }
                        OutlinedTextField(
                            value = state.message,
                            onValueChange = actions::setMessage,
                            label = { Text(stringResource(R.string.cover_why)) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PrimaryButton(stringResource(R.string.cover_ask, partner), actions::ask, Modifier.fillMaxWidth(), enabled = !state.busy && state.lock != null, big = true)
                    }
                    r.pending -> {
                        Text(stringResource(R.string.cover_waiting, partner), style = MaterialTheme.typography.titleMedium)
                        CenteredHint(
                            buildList {
                                add(minutesText(r.minutes))
                                if (r.message.isNotBlank()) add(stringResource(R.string.quoted, r.message))
                            }.joinToString(" · "),
                        )
                        r.expiresAt?.let { CenteredHint(stringResource(R.string.cover_waiting_hint, partner, timeOfDay(it, locale))) }
                        SecondaryButton(stringResource(R.string.take_back), actions::takeBack)
                    }
                    r.status == LockRequestStatus.Allowed -> {
                        Text(stringResource(R.string.cover_allowed, partner, minutesText(r.minutes)), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        if (r.reply.isNotBlank()) Text(stringResource(R.string.quoted, r.reply), style = MaterialTheme.typography.bodyLarge)
                        PrimaryButton(stringResource(R.string.cover_open, state.label), actions::open, Modifier.fillMaxWidth(), big = true)
                    }
                    else -> {
                        Text(
                            when (r.status) {
                                LockRequestStatus.Denied -> stringResource(R.string.cover_denied, partner)
                                LockRequestStatus.Cancelled -> stringResource(R.string.cover_cancelled)
                                else -> stringResource(R.string.cover_expired)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (r.reply.isNotBlank()) Text(stringResource(R.string.quoted, r.reply), style = MaterialTheme.typography.bodyLarge)
                        SecondaryButton(stringResource(R.string.ask_again), actions::askAgain)
                    }
                }

                Spacer(Modifier.height(8.dp))
                LinkButton(stringResource(R.string.not_now), onClose, quiet = true)
                if (r?.status != LockRequestStatus.Allowed && state.lock != null) {
                    LinkButton(stringResource(R.string.cover_emergency), { actions.confirmEmergency(true) }, danger = true)
                }
            }
        }
    }

    if (state.confirmEmergency) {
        AlertDialog(
            onDismissRequest = { actions.confirmEmergency(false) },
            text = { Text(stringResource(R.string.cover_emergency_confirm, state.label, minutesText(state.minutes), partner)) },
            confirmButton = { LinkButton(stringResource(R.string.open_now), actions::openWithoutAsking, enabled = !state.busy, danger = true) },
            dismissButton = { LinkButton(stringResource(R.string.cancel), { actions.confirmEmergency(false) }, quiet = true) },
        )
    }
}

/** A hint centred under the heading. */
@Composable
private fun CenteredHint(text: String) {
    Text(text, color = SamFranTheme.colors.muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
}
