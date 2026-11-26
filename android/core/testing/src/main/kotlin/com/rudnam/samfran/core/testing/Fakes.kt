package com.rudnam.samfran.core.testing

import com.rudnam.samfran.core.data.ActivityRepository
import com.rudnam.samfran.core.data.AuthRepository
import com.rudnam.samfran.core.data.BusAlarm
import com.rudnam.samfran.core.data.BusRepository
import com.rudnam.samfran.core.data.FoodRepository
import com.rudnam.samfran.core.data.PickedPhoto
import com.rudnam.samfran.core.data.Photos
import com.rudnam.samfran.core.data.UnreadablePhotoException
import com.rudnam.samfran.core.model.Photo
import com.rudnam.samfran.core.data.GoogleResult
import com.rudnam.samfran.core.data.GoogleReturn
import com.rudnam.samfran.core.data.InviteLinkMade
import com.rudnam.samfran.core.data.MapLookup
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.data.SpaceRepository
import com.rudnam.samfran.core.data.StopInput
import com.rudnam.samfran.core.data.UpdateRepository
import com.rudnam.samfran.core.data.UserRepository
import com.rudnam.samfran.core.model.ActivityEntry
import com.rudnam.samfran.core.model.AppUpdate
import com.rudnam.samfran.core.model.AuthOptions
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.BusStops
import com.rudnam.samfran.core.model.StopKind
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.McpToken
import com.rudnam.samfran.core.model.Me
import com.rudnam.samfran.core.model.ReadProgress
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.RestaurantFields
import com.rudnam.samfran.core.model.ShiftCoworker
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
            nextId++, fields.name, fields.area, fields.cuisine, category = fields.category, mapUrl = fields.mapUrl, notes = fields.notes,
            visitCount = if (visitedOn != null) 1 else 0, lastVisit = visitedOn,
            ratings = if (stars > 0 && visitedOn != null) listOf(com.rudnam.samfran.core.model.Rating("Fran", true, stars)) else emptyList(),
            visits = visitedOn?.let { listOf(Visit(1, it, "", "Fran")) } ?: emptyList(),
        )
        list.value = list.value.orEmpty() + r
        return Outcome.Done(r)
    }

    override suspend fun update(id: Long, fields: RestaurantFields) = change(id) {
        it.copy(name = fields.name, area = fields.area, cuisine = fields.cuisine, category = fields.category, mapUrl = fields.mapUrl, notes = fields.notes)
    }

    override suspend fun delete(id: Long): Outcome<Unit> {
        next?.let { return it }
        list.value = list.value?.filter { it.id != id }
        return Outcome.Done(Unit)
    }

    override suspend fun setBeen(id: Long, on: LocalDate) = change(id) {
        val old = it.visits.orEmpty()
        val visits = if (old.isEmpty()) listOf(Visit(System.nanoTime(), on, "", "Fran")) else listOf(old.first().copy(visitedOn = on)) + old.drop(1)
        it.copy(visits = visits, visitCount = maxOf(it.visitCount, 1), lastVisit = visits.maxOf { v -> v.visitedOn })
    }

    override suspend fun clearBeen(id: Long) = change(id) { it.copy(visits = emptyList(), visitCount = 0, lastVisit = null) }

    override suspend fun addVisit(id: Long, on: LocalDate, note: String) = change(id) {
        val visits = (it.visits.orEmpty() + Visit(nextId++, on, note.trim(), "Fran")).sortedByDescending { v -> v.visitedOn }
        it.copy(visits = visits, visitCount = visits.size, lastVisit = visits.first().visitedOn)
    }

    override suspend fun updateVisit(id: Long, visit: Long, on: LocalDate, note: String) = change(id) {
        val visits = it.visits.orEmpty().map { v -> if (v.id == visit) v.copy(visitedOn = on, note = note.trim()) else v }.sortedByDescending { v -> v.visitedOn }
        it.copy(visits = visits, lastVisit = visits.firstOrNull()?.visitedOn)
    }

    override suspend fun deleteVisit(id: Long, visit: Long) = change(id) {
        val visits = it.visits.orEmpty().filter { v -> v.id != visit }
        it.withPhotos(visits).copy(visitCount = visits.size, lastVisit = visits.firstOrNull()?.visitedOn)
    }

    /** The photos sent, in order. */
    val sentPhotos = mutableListOf<PickedPhoto>()

    override suspend fun addPhoto(id: Long, visit: Long, photo: PickedPhoto, onProgress: (Float) -> Unit) = change(id) {
        sentPhotos += photo
        onProgress(1f)
        val n = nextId++
        val p = Photo(n, "https://samfran.test/api/photos/$n", "https://samfran.test/api/photos/$n/thumb", photo.width, photo.height, "Fran")
        it.withPhotos(it.visits.orEmpty().map { v -> if (v.id == visit) v.copy(photos = v.photos + p) else v })
    }

    override suspend fun deletePhoto(id: Long, photo: Long) = change(id) {
        it.withPhotos(it.visits.orEmpty().map { v -> v.copy(photos = v.photos.filter { p -> p.id != photo }) })
    }

    /** With these visits, and the cover and count they make. */
    private fun Restaurant.withPhotos(visits: List<Visit>): Restaurant {
        val all = visits.flatMap { it.photos }
        return copy(visits = visits, photoCount = all.size, cover = all.maxByOrNull { it.id }?.thumbUrl)
    }

    override suspend fun rate(id: Long, stars: Int) = change(id) { r ->
        val others = r.ratings.filter { !it.me }
        r.copy(ratings = (if (stars > 0) listOf(com.rudnam.samfran.core.model.Rating("Fran", true, stars)) else emptyList()) + others)
    }

    override suspend fun addComment(id: Long, body: String) = change(id) {
        it.copy(comments = it.comments + com.rudnam.samfran.core.model.Comment(System.nanoTime(), body.trim(), "Fran", true, java.time.Instant.now()))
    }

    override suspend fun deleteComment(id: Long, comment: Long) = change(id) { r -> r.copy(comments = r.comments.filter { it.id != comment }) }

    override suspend fun lookup(url: String, position: Boolean): Outcome<MapLookup> =
        next ?: Outcome.Done(MapLookup(lookupName, if (position) 34.97 else null, if (position) 138.38 else null, false))

    /** The sessions searches and details were asked in, in order. */
    val sessions = mutableListOf<String>()
    /** Google's details, by place ID. */
    var details: Map<String, com.rudnam.samfran.core.model.FoundPlace> = emptyMap()

    /** Where the last search was asked from. */
    var searchedNear: Pair<Double, Double>? = null

    override suspend fun search(query: String, near: Pair<Double, Double>?, session: String): Outcome<List<com.rudnam.samfran.core.model.FoundPlace>> {
        sessions += session
        searchedNear = near
        return next ?: Outcome.Done(places.filter { it.name.contains(query, ignoreCase = true) })
    }

    override suspend fun place(placeId: String, session: String): Outcome<com.rudnam.samfran.core.model.FoundPlace> {
        sessions += session
        return next ?: details[placeId]?.let { Outcome.Done(it) } ?: Outcome.Refused("no such place")
    }

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
    var savedPeople: Map<LocalDate, List<ShiftCoworker>>? = null
    var savedCorrections: Map<String, String>? = null
    var savedClosing: List<Int>? = null
    private var pending: ShiftMonth? = null

    override fun month(month: YearMonth?): Flow<ShiftMonth?> = this.months.map { it[month ?: current] }
    override suspend fun refresh(month: YearMonth?): Outcome<ShiftMonth> =
        next ?: months.value[month ?: current]?.let { Outcome.Done(it) } ?: Outcome.Done(ShiftMonth(month ?: current, settingsFlow.value ?: ShiftSettings(), emptyList()))
    override suspend fun nextShift(now: Instant): ShiftDay? =
        months.value.values.sortedBy { it.month }.flatMap { it.days }.firstOrNull { it.end?.toInstant()?.isAfter(now) == true }
    override suspend fun upcomingShifts(now: Instant): Outcome<List<ShiftDay>> =
        Outcome.Done(months.value.values.flatMap { it.days }.filter { it.end?.toInstant()?.isAfter(now) == true }.sortedBy { it.end!!.toInstant() })
    /** The shift [homeShift] answers; by default, worked out from the months. */
    var home: ShiftDay? = null
    override suspend fun homeShift(now: Instant): ShiftDay? = home ?: com.rudnam.samfran.core.model.homeShift(months.value.values.flatMap { it.days }, now)
    override suspend fun save(
        month: YearMonth,
        codes: Map<LocalDate, String>,
        people: Map<LocalDate, List<ShiftCoworker>>,
        corrections: Map<String, String>,
        closingDays: List<Int>?,
    ): Outcome<ShiftMonth> {
        next?.let { return it }
        saved = codes
        savedPeople = people
        savedCorrections = corrections
        savedClosing = closingDays
        val m = ShiftMonth(month, settingsFlow.value ?: ShiftSettings(), months.value[month]?.days.orEmpty(), closingDays = closingDays ?: months.value[month]?.closingDays)
        months.value = months.value + (month to m)
        return Outcome.Done(m)
    }
    override suspend fun readPhoto(jpeg: ByteArray, onProgress: (ReadProgress) -> Unit): Outcome<ShiftMonth> = next ?: read?.let { pending = it; Outcome.Done(it) } ?: Outcome.Refused("the photo couldn't be read; try a clearer one, or enter the shifts by hand")
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

