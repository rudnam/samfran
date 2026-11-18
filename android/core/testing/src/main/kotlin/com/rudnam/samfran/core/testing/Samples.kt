package com.rudnam.samfran.core.testing

import com.rudnam.samfran.core.model.ActivityDetails
import com.rudnam.samfran.core.model.ActivityEntry
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.BusStops
import com.rudnam.samfran.core.model.FoundStop
import com.rudnam.samfran.core.model.Invite
import com.rudnam.samfran.core.model.Member
import com.rudnam.samfran.core.model.PlaceCategory
import com.rudnam.samfran.core.model.Rating
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.ShiftCodes
import com.rudnam.samfran.core.model.ShiftCoworker
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.model.ShiftMonth
import com.rudnam.samfran.core.model.ShiftSettings
import com.rudnam.samfran.core.model.ShiftThere
import com.rudnam.samfran.core.model.Space
import com.rudnam.samfran.core.model.Visit
import com.rudnam.samfran.core.model.VisitNote
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/** Sample data for tests and screenshots: Fran (signed in) and Sam, in November 2026. */
object Samples {
    private fun d(s: String) = LocalDate.parse(s)

    val space = Space(
        members = listOf(Member("Fran", "fran@example.com", me = true), Member("Sam", "sam@example.com", me = false)),
        invites = emptyList(),
        inviteLinkExpires = null,
    )

    val spaceAlone = Space(listOf(Member("Fran", "fran@example.com", me = true)), emptyList(), null)

    val spaceInvited = Space(listOf(Member("Fran", "fran@example.com", me = true)), listOf(Invite("sam@example.com", Instant.parse("2026-11-10T00:00:00Z"))), null)

    val restaurants = listOf(
        Restaurant(
            12, "Hanabi Grill", "Station East", "Yakiniku", mapUrl = "https://maps.app.goo.gl/hanabi", notes = "Book the window seats. The harami is the one.",
            addedBy = "Sam", visitCount = 4, lastVisit = d("2026-11-14"),
            ratings = listOf(Rating("Fran", true, 5), Rating("Sam", false, 4)),
            visits = listOf(
                Visit(41, d("2026-11-14"), "Harami and the cold noodles", "Sam"),
                Visit(37, d("2026-10-02"), "", "Fran"),
                Visit(30, d("2026-08-21"), "Birthday dinner", "Fran"),
                Visit(22, d("2026-06-05"), "", "Sam"),
            ),
            visitNotes = listOf(VisitNote(d("2026-11-14"), "Harami and the cold noodles"), VisitNote(d("2026-08-21"), "Birthday dinner")),
        ),
        Restaurant(
            9, "Menya Kaze", "Aoi", "Ramen", visitCount = 2, lastVisit = d("2026-11-03"), addedBy = "Fran",
            ratings = listOf(Rating("Fran", true, 4), Rating("Sam", false, 5)),
            visitNotes = listOf(VisitNote(d("2026-11-03"), "Spicy miso, extra egg")),
        ),
        Restaurant(7, "Cafe Lune", "Shichiken-cho", "Cafe", visitCount = 1, lastVisit = d("2026-09-18"), addedBy = "Sam", ratings = listOf(Rating("Sam", false, 3))),
        Restaurant(5, "Trattoria Sole", "Kusanagi", "Italian", visitCount = 3, lastVisit = d("2025-12-24"), addedBy = "Fran", ratings = listOf(Rating("Fran", true, 4))),
        Restaurant(15, "Sushi Kiyo", "Shimizu port", "Sushi", addedBy = "Sam", notes = "Closed Wednesdays", lat = 35.0141, lng = 138.4895),
        Restaurant(14, "Bistro Ume", "Station North", "French", addedBy = "Fran"),
        Restaurant(
            20, "Sumpu Castle Park", "Aoi", "Park", category = PlaceCategory.Nature, visitCount = 1, lastVisit = d("2026-10-25"), addedBy = "Fran",
            ratings = listOf(Rating("Fran", true, 4)),
        ),
        Restaurant(21, "Kunozan Toshogu", "Suruga", "Shrine", category = PlaceCategory.Culture, addedBy = "Sam", notes = "1,159 steps up from the sea", lat = 34.9658, lng = 138.4683),
        Restaurant(22, "Nihondaira Yume Terrace", "Shimizu", "Viewpoint", category = PlaceCategory.Nature, addedBy = "Fran", lat = 34.9958, lng = 138.4618),
        Restaurant(23, "Umegashima Onsen", "Aoi", "Onsen", category = PlaceCategory.Relax, addedBy = "Sam"),
    )

    val places = listOf(
        com.rudnam.samfran.core.model.FoundPlace("すき家 静岡駅南店", "静岡市駿河区", "南町", "fast_food", 34.968, 138.389, category = PlaceCategory.Eat),
        com.rudnam.samfran.core.model.FoundPlace("すき家 葵区役所前店", "静岡市葵区", "追手町", "fast_food", 34.976, 138.383, category = PlaceCategory.Eat),
        com.rudnam.samfran.core.model.FoundPlace("Hanabi Grill", "静岡市葵区", "紺屋町", "restaurant", 34.972, 138.387, category = PlaceCategory.Eat),
        com.rudnam.samfran.core.model.FoundPlace("駿府城公園", "静岡市葵区", "駿府城公園", "park", 34.979, 138.383, category = PlaceCategory.Nature),
        com.rudnam.samfran.core.model.FoundPlace("静岡浅間神社", "静岡市葵区", "宮ケ崎町", "place_of_worship", 34.983, 138.375, category = PlaceCategory.Culture),
    )

