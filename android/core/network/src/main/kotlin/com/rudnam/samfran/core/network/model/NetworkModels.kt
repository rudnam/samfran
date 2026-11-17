package com.rudnam.samfran.core.network.model

import com.rudnam.samfran.core.model.ActivityDetails
import com.rudnam.samfran.core.model.PlaceCategory
import com.rudnam.samfran.core.model.ActivityEntry
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.Invite
import com.rudnam.samfran.core.model.McpToken
import com.rudnam.samfran.core.model.Me
import com.rudnam.samfran.core.model.Member
import com.rudnam.samfran.core.model.Rating
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.model.ShiftKind
import com.rudnam.samfran.core.model.ShiftMonth
import com.rudnam.samfran.core.model.ShiftSettings
import com.rudnam.samfran.core.model.Space
import com.rudnam.samfran.core.model.Visit
import com.rudnam.samfran.core.model.VisitNote
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

// The server's JSON, as the Go handlers write it (web/src/api.ts has the same shapes).
// Unknown fields are ignored, so the server can add some without breaking this build.

@Serializable
data class NetworkError(val error: String = "")

/** A photo of the sheet being read: asked after by its id. */
@Serializable
data class NetworkReadStarted(val id: String)

@Serializable
data class NetworkMe(
    val email: String,
    @SerialName("has_password") val hasPassword: Boolean = true,
    @SerialName("confirm_email") val confirmEmail: Boolean = false,
    val demo: Boolean = false,
    /** A new session's token, given to the app only. */
    val token: String? = null,
)

fun NetworkMe.asModel() = Me(email, hasPassword, confirmEmail, demo)

@Serializable
data class NetworkAuthOptions(val google: Boolean = false, val demo: Boolean = false, val reset: Boolean = false)

@Serializable
data class NetworkCredentials(val email: String, val password: String)

@Serializable
data class NetworkEmail(val email: String)

@Serializable
data class NetworkDemo(val tz: String)

@Serializable
data class NetworkAppCode(val code: String, val verifier: String)

@Serializable
data class NetworkName(val name: String)

@Serializable
data class NetworkMember(val name: String, val email: String, val me: Boolean = false)

@Serializable
data class NetworkInvite(val email: String, @SerialName("created_at") val createdAt: String)

@Serializable
data class NetworkInviteLinkInfo(@SerialName("expires_at") val expiresAt: String)

@Serializable
data class NetworkSpace(
    val members: List<NetworkMember> = emptyList(),
    val invites: List<NetworkInvite> = emptyList(),
    @SerialName("invite_link") val inviteLink: NetworkInviteLinkInfo? = null,
)

fun NetworkSpace.asModel() = Space(
    members = members.map { Member(it.name, it.email, it.me) },
    invites = invites.map { Invite(it.email, Instant.parse(it.createdAt)) },
    inviteLinkExpires = inviteLink?.let { Instant.parse(it.expiresAt) },
)

@Serializable
data class NetworkInviteLink(val path: String, @SerialName("expires_at") val expiresAt: String)

@Serializable
data class NetworkRating(val name: String, val me: Boolean = false, val stars: Int)

@Serializable
data class NetworkVisit(
    val id: Long,
    @SerialName("visited_on") val visitedOn: String,
    val note: String = "",
    @SerialName("added_by") val addedBy: String = "",
)

@Serializable
data class NetworkVisitNote(@SerialName("visited_on") val visitedOn: String, val note: String)

@Serializable
data class NetworkRestaurant(
    val id: Long,
    val name: String,
    val area: String = "",
    val cuisine: String = "",
    val category: String = "",
    @SerialName("map_url") val mapUrl: String = "",
    val notes: String = "",
    @SerialName("added_by") val addedBy: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("visit_count") val visitCount: Int = 0,
    @SerialName("last_visit") val lastVisit: String? = null,
    val ratings: List<NetworkRating> = emptyList(),
    val visits: List<NetworkVisit>? = null,
    @SerialName("visit_notes") val visitNotes: List<NetworkVisitNote>? = null,
)

