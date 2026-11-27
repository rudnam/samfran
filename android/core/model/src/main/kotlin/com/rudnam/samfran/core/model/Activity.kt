package com.rudnam.samfran.core.model

import java.time.Instant

/** A change to what you share, for Recent changes (GET /api/activity). */
data class ActivityEntry(
    val id: Long,
    val at: Instant,
    /** The member's name; "" if they've left. */
    val who: String,
    val me: Boolean,
    /** Made through Claude rather than the app. */
    val viaClaude: Boolean,
    val kind: String,
    val details: ActivityDetails = ActivityDetails(),
)

/** The parts of a change's details the app words (internal/activity). */
data class ActivityDetails(
    val name: String = "",
    val been: Boolean = false,
    /** A ride ended (bus_ended): the phone got to the stop, rather than being stopped before. */
    val arrived: Boolean = false,
    val stars: Int = 0,
    /** YYYY-MM, for saved shifts. */
    val month: String = "",
    val email: String = "",
    /** Shift lengths changed: letter to (from, to) hours, null for none. */
    val lengths: Map<String, Pair<Double?, Double?>> = emptyMap(),
    /** Shift settings changed: person_name, name_on_sheet or time_zone, to (from, to). */
    val settings: Map<String, Pair<String, String>> = emptyMap(),
    val mealRemindersChanged: Boolean = false,
)

/** The newest change's id, 0 if there are none. */
fun List<ActivityEntry>.newestId(): Long = maxOfOrNull { it.id } ?: 0
