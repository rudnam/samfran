package com.rudnam.samfran.feature.food.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rudnam.samfran.feature.food.FoodListDestination
import com.rudnam.samfran.feature.food.PlaceDestination
import com.rudnam.samfran.feature.food.PlaceFormDestination
import kotlinx.serialization.Serializable

/** The Food tab: the website's home (been there) and want. */
@Serializable
data object FoodRoute

/** One place (the website's restaurant?id=…). */
@Serializable
data class PlaceRoute(val id: Long)

/**
 * Adding a place (the website's add, with want and shared) or editing one
 * ([id]). [shared] is text shared from Google Maps, to fill it in.
 */
@Serializable
data class PlaceFormRoute(val id: Long? = null, val want: Boolean = false, val shared: String? = null)

/** Where the Food pages go. */
interface FoodNavigation {
    fun openPlace(id: Long)
    fun addPlace(want: Boolean)
    fun editPlace(id: Long)
    /** A place saved from the form: its page, in the form's place. */
    fun placeSaved(id: Long)
    fun back()
}

fun NavGraphBuilder.foodScreens(navigation: FoodNavigation, snackbar: SnackbarHostState) {
    composable<FoodRoute> { FoodListDestination(navigation) }
    composable<PlaceRoute> { PlaceDestination(navigation, snackbar) }
    composable<PlaceFormRoute> { PlaceFormDestination(navigation) }
}
