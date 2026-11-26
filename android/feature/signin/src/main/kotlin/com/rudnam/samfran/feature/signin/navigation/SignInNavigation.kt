package com.rudnam.samfran.feature.signin.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.samfran.core.designsystem.component.Page
import com.rudnam.samfran.feature.signin.SignInDestination
import kotlinx.serialization.Serializable

/**
 * Signing in, the app's first page when nobody is. [mode] is sign-in or
 * sign-up (leaving a demo for an account of one's own).
 */
@Serializable
data class SignInRoute(val mode: String = "sign-in")

fun NavGraphBuilder.signInScreen() {
    composable<SignInRoute> { Page { SignInDestination() } }
}
