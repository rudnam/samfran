package com.rudnam.samfran.feature.bus

import android.annotation.SuppressLint
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.ui.currentLocale

/**
 * A map of the stop: Google Maps itself, zoomed in close enough to see the
 * shops and buildings around it, with a pin on the stop, as the website shows
 * it (web/src/features/bus/StopMap.tsx, the same keyless embed link). It can
 * be dragged and zoomed. Only Google's map page loads here; anything it links
 * to opens in the browser instead.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun StopMap(lat: Double, lng: Double, name: String, modifier: Modifier = Modifier) {
    val lang = currentLocale.language
    val url = Uri.parse("https://www.google.com/maps").buildUpon()
        .appendQueryParameter("q", "$lat,$lng")
        .appendQueryParameter("z", "17")
        .appendQueryParameter("hl", lang)
        .appendQueryParameter("output", "embed")
        .build().toString()
    val description = stringResource(R.string.stop_on_map, name.ifBlank { stringResource(R.string.your_stop_lower) })
    Box(
        modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(MaterialTheme.shapes.small)
            .background(SamFranTheme.colors.sunken)
            .semantics { contentDescription = description },
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true // Google's map needs it
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val host = request.url.host.orEmpty()
                            if (host == "www.google.com" && request.url.path.orEmpty().startsWith("/maps/embed")) return false
                            // "View larger map" and the like: Google Maps, outside the app.
                            if (request.isForMainFrame) context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                            return true
                        }
                    }
                    // Dragging the map moves the map, not the page.
                    setOnTouchListener { v, e ->
                        if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    tag = url
                    loadUrl(url)
                }
            },
            // Another stop (or language): load it. Google redirects to its own address, so the asked one is kept.
            update = { if (it.tag != url) {
                it.tag = url
                it.loadUrl(url)
            } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
