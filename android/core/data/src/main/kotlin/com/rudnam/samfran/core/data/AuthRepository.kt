package com.rudnam.samfran.core.data

import android.content.Context
import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.datastore.SessionStore
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import com.rudnam.samfran.core.model.AuthOptions
import com.rudnam.samfran.core.network.Server
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.model.NetworkMe
import com.rudnam.samfran.core.network.model.asModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Google sending the browser back to the app (<package>://auth): the one-time code, or why not. */
data class GoogleReturn(val code: String?, val error: String?)

/** Google sign-in coming back (MainActivity), until the sign-in page takes it. */
@Singleton
class GoogleReturns @Inject constructor() {
    private val _pending = MutableStateFlow<GoogleReturn?>(null)
    val pending: StateFlow<GoogleReturn?> = _pending.asStateFlow()

    fun send(value: GoogleReturn) {
        _pending.value = value
    }

    fun take(): GoogleReturn? = _pending.value.also { _pending.value = null }
}

/** Signing in: with email and password, a new account, the demo, or Google; and a forgotten password. */
interface AuthRepository {
    /** The ways this server offers to sign in; only email and password if it can't be asked. */
    suspend fun options(): AuthOptions

    /** Each answers Done once signed in; the app follows the session from there. */
    suspend fun signIn(email: String, password: String): Outcome<Unit>

    suspend fun signUp(email: String, password: String): Outcome<Unit>

    suspend fun startDemo(): Outcome<Unit>

    /** Emails a link to choose a new password, if an account uses [email]. */
    suspend fun forgotPassword(email: String): Outcome<Unit>

    /**
     * Where the phone's browser starts Google sign-in (OAuth for native apps,
     * RFC 8252 and 7636): a secret is kept, and its hash sent.
     */
    fun googleSignInUrl(): String

    /** Finishes Google sign-in with what came back: the code and the kept secret, traded for a session. */
    suspend fun finishGoogle(back: GoogleReturn): GoogleResult
}

/** How Google sign-in ended: signed in, or the server's reason code (cancelled, expired, closed…), or the usual. */
sealed interface GoogleResult {
    data object SignedIn : GoogleResult

    data class Failed(val reason: String) : GoogleResult

    data class Other(val outcome: Outcome<Unit>) : GoogleResult
}

internal class ServerAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: SamFranNetwork,
    private val server: Server,
    private val session: SessionStore,
    private val preferences: UserPreferencesRepository,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : AuthRepository {
    override suspend fun options(): AuthOptions = withContext(io) {
        try {
            network.authOptions().let { AuthOptions(it.google, it.demo, it.reset) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthOptions(google = false, demo = false, reset = false) // the email form still works
        }
    }

    override suspend fun signIn(email: String, password: String) = signedIn { network.login(email.trim(), password) }

    override suspend fun signUp(email: String, password: String) = signedIn { network.signup(email.trim(), password) }

    override suspend fun startDemo() = signedIn { network.startDemo(ZoneId.systemDefault().id) }

    override suspend fun forgotPassword(email: String) = outcome(io) { network.forgotPassword(email.trim()) }

    override fun googleSignInUrl(): String {
        val verifier = base64url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = base64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        session.keepGoogleVerifier(verifier)
        // The preview build installs beside the real app as <package>.preview; it asks to be sent
        // back to its own scheme, or the real app would be the one signed in.
        val variant = if (context.packageName.endsWith(".preview")) "&app_variant=preview" else ""
        return server.resolve("/api/auth/google?app_challenge=$challenge$variant")
    }

    override suspend fun finishGoogle(back: GoogleReturn): GoogleResult {
        val verifier = session.takeGoogleVerifier()
        if (back.code == null || verifier == null) return GoogleResult.Failed(back.error ?: "failed")
        return when (val o = signedIn { network.appToken(back.code, verifier) }) {
            is Outcome.Done -> GoogleResult.SignedIn
            else -> GoogleResult.Other(o)
        }
    }

    /** Keeps the new session and who it is; the app follows the session. */
    private suspend fun signedIn(block: suspend () -> NetworkMe): Outcome<Unit> = when (val o = outcome(io) { block() }) {
        is Outcome.Done -> {
            val token = o.value.token
            if (token == null) {
                Outcome.Refused("")
            } else {
                preferences.setMe(o.value.asModel())
                session.save(token)
                Outcome.Done(Unit)
            }
        }
        is Outcome.Refused -> o
        Outcome.Offline -> Outcome.Offline
        Outcome.SignedOut -> Outcome.Offline // can't be: nobody was signed in
    }

    private fun base64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