class FakeBusRepository(stops: BusStops? = Samples.stops) : BusRepository {
    val stopsFlow = MutableStateFlow(stops)
    var found: List<FoundStop> = Samples.found
    var next: Outcome<Nothing>? = null
    val used = mutableListOf<Long>()
    val done = mutableListOf<Pair<Long, Boolean>>()

    override val stops: Flow<BusStops?> = stopsFlow
    override suspend fun refresh(): Outcome<BusStops> = next ?: Outcome.Done(stopsFlow.value ?: BusStops())

    private fun now(): BusStops = stopsFlow.value ?: BusStops()

    private fun set(all: BusStops): Outcome<BusStops> {
        stopsFlow.value = all
        return Outcome.Done(all)
    }

    /** Each kind of shift on one stop at most: [id] takes [kinds] from the others. */
    private fun take(all: BusStops, id: Long, kinds: List<String>?) =
        if (kinds == null) all else all.copy(stops = all.stops.map { if (it.id == id) it else it.copy(shifts = it.shifts - kinds.toSet()) })

    override suspend fun add(stop: StopInput): Outcome<BusStops> {
        next?.let { return it }
        val all = now()
        val id = (all.stops.maxOfOrNull { it.id } ?: 0) + 1
        val s = BusStop(
            set = true, name = stop.name.trim(), lat = stop.lat, lng = stop.lng, alertMeters = stop.alertMeters, buzz = stop.buzz ?: Buzz.Strong,
            id = id, shifts = stop.shifts.orEmpty(), ask = stop.ask ?: false, kind = stop.kind ?: StopKind.Bus, soundAfter = stop.soundAfter ?: 60,
        )
        return set(take(all.copy(stops = all.stops + s, lastId = if (all.stops.isEmpty()) id else all.lastId), id, stop.shifts))
    }

