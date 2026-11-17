package com.rudnam.samfran.core.testing

import com.rudnam.samfran.core.data.ActivityRepository
import com.rudnam.samfran.core.data.AuthRepository
import com.rudnam.samfran.core.data.BusAlarm
import com.rudnam.samfran.core.data.BusRepository
import com.rudnam.samfran.core.data.FoodRepository
import com.rudnam.samfran.core.data.GoogleResult
import com.rudnam.samfran.core.data.GoogleReturn
import com.rudnam.samfran.core.data.InviteLinkMade
import com.rudnam.samfran.core.data.MapLookup
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.data.SpaceRepository
import com.rudnam.samfran.core.data.UpdateRepository
import com.rudnam.samfran.core.data.UserRepository
import com.rudnam.samfran.core.model.ActivityEntry
import com.rudnam.samfran.core.model.AppUpdate
import com.rudnam.samfran.core.model.AuthOptions
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.McpToken
import com.rudnam.samfran.core.model.Me
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.RestaurantFields
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.model.ShiftMonth
import com.rudnam.samfran.core.model.ShiftSettings
import com.rudnam.samfran.core.model.Space
import com.rudnam.samfran.core.model.TripStatus
import com.rudnam.samfran.core.model.Visit
import com.rudnam.samfran.core.model.newestId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * In-memory stand-ins for the repositories, for ViewModel and screen tests.
 * [next] is what the next change answers when it isn't Done (Offline, a refusal).
 */
class FakeAuthRepository(var options: AuthOptions = AuthOptions(google = true, demo = true, reset = true)) : AuthRepository {
    var next: Outcome<Unit> = Outcome.Done(Unit)
    val signedIn = mutableListOf<String>()

    override suspend fun options() = options
    override suspend fun signIn(email: String, password: String) = next.also { if (it is Outcome.Done) signedIn += email }
    override suspend fun signUp(email: String, password: String) = next.also { if (it is Outcome.Done) signedIn += email }
    override suspend fun startDemo() = next
    override suspend fun forgotPassword(email: String) = next
    override fun googleSignInUrl() = "https://samfran.example/api/auth/google?app_challenge=x"
    override suspend fun finishGoogle(back: GoogleReturn): GoogleResult =
        if (back.code != null) GoogleResult.SignedIn else GoogleResult.Failed(back.error ?: "failed")
}

