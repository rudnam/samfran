package com.rudnam.samfran.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import com.rudnam.samfran.R
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.ui.Dates
import java.time.format.TextStyle as DateTextStyle

/**
 * The Next shift widget: the shift on now or the next one (when, what kind,
 * the times, who's on it too), and, when it's big enough, the week ahead as
 * the Shifts calendar's coloured squares. Tapping it opens the Shifts tab.
 */
class NextShiftWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = Widgets.shiftState(context)
        provideContent { NextShiftContent(state) }
    }

    companion object {
        /** One row: when and what, and the times. */
        val COMPACT = DpSize(110.dp, 40.dp)

        /** With whose shift it is, the code and who else is on. */
        val MEDIUM = DpSize(150.dp, 100.dp)

        /** With the week ahead. */
        val LARGE = DpSize(250.dp, 165.dp)
    }
}

class NextShiftWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextShiftWidget()
}

@Composable
internal fun NextShiftContent(state: ShiftWidgetState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val oneRow = size.height < NextShiftWidget.MEDIUM.height
    WidgetFrame(actionStartActivity(Widgets.shiftsLink(context)), oneRow = oneRow) {
        when (state) {
            ShiftWidgetState.Empty -> Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                WidgetText(context.getString(R.string.widget_next_shift), 13.sp, WidgetColors.muted, bold = true)
                WidgetText(context.getString(R.string.widget_open_app), 14.sp, maxLines = 2)
            }
            is ShiftWidgetState.Shifts -> when {
                oneRow || size.width < NextShiftWidget.MEDIUM.width -> CompactShift(context, state)
                else -> FullShift(context, state, week = size.height >= NextShiftWidget.LARGE.height && size.width >= NextShiftWidget.LARGE.width)
            }
        }
    }
}

/** "Tomorrow · Late", "Now · Late", "Wed 4 · Early". */
private fun headline(context: Context, state: ShiftWidgetState.Shifts, next: ShiftDay): String {
    val locale = context.resources.configuration.locales[0]
    val day = when {
        state.onNow -> context.getString(R.string.widget_now)
        next.date == state.today -> context.getString(R.string.widget_today)
        next.date == state.today.plusDays(1) -> context.getString(R.string.widget_tomorrow)
        else -> Dates.day(next.date, locale)
    }
    return "$day · ${next.workplaceLabel}"
}

/** "13:00–22:00", or "until 22:00" while it's on. */
private fun times(context: Context, state: ShiftWidgetState.Shifts, next: ShiftDay): String {
    val end = next.end
    return if (state.onNow && end != null) context.getString(R.string.widget_until, "${end.hour}:%02d".format(end.minute)) else next.times
}

@Composable
private fun CompactShift(context: Context, state: ShiftWidgetState.Shifts) {
    val next = state.next
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (next == null) {
            WidgetText(context.getString(R.string.widget_no_shifts), 14.sp, WidgetColors.muted, maxLines = 2)
            return@Row
        }
        WidgetColors.dot(next.tone)?.let {
            Dot(it, 10.dp)
            Spacer(GlanceModifier.width(8.dp))
        }
        Column {
            WidgetText(headline(context, state, next), 14.sp, bold = true)
            times(context, state, next).takeIf { it.isNotEmpty() }?.let { WidgetText(it, 12.sp, WidgetColors.muted) }
        }
    }
}

@Composable
private fun FullShift(context: Context, state: ShiftWidgetState.Shifts, week: Boolean) {
    val next = state.next
    val title = when {
        state.onNow && state.person.isNotEmpty() -> context.getString(R.string.widget_shift_of, state.person)
        state.onNow -> context.getString(R.string.widget_shift)
        state.person.isNotEmpty() -> context.getString(R.string.widget_next_shift_of, state.person)
        else -> context.getString(R.string.widget_next_shift)
    }
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = if (week) Alignment.Top else Alignment.CenterVertically) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WidgetText(title, 12.sp, WidgetColors.accentInk, bold = true, modifier = GlanceModifier.defaultWeight())
            if (next != null) CodeChip(next.code)
        }
        Spacer(GlanceModifier.height(4.dp))
        if (next == null) {
            WidgetText(context.getString(R.string.widget_no_shifts), 15.sp, maxLines = 2)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WidgetColors.dot(next.tone)?.let {
                    Dot(it, 10.dp)
                    Spacer(GlanceModifier.width(7.dp))
                }
                WidgetText(headline(context, state, next), 17.sp, bold = true)
            }
            times(context, state, next).takeIf { it.isNotEmpty() }?.let { WidgetText(it, 15.sp) }
            next.with.takeIf { it.isNotEmpty() }?.let {
                WidgetText(context.getString(R.string.widget_with, it.joinToString(", ")), 12.sp, WidgetColors.muted)
            }
        }
        if (week) {
            Spacer(GlanceModifier.defaultWeight())
            Week(context, state)
        }
    }
}

/** The code as on the sheet ("13F"), on the sunken ground. */
@Composable
private fun CodeChip(code: String) {
    Box(GlanceModifier.cornerRadius(6.dp).background(WidgetColors.sunken).padding(horizontal = 6.dp, vertical = 1.dp)) {
        WidgetText(code, 11.sp, WidgetColors.muted, bold = true)
    }
}

/** Today and the six days after it, each a weekday letter over the day's square; today's letter in the accent. */
@Composable
private fun Week(context: Context, state: ShiftWidgetState.Shifts) {
    val locale = context.resources.configuration.locales[0]
    Row(GlanceModifier.fillMaxWidth()) {
        state.week.forEach { w ->
            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                val today = w.date == state.today
                WidgetText(
                    w.date.dayOfWeek.getDisplayName(DateTextStyle.NARROW, locale),
                    10.sp,
                    if (today) WidgetColors.accentInk else WidgetColors.muted,
                    bold = today,
                )
                Spacer(GlanceModifier.height(2.dp))
                val (bg, ink) = WidgetColors.square(w.day?.tone)
                Box(GlanceModifier.size(28.dp).cornerRadius(8.dp).background(bg), contentAlignment = Alignment.Center) {
                    WidgetText(w.day?.mark.orEmpty(), 11.sp, ink, bold = true)
                }
            }
        }
    }
}
