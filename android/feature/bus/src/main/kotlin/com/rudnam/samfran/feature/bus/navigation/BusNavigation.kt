package com.rudnam.samfran.feature.bus.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.samfran.feature.bus.BusDestination
import kotlinx.serialization.Serializable

/** The Bus tab (the website's bus); [start]: opened from "Start bus alarm?", which starts the trip. */
@Serializable
data class BusRoute(val start: Boolean = false)

fun NavGraphBuilder.busScreen(@Suppress("UNUSED_PARAMETER") snackbar: SnackbarHostState) {
    composable<BusRoute> { BusDestination() }
}
