package com.rudnam.samfran

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.samfran.core.data.ActivityRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.data.SpaceRepository
import com.rudnam.samfran.core.data.UpdateRepository
import com.rudnam.samfran.core.data.UserRepository
import com.rudnam.samfran.core.datastore.Appearance
import com.rudnam.samfran.core.datastore.SessionStore
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import com.rudnam.samfran.core.model.AppUpdate
import com.rudnam.samfran.ui.ShellActions
import com.rudnam.samfran.ui.ShellState
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** The app. */
@HiltAndroidApp
class SamFranApplication : Application()

/**
 * What the app as a whole shows: who's signed in, how it looks, the Us
 * tab's dot, and the notices above every page (a newer build, the demo,
 * confirming the email). It also keeps the phone's background reminders
 * up to date while someone is signed in.
 */
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    session: SessionStore,
    preferences: UserPreferencesRepository,
    private val user: UserRepository,
    private val updates: UpdateRepository,
    private val reminders: Reminders,
    private val activity: ActivityRepository,
    private val spaces: SpaceRepository,
    private val clock: Clock,
) : ViewModel(), ShellActions {
    val uiState: StateFlow<MainUiState> = combine(session.token, preferences.preferences) { token, prefs ->
        MainUiState.Ready(signedIn = token != null, appearance = prefs.appearance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)

    private val update = MutableStateFlow<AppUpdate?>(null)
    private val confirmSent = MutableStateFlow(false)

    override val shell: StateFlow<ShellState> = combine(user.me, update, activity.unseen, confirmSent) { me, u, unseen, sent ->
        ShellState(me = me, update = u, unseen = unseen, confirmSent = sent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellState())

    private val signedIn = session.token.map { it != null }.distinctUntilChanged()

    init {
        // Signed out (here, or the server ended the session): the next person starts from nothing.
        viewModelScope.launch {
            signedIn.drop(1).filter { !it }.collect {
                reminders.turnOff()
                user.signOut()
            }
        }
        // Signed in: who it is, the two of you, the changes, and the phone's reminders.
        viewModelScope.launch {
            signedIn.filter { it }.collect {
                user.refreshMe()
                spaces.refresh()
                activity.refresh()
                reminders.sync(now = true)
            }
        }
    }

    private var lastCheck = 0L

    /** The app came into view. */
    fun resumed() {
        reminders.clearNotifications()
        if (uiState.value !is MainUiState.Ready || !(uiState.value as MainUiState.Ready).signedIn) return
        viewModelScope.launch {
            reminders.sync()
            activity.refresh()
            // A newer build: when the app starts, and at most hourly when it comes back.
            if (clock.millis() - lastCheck > 60 * 60 * 1000) {
                lastCheck = clock.millis()
                update.value = updates.check()
            }
        }
    }

    override fun updateLater() {
        val u = update.value ?: return
        update.value = null
        viewModelScope.launch { updates.later(u) }
    }

    override fun resendConfirmation() {
        viewModelScope.launch { if (user.resendConfirmation() is Outcome.Done) confirmSent.update { true } }
    }

    /** From a demo to creating an account: signing out deletes the demo. */
    override fun leaveDemo() {
        viewModelScope.launch {
            reminders.turnOff()
            user.signOut()
        }
    }
}

sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(val signedIn: Boolean, val appearance: Appearance) : MainUiState
}
