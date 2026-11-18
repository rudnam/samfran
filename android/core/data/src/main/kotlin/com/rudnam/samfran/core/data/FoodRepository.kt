package com.rudnam.samfran.core.data

import com.rudnam.samfran.core.common.ApplicationScope
import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.RestaurantFields
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.model.NetworkNewVisit
import com.rudnam.samfran.core.network.model.NetworkRestaurant
import com.rudnam.samfran.core.network.model.NetworkRestaurantInput
import com.rudnam.samfran.core.network.model.asModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** What a Google Maps link leads to (POST /api/restaurants/lookup). */
data class MapLookup(val name: String, val lat: Double?, val lng: Double?, val foundByName: Boolean)

/** The Food tab: places you've been (and when) and want to go, and their ratings. */
interface FoodRepository {
    /** Every place, as last fetched; null before the first answer. */
    val restaurants: Flow<List<Restaurant>?>

    suspend fun refresh(): Outcome<Unit>

    /** One place with its visits; until those come, as the list knows it. */
    fun restaurant(id: Long): Flow<Restaurant?>

    suspend fun refresh(id: Long): Outcome<Restaurant>

    /** A new place; [visitedOn] logs a first visit (been there), with the creator's rating if [stars] > 0. */
    suspend fun create(fields: RestaurantFields, visitedOn: LocalDate?, stars: Int): Outcome<Restaurant>

    suspend fun update(id: Long, fields: RestaurantFields): Outcome<Restaurant>

    suspend fun delete(id: Long): Outcome<Unit>

    /** Been there on [on]: the one date a place shows (a first visit, or the newest one moved). */
    suspend fun setBeen(id: Long, on: LocalDate): Outcome<Restaurant>

    /** Not been yet: back on the want-to-go list. */
    suspend fun clearBeen(id: Long): Outcome<Restaurant>

    /** The signed-in person's rating; 0 clears it. */
    suspend fun rate(id: Long, stars: Int): Outcome<Restaurant>

    /** The place's name (and, with [position], where it is) from a Google Maps link. */
    suspend fun lookup(url: String, position: Boolean = false): Outcome<MapLookup>

    /**
     * Places to eat whose names match [query] (2+ characters), near ones first when [near] is given: from Google
     * Places when the server has a key (each has a placeId; ask [place] for the rest), else from OpenStreetMap.
     * [session] is the form's, shared with [place], so Google counts typing and picking as one.
     */
    suspend fun search(query: String, near: Pair<Double, Double>?, session: String): Outcome<List<com.rudnam.samfran.core.model.FoundPlace>>

    /** A place Google suggested, with where it is, its Maps link and its food. */
    suspend fun place(placeId: String, session: String): Outcome<com.rudnam.samfran.core.model.FoundPlace>
}

@Singleton
internal class OfflineFirstFoodRepository @Inject constructor(
    private val network: SamFranNetwork,
    private val documents: Documents,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : FoodRepository {
    private val listSerializer = ListSerializer(NetworkRestaurant.serializer())

    override val restaurants: Flow<List<Restaurant>?> =
        documents.observe(Keys.RESTAURANTS, listSerializer) { list -> list.map { it.asModel() } }

    override suspend fun refresh(): Outcome<Unit> = outcome(io) {
        documents.put(Keys.RESTAURANTS, listSerializer, network.restaurants())
    }

    override fun restaurant(id: Long): Flow<Restaurant?> = combine(
        documents.observe(Keys.restaurant(id), NetworkRestaurant.serializer()) { it.asModel() },
        restaurants,
    ) { detail, list -> detail ?: list?.firstOrNull { it.id == id } }

    override suspend fun refresh(id: Long) = changed { network.restaurant(id) }

    override suspend fun create(fields: RestaurantFields, visitedOn: LocalDate?, stars: Int) = changed {
        network.createRestaurant(
            fields.input().copy(
                visit = visitedOn?.let { NetworkNewVisit(it.toString()) },
                stars = stars.takeIf { visitedOn != null && it > 0 },
            ),
        )
    }

    override suspend fun update(id: Long, fields: RestaurantFields) = changed { network.updateRestaurant(id, fields.input()) }

    override suspend fun delete(id: Long): Outcome<Unit> = outcome(io) {
        network.deleteRestaurant(id)
        documents.delete(Keys.restaurant(id))
        documents.get(Keys.RESTAURANTS, listSerializer)?.let { list ->
            documents.put(Keys.RESTAURANTS, listSerializer, list.filter { it.id != id })
        }
    }

    override suspend fun setBeen(id: Long, on: LocalDate) = changed { network.setBeen(id, on.toString()) }

    override suspend fun clearBeen(id: Long) = changed { network.clearBeen(id) }

    override suspend fun rate(id: Long, stars: Int) = changed { network.rate(id, stars) }

    override suspend fun lookup(url: String, position: Boolean) = outcome(io) {
        network.lookup(url.trim(), position).let { MapLookup(it.name, it.lat, it.lng, it.foundByName) }
    }

    override suspend fun search(query: String, near: Pair<Double, Double>?, session: String) = outcome(io) {
        network.suggestPlaces(query.trim(), near?.first, near?.second, session).map { it.asModel() }
    }

    override suspend fun place(placeId: String, session: String) = outcome(io) { network.placeDetails(placeId, session).asModel() }

    /**
     * Runs a change the server answers with the place as it now is: that's
     * kept, the list shows it at once, and the list is fetched again
     * meanwhile (a visit's notes, counts).
     */
    private suspend fun changed(block: suspend () -> NetworkRestaurant): Outcome<Restaurant> {
        val result = outcome(io) {
            val fresh = block()
            documents.put(Keys.restaurant(fresh.id), NetworkRestaurant.serializer(), fresh)
            val list = documents.get(Keys.RESTAURANTS, listSerializer).orEmpty()
            val summary = fresh.copy(visits = null, visitNotes = list.firstOrNull { it.id == fresh.id }?.visitNotes)
            val updated = if (list.any { it.id == fresh.id }) list.map { if (it.id == fresh.id) summary else it } else list + summary
            documents.put(Keys.RESTAURANTS, listSerializer, updated)
            fresh.asModel()
        }
        if (result is Outcome.Done) scope.launch { refresh() }
        return result
    }

    private fun RestaurantFields.input() =
        NetworkRestaurantInput(name.trim(), area.trim(), cuisine.trim(), category.key, mapUrl.trim(), notes.trim(), lat = lat, lng = lng)
}
