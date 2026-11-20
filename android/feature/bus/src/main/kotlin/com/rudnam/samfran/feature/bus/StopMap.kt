package com.rudnam.samfran.feature.bus

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.mapLink
import com.rudnam.samfran.core.ui.currentLocale

/**
 * A map of the stop, zoomed in close enough to see the shops and buildings
 * around it, with a pin on the stop. Tapping it opens the stop in Google Maps.
 *
 * With a Google Maps key (app/build.gradle.kts) it's Google's native map, in
 * lite mode: a still map, the way Google suggests for a small map in a
 * scrolling page, so swiping over it scrolls the page. Without a key (a build
 * made without it) it's Google's web map, as the website shows it.
 */
@Composable
fun StopMap(lat: Double, lng: Double, name: String, modifier: Modifier = Modifier, height: Dp = 280.dp) {
    val context = LocalContext.current
    val native = remember { hasMapsKey(context) }
    val description = stringResource(R.string.stop_on_map, name.ifBlank { stringResource(R.string.your_stop_lower) })
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(MaterialTheme.shapes.small)
            .background(SamFranTheme.colors.sunken)
            .semantics { contentDescription = description },
    ) {
        if (native) NativeStopMap(lat, lng, name) else WebStopMap(lat, lng)
    }
}

/** Google's native map (lite mode), with a pin on the stop. */
@Composable
private fun NativeStopMap(lat: Double, lng: Double, name: String) {
    val context = LocalContext.current
    val stop = LatLng(lat, lng)
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(stop, 17f) }
    // Another stop: look at it.
    LaunchedEffect(lat, lng) { camera.position = CameraPosition.fromLatLngZoom(stop, 17f) }
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
        cameraPositionState = camera,
        uiSettings = MapUiSettings(mapToolbarEnabled = false, zoomControlsEnabled = false),
        onMapClick = { openOutside(context, Uri.parse(mapLink(lat, lng))) },
    ) {
        Marker(state = rememberUpdatedMarkerState(stop), title = name.ifBlank { null })
    }
}

/**
 * Google's web map (web/src/features/bus/StopMap.tsx, the same keyless embed
 * link). It can be dragged and zoomed. Google only draws the embed inside an
 * iframe (opened as the whole page it says "must be used in an iframe"), so
 * the WebView shows a tiny page holding that iframe, as the website does.
 * Anything the map links to opens outside the app instead.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun WebStopMap(lat: Double, lng: Double) {
    val lang = currentLocale.language
    val url = Uri.parse("https://www.google.com/maps").buildUpon()
        .appendQueryParameter("q", "$lat,$lng")
        .appendQueryParameter("z", "17")
        .appendQueryParameter("hl", lang)
        .appendQueryParameter("output", "embed")
        .build().toString()
    val page = stopMapPage(url)
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true // Google's map needs it
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // The map moving about inside its iframe.
                        if (!request.isForMainFrame) return false
                        // "View larger map" and the like: Google Maps, outside the app.
                        openOutside(context, request.url)
                        return true
                    }
                }
                // Dragging the map moves the map, not the page.
                setOnTouchListener { v, e ->
                    if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                tag = url
                loadDataWithBaseURL(BASE, page, "text/html", "utf-8", null)
            }
        },
        // Another stop (or language): load it.
        update = {
            if (it.tag != url) {
                it.tag = url
                it.loadDataWithBaseURL(BASE, page, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Whether this build has a Google Maps key (the manifest's, from app/build.gradle.kts). */
private fun hasMapsKey(context: Context): Boolean = try {
    val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    !info.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
} catch (_: PackageManager.NameNotFoundException) {
    false
}

/** Google Maps (or the browser), outside the app; nothing happens if nothing on the phone opens it. */
private fun openOutside(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
    }
}

/** Android's address for a page an app makes itself; it's never fetched. */
private const val BASE = "https://appassets.androidplatform.net/"

/** A page that is only Google's map, in an iframe filling it. */
internal fun stopMapPage(embedUrl: String): String {
    val src = embedUrl.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">""" +
        """<style>html,body{margin:0;height:100%;overflow:hidden}iframe{border:0;width:100%;height:100%;display:block}</style>""" +
        """</head><body><iframe src="$src" referrerpolicy="no-referrer-when-downgrade" allowfullscreen></iframe></body></html>"""
}
