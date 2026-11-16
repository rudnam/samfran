package com.rudnam.samfran.core.model

import java.util.Locale
import kotlin.math.roundToInt

/** Where the signed-in person gets off the bus (GET /api/bus/stop). */
data class BusStop(
    /** False until a stop is chosen. */
    val set: Boolean,
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** Buzz this many metres before the stop. */
    val alertMeters: Int = 800,
) {
    /** The stop on Google Maps. */
    val mapLink: String get() = mapLink(lat, lng)
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

object Distances {
    /** The choices for how far before the stop to buzz. */
    val choices = listOf(400, 600, 800, 1000, 1500, 2000)

    /** "600 m", "1.5 km". */
    fun format(meters: Float): String =
        if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else String.format(Locale.ROOT, "%.1f km", meters / 1000)
}

/** Google Maps at a point. */
fun mapLink(lat: Double, lng: Double): String = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"

/** Google Maps calls a stop "Name（バス）": the name alone. */
fun stopNameFromMaps(name: String): String = name.replace(Regex("\\s*[（(](バス|Bus)[）)]$"), "")
