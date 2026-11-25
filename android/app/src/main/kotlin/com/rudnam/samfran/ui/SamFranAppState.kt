package com.rudnam.samfran.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.rudnam.samfran.R
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.feature.bus.navigation.BusRoute
import com.rudnam.samfran.feature.food.navigation.FoodRoute
import com.rudnam.samfran.feature.food.navigation.PlaceFormRoute
import com.rudnam.samfran.feature.shifts.navigation.ShiftsRoute
import com.rudnam.samfran.feature.signin.navigation.SignInRoute
import com.rudnam.samfran.feature.us.navigation.LocksRoute
import com.rudnam.samfran.feature.us.navigation.UsRoute

/** The four tabs, as on the website. */
enum class TopLevelDestination(@StringRes val label: Int, val icon: String, val route: Any) {
    Food(R.string.tab_food, SamFranIcons.places, FoodRoute),
    Shifts(R.string.tab_shifts, SamFranIcons.shifts, ShiftsRoute()),
    Bus(R.string.tab_bus, SamFranIcons.ride, BusRoute()),
    Us(R.string.tab_us, SamFranIcons.us, UsRoute),
}

/** What opens a page from outside the app: its notifications, and a place shared from Google Maps. */
sealed interface AppLink {
    /** The Bus tab; [start]: from "Start bus alarm?", which starts the trip. */
    /** [start] the trip (to [stop], by id, when given), or [pick] which stop first. */
    data class Bus(val start: Boolean, val stop: Long = 0, val pick: Boolean = false) : AppLink

    data object Us : AppLink

    /** Us › Locked apps: a locked app's notification. */
    data object Locks : AppLink

    /** Add a place, filled in with text shared from Maps. */
    data class AddPlace(val shared: String) : AppLink
}

/** The app's navigation, in one place the app's UI reads. */
@Stable
class SamFranAppState(val navController: NavHostController) {
    private val page: NavBackStackEntry?
        @Composable get() = navController.visibleEntries.collectAsState().value.lastOrNull { it.destination.navigatorName != "dialog" }

    /** The tab whose own page is shown: the tab bar shows only there. A page over a tab has its own way back. */
    val currentTab: TopLevelDestination?
        @Composable get() {
            val destination = page?.destination ?: return null
            return TopLevelDestination.entries.firstOrNull { destination.hasRoute(it.route::class) }
        }

    fun navigateToTab(tab: TopLevelDestination) {
        navController.navigate(tab.route) {
            // Food is the root once someone is signed in (the graph may have started on signing in).
            popUpTo<FoodRoute> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun open(link: AppLink) {
        when (link) {
            is AppLink.Bus -> navController.navigate(BusRoute(start = link.start, stop = link.stop, pick = link.pick)) {
                popUpTo<FoodRoute>()
                launchSingleTop = !link.start && !link.pick
            }
            AppLink.Us -> navigateToTab(TopLevelDestination.Us)
            AppLink.Locks -> {
                navigateToTab(TopLevelDestination.Us)
                navController.navigate(LocksRoute) { launchSingleTop = true }
            }
            is AppLink.AddPlace -> navController.navigate(PlaceFormRoute(shared = link.shared))
        }
    }

    /** The sign-in page is up (signed out). */
    val onSignIn: Boolean
        get() = navController.currentBackStackEntry?.destination?.hasRoute<SignInRoute>() == true

    /** Leaving a demo for an account of one's own: the next sign-in page opens on creating one. */
    var signUpNext: Boolean = false

    fun showSignIn() {
        val mode = if (signUpNext) "sign-up" else "sign-in"
        signUpNext = false
        navController.navigate(SignInRoute(mode)) { popUpTo(0) { inclusive = true } }
    }

    /** A saved month: the Shifts tab's page, showing it, in place of the editor and the page under it. */
    fun showMonth(route: ShiftsRoute) {
        navController.navigate(route) {
            popUpTo<ShiftsRoute> { inclusive = true }
            launchSingleTop = true
        }
    }

    fun showHome() {
        navController.navigate(FoodRoute) { popUpTo(0) { inclusive = true } }
    }
}

@Composable
fun rememberSamFranAppState(navController: NavHostController = rememberNavController()) =
    remember(navController) { SamFranAppState(navController) }
