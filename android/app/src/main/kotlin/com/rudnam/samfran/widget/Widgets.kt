package com.rudnam.samfran.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.appwidget.updateAll
import com.rudnam.samfran.BusTripService
import com.rudnam.samfran.MainActivity
import com.rudnam.samfran.core.data.BusRepository
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.data.valueOrNull
import com.rudnam.samfran.core.datastore.SessionStore
import com.rudnam.samfran.core.model.StopKind
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.YearMonth

/**
 * The home-screen widgets ([NextShiftWidget], [BusWidget]). They draw from
 * the phone's copy of the server's answers (Room), so they work offline and
 * cost nothing to redraw. Android redraws them every half hour; the app
 * redraws them whenever it syncs ([update]: opening it, saving shifts or a
 * stop, signing out) and when a bus trip starts or ends.
 */
object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Redraws both widgets, wherever they're placed (nothing if none are). */
    fun update(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { NextShiftWidget().updateAll(app) }
            runCatching { BusWidget().updateAll(app) }
        }
    }

    /** Redraws the bus widget: a trip started or ended. */
    fun updateBus(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { BusWidget().updateAll(app) } }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun shifts(): ShiftsRepository
        fun bus(): BusRepository
        fun session(): SessionStore
        fun clock(): Clock
    }

    private fun deps(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)

    /** This month's shifts and next month's, as the phone has them; this month from the server if the phone's copy is an old month's. */
    internal suspend fun shiftState(context: Context): ShiftWidgetState {
        val d = deps(context)
        if (d.session().currentToken() == null) return ShiftWidgetState.Empty
        val clock = d.clock()
        var current = d.shifts().month(null).first()
        if (current == null || current.month < YearMonth.now(clock)) current = d.shifts().refresh(null).valueOrNull ?: current
        current ?: return ShiftWidgetState.Empty
        val next = d.shifts().month(current.month.plusMonths(1)).first()
        return shiftWidgetState(listOfNotNull(current, next), clock.instant(), clock.zone)
    }

    /** The trip on now, or the stop a trip would go to: the shift you'd be going home from's, else the one used last (as the Ride tab picks). */
    internal suspend fun busState(context: Context): BusWidgetState {
        val d = deps(context)
        if (d.session().currentToken() == null) return BusWidgetState.Empty
        val trip = BusTripService.status.value
        if (trip.running) return BusWidgetState.Riding(BusTripService.currentStopName, trip.buzzing)
        val stops = d.bus().stops.first() ?: d.bus().refresh().valueOrNull ?: return BusWidgetState.Empty
        val stop = stops.pick(d.shifts().homeShift(d.clock().instant())) ?: return BusWidgetState.NoStop
        return BusWidgetState.Ready(stop.id, stop.name, train = stop.kind == StopKind.Train)
    }

    /** The Shifts tab. */
    internal fun shiftsLink(context: Context): Intent = link(context, "shifts")

    /** The Ride tab; with [startStop], a trip to that stop starts at once (as "Start the ride alarm?" does). */
    internal fun busLink(context: Context, startStop: Long? = null): Intent =
        link(context, if (startStop == null) "bus" else "bus?start=1&stop=$startStop")

    private fun link(context: Context, path: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("${context.packageName}://$path"), context, MainActivity::class.java)
}
