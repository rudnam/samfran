package com.rudnam.samfran.background

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.rudnam.samfran.BuildConfig
import com.rudnam.samfran.LockWatcher
import com.rudnam.samfran.core.data.AppLocks
import com.rudnam.samfran.core.data.LocksRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.datastore.SessionStore
import com.rudnam.samfran.core.model.InstalledApp
import com.rudnam.samfran.core.model.LockSetup
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Firebase, set up from BuildConfig (app/build.gradle.kts); null in a build without it. */
object Push {
    fun start(c: Context): Boolean {
        if (BuildConfig.FIREBASE_APP_ID.isEmpty() || BuildConfig.FIREBASE_PROJECT_ID.isEmpty()) return false
        if (FirebaseApp.getApps(c).isNotEmpty()) return true
        return runCatching {
            FirebaseApp.initializeApp(
                c,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        }.isSuccess
    }

    /** This phone's push address, or null (no Firebase, or no Google Play services). */
    suspend fun token(c: Context): String? {
        if (!start(c)) return null
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }.onFailure { cont.resume(null) }
            }
        }
    }

    /** Signing out: this phone's address goes, so the server's copy stops working; the next sign-in gets a new one. */
    fun forget(c: Context) {
        if (start(c)) runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }
}

/** Locked apps on this phone: what it allows, its apps, and [LockWatcher]. */
@Singleton
class AndroidAppLocks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locks: LocksRepository,
    private val session: SessionStore,
) : AppLocks {
    private val state = MutableStateFlow(read())
    override val setup: StateFlow<LockSetup> = state

    private fun read() = LockSetup(
        usageAccess = usageAccess(),
        overlay = Settings.canDrawOverlays(context),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

    private fun usageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun checkSetup() {
        state.value = read()
    }

    private fun openSettings(action: String, withPackage: Boolean) {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withPackage) i.data = Uri.parse("package:" + context.packageName)
        // Some phones don't have the page for one app: the list instead.
        runCatching { context.startActivity(i) }.onFailure {
            runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun openUsageAccess() = openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackage = Build.VERSION.SDK_INT >= 29)

    override fun openOverlay() = openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true)

    override suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName && it.packageName !in NEVER }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    @Volatile private var lastToken: String? = null

    override suspend fun sync(updated: Boolean) {
        checkSetup()
        if (session.currentToken() == null) {
            LockWatcher.stop(context)
            return
        }
        val page = locks.page.first()
        val locked = page != null && page.mine.isNotEmpty()
        when {
            locked && state.value.usageAccess -> LockWatcher.start(context, updated)
            locked -> {
                // It can't see which app is open: the other person hears that nothing is covered.
                LockWatcher.stop(context)
                val s = state.value
                locks.checkIn(s.usageAccess, s.overlay, watching = false, watchingSince = null, bootedAt = null)
            }
            else -> LockWatcher.stop(context)
        }
        Push.token(context)?.let { token ->
            if (token != lastToken && locks.savePushToken(token) is Outcome.Done) lastToken = token
        }
    }

    override fun turnOff() {
        LockWatcher.stop(context)
        lastToken = null
        Push.forget(context)
    }

    private companion object {
        /** Settings stays reachable: it's where usage access is taken back, and where an emergency may need to go. */
        val NEVER = setOf("com.android.settings")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface AppLocksModule {
    @Binds fun appLocks(impl: AndroidAppLocks): AppLocks
}
