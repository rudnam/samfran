package com.rudnam.samfran.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import com.rudnam.samfran.R
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.Notice
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.AppUpdate
import com.rudnam.samfran.core.model.Me
import com.rudnam.samfran.core.ui.MaxContentWidth
import com.rudnam.samfran.core.ui.openWeb
import com.rudnam.samfran.feature.bus.navigation.busScreen
import com.rudnam.samfran.feature.food.navigation.FoodNavigation
import com.rudnam.samfran.feature.food.navigation.FoodRoute
import com.rudnam.samfran.feature.food.navigation.PlaceFormRoute
import com.rudnam.samfran.feature.food.navigation.PlaceRoute
import com.rudnam.samfran.feature.food.navigation.foodScreens
import com.rudnam.samfran.feature.shifts.navigation.ShiftsNavigation
import com.rudnam.samfran.feature.shifts.navigation.shiftsScreens
import com.rudnam.samfran.feature.signin.navigation.signInScreen
import com.rudnam.samfran.feature.us.navigation.UsNavigation
import com.rudnam.samfran.feature.us.navigation.usScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.widthIn

/** What the notices above every page and the tab bar show. */
data class ShellState(
    val me: Me? = null,
    val update: AppUpdate? = null,
    /** The other person's changes not yet seen here: the Us tab's dot. */
    val unseen: Int = 0,
    val confirmSent: Boolean = false,
)

interface ShellActions {
    val shell: StateFlow<ShellState>
    fun updateLater()
    fun resendConfirmation()
    fun leaveDemo()
}

/** The app: the four tabs along the bottom, and the pages above them, each a destination of the NavHost. */
@Composable
fun SamFranApp(
    signedIn: Boolean,
    links: Flow<AppLink>,
    shell: ShellActions,
    appState: SamFranAppState = rememberSamFranAppState(),
) {
    val snackbar = remember { SnackbarHostState() }
    val state by shell.shell.collectAsStateWithLifecycle()
    val tab = appState.currentTab
    val context = LocalContext.current

    // The app follows the session: the sign-in page without one, Food with one.
    LaunchedEffect(signedIn) {
        if (!signedIn) appState.showSignIn() else if (appState.onSignIn || appState.navController.currentBackStackEntry == null) appState.showHome()
    }
    // Links into the app wait until someone is signed in.
    LaunchedEffect(links, signedIn) {
        if (signedIn) links.collect { appState.open(it) }
    }

    Scaffold(
        containerColor = SamFranTheme.colors.bg,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (signedIn && tab != null) {
                Banners(
                    state,
                    onUpdate = { state.update?.let { context.openWeb(it.url) } },
                    shell = shell,
                    onLeaveDemo = {
                        appState.signUpNext = true
                        shell.leaveDemo()
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(visible = signedIn && tab != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                TabBar(tab ?: TopLevelDestination.Food, state.unseen, appState::navigateToTab)
            }
        },
    ) { padding ->
        NavHost(
            appState.navController,
            startDestination = if (signedIn) FoodRoute else com.rudnam.samfran.feature.signin.navigation.SignInRoute(),
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        ) {
            signInScreen()
            foodScreens(
                navigation = object : FoodNavigation {
                    override fun openPlace(id: Long) = appState.navController.navigate(PlaceRoute(id))
                    override fun addPlace(want: Boolean) = appState.navController.navigate(PlaceFormRoute(want = want))
                    override fun editPlace(id: Long) = appState.navController.navigate(PlaceFormRoute(id = id))
                    override fun placeSaved(id: Long) {
                        appState.navController.navigate(PlaceRoute(id)) {
                            popUpTo<PlaceFormRoute> { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                    override fun back() {
                        appState.navController.popBackStack()
                    }
                },
                snackbar = snackbar,
            )
            shiftsScreens(
                navigation = object : ShiftsNavigation {
                    override fun navigate(route: Any) = appState.navController.navigate(route)
                    override fun replace(route: Any) {
                        appState.navController.popBackStack()
                        appState.navController.navigate(route) { launchSingleTop = true }
                    }
                    override fun back() {
                        appState.navController.popBackStack()
                    }
                },
                snackbar = snackbar,
            )
            busScreen(snackbar = snackbar)
            usScreen(
                navigation = object : UsNavigation {
                    override fun leaveDemo() {
                        appState.signUpNext = true
                        shell.leaveDemo()
                    }
                },
                snackbar = snackbar,
            )
        }
    }
}

/** The notices above every tab: a newer build, the demo, confirming the email. */
@Composable
private fun Banners(state: ShellState, onUpdate: () -> Unit, shell: ShellActions, onLeaveDemo: () -> Unit) {
    val me = state.me
    val any = state.update != null || me?.demo == true || me?.confirmEmail == true
    if (!any) return
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = MaxContentWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.update?.let { u ->
            Notice(actions = {
                LinkButton(stringResource(R.string.update_later), shell::updateLater, quiet = true)
                PrimaryButton(stringResource(R.string.update_now), onUpdate)
            }) {
                Text(if (u.name.isNotEmpty()) stringResource(R.string.update_named, u.name) else stringResource(R.string.update_unnamed))
            }
        }
        if (me?.demo == true) {
            Notice(actions = { SecondaryButton(stringResource(R.string.demo_create), onLeaveDemo) }) {
                Text(stringResource(R.string.demo_title), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.demo_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (me?.confirmEmail == true) {
            Notice(actions = { if (!state.confirmSent) LinkButton(stringResource(R.string.confirm_again), shell::resendConfirmation) }) {
                Text(stringResource(if (state.confirmSent) R.string.confirm_sent else R.string.confirm_email, me.email), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TabBar(current: TopLevelDestination, unseen: Int, onSelect: (TopLevelDestination) -> Unit) {
    val colors = SamFranTheme.colors
    NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
        for (tab in TopLevelDestination.entries) {
            val selected = tab == current
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(tab) },
                icon = {
                    if (tab == TopLevelDestination.Us && unseen > 0) {
                        val badge = pluralStringResource(R.plurals.new_changes, unseen, unseen)
                        BadgedBox(badge = { Badge(Modifier.semantics { contentDescription = badge }, containerColor = colors.accent) }) {
                            LineIcon(tab.icon, null)
                        }
                    } else {
                        LineIcon(tab.icon, null)
                    }
                },
                label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.accent,
                    selectedTextColor = colors.accent,
                    indicatorColor = colors.a.bg,
                    unselectedIconColor = colors.muted,
                    unselectedTextColor = colors.muted,
                ),
            )
        }
    }
}