class FakeUserRepository(me: Me? = Me("fran@example.com")) : UserRepository {
    val meFlow = MutableStateFlow(me)
    var signedOut = false
    var tokens = listOf(McpToken(1, "Claude", Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-10T00:00:00Z")))

    override val me: Flow<Me?> = meFlow
    override suspend fun refreshMe(): Outcome<Me> = meFlow.value?.let { Outcome.Done(it) } ?: Outcome.SignedOut
    override suspend fun signOut() {
        signedOut = true
        meFlow.value = null
    }
    override suspend fun resendConfirmation() = Outcome.Done(Unit)
    override suspend fun mcpTokens(): Outcome<List<McpToken>> = Outcome.Done(tokens)
    override suspend fun deleteMcpToken(id: Long): Outcome<Unit> {
        tokens = tokens.filter { it.id != id }
        return Outcome.Done(Unit)
    }
}

class FakeUpdateRepository(var update: AppUpdate? = null) : UpdateRepository {
    var checks = 0

    override suspend fun check() = update.also { checks++ }
    override suspend fun later(update: AppUpdate) {
        this.update = null
    }
}

class FakeFoodRepository(places: List<Restaurant>? = Samples.restaurants) : FoodRepository {
    val list = MutableStateFlow(places)
    var next: Outcome<Nothing>? = null
    var lookupName = ""
    var places: List<com.rudnam.samfran.core.model.FoundPlace> = Samples.places
    private var nextId = 100L

    override val restaurants: Flow<List<Restaurant>?> = list
    override suspend fun refresh(): Outcome<Unit> = next ?: Outcome.Done(Unit)
    override fun restaurant(id: Long): Flow<Restaurant?> = list.map { l -> l?.firstOrNull { it.id == id } }
    override suspend fun refresh(id: Long): Outcome<Restaurant> = next ?: find(id)

    override suspend fun create(fields: RestaurantFields, visitedOn: LocalDate?, stars: Int): Outcome<Restaurant> {
        next?.let { return it }
        val r = Restaurant(
            nextId++, fields.name, fields.area, fields.cuisine, fields.mapUrl, fields.notes,
            visitCount = if (visitedOn != null) 1 else 0, lastVisit = visitedOn,
            ratings = if (stars > 0 && visitedOn != null) listOf(com.rudnam.samfran.core.model.Rating("Fran", true, stars)) else emptyList(),
            visits = visitedOn?.let { listOf(Visit(1, it, "", "Fran")) } ?: emptyList(),
        )
        list.value = list.value.orEmpty() + r
        return Outcome.Done(r)
    }

    override suspend fun update(id: Long, fields: RestaurantFields) = change(id) {
        it.copy(name = fields.name, area = fields.area, cuisine = fields.cuisine, mapUrl = fields.mapUrl, notes = fields.notes)
    }

    override suspend fun delete(id: Long): Outcome<Unit> {
        next?.let { return it }
        list.value = list.value?.filter { it.id != id }
        return Outcome.Done(Unit)
    }

    override suspend fun addVisit(id: Long, on: LocalDate, note: String) = change(id) {
        val visits = listOf(Visit(System.nanoTime(), on, note, "Fran")) + it.visits.orEmpty()
        it.copy(visits = visits, visitCount = it.visitCount + 1, lastVisit = maxOf(on, it.lastVisit ?: on))
    }

    override suspend fun deleteVisit(id: Long, visit: Long) = change(id) {
        it.copy(visits = it.visits?.filter { v -> v.id != visit }, visitCount = it.visitCount - 1)
    }

    override suspend fun rate(id: Long, stars: Int) = change(id) { r ->
        val others = r.ratings.filter { !it.me }
        r.copy(ratings = (if (stars > 0) listOf(com.rudnam.samfran.core.model.Rating("Fran", true, stars)) else emptyList()) + others)
    }

    override suspend fun lookup(url: String, position: Boolean): Outcome<MapLookup> =
        next ?: Outcome.Done(MapLookup(lookupName, if (position) 34.97 else null, if (position) 138.38 else null, false))

    override suspend fun search(query: String, near: Pair<Double, Double>?): Outcome<List<com.rudnam.samfran.core.model.FoundPlace>> =
        next ?: Outcome.Done(places.filter { it.name.contains(query, ignoreCase = true) })

    private fun find(id: Long): Outcome<Restaurant> = list.value?.firstOrNull { it.id == id }?.let { Outcome.Done(it) } ?: Outcome.Refused("no such restaurant")

    private fun change(id: Long, f: (Restaurant) -> Restaurant): Outcome<Restaurant> {
        next?.let { return it }
        val r = list.value?.firstOrNull { it.id == id } ?: return Outcome.Refused("no such restaurant")
        val fresh = f(r)
        list.value = list.value?.map { if (it.id == id) fresh else it }
        return Outcome.Done(fresh)
    }
}

class FakeShiftsRepository(months: List<ShiftMonth> = listOf(Samples.november)) : ShiftsRepository {
    val months = MutableStateFlow(months.associateBy { it.month })
    var current: YearMonth = months.firstOrNull()?.month ?: YearMonth.of(2026, 11)
    val settingsFlow = MutableStateFlow<ShiftSettings?>(months.firstOrNull()?.settings ?: Samples.settings)
    var next: Outcome<Nothing>? = null
    var read: ShiftMonth? = null
    var saved: Map<LocalDate, String>? = null
    var savedWith: Map<LocalDate, List<String>>? = null
    var savedCorrections: Map<String, String>? = null
    private var pending: ShiftMonth? = null

    override fun month(month: YearMonth?): Flow<ShiftMonth?> = this.months.map { it[month ?: current] }
    override suspend fun refresh(month: YearMonth?): Outcome<ShiftMonth> =
        next ?: months.value[month ?: current]?.let { Outcome.Done(it) } ?: Outcome.Done(ShiftMonth(month ?: current, settingsFlow.value ?: ShiftSettings(), emptyList()))
    override suspend fun nextShift(now: Instant): ShiftDay? =
        months.value.values.sortedBy { it.month }.flatMap { it.days }.firstOrNull { it.end?.toInstant()?.isAfter(now) == true }
    override suspend fun upcomingEnds(now: Instant): Outcome<List<Instant>> =
        Outcome.Done(months.value.values.flatMap { it.days }.mapNotNull { it.end?.toInstant() }.filter { it.isAfter(now) }.sorted())
    override suspend fun save(month: YearMonth, codes: Map<LocalDate, String>, with: Map<LocalDate, List<String>>, corrections: Map<String, String>): Outcome<ShiftMonth> {
        next?.let { return it }
        saved = codes
        savedWith = with
        savedCorrections = corrections
        val m = ShiftMonth(month, settingsFlow.value ?: ShiftSettings(), months.value[month]?.days.orEmpty())
        months.value = months.value + (month to m)
        return Outcome.Done(m)
    }
    override suspend fun readPhoto(jpeg: ByteArray): Outcome<ShiftMonth> = next ?: read?.let { pending = it; Outcome.Done(it) } ?: Outcome.Refused("the photo couldn't be read; try a clearer one, or enter the shifts by hand")
    override fun takePendingRead(month: YearMonth): ShiftMonth? = pending?.takeIf { it.month == month }.also { pending = null }
    override val settings: Flow<ShiftSettings?> = settingsFlow
    override suspend fun refreshSettings(): Outcome<ShiftSettings> = next ?: Outcome.Done(settingsFlow.value ?: ShiftSettings())
    override suspend fun saveSettings(personName: String, nameOnSheet: String, lengths: Map<String, Double>, closingDays: List<Int>?): Outcome<ShiftSettings> {
        next?.let { return it }
        val before = settingsFlow.value ?: ShiftSettings()
        val s = before.copy(personName = personName, nameOnSheet = nameOnSheet, lengths = lengths, closingDays = closingDays?.distinct()?.sorted() ?: before.closingDays)
        settingsFlow.value = s
        return Outcome.Done(s)
    }
    override suspend fun feed(on: Boolean): Outcome<ShiftSettings> {
        val s = (settingsFlow.value ?: ShiftSettings()).copy(feedPath = if (on) "/calendar/abc123.ics" else "")
        settingsFlow.value = s
        return Outcome.Done(s)
    }
    override fun address(path: String) = "https://samfran.example$path"
}

class FakeBusRepository(stop: BusStop? = Samples.stop) : BusRepository {
    val stopFlow = MutableStateFlow(stop)
    var found: List<FoundStop> = Samples.found
    var next: Outcome<Nothing>? = null

    override val stop: Flow<BusStop?> = stopFlow
    override suspend fun refresh(): Outcome<BusStop> = next ?: Outcome.Done(stopFlow.value ?: BusStop(set = false))
    override suspend fun save(name: String, lat: Double, lng: Double, alertMeters: Int): Outcome<BusStop> {
        next?.let { return it }
        return BusStop(true, name, lat, lng, alertMeters).also { stopFlow.value = it }.let { Outcome.Done(it) }
    }
    override suspend fun search(query: String, near: Pair<Double, Double>?): Outcome<List<FoundStop>> =
        next ?: Outcome.Done(found.filter { it.name.contains(query) })
}

class FakeBusAlarm(status: TripStatus = TripStatus()) : BusAlarm {
    override val status = MutableStateFlow(status)
    var here: Pair<Double, Double>? = 34.97 to 138.38
    val tests = mutableListOf<String>()

    override fun start(stop: BusStop) {
        status.value = TripStatus(running = true)
    }
    override fun stop() {
        status.value = TripStatus()
    }
    override fun stopBuzz() {
        status.value = TripStatus()
    }
    override fun test(stopName: String) {
        tests += stopName
    }
    override suspend fun here() = here
}

class FakeSpaceRepository(space: Space? = Samples.space) : SpaceRepository {
    val spaceFlow = MutableStateFlow(space)
    var next: Outcome<Nothing>? = null

    override val space: Flow<Space?> = spaceFlow
    override suspend fun refresh(): Outcome<Space> = next ?: spaceFlow.value?.let { Outcome.Done(it) } ?: Outcome.Offline
    override suspend fun setMyName(name: String): Outcome<Space> {
        next?.let { return it }
        val s = spaceFlow.value!!.let { s -> s.copy(members = s.members.map { if (it.me) it.copy(name = name) else it }) }
        spaceFlow.value = s
        return Outcome.Done(s)
    }
    override suspend fun createInviteLink(): Outcome<InviteLinkMade> =
        next ?: Outcome.Done(InviteLinkMade("https://samfran.example/join/abc", Instant.parse("2026-11-23T00:00:00Z")))
    override suspend fun cancelInviteLink(): Outcome<Space> = Outcome.Done(spaceFlow.value!!.copy(inviteLinkExpires = null).also { spaceFlow.value = it })
    override suspend fun uninvite(email: String): Outcome<Space> =
        Outcome.Done(spaceFlow.value!!.let { s -> s.copy(invites = s.invites.filter { it.email != email }) }.also { spaceFlow.value = it })
    override val mcpAddress = "https://samfran.example/mcp"
}

class FakeActivityRepository(entries: List<ActivityEntry>? = Samples.activity, seen: Long? = 0) : ActivityRepository {
    val list = MutableStateFlow(entries)
    val seenFlow = MutableStateFlow(seen)

    override val entries: Flow<List<ActivityEntry>?> = list
    override suspend fun refresh(): Outcome<List<ActivityEntry>> = Outcome.Done(list.value.orEmpty())
    override val unseen: Flow<Int> = combine(list, seenFlow) { l, s -> if (s == null) 0 else l.orEmpty().count { !it.me && it.id > s } }
    override suspend fun seenUpTo() = seenFlow.value
    override suspend fun markSeen(entries: List<ActivityEntry>) {
        seenFlow.value = maxOf(seenFlow.value ?: 0, entries.newestId())
    }
}

class FakeReminders : Reminders {
    val prompts = MutableStateFlow(false)
    val alerts = MutableStateFlow(false)
    var allowed = true
    var syncs = 0

    override val shiftPrompts: StateFlow<Boolean> = prompts
    override val activityAlerts: StateFlow<Boolean> = alerts
    override suspend fun setShiftPrompts(on: Boolean) = (on && allowed).also { prompts.value = it }
    override suspend fun setActivityAlerts(on: Boolean) = (on && allowed).also { alerts.value = it }
    override suspend fun sync(now: Boolean) {
        syncs++
    }
    override fun clearNotifications() {}
    override fun turnOff() {
        prompts.value = false
        alerts.value = false
    }
}
