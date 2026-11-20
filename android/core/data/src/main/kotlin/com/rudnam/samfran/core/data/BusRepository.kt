package com.rudnam.samfran.core.data

import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.BusStops
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.StopKind
import com.rudnam.samfran.core.model.TripStatus
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.model.NetworkAlarmSettings
import com.rudnam.samfran.core.network.model.NetworkBusStops
import com.rudnam.samfran.core.network.model.NetworkBusStopInput
import com.rudnam.samfran.core.network.model.asModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A stop as it's saved: [shifts], [ask] or [kind] null keeps a changed stop's own. */
data class StopInput(
    val name: String,
    val lat: Double,
    val lng: Double,
    val alertMeters: Int,
    val shifts: List<String>? = null,
    val ask: Boolean? = null,
    val kind: StopKind? = null,
)

/** Where the signed-in person gets off the bus or the train; the server only ever knows the stops, never the phone's position. */
interface BusRepository {
    val stops: Flow<BusStops?>

    suspend fun refresh(): Outcome<BusStops>

    suspend fun add(stop: StopInput): Outcome<BusStops>

    suspend fun change(id: Long, stop: StopInput): Outcome<BusStops>

    suspend fun remove(id: Long): Outcome<BusStops>

    /** A trip to it has started: it's the one used last. */
    suspend fun use(id: Long): Outcome<BusStops>

    suspend fun setBuzz(buzz: Buzz): Outcome<BusStops>

    /** Seconds of buzzing before the alarm sound joins in (0: never). */
    suspend fun setSoundAfter(seconds: Int): Outcome<BusStops>

    /** Stops and stations whose names match [query] (2+ characters); near ones first when [near] is given. */
    suspend fun search(query: String, near: Pair<Double, Double>?): Outcome<List<FoundStop>>
}

@Singleton
internal class OfflineFirstBusRepository @Inject constructor(
    private val network: SamFranNetwork,
    private val documents: Documents,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : BusRepository {
    private val serializer = NetworkBusStops.serializer()

    override val stops: Flow<BusStops?> = documents.observe(Keys.BUS_STOPS, serializer) { it.asModel() }

    private suspend fun keep(f: suspend () -> NetworkBusStops) = outcome(io) { f().also { documents.put(Keys.BUS_STOPS, serializer, it) }.asModel() }

    private fun StopInput.body() = NetworkBusStopInput(name.trim(), lat, lng, alertMeters, shifts, ask, kind?.api)

    override suspend fun refresh() = keep { network.busStops() }

    override suspend fun add(stop: StopInput) = keep { network.addBusStop(stop.body()) }

    override suspend fun change(id: Long, stop: StopInput) = keep { network.changeBusStop(id, stop.body()) }

    override suspend fun remove(id: Long) = keep { network.deleteBusStop(id) }

    override suspend fun use(id: Long) = keep { network.useBusStop(id) }

    override suspend fun setBuzz(buzz: Buzz) = keep { network.setAlarm(NetworkAlarmSettings(buzz = buzz.api)) }

    override suspend fun setSoundAfter(seconds: Int) = keep { network.setAlarm(NetworkAlarmSettings(soundAfter = seconds)) }

    override suspend fun search(query: String, near: Pair<Double, Double>?) = outcome(io) {
        network.searchStops(query.trim(), near?.first, near?.second).map { it.asModel() }
    }
}

/**
 * The bus-stop alarm on this phone (the app's BusTripService): a trip that
 * watches the phone's position and buzzes near the stop. The screen asks for
 * location and notification permission before starting one.
 */
interface BusAlarm {
    val status: StateFlow<TripStatus>

    fun start(stop: BusStop)

    fun stop()

    /** "I'm up": stops buzzing, and the trip with it. */
    fun stopBuzz()

    /** Buzzes now, the way [buzz] says, to feel what it's like (with no sound: that's [hearSound]). */
    fun test(stopName: String, buzz: Buzz)

    /** Plays the alarm sound for a few seconds, rising as it does when the buzz isn't stopped. */
    fun hearSound()

    /** Where the phone is now (to choose the stop while standing at it); null if it can't tell. */
    suspend fun here(): Pair<Double, Double>?
}
