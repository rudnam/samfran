package com.rudnam.samfran.core.data

import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import com.rudnam.samfran.core.model.ActivityEntry
import com.rudnam.samfran.core.model.Space
import com.rudnam.samfran.core.model.newestId
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.Server
import com.rudnam.samfran.core.network.model.NetworkActivity
import com.rudnam.samfran.core.network.model.NetworkSpace
import com.rudnam.samfran.core.network.model.asModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** An invite link just made: its address is shown only now. */
data class InviteLinkMade(val url: String, val expires: Instant)

/** The two of you: names, and inviting the other one. */
interface SpaceRepository {
    val space: Flow<Space?>

    suspend fun refresh(): Outcome<Space>

    suspend fun setMyName(name: String): Outcome<Space>

    /** Makes an invite link, replacing an unused one. */
    suspend fun createInviteLink(): Outcome<InviteLinkMade>

    suspend fun cancelInviteLink(): Outcome<Space>

    suspend fun uninvite(email: String): Outcome<Space>

    /** Claude's connector address (Us › Claude). */
    val mcpAddress: String
}

@Singleton
internal class OfflineFirstSpaceRepository @Inject constructor(
    private val network: SamFranNetwork,
    private val documents: Documents,
    private val server: Server,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : SpaceRepository {
    override val space: Flow<Space?> = documents.observe(Keys.SPACE, NetworkSpace.serializer()) { it.asModel() }

    override suspend fun refresh() = kept { network.space() }

    override suspend fun setMyName(name: String) = kept { network.setMyName(name.trim()) }

    override suspend fun createInviteLink() = outcome(io) {
        val link = network.createInviteLink()
        runCatching { documents.put(Keys.SPACE, NetworkSpace.serializer(), network.space()) }
        InviteLinkMade(server.resolve(link.path), Instant.parse(link.expiresAt))
    }

    override suspend fun cancelInviteLink() = kept { network.cancelInviteLink() }

    override suspend fun uninvite(email: String) = kept { network.uninvite(email) }

    override val mcpAddress: String get() = server.resolve("/mcp")

    private suspend fun kept(block: suspend () -> NetworkSpace) = outcome(io) {
        block().also { documents.put(Keys.SPACE, NetworkSpace.serializer(), it) }.asModel()
    }
}

/**
 * Recent changes either of you made, and which of the other person's are
 * new on this phone: the newest one seen is kept on the phone (the website
 * keeps it in the browser). A phone's first look counts everything so far as seen.
 */
interface ActivityRepository {
    val entries: Flow<List<ActivityEntry>?>

    suspend fun refresh(): Outcome<List<ActivityEntry>>

    /** How many of the other person's changes are new here. */
    val unseen: Flow<Int>

    /** The newest change seen here, as Recent changes opened (null: none yet). */
    suspend fun seenUpTo(): Long?

    /** Recent changes has shown the list: nothing in it is new any more. */
    suspend fun markSeen(entries: List<ActivityEntry>)

    /** The latest changes with the bus alarms started, which Recent changes leaves out; not kept. */
    suspend fun forNotifications(): Outcome<List<ActivityEntry>>
}

@Singleton
internal class OfflineFirstActivityRepository @Inject constructor(
    private val network: SamFranNetwork,
    private val documents: Documents,
    private val preferences: UserPreferencesRepository,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : ActivityRepository {
    private val serializer = ListSerializer(NetworkActivity.serializer())

    override val entries: Flow<List<ActivityEntry>?> = documents.observe(Keys.ACTIVITY, serializer) { list -> list.map { it.asModel() } }

    override suspend fun refresh() = outcome(io) {
        val list = network.activity()
        documents.put(Keys.ACTIVITY, serializer, list)
        list.map { it.asModel() }.also { fresh ->
            if (preferences.preferences.first().activitySeen == null) preferences.setActivitySeen(fresh.newestId())
        }
    }

    override val unseen: Flow<Int> = combine(entries, preferences.preferences) { list, prefs ->
        val seen = prefs.activitySeen ?: return@combine 0
        list.orEmpty().count { !it.me && it.id > seen }
    }

    override suspend fun seenUpTo(): Long? = preferences.preferences.first().activitySeen

    override suspend fun markSeen(entries: List<ActivityEntry>) {
        val newest = entries.newestId()
        if (newest > (seenUpTo() ?: 0)) preferences.setActivitySeen(newest)
    }

    override suspend fun forNotifications() = outcome(io) { network.activityWithBus().map { it.asModel() } }
}
