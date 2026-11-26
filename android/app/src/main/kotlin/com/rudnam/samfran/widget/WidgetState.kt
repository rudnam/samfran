package com.rudnam.samfran.widget

import com.rudnam.samfran.core.model.Member
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.model.ShiftKind
import com.rudnam.samfran.core.model.ShiftMonth
import com.rudnam.samfran.core.model.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the Next shift widget shows. */
sealed interface ShiftWidgetState {
    /** Nothing on the phone to show: signed out, or SamFran not opened since it was installed. */
    data object Empty : ShiftWidgetState

    data class Shifts(
        /** Whose shifts they are ("Sam"), or "" when the settings don't say or they're the signed-in person's own. */
        val person: String,
        /** The shift on now, or else the next one; null when none is known. */
        val next: ShiftDay?,
        /** [next] has started and not ended. */
        val onNow: Boolean,
        val today: LocalDate,
        /** Today and the six days after it; a day the phone doesn't have is null. */
        val week: List<WeekDay>,
    ) : ShiftWidgetState
}

data class WeekDay(val date: LocalDate, val day: ShiftDay?)

/**
 * The shifts are the signed-in person's own: "whose shifts" ([person], free
 * text in the shift settings) names them, by the same name or the same first
 * name ("Sam" and "Sam Otoru"), and doesn't name the other one of you too.
 * Unknown (no space on the phone) counts as not.
 */
fun isOwnShifts(person: String, space: Space?): Boolean {
    fun words(name: String) = name.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    fun names(member: Member?): Boolean {
        val a = words(person)
        val b = words(member?.name.orEmpty())
        return a.isNotEmpty() && b.isNotEmpty() && (a == b || a.first() == b.first())
    }
    val me = space?.members?.firstOrNull { it.me } ?: return false
    return names(me) && !names(space.partner)
}

/**
 * The widget's view of [months] (this month and, if the phone has it, the
 * next) at [now]: the shift on now or the next to come, and the week ahead.
 * A working day whose times aren't known counts as coming until its day is over.
 */
fun shiftWidgetState(months: List<ShiftMonth>, now: Instant, zone: ZoneId): ShiftWidgetState {
    if (months.isEmpty()) return ShiftWidgetState.Empty
    val today = now.atZone(zone).toLocalDate()
    val days = months.flatMap { it.days }.sortedBy { it.date }
    val next = days.firstOrNull { d ->
        val end = d.end?.toInstant()
        if (end != null) end.isAfter(now) else d.kind == ShiftKind.Work && !d.date.isBefore(today)
    }
    val onNow = next?.start?.toInstant()?.let { !it.isAfter(now) } == true
    val byDate = days.associateBy { it.date }
    return ShiftWidgetState.Shifts(
        person = months.first().settings.personName,
        next = next,
        onNow = onNow,
        today = today,
        week = (0L..6L).map { today.plusDays(it).let { d -> WeekDay(d, byDate[d]) } },
    )
}

/** What the Ride alarm widget shows. */
sealed interface BusWidgetState {
    /** Signed out, or nothing on the phone yet. */
    data object Empty : BusWidgetState

    /** No stop chosen yet. */
    data object NoStop : BusWidgetState

    /** Ready to start a trip to this stop (the shift's, or else the one used last); [others]: there are more stops to choose from. */
    data class Ready(val stopId: Long, val stopName: String, val train: Boolean = false, val others: Boolean = false) : BusWidgetState

    /** A trip is on; [buzzing] once the stop is near. */
    data class Riding(val stopName: String, val buzzing: Boolean) : BusWidgetState
}
