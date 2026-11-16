package com.rudnam.samfran.feature.signin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.samfran.core.data.AuthRepository
import com.rudnam.samfran.core.data.GoogleResult
import com.rudnam.samfran.core.data.GoogleReturns
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.model.AuthOptions
import com.rudnam.samfran.feature.signin.navigation.SignInRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SignInMode { SignIn, SignUp, Forgot }

/** What the page says above the form. */
sealed interface SignInNotice {
    /** The server's words (or that it couldn't be reached), for the user. */
    data class Problem(val outcome: Outcome<Unit>) : SignInNotice

    /** Google sign-in failed, by the server's reason code (web/src/notice.ts). */
    data class Google(val reason: String) : SignInNotice

    /** The link to choose a new password was sent (if the account exists). */
    data object ResetSent : SignInNotice
}

data class SignInUiState(
    val mode: SignInMode,
    val email: String = "",
    val password: String = "",
    val options: AuthOptions = AuthOptions(google = false, demo = false, reset = false),
    val busy: Boolean = false,
    /** Setting up a demo takes a few seconds: the whole page says so. */
    val startingDemo: Boolean = false,
    val notice: SignInNotice? = null,
) {
    val ready: Boolean get() = email.isNotBlank() && (mode == SignInMode.Forgot || password.isNotEmpty())
}

/** What the sign-in page's controls do. */
interface SignInActions {
    fun setMode(mode: SignInMode)
    fun setEmail(email: String)
    fun setPassword(password: String)
    fun submit()
    fun startDemo()
    /** Where the phone's browser starts Google sign-in. */
    fun googleUrl(): String
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val auth: AuthRepository,
    private val google: GoogleReturns,
) : ViewModel(), SignInActions {
    private val route = savedState.toRoute<SignInRoute>()

    private val _state = MutableStateFlow(SignInUiState(mode = if (route.mode == "sign-up") SignInMode.SignUp else SignInMode.SignIn))
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(options = auth.options()) } }
        // Google sending the browser back (MainActivity), now or later.
        viewModelScope.launch {
            google.pending.filterNotNull().collect {
                google.take()?.let { back ->
                    _state.update { s -> s.copy(busy = true, notice = null) }
                    val notice = when (val r = auth.finishGoogle(back)) {
                        GoogleResult.SignedIn -> null
                        is GoogleResult.Failed -> SignInNotice.Google(r.reason)
                        is GoogleResult.Other -> SignInNotice.Problem(r.outcome)
                    }
                    _state.update { s -> s.copy(busy = false, notice = notice) }
                }
            }
        }
    }

    override fun setMode(mode: SignInMode) = _state.update { it.copy(mode = mode, notice = null) }

    override fun setEmail(email: String) = _state.update { it.copy(email = email) }

    override fun setPassword(password: String) = _state.update { it.copy(password = password) }

    override fun submit() {
        val s = _state.value
        if (s.busy || !s.ready) return
        _state.value = s.copy(busy = true, notice = null)
        viewModelScope.launch {
            val outcome = when (s.mode) {
                SignInMode.SignIn -> auth.signIn(s.email, s.password)
                SignInMode.SignUp -> auth.signUp(s.email, s.password)
                SignInMode.Forgot -> auth.forgotPassword(s.email)
            }
            _state.update {
                when {
                    outcome !is Outcome.Done -> it.copy(busy = false, notice = SignInNotice.Problem(outcome))
                    s.mode == SignInMode.Forgot -> it.copy(busy = false, notice = SignInNotice.ResetSent)
                    // Signed in: the app moves on by itself (it follows the session).
                    else -> it.copy(busy = false, password = "")
                }
            }
        }
    }

    override fun startDemo() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, startingDemo = true, notice = null) }
        viewModelScope.launch {
            val outcome = auth.startDemo()
            _state.update { it.copy(busy = false, startingDemo = false, notice = if (outcome is Outcome.Done) null else SignInNotice.Problem(outcome)) }
        }
    }

    override fun googleUrl(): String = auth.googleSignInUrl()
}
