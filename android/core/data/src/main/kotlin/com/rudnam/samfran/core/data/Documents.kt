package com.rudnam.samfran.core.data

import com.rudnam.samfran.core.database.DocumentDao
import com.rudnam.samfran.core.database.DocumentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The server's answers kept on the phone (core:database's documents), read
 * and written as the network models they came as. A document that can't be
 * read any more (an older build's shape) reads as none, and is fetched again.
 */
@Singleton
class Documents @Inject internal constructor(
    private val dao: DocumentDao,
    private val json: Json,
    private val clock: Clock,
) {
    fun <N, M> observe(key: String, serializer: KSerializer<N>, asModel: (N) -> M): Flow<M?> =
        dao.observe(key).distinctUntilChanged().map { text -> text?.let { decode(it, serializer, asModel) } }

    suspend fun <N> get(key: String, serializer: KSerializer<N>): N? =
        dao.get(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    suspend fun <N> put(key: String, serializer: KSerializer<N>, value: N) {
        dao.put(DocumentEntity(key, json.encodeToString(serializer, value), clock.millis()))
    }

    suspend fun delete(key: String) = dao.delete(key)

    suspend fun clear() = dao.clear()

    private fun <N, M> decode(text: String, serializer: KSerializer<N>, asModel: (N) -> M): M? =
        runCatching { asModel(json.decodeFromString(serializer, text)) }.getOrNull()
}

/** The documents' keys, the website's page cache's (web/src/cache.ts, useCached). */
internal object Keys {
    const val RESTAURANTS = "restaurants"
    fun restaurant(id: Long) = "restaurant:$id"
    /** "" for the current month, which the server picks in the shifts' time zone. */
    fun shifts(month: String) = "shifts:$month"
    const val SHIFT_SETTINGS = "shift-settings"
    const val SPACE = "space"
    const val BUS_STOP = "bus-stop"
    const val ACTIVITY = "activity"
}