    val settings = ShiftSettings(
        personName = "Sam",
        nameOnSheet = "オトル サマンタ",
        lengths = mapOf("F" to 9.0, "D" to 8.0),
        timeZone = "Asia/Tokyo",
        feedPath = "",
        reader = true,
    )

    private val tokyo = ZoneOffset.ofHours(9)

    /** A day as the server gives it, worked out from its code. */
    fun day(date: String, code: String, s: ShiftSettings = settings): ShiftDay {
        val m = ShiftCodes.explain(code, s.lengths)
        val day = d(date)
        val start = m.hours?.let { OffsetDateTime.of(day.atStartOfDay(), tokyo).plusMinutes((it.first * 60).toLong()) }
        val end = m.hours?.let { OffsetDateTime.of(day.atStartOfDay(), tokyo).plusMinutes((it.second * 60).toLong()) }
        return ShiftDay(day, code, m.kind, m.label, start, end, m.problem)
    }

    /** Someone else there around [date], on [code] that day or the day before/after ([offset]). */
    fun there(name: String, code: String, date: String, offset: Int = 0): ShiftThere {
        val d = day(d(date).plusDays(offset.toLong()).toString(), code)
        return ShiftThere(name, code, offset, d.start, d.end)
    }

    val november = ShiftMonth(
        YearMonth.of(2026, 11),
        settings,
        listOf(
            "01" to "普", "02" to "7F", "03" to "7F", "04" to "13F", "05" to "13F", "06" to "法", "07" to "普",
            "08" to "22F", "09" to "22F", "10" to "普", "11" to "9F", "12" to "9F", "13" to "有休", "14" to "普",
            "15" to "7F", "16" to "13F", "17" to "13F", "18" to "22F", "19" to "普", "20" to "研修", "21" to "普",
            "22" to "9F", "23" to "9F", "24" to "13F", "25" to "13Z", "26" to "法", "27" to "7F", "28" to "普",
            "29" to "22F", "30" to "普",
        ).map { (dd, code) ->
            day("2026-11-$dd", code).let { d ->
                // Others with the same code that day, as the photo reader lists them,
                // others on other shifts whose hours overlap it, and everyone kept (people).
                when (code) {
                    "13F" -> d.copy(
                        with = listOf("タナカ ユイ"),
                        others = listOf(there("ヤマダ ハナ", "9F", "2026-11-$dd"), there("サトウ ミオ", "17F", "2026-11-$dd")),
                        people = listOf(
                            ShiftCoworker("タナカ ユイ", "13F"), ShiftCoworker("ヤマダ ハナ", "9F"), ShiftCoworker("サトウ ミオ", "17F"),
                            ShiftCoworker("コバヤシ リク", "5D"), // 5:00–13:00: gone as the shift starts
                            ShiftCoworker("ヨル タロウ", "22F", -1),
                        ),
                    )
                    "22F" -> d.copy(
                        with = listOf("スズキ ケン", "ヤマモト アオイ"),
                        others = listOf(there("アサ ミキ", "6F", "2026-11-$dd", offset = 1)),
                        people = listOf(ShiftCoworker("スズキ ケン", "22F"), ShiftCoworker("ヤマモト アオイ", "22F"), ShiftCoworker("アサ ミキ", "6F", 1)),
                    )
                    "7F" -> d.copy(
                        others = listOf(there("ヨル タロウ", "23F", "2026-11-$dd", offset = -1)),
                        people = listOf(ShiftCoworker("ヨル タロウ", "23F", -1)),
                    )
                    else -> d
                }
            }
        },
    )

    val stop = BusStop(set = true, name = "葵西", lat = 34.9756, lng = 138.3829, alertMeters = 800, id = 1, shifts = listOf("Late"))

    val station = BusStop(set = true, name = "静岡駅", lat = 34.9715, lng = 138.3890, alertMeters = 600, id = 2, shifts = listOf("Early", "Middle"))

    val mums = BusStop(set = true, name = "実家", lat = 34.9500, lng = 138.4000, alertMeters = 800, id = 3)

    /** One stop, as most people have. */
    val oneStop = BusStops(listOf(stop.copy(shifts = emptyList())), lastId = 1)

    /** A stop for late shifts, one for early and middle ones, and one for when it's picked. */
    val stops = BusStops(listOf(stop, station, mums), lastId = 1)

    val found = listOf(
        FoundStop("葵西", "静岡市葵区", train = false, lat = 34.9756, lng = 138.3829),
        FoundStop("葵西三丁目", "静岡市葵区", train = false, lat = 34.9771, lng = 138.3801),
        FoundStop("静岡", "静岡市葵区黒金町", train = true, lat = 34.9715, lng = 138.3890),
    )

    val activity = listOf(
        ActivityEntry(58, Instant.parse("2026-11-15T09:12:00Z"), "Sam", me = false, viaClaude = true, kind = "shifts_saved", details = ActivityDetails(month = "2026-12")),
        ActivityEntry(57, Instant.parse("2026-11-14T12:40:00Z"), "Sam", me = false, viaClaude = false, kind = "visit_logged", details = ActivityDetails(name = "Hanabi Grill")),
        ActivityEntry(56, Instant.parse("2026-11-14T12:41:00Z"), "Sam", me = false, viaClaude = false, kind = "rated", details = ActivityDetails(name = "Hanabi Grill", stars = 4)),
        ActivityEntry(52, Instant.parse("2026-11-10T03:00:00Z"), "Fran", me = true, viaClaude = false, kind = "restaurant_added", details = ActivityDetails(name = "Bistro Ume")),
        ActivityEntry(
            50, Instant.parse("2026-11-08T03:00:00Z"), "Fran", me = true, viaClaude = false, kind = "shift_settings",
            details = ActivityDetails(lengths = mapOf("F" to (8.5 to 9.0))),
        ),
    )
}
