package com.rudnam.samfran

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rudnam.samfran.core.datastore.Appearance
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.feature.us.LockCover
import com.rudnam.samfran.feature.us.LockCoverViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The cover over a locked app ([LockWatcher] starts it): asking the other
 * person to open it, and waiting for the answer. Allowed, it opens the app;
 * "Not now" or Back goes to the home screen rather than back into the app.
 * It runs in its own task, left out of the recent apps.
 */
@AndroidEntryPoint
class LockActivity : AppCompatActivity() {
    private val viewModel: LockCoverViewModel by viewModels()

    @Inject lateinit var preferences: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        show(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toOpen.collect(::openApp)
            }
        }
        setContent {
            val prefs by preferences.preferences.collectAsState(initial = null)
            val dark = when (prefs?.appearance) {
                Appearance.Light -> false
                Appearance.Dark -> true
                else -> isSystemInDarkTheme()
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            BackHandler { goHome() }
            SamFranTheme(darkTheme = dark) {
                LockCover(state, viewModel, onClose = ::goHome)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        val app = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        viewModel.show(app, intent.getStringExtra(EXTRA_LABEL) ?: app)
    }

    /** Allowed (or not locked after all): the app itself, in this cover's place. */
    private fun openApp(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
    }

    private fun goHome() {
        runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_LABEL = "label"

        fun intent(c: Context, packageName: String, label: String): Intent =
            Intent(c, LockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_LABEL, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
