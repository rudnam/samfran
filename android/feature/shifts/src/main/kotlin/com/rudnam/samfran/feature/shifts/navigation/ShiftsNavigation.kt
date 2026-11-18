package com.rudnam.samfran.feature.shifts.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.samfran.feature.shifts.ShiftCalendarLinkDestination
import com.rudnam.samfran.feature.shifts.ShiftEditorDestination
import com.rudnam.samfran.feature.shifts.ShiftSettingsDestination
import com.rudnam.samfran.feature.shifts.ShiftsDestination
import kotlinx.serialization.Serializable

/** The Shifts tab (the website's shifts, with month=YYYY-MM); no month is the current one. */
@Serializable
data class ShiftsRoute(val month: String? = null)

/** Editing a month (the website's shifts/edit); [fromPhoto]: checking what the photo reader found. */
@Serializable
data class ShiftEditRoute(val month: String, val fromPhoto: Boolean = false)

/** Shift settings (the website's shifts/settings). */
@Serializable
data object ShiftSettingsRoute

/** Adding the shifts to Google Calendar: the calendar link (the website's shifts/calendar). */
@Serializable
data object ShiftCalendarLinkRoute

interface ShiftsNavigation {
    fun navigate(route: Any)
    /** This page goes, and [route] opens in its place (a saved month). */
    fun replace(route: Any)
    fun back()
}

fun NavGraphBuilder.shiftsScreens(navigation: ShiftsNavigation, @Suppress("UNUSED_PARAMETER") snackbar: SnackbarHostState) {
    composable<ShiftsRoute> { ShiftsDestination(navigation) }
    composable<ShiftEditRoute> { ShiftEditorDestination(navigation) }
    composable<ShiftSettingsRoute> { ShiftSettingsDestination(navigation) }
    composable<ShiftCalendarLinkRoute> { ShiftCalendarLinkDestination(navigation) }
}
