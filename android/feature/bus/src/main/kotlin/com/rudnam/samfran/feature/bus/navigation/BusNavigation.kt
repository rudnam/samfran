package com.rudnam.samfran.feature.bus.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.samfran.feature.bus.BusDestination
import kotlinx.serialization.Serializable

/** The Bus tab (the website's bus). */
@Serializable
data object BusRoute

fun NavGraphBuilder.busScreen(@Suppress("UNUSED_PARAMETER") snackbar: SnackbarHostState) {
    composable<BusRoute> { BusDestination() }
}
