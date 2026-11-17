package com.rudnam.samfran.core.data

import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.TripStatus
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.model.NetworkBusStop
import com.rudnam.samfran.core.network.model.NetworkBusStopInput
import com.rudnam.samfran.core.network.model.asModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where the signed-in person gets off the bus; the server only ever knows the stop, never the phone's position. */
interface BusRepository {
    val stop: Flow<BusStop?>

    suspend fun refresh(): Outcome<BusStop>

    /** Without [buzz], it stays as it was. */
    suspend fun save(name: String, lat: Double, lng: Double, alertMeters: Int, buzz: Buzz? = null): Outcome<BusStop>

    /** Stops and stations whose names match [query] (2+ characters); near ones first when [near] is given. */
    suspend fun search(query: String, near: Pair<Double, Double>?): Outcome<List<FoundStop>>
}

@Singleton
internal class OfflineFirstBusRepository @Inject constructor(
    private val network: SamFranNetwork,
    private val documents: Documents,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : BusRepository {
    override val stop: Flow<BusStop?> = documents.observe(Keys.BUS_STOP, NetworkBusStop.serializer()) { it.asModel() }

    override suspend fun refresh() = outcome(io) {
        network.busStop().also { documents.put(Keys.BUS_STOP, NetworkBusStop.serializer(), it) }.asModel()
    }

    override suspend fun save(name: String, lat: Double, lng: Double, alertMeters: Int, buzz: Buzz?) = outcome(io) {
        network.saveBusStop(NetworkBusStopInput(name.trim(), lat, lng, alertMeters, buzz?.api))
            .also { documents.put(Keys.BUS_STOP, NetworkBusStop.serializer(), it) }.asModel()
    }

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

    /** Buzzes now, the way [buzz] says, to feel what it's like. */
    fun test(stopName: String, buzz: Buzz)

    /** Where the phone is now (to choose the stop while standing at it); null if it can't tell. */
    suspend fun here(): Pair<Double, Double>?
}