fun NetworkRestaurant.asModel() = Restaurant(
    id = id,
    name = name,
    area = area,
    cuisine = cuisine,
    category = PlaceCategory.of(category),
    mapUrl = mapUrl,
    notes = notes,
    addedBy = addedBy,
    createdAt = createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
    visitCount = visitCount,
    lastVisit = lastVisit?.let(LocalDate::parse),
    ratings = ratings.map { Rating(it.name, it.me, it.stars) },
    visits = visits?.map { Visit(it.id, LocalDate.parse(it.visitedOn), it.note, it.addedBy) },
    visitNotes = visitNotes.orEmpty().map { VisitNote(LocalDate.parse(it.visitedOn), it.note) },
)

/** What a person types in about a place; a new one may carry a first visit and the creator's rating. */
@Serializable
data class NetworkRestaurantInput(
    val name: String,
    val area: String,
    val cuisine: String,
    val category: String,
    @SerialName("map_url") val mapUrl: String,
    val notes: String,
    val visit: NetworkNewVisit? = null,
    val stars: Int? = null,
)

@Serializable
data class NetworkNewVisit(@SerialName("visited_on") val visitedOn: String, val note: String = "")

@Serializable
data class NetworkStars(val stars: Int)

@Serializable
data class NetworkLookup(val url: String, val position: Boolean = false)

@Serializable
data class NetworkLookupResult(
    val name: String = "",
    @SerialName("map_url") val mapUrl: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("found_by_name") val foundByName: Boolean = false,
)

@Serializable
data class NetworkFoundPlace(
    val name: String,
    val area: String = "",
    val street: String = "",
    val kind: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    @SerialName("place_id") val placeId: String = "",
    val food: String = "",
    @SerialName("map_url") val mapUrl: String = "",
    val category: String = "",
)

fun NetworkFoundPlace.asModel() = com.rudnam.samfran.core.model.FoundPlace(
    name, area, street, kind, lat, lng, placeId, food, mapUrl,
    category = category.takeIf { it.isNotEmpty() }?.let(PlaceCategory::of),
)

@Serializable
data class NetworkShiftDay(
    val date: String,
    val code: String,
    val kind: String = "other",
    val label: String = "",
    val start: String? = null,
    val end: String? = null,
    val problem: String? = null,
    /** Others on the same shift (the same code that day), names as on the sheet. */
    val with: List<String>? = null,
)

@Serializable
data class NetworkShiftSettings(
    @SerialName("person_name") val personName: String = "",
    @SerialName("name_on_sheet") val nameOnSheet: String = "",
    val lengths: Map<String, Double> = emptyMap(),
    @SerialName("time_zone") val timeZone: String = "",
    /** Older servers leave it out: the 15th and the last day. */
    @SerialName("closing_days") val closingDays: List<Int> = listOf(15, 31),
    @SerialName("feed_path") val feedPath: String = "",
    val reader: Boolean = false,
)

fun NetworkShiftSettings.asModel() = ShiftSettings(personName, nameOnSheet, lengths, timeZone, closingDays, feedPath, reader)

@Serializable
data class NetworkShiftMonth(
    val month: String,
    val settings: NetworkShiftSettings = NetworkShiftSettings(),
    val days: List<NetworkShiftDay> = emptyList(),
    @SerialName("name_as_read") val nameAsRead: String? = null,
    val notes: String? = null,
)

fun NetworkShiftMonth.asModel() = ShiftMonth(
    month = YearMonth.parse(month),
    settings = settings.asModel(),
    days = days.map { d ->
        ShiftDay(
            date = LocalDate.parse(d.date),
            code = d.code,
            kind = ShiftKind.entries.firstOrNull { it.name.equals(d.kind, ignoreCase = true) } ?: ShiftKind.Other,
            label = d.label,
            start = d.start?.let(OffsetDateTime::parse),
            end = d.end?.let(OffsetDateTime::parse),
            problem = d.problem,
            with = d.with.orEmpty(),
        )
    },
    nameAsRead = nameAsRead,
    notes = notes.orEmpty(),
)

