package com.rudnam.samfran.feature.bus.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.samfran.feature.bus.BusDestination
import kotlinx.serialization.Serializable

/**
 * The Bus tab (the website's bus). From "Start bus alarm?": [start] starts
 * the trip, to [stop] when given (its id); [pick] asks which stop first.
 */
@Serializable
data class BusRoute(val start: Boolean = false, val stop: Long = 0, val pick: Boolean = false)

fun NavGraphBuilder.busScreen(@Suppress("UNUSED_PARAMETER") snackbar: SnackbarHostState) {
    composable<BusRoute> { BusDestination() }
}
