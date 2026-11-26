package com.rudnam.samfran.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import com.rudnam.samfran.R

/**
 * The Ride alarm widget: one tap on "I'm on the bus" (or the train) opens the
 * Ride tab and starts the trip to the stop it names (the shift's, or else the
 * one used last), the way "Start the ride alarm?" does. The app starts it rather than the
 * widget, so it asks for location when it must and shows the ride with its
 * Stop button. While a trip is on, the widget says so and opens the ride; it
 * has no Stop of its own, so a stray tap on the home screen can't end it.
 */
class BusWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = Widgets.busState(context)
        provideContent { BusContent(state) }
    }

    companion object {
        /** The icon beside the words. */
        val COMPACT = DpSize(110.dp, 40.dp)

        /** The icon above them. */
        val TALL = DpSize(110.dp, 100.dp)
    }
}

class BusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BusWidget()
}

@Composable
internal fun BusContent(state: BusWidgetState) {
    val context = LocalContext.current
    val ready = state as? BusWidgetState.Ready
    val open = actionStartActivity(Widgets.busLink(context, ready?.stopId))
    val (title, detail) = when (state) {
        BusWidgetState.Empty -> context.getString(R.string.widget_bus) to context.getString(R.string.widget_open_app)
        BusWidgetState.NoStop -> context.getString(R.string.widget_bus) to context.getString(R.string.widget_choose_stop)
        is BusWidgetState.Ready -> context.getString(if (state.train) R.string.widget_on_train else R.string.widget_on_bus) to context.getString(R.string.widget_to_stop, state.stopName)
        is BusWidgetState.Riding -> context.getString(if (state.buzzing) R.string.widget_nearly else R.string.widget_riding) to
            context.getString(R.string.widget_to_stop, state.stopName)
    }
    // Ready to start, it's a button in the accent; otherwise the app's card.
    val ink = if (ready != null) WidgetColors.accentText else WidgetColors.text
    val soft = if (ready != null) WidgetColors.accentText else WidgetColors.muted
    val icon = if (ready != null) WidgetColors.accentText else WidgetColors.accent
    val oneRow = LocalSize.current.height < BusWidget.TALL.height
    WidgetFrame(open, background = if (ready != null) WidgetColors.accent else WidgetColors.surface, oneRow = oneRow) {
        if (oneRow) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                BusIcon(icon, 22)
                Spacer(GlanceModifier.width(10.dp))
                Column {
                    WidgetText(title, 14.sp, ink, bold = true)
                    WidgetText(detail, 12.sp, soft)
                }
            }
        } else {
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                BusIcon(icon, 28)
                Spacer(GlanceModifier.height(8.dp))
                WidgetText(title, 16.sp, ink, bold = true, maxLines = 2)
                WidgetText(detail, 13.sp, soft, maxLines = 2)
            }
        }
    }
}

@Composable
private fun BusIcon(color: ColorProvider, size: Int) {
    Image(ImageProvider(R.drawable.ic_stat_bus), contentDescription = null, modifier = GlanceModifier.size(size.dp), colorFilter = ColorFilter.tint(color))
}