    override suspend fun change(id: Long, stop: StopInput): Outcome<BusStops> {
        next?.let { return it }
        val all = now()
        val changed = all.copy(
            stops = all.stops.map {
                if (it.id != id) it else it.copy(name = stop.name.trim(), lat = stop.lat, lng = stop.lng, alertMeters = stop.alertMeters, shifts = stop.shifts ?: it.shifts, ask = stop.ask ?: it.ask, kind = stop.kind ?: it.kind, buzz = stop.buzz ?: it.buzz, soundAfter = stop.soundAfter ?: it.soundAfter)
            },
        )
        return set(take(changed, id, stop.shifts))
    }

    override suspend fun remove(id: Long): Outcome<BusStops> {
        next?.let { return it }
        val all = now()
        val left = all.stops.filter { it.id != id }
        return set(all.copy(stops = left, lastId = if (all.lastId == id) left.firstOrNull()?.id ?: 0 else all.lastId))
    }

    override suspend fun use(id: Long): Outcome<BusStops> {
        next?.let { return it }
        used += id
        return set(now().copy(lastId = id))
    }

    override suspend fun done(id: Long, arrived: Boolean): Outcome<BusStops> {
        next?.let { return it }
        done += id to arrived
        return Outcome.Done(now())
    }

    override suspend fun search(query: String, near: Pair<Double, Double>?): Outcome<List<FoundStop>> =
        next ?: Outcome.Done(found.filter { it.name.contains(query) })
}

class FakeBusAlarm(status: TripStatus = TripStatus()) : BusAlarm {
    override val status = MutableStateFlow(status)
    var here: Pair<Double, Double>? = 34.97 to 138.38
    val tests = mutableListOf<String>()
    var testBuzz: Buzz? = null

    override fun start(stop: BusStop) {
        status.value = TripStatus(running = true)
    }
    override fun stop() {
        status.value = TripStatus()
    }
    override fun stopBuzz() {
        status.value = TripStatus()
    }
    override fun test(stopName: String, buzz: Buzz) {
        tests += stopName
        testBuzz = buzz
    }
    var heard = 0
    override fun hearSound() {
        heard++
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
    override suspend fun forNotifications(): Outcome<List<ActivityEntry>> = Outcome.Done(list.value.orEmpty())
}

class FakeReminders : Reminders {
    val alerts = MutableStateFlow(false)
    var allowed = true
    var syncs = 0

    override val activityAlerts: StateFlow<Boolean> = alerts
    override fun notificationsAllowed() = allowed
    override suspend fun setActivityAlerts(on: Boolean) = (on && allowed).also { alerts.value = it }
    override suspend fun sync(now: Boolean) {
        syncs++
    }
    override fun clearNotifications() {}
    override fun turnOff() {
        alerts.value = false
    }
}

/** Photos "picked": any uri reads as a 4000×3000 JPEG, except one with "unreadable" in it. */
class FakePhotos : Photos {
    override suspend fun read(uri: String): PickedPhoto {
        if ("unreadable" in uri) throw UnreadablePhotoException()
        return PickedPhoto(uri.substringAfterLast('/'), "image/jpeg", 3, 4000, 3000, byteArrayOf(1)) { byteArrayOf(-1, -40, -1).inputStream() }
    }
}
