package com.rudnam.samfran.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class MealRemindersTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val offset = ZoneOffset.ofHours(9)

    private fun workDay(date: LocalDate, code: String, label: String, startHour: Int, hours: Long = 9): ShiftDay {
        val start = OffsetDateTime.of(date.year, date.monthValue, date.dayOfMonth, startHour, 0, 0, 0, offset)
        return ShiftDay(date, code, ShiftKind.Work, label, start = start, end = start.plusHours(hours))
    }

    @Test
    fun `early shift uses relative breakfast and fixed lunch and dinner`() {
        val d1 = workDay(LocalDate.of(2026, 11, 20), "6F", "Early", 6)
        val d2 = workDay(LocalDate.of(2026, 11, 21), "7F", "Early", 7)
        val now = Instant.parse("2026-11-19T00:00:00Z")
        val meals = computeMealReminders(listOf(d1, d2), defaultMealReminders(), zone, now)

        assertEquals(6, meals.size)
        // 6F -> breakfast at 04:00 JST
        assertEquals(Instant.parse("2026-11-19T19:00:00Z"), meals[0].at)
        assertEquals(MealKind.Breakfast, meals[0].meal)
        assertEquals("Early", meals[0].shiftKey)
        // 7F -> breakfast at 05:00 JST
        assertEquals(Instant.parse("2026-11-20T20:00:00Z"), meals[3].at)
    }

    @Test
    fun `overnight rollover for late and night shifts`() {
        val late = workDay(LocalDate.of(2026, 11, 20), "13F", "Late", 13)
        val night = workDay(LocalDate.of(2026, 11, 21), "22F", "Night", 22)
        val now = Instant.parse("2026-11-19T00:00:00Z")
        val meals = computeMealReminders(listOf(late, night), defaultMealReminders(), zone, now)

        assertEquals(6, meals.size)
        // Late dinner: 00:00 on Nov 21 JST = 2026-11-20T15:00:00Z
        assertEquals(Instant.parse("2026-11-20T15:00:00Z"), meals[2].at)
        assertEquals(MealKind.Dinner, meals[2].meal)
        // Night breakfast: 20:00 on Nov 21 JST = 2026-11-21T11:00:00Z
        assertEquals(Instant.parse("2026-11-21T11:00:00Z"), meals[3].at)
        // Night lunch: 03:00 on Nov 22 JST = 2026-11-21T18:00:00Z
        assertEquals(Instant.parse("2026-11-21T18:00:00Z"), meals[4].at)
        // Night dinner: 08:00 on Nov 22 JST = 2026-11-21T23:00:00Z
        assertEquals(Instant.parse("2026-11-21T23:00:00Z"), meals[5].at)
    }

    @Test
    fun `night shift with breakfast off still rolls lunch and dinner to next day`() {
        val night = workDay(LocalDate.of(2026, 11, 21), "22F", "Night", 22)
        val schedule = defaultMealReminders().toMutableMap()
        schedule["Night"] = schedule.getValue("Night").copy(breakfast = MealRule(MealRuleMode.Off))
        val meals = computeMealReminders(listOf(night), schedule, zone, Instant.parse("2026-11-20T00:00:00Z"))

        assertEquals(2, meals.size)
        assertEquals(Instant.parse("2026-11-21T18:00:00Z"), meals[0].at) // 03:00 Nov 22 JST
        assertEquals(Instant.parse("2026-11-21T23:00:00Z"), meals[1].at) // 08:00 Nov 22 JST
    }

    @Test
    fun `after_end and off days and past filtering work`() {
        val middle = workDay(LocalDate.of(2026, 11, 20), "9F", "Middle", 9)
        val off = ShiftDay(LocalDate.of(2026, 11, 21), "普", ShiftKind.Off, "Day off")
        val schedule = defaultMealReminders().toMutableMap()
        schedule["Middle"] = schedule.getValue("Middle").copy(
            dinner = MealRule(MealRuleMode.AfterEnd, minutes = 60), // 18:00 + 60m = 19:00 JST
        )
        // Now is 10:00 JST on Nov 20 (01:00Z): breakfast (08:00 JST) is filtered out.
        val now = Instant.parse("2026-11-20T01:00:00Z")
        val meals = computeMealReminders(listOf(middle, off), schedule, zone, now)

        assertEquals(5, meals.size)
        assertEquals(MealKind.Lunch, meals[0].meal)
        assertEquals(Instant.parse("2026-11-20T10:00:00Z"), meals[1].at) // 19:00 JST
        assertEquals("Off", meals[2].shiftKey)
    }

    @Test
    fun `nextDayMeals marks late dinner and night lunch and dinner`() {
        val defs = defaultMealReminders()
        assertTrue(nextDayMeals("Early", defs.getValue("Early")).isEmpty())
        assertTrue(nextDayMeals("Middle", defs.getValue("Middle")).isEmpty())
        assertEquals(setOf(MealKind.Dinner), nextDayMeals("Late", defs.getValue("Late")))
        assertEquals(setOf(MealKind.Lunch, MealKind.Dinner), nextDayMeals("Night", defs.getValue("Night")))
        assertTrue(nextDayMeals("Off", defs.getValue("Off")).isEmpty())
    }
}