@Serializable
data class NetworkShiftCode(
    val date: String,
    val code: String,
    /** Who's on the same shift, kept only while the day's code is unchanged; left out otherwise. */
    val with: List<String>? = null,
)

@Serializable
data class NetworkSaveShifts(
    val month: String,
    val days: List<NetworkShiftCode>,
    /** Names the photo reader got wrong, as fixed while checking a scan; later scans use them. */
    val corrections: List<NetworkNameFix>? = null,
)

@Serializable
data class NetworkNameFix(val from: String, val to: String)

/** Only the fields being changed are sent. */
@Serializable
data class NetworkShiftSettingsInput(
    @SerialName("person_name") val personName: String? = null,
    @SerialName("name_on_sheet") val nameOnSheet: String? = null,
    val lengths: Map<String, Double>? = null,
    @SerialName("closing_days") val closingDays: List<Int>? = null,
)

@Serializable
data class NetworkBusStop(
    val set: Boolean = false,
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    @SerialName("alert_meters") val alertMeters: Int = 800,
    val buzz: String = "strong",
)

fun NetworkBusStop.asModel() = BusStop(set, name, lat, lng, alertMeters, Buzz.from(buzz))

@Serializable
data class NetworkBusStopInput(
    val name: String,
    val lat: Double,
    val lng: Double,
    @SerialName("alert_meters") val alertMeters: Int,
    /** Left out (null), the server keeps the buzz as it was. */
    val buzz: String? = null,
)

@Serializable
data class NetworkFoundStop(
    val name: String,
    val area: String = "",
    val kind: String = "bus",
    val lat: Double,
    val lng: Double,
)

fun NetworkFoundStop.asModel() = FoundStop(name, area, kind == "train", lat, lng)

@Serializable
data class NetworkActivity(
    val id: Long,
    val at: String,
    val who: String = "",
    val me: Boolean = false,
    val via: String = "app",
    val kind: String,
    val details: JsonObject = JsonObject(emptyMap()),
)

fun NetworkActivity.asModel() = ActivityEntry(
    id = id,
    at = runCatching { Instant.parse(at) }.getOrDefault(Instant.EPOCH),
    who = who,
    me = me,
    viaClaude = via == "claude",
    kind = kind,
    details = details.asDetails(),
)

private fun JsonElement?.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (this as? JsonPrimitive)?.content.orEmpty()

private fun JsonElement?.number(): Double? = if (this == null || this is JsonNull) null else (this as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.asDetails(): ActivityDetails {
    val lengths = (this["lengths"] as? JsonObject).orEmpty().mapValues { (_, v) ->
        val pair = v as? JsonArray
        pair?.getOrNull(0).number() to pair?.getOrNull(1).number()
    }
    val settings = listOf("person_name", "name_on_sheet", "time_zone", "closing_days").mapNotNull { key ->
        val pair = this[key] as? JsonArray ?: return@mapNotNull null
        key to (pair.getOrNull(0).string() to pair.getOrNull(1).string())
    }.toMap()
    return ActivityDetails(
        name = this["name"].string(),
        been = (this["been"] as? JsonPrimitive)?.booleanOrNull ?: false,
        stars = (this["stars"] as? JsonPrimitive)?.intOrNull ?: 0,
        month = this["month"].string(),
        email = this["email"].string(),
        lengths = lengths,
        settings = settings,
    )
}

@Serializable
data class NetworkMcpToken(
    val id: Long,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
)

fun NetworkMcpToken.asModel() = McpToken(id, name, Instant.parse(createdAt), lastUsedAt?.let(Instant::parse))

@Serializable
data class NetworkApk(
    val url: String,
    val size: Long = 0,
    val version: Int? = null,
    @SerialName("version_name") val versionName: String? = null,
)

@Serializable
data class NetworkDownloads(val android: NetworkApk? = null)
