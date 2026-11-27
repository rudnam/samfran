package com.rudnam.samfran.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class MealKind(val api: String) {
    Breakfast("breakfast"),
    Lunch("lunch"),
    Dinner("dinner"),
}

enum class MealRuleMode(val api: String) {
    Fixed("fixed"),
    BeforeStart("before_start"),
    AfterEnd("after_end"),
    Off("off"),
    ;

    companion object {
        fun fromApi(s: String?): MealRuleMode = entries.firstOrNull { it.api == s } ?: Off
    }
}

data class MealRule(
    val mode: MealRuleMode,
    /** 24h "HH:MM" when [mode] is [MealRuleMode.Fixed]. */
    val time: String = "12:00",
    /** 1..360 when [mode] is [MealRuleMode.BeforeStart] or [MealRuleMode.AfterEnd]. */
    val minutes: Int = 120,
)

data class DayMeals(
    val breakfast: MealRule,
    val lunch: MealRule,
    val dinner: MealRule,
) {
    operator fun get(meal: MealKind): MealRule = when (meal) {
        MealKind.Breakfast -> breakfast
        MealKind.Lunch -> lunch
        MealKind.Dinner -> dinner
    }

    fun with(meal: MealKind, rule: MealRule): DayMeals = when (meal) {
        MealKind.Breakfast -> copy(breakfast = rule)
        MealKind.Lunch -> copy(lunch = rule)
        MealKind.Dinner -> copy(dinner = rule)
    }
}

val MEAL_DAY_KEYS = listOf("Early", "Middle", "Late", "Night", "Off")
val MEAL_OFFSETS = listOf(15, 30, 45, 60, 90, 120, 150, 180, 210, 240, 300, 360)

fun defaultMealReminders(): Map<String, DayMeals> = mapOf(
    "Early" to DayMeals(
        breakfast = MealRule(MealRuleMode.BeforeStart, minutes = 120),
        lunch = MealRule(MealRuleMode.Fixed, time = "11:00"),
        dinner = MealRule(MealRuleMode.Fixed, time = "18:00"),
    ),
    "Middle" to DayMeals(
        breakfast = MealRule(MealRuleMode.Fixed, time = "08:00"),
        lunch = MealRule(MealRuleMode.Fixed, time = "13:00"),
        dinner = MealRule(MealRuleMode.Fixed, time = "19:00"),
    ),
    "Late" to DayMeals(
        breakfast = MealRule(MealRuleMode.Fixed, time = "10:00"),
        lunch = MealRule(MealRuleMode.Fixed, time = "15:00"),
        dinner = MealRule(MealRuleMode.Fixed, time = "00:00"),
    ),
    "Night" to DayMeals(
        breakfast = MealRule(MealRuleMode.Fixed, time = "20:00"),
        lunch = MealRule(MealRuleMode.Fixed, time = "03:00"),
        dinner = MealRule(MealRuleMode.Fixed, time = "08:00"),
    ),
    "Off" to DayMeals(
        breakfast = MealRule(MealRuleMode.Fixed, time = "08:00"),
        lunch = MealRule(MealRuleMode.Fixed, time = "13:00"),
        dinner = MealRule(MealRuleMode.Fixed, time = "19:00"),
    ),
)

data class ScheduledMeal(
    val at: Instant,
    val meal: MealKind,
    /** Shift key ("Early", "Middle", "Late", "Night", "Off"). */
    val shiftKey: String,
)

private val REPRESENTATIVE_START = mapOf(
    "Early" to 7,
    "Middle" to 9,
    "Late" to 13,
    "Night" to 22,
    "Off" to null,
)

private fun parseLocalTime(s: String): LocalTime? = runCatching { LocalTime.parse(s) }.getOrNull()

private fun resolveDayMeals(
    date: LocalDate,
    shiftStart: ZonedDateTime?,
    shiftEnd: ZonedDateTime?,
    dayMeals: DayMeals,
    zone: ZoneId,
): List<Pair<MealKind, ZonedDateTime>> {
    val result = mutableListOf<Pair<MealKind, ZonedDateTime>>()
    var cursor: ZonedDateTime? = null
    for (meal in MealKind.entries) {
        val rule = dayMeals[meal]
        when (rule.mode) {
            MealRuleMode.Off -> continue
            MealRuleMode.BeforeStart -> {
                val start = shiftStart ?: continue
                val at = start.minusMinutes(rule.minutes.toLong())
                cursor = at
                result += meal to at
            }
            MealRuleMode.AfterEnd -> {
                val end = shiftEnd ?: continue
                val at = end.plusMinutes(rule.minutes.toLong())
                cursor = at
                result += meal to at
            }
            MealRuleMode.Fixed -> {
                val local = parseLocalTime(rule.time) ?: continue
                var candidate = ZonedDateTime.of(date, local, zone)
                val c = cursor
                if (c != null) {
                    while (!candidate.isAfter(c)) {
                        candidate = candidate.plusDays(1)
                    }
                } else if (shiftStart != null && candidate.isBefore(shiftStart.minusHours(6))) {
                    candidate = candidate.plusDays(1)
                }
                cursor = candidate
                result += meal to candidate
            }
        }
    }
    return result
}

/**
 * Computes upcoming meal reminders in chronological order from [days],
 * filtering to times strictly after [now].
 */
fun computeMealReminders(
    days: List<ShiftDay>,
    schedule: Map<String, DayMeals>,
    zone: ZoneId,
    now: Instant,
): List<ScheduledMeal> {
    val defaults = defaultMealReminders()
    val out = mutableListOf<ScheduledMeal>()
    for (day in days) {
        val key = when (day.kind) {
            ShiftKind.Work -> day.label.takeIf { it in MEAL_DAY_KEYS } ?: continue
            ShiftKind.Off, ShiftKind.Leave, ShiftKind.Training, ShiftKind.Other -> "Off"
        }
        val dayMeals = schedule[key] ?: defaults[key] ?: continue
        val shiftStart = day.start?.atZoneSameInstant(zone)
        val shiftEnd = day.end?.atZoneSameInstant(zone)
        for ((meal, at) in resolveDayMeals(day.date, shiftStart, shiftEnd, dayMeals, zone)) {
            val instant = at.toInstant()
            if (instant.isAfter(now)) {
                out += ScheduledMeal(at = instant, meal = meal, shiftKey = key)
            }
        }
    }
    return out.sortedBy { it.at }
}

/** Returns which meals roll over to the next calendar day in the UI preview. */
fun nextDayMeals(shiftKey: String, dayMeals: DayMeals): Set<MealKind> {
    val baseDate = LocalDate.of(2026, 1, 1)
    val zone = ZoneId.of("UTC")
    val startHour = REPRESENTATIVE_START[shiftKey]
    val start = startHour?.let { ZonedDateTime.of(baseDate, LocalTime.of(it, 0), zone) }
    val end = start?.plusHours(9)
    return resolveDayMeals(baseDate, start, end, dayMeals, zone)
        .filter { (_, at) -> at.toLocalDate().isAfter(baseDate) }
        .map { (meal, _) -> meal }
        .toSet()
}
