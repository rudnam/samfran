package com.rudnam.samfran.core.model

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate

data class Rating(
    val name: String,
    val me: Boolean,
    /** 1 to 5. */
    val stars: Int,
)

data class Visit(
    val id: Long,
    val visitedOn: LocalDate,
    val note: String,
    val addedBy: String,
)

/** A visit's note, for searching ("where did we have that?"). */
data class VisitNote(val visitedOn: LocalDate, val note: String)

/**
 * Why you'd go to a place (internal/restaurant/category.go). [key] is the
 * server's word for it; one this app doesn't know (from a newer server) is [Other].
 */
enum class PlaceCategory(val key: String) {
    Eat("eat"),
    Nature("nature"),
    Culture("culture"),
    Fun("fun"),
    Relax("relax"),
    Shopping("shopping"),
    Other("other"),
    ;

    companion object {
        /** Empty is [Eat]: what every place was before there were categories. */
        fun of(key: String?): PlaceCategory = if (key.isNullOrEmpty()) Eat else entries.firstOrNull { it.key == key } ?: Other
    }
}

/** A place to go: a restaurant, park, shrine… (it began as a list of restaurants). */
data class Restaurant(
    val id: Long,
    val name: String,
    val area: String = "",
    /** The kind of place: "Ramen", "公園". */
    val cuisine: String = "",
    val category: PlaceCategory = PlaceCategory.Eat,
    val mapUrl: String = "",
    val notes: String = "",
    /** Where it is; null until the server knows (it looks it up from the map link or name). */
    val lat: Double? = null,
    val lng: Double? = null,
    /** Who added it; empty if they've left. */
    val addedBy: String = "",
    val createdAt: Instant = Instant.EPOCH,
    val visitCount: Int = 0,
    /** Null if you haven't been. */
    val lastVisit: LocalDate? = null,
    /** The signed-in person's first. */
    val ratings: List<Rating> = emptyList(),
    /** Newest first; only on one place's page. */
    val visits: List<Visit>? = null,
    /** The visits with a note, newest first. */
    val visitNotes: List<VisitNote> = emptyList(),
) {
    val been: Boolean get() = visitCount > 0

    val myStars: Int get() = ratings.firstOrNull { it.me }?.stars ?: 0

    /** The saved map link, else a Google Maps search for the name and area. */
    val mapLink: String
        get() = mapUrl.ifEmpty {
            val q = listOf(name, area).filter { it.isNotBlank() }.joinToString(" ")
            "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        }

    /** Kind and area, as the place's page writes them under its name, after the category. */
    val subtitle: String get() = listOf(cuisine, area).filter { it.isNotBlank() }.joinToString(" · ")

    /** The search matches its name, area, kind or notes. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return listOf(name, area, cuisine, notes).any { it.contains(q, ignoreCase = true) }
    }

    /** Meters from [here], or null when where it is isn't known. */
    fun metersFrom(here: Pair<Double, Double>): Double? {
        val a = lat ?: return null
        val b = lng ?: return null
        return meters(here.first, here.second, a, b)
    }
}

/** A place found by name (GET /api/restaurants/suggest): from Google Places, or OpenStreetMap. */
data class FoundPlace(
    val name: String,
    /** City and district. */
    val area: String,
    /** Street or neighbourhood, to tell branches apart. */
    val street: String,
    /** OpenStreetMap's kind (restaurant, cafe, park, museum…) or Google's type (ramen_restaurant, park…). */
    val kind: String,
    val lat: Double,
    val lng: Double,
    /** Google's, for a place Google suggested: until its details come, only [name] and [street] (an address line) are set. */
    val placeId: String = "",
    /** From Google's details: the kind of place, for the Kind field, when it says one. */
    val food: String = "",
    /** From Google's details: the place's own Google Maps link. */
    val googleMapUrl: String = "",
    /** The category the kind of place is in; null from an older server. */
    val category: PlaceCategory? = null,
) {
    /** Where it is, as a suggestion's second line: street and area. */
    val where: String get() = listOf(street, area).filter { it.isNotBlank() }.joinToString(" · ")

    /** The place on Google Maps: its own link, from Google, or Maps searching for the name around where it is, which opens on that branch, not the chain. */
    val mapUrl: String
        get() = googleMapUrl.ifBlank { null } ?: "https://www.google.com/maps/search/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") + "/@$lat,$lng,17z"
}

/** Meters between two points (internal/geo's formula). */
fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val rad = Math.PI / 180
    val dLat = (lat2 - lat1) * rad
    val dLng = (lng2 - lng1) * rad
    val h = Math.sin(dLat / 2).let { it * it } + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2).let { it * it }
    return 2 * 6371000.0 * Math.asin(Math.sqrt(h))
}

/** How far, as the website writes it: "350 m", "1.2 km", "14 km". */
fun distanceText(meters: Double): String = when {
    meters < 1000 -> "${maxOf(10, (Math.round(meters / 10) * 10).toInt())} m"
    meters < 10000 -> String.format(java.util.Locale.ROOT, "%.1f km", meters / 1000)
    else -> "${Math.round(meters / 1000)} km"
}

/** Nearest first; places without a position last, newest first among them. */
fun List<Restaurant>.byDistance(here: Pair<Double, Double>): List<Restaurant> =
    sortedWith(compareBy<Restaurant> { it.metersFrom(here) ?: Double.MAX_VALUE }.thenByDescending { it.id })

/** A place on the list with this name (any case, spaces around ignored), so it isn't added twice. */
fun List<Restaurant>.named(name: String): Restaurant? =
    name.trim().takeIf { it.isNotEmpty() }?.let { n -> firstOrNull { it.name.trim().equals(n, ignoreCase = true) } }

/** What a person types in about a place. */
data class RestaurantFields(
    val name: String = "",
    val area: String = "",
    val cuisine: String = "",
    val category: PlaceCategory = PlaceCategory.Eat,
    val mapUrl: String = "",
    val notes: String = "",
    /** Where it is, both or neither: from the place picked in the search. */
    val lat: Double? = null,
    val lng: Double? = null,
)

/** The two lists of the Places tab. */
data class FoodLists(val been: List<Restaurant>, val want: List<Restaurant>) {
    companion object {
        /** Been there: latest visit first. Want to go: newest first. */
        fun of(all: List<Restaurant>) = FoodLists(
            been = all.filter { it.been }.sortedWith(compareByDescending<Restaurant> { it.lastVisit }.thenByDescending { it.id }),
            want = all.filter { !it.been }.sortedByDescending { it.id },
        )
    }
}
