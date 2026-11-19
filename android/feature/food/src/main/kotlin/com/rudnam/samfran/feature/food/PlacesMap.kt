package com.rudnam.samfran.feature.food

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Restaurant
import kotlinx.coroutines.launch

/** Whether this build has a Google Maps key (the manifest's, from app/build.gradle.kts): without one, there's no map. */
internal fun hasMapsKey(context: Context): Boolean = try {
    val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    !info.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
} catch (_: PackageManager.NameNotFoundException) {
    false
}

/**
 * The places on Google's map (web/src/features/restaurants/PlacesMap.tsx):
 * a pin per place that has a position, in its category's colour and icon,
 * and the phone's spot when [here] is known. Tapping a pin centers it and
 * shows that place's card at the bottom of the map, which opens it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlacesMap(
    places: List<Restaurant>,
    here: Pair<Double, Double>?,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    card: @Composable (Restaurant, () -> Unit) -> Unit,
) {
    val placed = places.filter { it.lat != null && it.lng != null }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(34.7108, 137.7261), 12f) }

    // Clear selection if the selected place is no longer in the list (e.g. filter changed).
    LaunchedEffect(placed) {
        if (selected != null && placed.none { it.id == selected }) {
            selected = null
        }
    }

    // Fit the map to the places when the set of places changes, not on every touch or minor GPS drift.
    val points = remember(placed, here) {
        placed.map { LatLng(it.lat!!, it.lng!!) } + listOfNotNull(here?.let { LatLng(it.first, it.second) })
    }
    val fitKey = remember(placed) {
        placed.map { it.id }.sorted().joinToString(",")
    }
    var lastFitKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loaded, fitKey) {
        if (!loaded || points.isEmpty()) return@LaunchedEffect
        if (fitKey != lastFitKey) {
            lastFitKey = fitKey
            if (points.size == 1) {
                camera.animate(CameraUpdateFactory.newLatLngZoom(points.first(), 15f))
            } else {
                val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
                camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 100))
            }
        }
    }

    // A drag on the map moves the map, not the page around it.
    val disallow = remember { RequestDisallowInterceptTouchEvent() }
    val description = stringResource(R.string.map_of_places)
    val picked = placed.firstOrNull { it.id == selected }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(480.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(SamFranTheme.colors.sunken)
                .pointerInteropFilter(disallow) {
                    disallow(true)
                    false
                }
                .semantics { contentDescription = description },
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                uiSettings = MapUiSettings(
                    mapToolbarEnabled = false,
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                ),
                onMapLoaded = { loaded = true },
                onMapClick = { selected = null },
            ) {
                for (p in placed) {
                    val isSelected = p.id == selected
                    MarkerComposable(
                        p.id, p.category, isSelected,
                        state = rememberUpdatedMarkerState(LatLng(p.lat!!, p.lng!!)),
                        title = p.name,
                        zIndex = if (isSelected) 1f else 0f,
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.988f),
                        onClick = {
                            selected = p.id
                            scope.launch {
                                camera.animate(CameraUpdateFactory.newLatLng(LatLng(p.lat!!, p.lng!!)))
                            }
                            true
                        },
                    ) { Pin(p, isSelected) }
                }
                here?.let { h ->
                    MarkerComposable(h, state = rememberUpdatedMarkerState(LatLng(h.first, h.second)), anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF2F6FD6)))
                        }
                    }
                }
            }

            // Quick controls in the top-end corner: My location and Recenter
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (here != null) {
                    MapButton(
                        icon = SamFranIcons.nearby,
                        description = stringResource(R.string.my_location),
                        onClick = {
                            scope.launch {
                                camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(here.first, here.second), 15f))
                            }
                        },
                    )
                }
                if (points.isNotEmpty()) {
                    MapButton(
                        icon = SamFranIcons.all,
                        description = stringResource(R.string.recenter_map),
                        onClick = {
                            scope.launch {
                                if (points.size == 1) {
                                    camera.animate(CameraUpdateFactory.newLatLngZoom(points.first(), 15f))
                                } else if (points.size > 1) {
                                    val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
                                    camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                                }
                            }
                        },
                    )
                }
            }

            // Selected place card floating smoothly at the bottom of the map
            AnimatedVisibility(
                visible = picked != null,
                enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { it / 2 },
                exit = fadeOut(animationSpec = tween(100)) + slideOutVertically(animationSpec = tween(100)) { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                picked?.let { p ->
                    card(p) { onOpen(p.id) }
                }
            }
        }
        val missing = places.size - placed.size
        if (missing > 0) Hint(pluralStringResource(R.plurals.n_not_on_map, missing, missing))
    }
}

/** A circular floating control button on the map. */
@Composable
private fun MapButton(icon: String, description: String, onClick: () -> Unit) {
    val colors = SamFranTheme.colors
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(colors.surface.copy(alpha = 0.92f))
            .border(1.dp, colors.border, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(icon, null, size = 18.dp, tint = colors.text)
    }
}

/** A map pin (.pin): a teardrop in the category's colour with its icon in white; bigger when picked. */
@Composable
private fun Pin(place: Restaurant, on: Boolean) {
    val size = if (on) 42.dp else 34.dp
    val shape = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 50, bottomStartPercent = 12)
    // Room for the turned square, whose point is the bottom of the marker.
    Box(Modifier.size(size * 1.45f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .rotate(-45f)
                .clip(shape)
                .background(place.category.pinColor)
                .border(2.dp, Color.White, shape),
            contentAlignment = Alignment.Center,
        ) {
            LineIcon(place.category.icon, null, Modifier.rotate(45f), tint = Color.White, size = size * 0.5f)
        }
    }
}
