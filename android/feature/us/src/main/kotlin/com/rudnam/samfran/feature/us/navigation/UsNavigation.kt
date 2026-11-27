package com.rudnam.samfran.feature.us.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.samfran.core.designsystem.component.Page
import com.rudnam.samfran.feature.us.LockAppDestination
import com.rudnam.samfran.feature.us.LocksDestination
import com.rudnam.samfran.feature.us.UsDestination
import kotlinx.serialization.Serializable

/** The Us tab (the website's us). */
@Serializable
data object UsRoute

/** Locked apps (Us › Locked apps). */
@Serializable
data object LocksRoute

/** Choosing an app on this phone to lock. */
@Serializable
data object LockAppRoute

interface UsNavigation {
    /** End the demo and open on creating an account. */
    fun leaveDemo()

    fun openLocks()

    fun lockApp()

    fun back()
}

fun NavGraphBuilder.usScreen(navigation: UsNavigation, @Suppress("UNUSED_PARAMETER") snackbar: SnackbarHostState) {
    composable<UsRoute> { Page { UsDestination(navigation) } }
    composable<LocksRoute> { Page { LocksDestination(navigation) } }
    composable<LockAppRoute> { Page { LockAppDestination(navigation) } }
}
