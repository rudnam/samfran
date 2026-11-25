package com.rudnam.samfran.widget

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.model.ShiftMonth
import com.rudnam.samfran.core.testing.Samples
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private val tokyo = ZoneId.of("Asia/Tokyo")

private fun at(s: String) = Instant.parse(s)

private fun state(now: String, months: List<ShiftMonth> = listOf(Samples.november)) =
    shiftWidgetState(months, at(now), tokyo) as ShiftWidgetState.Shifts

/** What the Next shift widget shows at a given moment (Samples.november). */
class ShiftWidgetStateTest {
    @Test
    fun nextIsTomorrowsShiftOnADayOff() {
        val s = state("2026-11-26T01:00:00Z") // the 26th, 法
        assertEquals("7F", s.next?.code)
        assertEquals(LocalDate.of(2026, 11, 27), s.next?.date)
        assertFalse(s.onNow)
        assertEquals("Sam", s.person)
    }

    @Test
    fun aShiftOnNowIsTheOneShown() {
        val s = state("2026-11-24T06:00:00Z") // 15:00 on the 24th, during 13F (13:00–22:00)
        assertEquals("13F", s.next?.code)
        assertEquals(LocalDate.of(2026, 11, 24), s.next?.date)
        assertTrue(s.onNow)
    }

    @Test
    fun aWorkingDayWithoutTimesCountsUntilItsDayIsOver() {
        val s = state("2026-11-25T00:00:00Z") // 9:00 on the 25th: 13Z, whose length isn't known
        assertEquals("13Z", s.next?.code)
        assertFalse(s.onNow)
    }

    @Test
    fun noneLeftThisMonthWithoutTheNext() {
        val s = state("2026-11-30T01:00:00Z") // the 30th, 普, after the night shift ended
        assertNull(s.next)
        assertEquals(7, s.week.size)
        assertEquals("普", s.week[0].day?.code)
        assertNull(s.week[1].day) // December isn't on the phone
    }

    @Test
    fun weekStartsToday() {
        val s = state("2026-11-26T01:00:00Z")
        assertEquals(LocalDate.of(2026, 11, 26), s.week.first().date)
        assertEquals(LocalDate.of(2026, 12, 2), s.week.last().date)
    }

    @Test
    fun nothingOnThePhone() {
        assertEquals(ShiftWidgetState.Empty, shiftWidgetState(emptyList(), at("2026-11-26T01:00:00Z"), tokyo))
    }
}

/**
 * The widgets drawn as a launcher would (RemoteViews), into build/outputs/roborazzi
 * (CI's native-screenshots): each size, light and dark, and in Japanese.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetScreenshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** A widget that draws [content] at one size. */
    private class Fixed(private val content: @Composable () -> Unit) : GlanceAppWidget() {
        override val sizeMode = SizeMode.Single

        override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { content() }
    }

    private fun shot(name: String, size: DpSize, content: @Composable () -> Unit) = runTest {
        val views = Fixed(content).compose(context, size = size)
        val density = context.resources.displayMetrics.density
        val w = (size.width.value * density).roundToInt()
        val h = (size.height.value * density).roundToInt()
        val pad = (12 * density).roundToInt()
        // On a plain wallpaper-ish ground, so the card's edges show.
        val ground = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(0x8A, 0x9B, 0xA8))
            setPadding(pad, pad, pad, pad)
        }
        ground.addView(views.apply(context, ground), FrameLayout.LayoutParams(w, h))
        ground.measure(View.MeasureSpec.makeMeasureSpec(w + 2 * pad, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h + 2 * pad, View.MeasureSpec.EXACTLY))
        ground.layout(0, 0, w + 2 * pad, h + 2 * pad)
        ground.captureRoboImage("build/outputs/roborazzi/widget-$name.png")
    }

    private val large = DpSize(300.dp, 180.dp)
    private val medium = DpSize(200.dp, 110.dp)
    private val compact = DpSize(200.dp, 56.dp)
    private val square = DpSize(140.dp, 140.dp)
    private val wide = DpSize(200.dp, 56.dp)

    private val tomorrow = state("2026-11-26T01:00:00Z")
    private val onNow = state("2026-11-24T06:00:00Z")
    private val night = state("2026-11-18T01:00:00Z") // 22F tonight, with two others

    @Test fun shiftLarge() = shot("shift-large", large) { NextShiftContent(tomorrow) }

    @Test fun shiftLargeNight() = shot("shift-large-night", large) { NextShiftContent(night) }

    @Test fun shiftMediumOnNow() = shot("shift-medium-now", medium) { NextShiftContent(onNow) }

    @Test fun shiftCompact() = shot("shift-compact", compact) { NextShiftContent(tomorrow) }

    @Test fun shiftNoneLeft() = shot("shift-none", large) { NextShiftContent(state("2026-11-30T01:00:00Z")) }

    @Test fun shiftEmpty() = shot("shift-empty", medium) { NextShiftContent(ShiftWidgetState.Empty) }

    @Test
    @Config(qualifiers = "+night")
    fun shiftLargeDark() = shot("shift-large-dark", large) { NextShiftContent(tomorrow) }

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun shiftLargeJapanese() = shot("shift-large-ja", large) { NextShiftContent(night) }

    @Test fun busReady() = shot("bus-ready", square) { BusContent(BusWidgetState.Ready(1, Samples.stop.name)) }

    @Test fun busReadyWide() = shot("bus-ready-wide", wide) { BusContent(BusWidgetState.Ready(1, Samples.stop.name)) }

    @Test fun busReadyTrain() = shot("bus-ready-train", wide) { BusContent(BusWidgetState.Ready(2, Samples.station.name, train = true)) }

    @Test fun busRiding() = shot("bus-riding", square) { BusContent(BusWidgetState.Riding(Samples.stop.name, buzzing = false)) }

    @Test fun busNearly() = shot("bus-nearly", wide) { BusContent(BusWidgetState.Riding(Samples.stop.name, buzzing = true)) }

    @Test fun busNoStop() = shot("bus-no-stop", square) { BusContent(BusWidgetState.NoStop) }

    @Test
    @Config(qualifiers = "+night")
    fun busReadyDark() = shot("bus-ready-dark", square) { BusContent(BusWidgetState.Ready(1, Samples.stop.name)) }

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun busReadyJapanese() = shot("bus-ready-ja", square) { BusContent(BusWidgetState.Ready(1, Samples.stop.name)) }
}
