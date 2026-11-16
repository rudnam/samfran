package com.rudnam.samfran

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.data.GoogleReturn
import com.rudnam.samfran.core.data.GoogleReturns
import com.rudnam.samfran.core.datastore.Appearance
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Shared
import com.rudnam.samfran.ui.AppLink
import com.rudnam.samfran.ui.SamFranApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * The app's one activity. It draws edge to edge, keeps the splash screen
 * until the session is known, and routes what opens the app: Google sign-in
 * coming back (<package>://auth) to the sign-in page, its own notifications
 * (<package>://bus?start=1, ://us) and a place shared from Google Maps to the
 * app's navigation.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainActivityViewModel by viewModels()

    @Inject lateinit var googleReturns: GoogleReturns

    /** Pages asked for by links and shares, for the app's navigation once someone is signed in. */
    private val links = Channel<AppLink>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val start = SystemClock.uptimeMillis()
        // Until the session is known; never more than 3 s.
        splash.setKeepOnScreenCondition { viewModel.uiState.value == MainUiState.Loading && SystemClock.uptimeMillis() - start < 3_000 }
        enableEdgeToEdge()
        if (savedInstanceState == null) route(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = state as? MainUiState.Ready ?: return@setContent
            val dark = when (ready.appearance) {
                Appearance.System -> isSystemInDarkTheme()
                Appearance.Light -> false
                Appearance.Dark -> true
            }
            // The status and navigation bars' icons follow the app's appearance, not only the phone's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            SamFranTheme(darkTheme = dark) {
                SamFranApp(
                    signedIn = ready.signedIn,
                    links = links.receiveAsFlow(),
                    shell = viewModel,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening SamFran means she's up (the bus buzz stops), and what its notifications said has been seen.
        viewModel.resumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent) {
        val data = intent.data
        when {
            intent.action == Intent.ACTION_SEND -> {
                Shared.fromIntent(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))?.let {
                    links.trySend(AppLink.AddPlace(it))
                }
            }
            data?.scheme == packageName && data.host == "auth" ->
                googleReturns.send(GoogleReturn(data.getQueryParameter("code"), data.getQueryParameter("signin_error")))
            data?.scheme == packageName && data.host == "bus" -> links.trySend(AppLink.Bus(start = data.getQueryParameter("start") != null))
            data?.scheme == packageName && data.host == "us" -> links.trySend(AppLink.Us)
            else -> return
        }
        // Handled: not again if the activity is remade.
        intent.action = Intent.ACTION_MAIN
        intent.data = null
    }
}
