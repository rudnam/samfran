package com.rudnam.samfran.core.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.rudnam.samfran.core.common.ApplicationScope
import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.datastore.SessionStore
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import com.rudnam.samfran.core.model.AppUpdate
import com.rudnam.samfran.core.model.McpToken
import com.rudnam.samfran.core.model.Me
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.Server
import com.rudnam.samfran.core.network.model.asModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Who's signed in, signing out, and the account's own pages (Claude's connections). */
interface UserRepository {
    /** As the server last said, kept on the phone; null when signed out. */
    val me: Flow<Me?>

    suspend fun refreshMe(): Outcome<Me>

    /** Signs out here and on the server (if it can be reached); the phone's copy goes with it. */
    suspend fun signOut()

    /** Emails the link to confirm the address again. */
    suspend fun resendConfirmation(): Outcome<Unit>

    suspend fun mcpTokens(): Outcome<List<McpToken>>

    suspend fun deleteMcpToken(id: Long): Outcome<Unit>
}

@Singleton
internal class ServerUserRepository @Inject constructor(
    private val network: SamFranNetwork,
    private val session: SessionStore,
    private val preferences: UserPreferencesRepository,
    private val documents: Documents,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : UserRepository {
    override val me: Flow<Me?> = preferences.me

    override suspend fun refreshMe() = outcome(io) { network.me().asModel() }.also {
        if (it is Outcome.Done) preferences.setMe(it.value)
    }

    override suspend fun signOut() {
        // The server forgets the session, if it can be reached in a few seconds; the phone forgets it anyway.
        val ending = scope.launch(io) { runCatching { network.logout() } }
        withTimeoutOrNull(3_000) { ending.join() }
        session.clear()
        forget()
    }

    /** What one person saw isn't the next one's (also when the server ends the session). */
    suspend fun forget() {
        documents.clear()
        preferences.setMe(null)
    }

    override suspend fun resendConfirmation() = outcome(io) { network.resendConfirmation() }

    override suspend fun mcpTokens() = outcome(io) { network.mcpTokens().map { it.asModel() } }

    override suspend fun deleteMcpToken(id: Long) = outcome(io) { network.deleteMcpToken(id) }
}

/**
 * Whether the server offers a newer build than this one (GET /api/downloads
 * reads the APK's versionCode). A copy from the Play Store is left to the
 * Play Store; "Later" hides an update until a newer one.
 */
interface UpdateRepository {
    suspend fun check(): AppUpdate?

    suspend fun later(update: AppUpdate)
}

@Singleton
internal class ServerUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: SamFranNetwork,
    private val preferences: UserPreferencesRepository,
    private val server: Server,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : UpdateRepository {
    override suspend fun check(): AppUpdate? = withContext(io) {
        if (installer() == "com.android.vending") return@withContext null
        val apk = try {
            network.downloads().android
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // offline: the next check will tell
        } ?: return@withContext null
        val offered = apk.version ?: return@withContext null
        val skipped = preferences.preferences.first().updateLaterVersion
        if (offered <= installedVersion() || offered <= skipped) return@withContext null
        AppUpdate(offered, apk.versionName.orEmpty(), server.resolve(apk.url))
    }

    override suspend fun later(update: AppUpdate) = preferences.updateLater(update.versionCode)

    private fun installedVersion(): Int =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    @Suppress("DEPRECATION")
    private fun installer(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
