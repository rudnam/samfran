package com.rudnam.samfran.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rudnam.samfran.core.designsystem.theme.DarkSamFranColors
import com.rudnam.samfran.core.designsystem.theme.LightSamFranColors
import com.rudnam.samfran.core.designsystem.theme.Pair2
import com.rudnam.samfran.core.designsystem.theme.ShiftDotColors
import com.rudnam.samfran.core.model.ShiftTone

/**
 * The app's colours (core/designsystem's theme), light and dark as the
 * phone is: a widget follows the phone's dark mode, not the app's own
 * appearance setting.
 */
internal object WidgetColors {
    private val l = LightSamFranColors
    private val d = DarkSamFranColors

    val surface = ColorProvider(day = l.surface, night = d.surface)
    val sunken = ColorProvider(day = l.sunken, night = d.sunken)
    val text = ColorProvider(day = l.text, night = d.text)
    val muted = ColorProvider(day = l.muted, night = d.muted)
    val accent = ColorProvider(day = l.accent, night = d.accent)
    val accentInk = ColorProvider(day = l.accentInk, night = d.accentInk)
    val accentText = ColorProvider(day = l.accentText, night = d.accentText)

    /** A thin line on the accent. */
    val accentLine = ColorProvider(day = l.accentText.copy(alpha = 0.3f), night = d.accentText.copy(alpha = 0.3f))

    /** A shift's dot (.shift-dot.*), the same light and dark; null for the kinds without one. */
    fun dot(tone: ShiftTone): ColorProvider? = when (tone) {
        ShiftTone.Early -> ShiftDotColors.early
        ShiftTone.Middle -> ShiftDotColors.middle
        ShiftTone.Late -> ShiftDotColors.late
        ShiftTone.Night -> ShiftDotColors.night
        ShiftTone.Off -> ShiftDotColors.off
        ShiftTone.Leave -> ShiftDotColors.leave
        ShiftTone.Training -> ShiftDotColors.training
        ShiftTone.Other -> null
    }?.let { ColorProvider(it) }

    /** A day's square, as in the Shifts calendar (.cal-day.*): background and text. Off days and unknown ones are quiet. */
    fun square(tone: ShiftTone?): Pair<ColorProvider, ColorProvider> {
        fun pick(f: (com.rudnam.samfran.core.designsystem.theme.SamFranColors) -> Pair2) =
            ColorProvider(day = f(l).bg, night = f(d).bg) to ColorProvider(day = f(l).ink, night = f(d).ink)
        return when (tone) {
            ShiftTone.Early -> pick { it.calEarly }
            ShiftTone.Middle -> pick { it.calMiddle }
            ShiftTone.Late -> pick { it.calLate }
            ShiftTone.Night -> pick { it.calNight }
            ShiftTone.Leave -> pick { it.calLeave }
            ShiftTone.Training -> pick { it.calTraining }
            else -> sunken to muted
        }
    }
}

/**
 * The widget's frame: the app's card, rounded (Android 12+ rounds it to the
 * launcher's corners), opening [open] when tapped. [oneRow]: one cell high,
 * where two lines of text only fit with less room above and below. Without
 * [open] it has no padding either: the content has its own places to tap.
 */
@Composable
internal fun WidgetFrame(open: Action?, background: ColorProvider = WidgetColors.surface, oneRow: Boolean = false, content: @Composable () -> Unit) {
    var m = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(background)
    if (open != null) m = m.clickable(open).padding(horizontal = 14.dp, vertical = if (oneRow) 4.dp else 12.dp)
    Box(m) { content() }
}

@Composable
internal fun WidgetText(
    text: String,
    size: TextUnit,
    color: ColorProvider = WidgetColors.text,
    bold: Boolean = false,
    maxLines: Int = 1,
    modifier: GlanceModifier = GlanceModifier,
) {
    Text(text, modifier, TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal), maxLines)
}

/** A round dot of [size]. */
@Composable
internal fun Dot(color: ColorProvider, size: Dp) {
    Box(GlanceModifier.size(size).cornerRadius(size / 2).background(color)) {}
}
