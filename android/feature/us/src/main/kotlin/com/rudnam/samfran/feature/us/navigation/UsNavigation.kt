package com.rudnam.samfran.feature.us.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.samfran.feature.us.UsDestination
import kotlinx.serialization.Serializable

/** The Us tab (the website's us). */
@Serializable
data object UsRoute

interface UsNavigation {
    /** End the demo and open on creating an account. */
    fun leaveDemo()
}

fun NavGraphBuilder.usScreen(navigation: UsNavigation, @Suppress("UNUSED_PARAMETER") snackbar: SnackbarHostState) {
    composable<UsRoute> { UsDestination(navigation) }
}
