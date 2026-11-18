package com.rudnam.samfran.core.model

import java.util.Locale
import kotlin.math.roundToInt

/** One of the places the signed-in person gets off the bus (GET /api/bus/stops), and how the phone buzzes there. */
data class BusStop(
    /** False until a stop is chosen. */
    val set: Boolean,
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** Buzz this many metres before the stop. */
    val alertMeters: Int = 800,
    /** How the phone buzzes there (the person's, at every stop). */
    val buzz: Buzz = Buzz.Strong,
    /** The server's id for it; 0 for none. */
    val id: Long = 0,
    /** The kinds of shift it's the stop for ([ShiftKinds]); each is on one stop at most. */
    val shifts: List<String> = emptyList(),
    /** Ask "Start bus alarm?" when a shift whose stop this is ends. Off until switched on. */
    val ask: Boolean = false,
) {
    /** The stop on Google Maps. */
    val mapLink: String get() = mapLink(lat, lng)
}

/** The kinds of working shift a stop can be for, as the server labels shifts by when they start. */
val ShiftKinds = listOf("Early", "Middle", "Late", "Night")

/** The signed-in person's stops, oldest first. */
data class BusStops(
    val stops: List<BusStop> = emptyList(),
    /** The stop used last: the one to go to when no shift says which. 0 when there are none. */
    val lastId: Long = 0,
    val buzz: Buzz = Buzz.Strong,
) {
    /** The stop used last (or the first). */
    val last: BusStop? get() = stops.firstOrNull { it.id == lastId } ?: stops.firstOrNull()

    fun byId(id: Long): BusStop? = stops.firstOrNull { it.id == id }

    /** The stop for a shift, if one is for its kind. */
    fun forShift(shift: ShiftDay?): BusStop? = shift?.let { s -> stops.firstOrNull { s.label in it.shifts } }

    /** Where to get off after [shift]: its stop, or else the one used last. */
    fun pick(shift: ShiftDay?): BusStop? = forShift(shift) ?: last

    companion object {
        /** How many stops a person can keep (MaxStops, internal/bus). */
        const val MAX = 10
    }
}

/**
 * The shift you'd be going home from: one that ended in the last six hours,
 * or else the next to end within a day. The website works it out the same
 * way (homeShift, web/src/features/bus/BusPage.tsx).
 */
fun homeShift(days: List<ShiftDay>, now: java.time.Instant): ShiftDay? = days
    .filter { it.kind == ShiftKind.Work && it.end != null }
    .filter { val end = it.end!!.toInstant(); end.isAfter(now.minusSeconds(6 * 3600)) && end.isBefore(now.plusSeconds(24 * 3600)) }
    .minByOrNull { it.end!!.toInstant() }

/**
 * How the phone buzzes at the stop ("buzz" in /api/bus/stops): [onMs] on,
 * then [offMs] off, on repeat; at the phone's full strength when [full],
 * where the phone can set it, or else at its usual strength. The website's
 * practice run vibrates the same lengths (web/src/features/bus/BusPage.tsx).
 */
enum class Buzz(val api: String, val onMs: Long, val offMs: Long, val full: Boolean) {
    /** How it always buzzed. */
    Gentle("gentle", 800, 600, full = false),
    Strong("strong", 1000, 300, full = true),
    Strongest("strongest", 2000, 100, full = true),
    ;

    companion object {
        /** An unknown one (from a newer server) is the default. */
        fun from(api: String?): Buzz = entries.firstOrNull { it.api == api } ?: Strong
    }
}

/** A stop or station whose name matches what's typed (GET /api/bus/search). */
data class FoundStop(
    val name: String,
    /** City, district, street. */
    val area: String,
    val train: Boolean,
    val lat: Double,
    val lng: Double,
)

/** A trip on the phone (the app's BusTripService). */
data class TripStatus(
    val running: Boolean = false,
    /** Metres to the stop, -1 until the phone knows. */
    val distance: Float = -1f,
    /** Buzzing now, until stopped. */
    val buzzing: Boolean = false,
)

/** What a buzz distance means on a ride, roughly: city bus stops are 300–500 m apart. */
enum class Early { RightBefore, OneStop, TwoStops, Plenty }

object Distances {
    /** The choices for how far before the stop to buzz. */
    val choices = listOf(400, 600, 800, 1000, 1500, 2000)

    fun early(meters: Int): Early = when {
        meters <= 400 -> Early.RightBefore
        meters <= 800 -> Early.OneStop
        meters <= 1500 -> Early.TwoStops
        else -> Early.Plenty
    }

    /**
     * How far along the ride the bus is, 0 to 1, for the progress line: from
     * the first distance the phone knew ([from]) to where it starts buzzing.
     */
    fun progress(from: Float, now: Float, alert: Int): Float =
        if (now < 0 || from <= alert) 0f else ((from - now) / (from - alert)).coerceIn(0f, 1f)

    /** "600 m", "1.5 km". */
    fun format(meters: Float): String =
        if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else String.format(Locale.ROOT, "%.1f km", meters / 1000)
}

/** Google Maps at a point. */
fun mapLink(lat: Double, lng: Double): String = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"

/** Google Maps calls a stop "Name（バス）": the name alone. */
fun stopNameFromMaps(name: String): String = name.replace(Regex("\\s*[（(](バス|Bus)[）)]$"), "")
