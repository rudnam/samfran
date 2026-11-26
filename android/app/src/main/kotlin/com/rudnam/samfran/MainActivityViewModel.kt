package com.rudnam.samfran

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.samfran.core.data.ActivityRepository
import com.rudnam.samfran.core.data.AppLocks
import com.rudnam.samfran.core.data.LocksRepository
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

/**
 * The app. Firebase starts here when the build has it (locked apps' push
 * messages). Places' photos load through the API's own client, so they're
 * asked for signed in, and are kept on the phone once seen (a photo never
 * changes).
 */
@HiltAndroidApp
class SamFranApplication : Application(), coil3.SingletonImageLoader.Factory {
    @Inject lateinit var okHttp: dagger.Lazy<okhttp3.OkHttpClient>

    override fun onCreate() {
        super.onCreate()
        com.rudnam.samfran.background.Push.start(this)
    }

    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { okHttp.get() })) }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("photos").absolutePath.let { okio.Path.Companion.run { it.toPath() } })
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .build()
}

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
    private val appLocks: AppLocks,
    private val locks: LocksRepository,
) : ViewModel(), ShellActions {
    val uiState: StateFlow<MainUiState> = combine(session.token, preferences.preferences) { token, prefs ->
        MainUiState.Ready(signedIn = token != null, appearance = prefs.appearance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)

    private val update = MutableStateFlow<AppUpdate?>(null)
    private val confirmSent = MutableStateFlow(false)
    private var lastCheck: Long? = null

    override val shell: StateFlow<ShellState> = combine(user.me, update, activity.unseen, confirmSent) { me, u, unseen, sent ->
        ShellState(me = me, update = u, unseen = unseen, confirmSent = sent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellState())

    private val signedIn = session.token.map { it != null }.distinctUntilChanged()

    init {
        // Signed out (here, or the server ended the session): the next person starts from nothing.
        viewModelScope.launch {
            signedIn.drop(1).filter { !it }.collect {
                reminders.turnOff()
                appLocks.turnOff()
                user.signOut()
            }
        }
        // Signed in: who it is, the two of you, the changes, the phone's reminders, and a newer build.
        // The first onResume comes before the stored session has been read, so
        // resumed() can't check for an update on its own at launch; this does.
        viewModelScope.launch {
            signedIn.filter { it }.collect {
                launch { checkForUpdate() }
                user.refreshMe()
                spaces.refresh()
                activity.refresh()
                reminders.sync(now = true)
                locks.refresh()
                appLocks.sync()
            }
        }
    }

    /** The app came into view. */
    fun resumed() {
        reminders.clearNotifications()
        if (uiState.value !is MainUiState.Ready || !(uiState.value as MainUiState.Ready).signedIn) return
        viewModelScope.launch {
            reminders.sync()
            activity.refresh()
            locks.refresh()
            appLocks.sync()
            checkForUpdate()
        }
    }

    /** A newer build: when someone is signed in at launch, and at most hourly when the app comes back. */
    private suspend fun checkForUpdate() {
        lastCheck?.let { if (clock.millis() - it <= 60 * 60 * 1000) return }
        lastCheck = clock.millis()
        update.value = updates.check()
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
            appLocks.turnOff()
            user.signOut()
        }
    }
}

sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(val signedIn: Boolean, val appearance: Appearance) : MainUiState
}
