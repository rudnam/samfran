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

    /** The search matches its name, area, kind, notes or a visit's note. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return listOf(name, area, cuisine, notes).any { it.contains(q, ignoreCase = true) } || noteMatching(q) != null
    }

    /** The newest visit whose note has the search in it. */
    fun noteMatching(query: String): VisitNote? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return visitNotes.firstOrNull { it.note.contains(q, ignoreCase = true) }
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
