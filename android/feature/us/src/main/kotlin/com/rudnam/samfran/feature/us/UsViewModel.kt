package com.rudnam.samfran.feature.us

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.samfran.core.data.ActivityRepository
import com.rudnam.samfran.core.data.InviteLinkMade
import com.rudnam.samfran.core.data.LocksRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.data.SpaceRepository
import com.rudnam.samfran.core.data.UserRepository
import com.rudnam.samfran.core.datastore.Appearance
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import com.rudnam.samfran.core.model.ActivityEntry
import com.rudnam.samfran.core.model.McpToken
import com.rudnam.samfran.core.model.Me
import com.rudnam.samfran.core.model.Space
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UsUiState(
    val me: Me? = null,
    val space: Space? = null,
    /** Your name as typed; null until the space has said it. */
    val name: String? = null,
    val nameSaved: Boolean = false,
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    /** An invite link just made: its address is shown only now. */
    val invite: InviteLinkMade? = null,
    val activity: List<ActivityEntry>? = null,
    /** What was new when the page opened stays marked while it's on screen; null on a phone's first look. */
    val seenAtOpen: Long? = null,
    val showAll: Boolean = false,
    val tokens: List<McpToken>? = null,
    val mcpAddress: String = "",
    val alerts: Boolean = false,
    val alertsBlocked: Boolean = false,
    val mealReminders: Boolean = false,
    val mealRemindersBlocked: Boolean = false,
    /** Meal reminders are on but may come late: the phone doesn't allow exact alarms. */
    val mealsLate: Boolean = false,
    val appearance: Appearance = Appearance.System,
    /** How many apps this person has locked; null until known. */
    val lockedHere: Int? = null,
) {
    val myName: String? get() = space?.members?.firstOrNull { it.me }?.name
}

interface UsActions {
    fun setName(name: String)
    fun saveName()
    fun createInviteLink()
    fun cancelInviteLink()
    fun uninvite(email: String)
    fun showAll()
    fun disconnect(token: Long)
    /** The screen has asked for notification permission first. */
    fun setAlerts(on: Boolean)
    /** The screen has asked for notification permission first. */
    fun setMealReminders(on: Boolean)
    /** Back on screen, perhaps from allowing exact alarms in Settings. */
    fun resumed() {}
    fun setAppearance(appearance: Appearance)
    fun signOut()
}

@HiltViewModel
class UsViewModel @Inject constructor(
    private val user: UserRepository,
    private val spaces: SpaceRepository,
    private val activity: ActivityRepository,
    private val reminders: Reminders,
    private val preferences: UserPreferencesRepository,
    private val locks: LocksRepository,
) : ViewModel(), UsActions {
    private val local = MutableStateFlow(UsUiState(mcpAddress = spaces.mcpAddress))
    private val onTime = MutableStateFlow(reminders.mealsOnTime())

    val state: StateFlow<UsUiState> = combine(
        local,
        combine(user.me, spaces.space, activity.entries) { me, space, entries -> Triple(me, space, entries) },
        combine(reminders.activityAlerts, reminders.mealReminders, onTime, locks.page) { alerts, meals, onTime, page ->
            Triple(alerts, meals to onTime, page?.mine?.size)
        },
        preferences.preferences,
    ) { s, (me, space, entries), (alerts, meals, locked), prefs ->
        s.copy(
            lockedHere = locked,
            me = me,
            space = space,
            name = s.name ?: space?.members?.firstOrNull { it.me }?.name,
            activity = entries,
            alerts = alerts,
            mealReminders = meals.first,
            mealsLate = meals.first && !meals.second,
            appearance = prefs.appearance,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            local.update { it.copy(seenAtOpen = activity.seenUpTo()) }
            spaces.refresh()
            launch { locks.refresh() }
            activity.refresh().let { r -> if (r is Outcome.Done) activity.markSeen(r.value) }
        }
        viewModelScope.launch {
            val r = user.mcpTokens()
            local.update { it.copy(tokens = (r as? Outcome.Done)?.value) }
        }
    }

    private fun run(block: suspend () -> Outcome<Any>, after: (UsUiState, Any) -> UsUiState = { s, _ -> s }) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val r = block()
            local.update { s ->
                val next = s.copy(busy = false, problem = r.takeIf { it !is Outcome.Done })
                if (r is Outcome.Done) after(next, r.value as Any) else next
            }
        }
    }

    override fun setName(name: String) = local.update { it.copy(name = name.take(40), nameSaved = false) }

    override fun saveName() {
        val name = state.value.name ?: return
        run({ spaces.setMyName(name) }) { s, _ -> s.copy(nameSaved = true) }
    }

    override fun createInviteLink() = run({ spaces.createInviteLink() }) { s, made -> s.copy(invite = made as InviteLinkMade) }

    override fun cancelInviteLink() = run({ spaces.cancelInviteLink() }) { s, _ -> s.copy(invite = null) }

    override fun uninvite(email: String) = run({ spaces.uninvite(email) })

    override fun showAll() = local.update { it.copy(showAll = true) }

    override fun disconnect(token: Long) {
        viewModelScope.launch {
            val r = user.deleteMcpToken(token)
            local.update { s -> if (r is Outcome.Done) s.copy(tokens = s.tokens?.filter { it.id != token }) else s.copy(problem = r) }
        }
    }

    override fun setAlerts(on: Boolean) {
        local.update { it.copy(alertsBlocked = false) }
        viewModelScope.launch {
            val now = reminders.setActivityAlerts(on)
            local.update { it.copy(alertsBlocked = on && !now) }
        }
    }

    override fun setMealReminders(on: Boolean) {
        local.update { it.copy(mealRemindersBlocked = false) }
        viewModelScope.launch {
            val now = reminders.setMealReminders(on)
            local.update { it.copy(mealRemindersBlocked = on && !now) }
        }
    }

    override fun resumed() {
        onTime.value = reminders.mealsOnTime()
    }

    override fun setAppearance(appearance: Appearance) {
        viewModelScope.launch { preferences.setAppearance(appearance) }
    }

    override fun signOut() {
        viewModelScope.launch {
            locks.signingOut()
            reminders.turnOff()
            user.signOut()
        }
    }
}
