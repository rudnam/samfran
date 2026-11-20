package com.rudnam.samfran.feature.food

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Restaurant

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
 * and the phone's spot when [here] is known. Tapping a pin shows the place's
 * card under the map, which opens it.
 *
 * [onHeld] says when a finger goes down on the map and when the last one
 * lifts: the page around it must stop scrolling meanwhile (TabPage's
 * userScrollEnabled), or it takes every drag that's more up-and-down than
 * sideways and the map only pans sideways.
 */
@Composable
fun PlacesMap(
    places: List<Restaurant>,
    here: Pair<Double, Double>?,
    onOpen: (Long) -> Unit,
    onHeld: (Boolean) -> Unit,
    card: @Composable (Restaurant, () -> Unit) -> Unit,
) {
    val placed = places.filter { it.lat != null && it.lng != null }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(34.7108, 137.7261), 12f) }
    // Fit the map to the places (and the phone) when they change.
    val points = placed.map { LatLng(it.lat!!, it.lng!!) } + listOfNotNull(here?.let { LatLng(it.first, it.second) })
    LaunchedEffect(loaded, points) {
        if (!loaded || points.isEmpty()) return@LaunchedEffect
        if (points.size == 1) {
            camera.move(CameraUpdateFactory.newLatLngZoom(points.first(), 15f))
        } else {
            val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
            camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        }
    }
    val description = stringResource(R.string.map_of_places)
    // The latest callback, without restarting the watcher (and dropping the hold) mid-drag.
    val held by rememberUpdatedState(onHeld)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(440.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(SamFranTheme.colors.sunken)
                // Watches the fingers before the map sees them, and takes nothing from it.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        held(true)
                        try {
                            while (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) Unit
                        } finally {
                            held(false)
                        }
                    }
                }
                .semantics { contentDescription = description },
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                uiSettings = MapUiSettings(mapToolbarEnabled = false, zoomControlsEnabled = false, myLocationButtonEnabled = false),
                onMapLoaded = { loaded = true },
                onMapClick = { selected = null },
            ) {
                for (p in placed) {
                    MarkerComposable(
                        p.id, p.category, p.id == selected,
                        state = rememberUpdatedMarkerState(LatLng(p.lat!!, p.lng!!)),
                        title = p.name,
                        onClick = {
                            selected = p.id
                            true
                        },
                    ) { Pin(p, p.id == selected) }
                }
                here?.let { h ->
                    MarkerComposable(h, state = rememberUpdatedMarkerState(LatLng(h.first, h.second)), anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF2F6FD6)))
                        }
                    }
                }
            }
        }
        placed.firstOrNull { it.id == selected }?.let { p -> card(p) { onOpen(p.id) } }
        val missing = places.size - placed.size
        if (missing > 0) Hint(pluralStringResource(R.plurals.n_not_on_map, missing, missing))
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
