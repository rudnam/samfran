package com.rudnam.samfran.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class ShiftCodesTest {
    private val lengths = mapOf("F" to 9.0, "D" to 8.5)

    @Test
    fun `full-width codes read as ASCII, as the server reads them`() {
        assertEquals("13F", ShiftCodes.normalize(" １３ｆ "))
        assertEquals("有休", ShiftCodes.normalize("有　休"))
    }

    @Test
    fun `a working code gives its kind of shift and hours`() {
        assertEquals(ShiftCodes.Meaning(ShiftKind.Work, "Early", 7.0 to 16.0), ShiftCodes.explain("7F", lengths))
        assertEquals(ShiftCodes.Meaning(ShiftKind.Work, "Middle", 9.0 to 17.5), ShiftCodes.explain("9d", lengths))
        assertEquals("Late", ShiftCodes.explain("13F", lengths).label)
        assertEquals(22.0 to 31.0, ShiftCodes.explain("22F", lengths).hours)
        assertEquals("unknown length for Z", ShiftCodes.explain("13Z", lengths).problem)
        assertEquals("no such start hour", ShiftCodes.explain("25F", lengths).problem)
    }

    @Test
    fun `days off, leave and training`() {
        assertEquals(ShiftKind.Off, ShiftCodes.explain("普", lengths).kind)
        assertEquals(ShiftKind.Leave, ShiftCodes.explain("有給", lengths).kind)
        assertEquals(ShiftKind.Training, ShiftCodes.explain("研修", lengths).kind)
        assertEquals(ShiftCodes.Meaning(ShiftKind.Other, "X"), ShiftCodes.explain("x", lengths))
    }

    @Test
    fun `clock writes the next morning with +1`() {
        assertEquals("13:00", ShiftCodes.clock(13.0))
        assertEquals("17:30", ShiftCodes.clock(17.5))
        assertEquals("7:00 (+1)", ShiftCodes.clock(31.0))
    }

    @Test
    fun `a day's times are as written, in the shift's own time zone`() {
        val day = ShiftDay(
            LocalDate.of(2026, 11, 4), "22F", ShiftKind.Work, "Night",
            OffsetDateTime.parse("2026-11-04T22:00:00+09:00"), OffsetDateTime.parse("2026-11-05T07:00:00+09:00"),
        )
        assertEquals("22:00–7:00", day.times)
        assertEquals(ShiftTone.Night, day.tone)
        assertEquals("夜", day.mark)
        assertEquals("X9", ShiftDay(day.date, "X9", ShiftKind.Other, "X9").mark)
    }
}

class SharedTest {
    @Test
    fun `a place shared from Maps`() {
        assertEquals(Shared("Hanabi Grill", "https://maps.app.goo.gl/AbC123"), Shared.parse("Hanabi Grill\nhttps://maps.app.goo.gl/AbC123"))
        assertEquals(Shared("Ramen Ichi", "https://maps.app.goo.gl/x"), Shared.parse("Ramen Ichi · 4.5★ · Ramen\nhttps://maps.app.goo.gl/x"))
        assertEquals(Shared("Sushi Go", "https://maps.app.goo.gl/y"), Shared.parse("Check out this place:\nSushi Go\nhttps://maps.app.goo.gl/y"))
        assertEquals(Shared("", "https://maps.app.goo.gl/z"), Shared.parse("https://maps.app.goo.gl/z"))
    }

    @Test
    fun `the subject goes first when the text has only the link`() {
        assertEquals("Cafe\nhttps://maps.app.goo.gl/q", Shared.fromIntent("https://maps.app.goo.gl/q", "Cafe"))
        assertEquals("Cafe https://maps.app.goo.gl/q", Shared.fromIntent("Cafe https://maps.app.goo.gl/q", "Cafe"))
        assertNull(Shared.fromIntent("", "Cafe"))
    }

    @Test
    fun `maps links`() {
        assertTrue(Shared.isMapsLink("https://maps.app.goo.gl/AbC"))
        assertTrue(Shared.isMapsLink("https://www.google.co.jp/maps/place/x"))
        assertTrue(Shared.isMapsLink("https://goo.gl/maps/abc"))
        assertFalse(Shared.isMapsLink("https://example.com/maps"))
        assertFalse(Shared.isMapsLink("not a link"))
    }
}

class FoodTest {
    private fun place(id: Long, last: String? = null, visits: Int = if (last == null) 0 else 1, notes: List<VisitNote> = emptyList()) =
        Restaurant(id, "Place $id", area = "Station East", lastVisit = last?.let(LocalDate::parse), visitCount = visits, visitNotes = notes)

    @Test
    fun `been there is latest visit first, want to go newest first`() {
        val lists = FoodLists.of(listOf(place(1, "2026-10-01"), place(2), place(3, "2026-11-02"), place(4), place(5, "2026-11-02")))
        assertEquals(listOf(5L, 3L, 1L), lists.been.map { it.id })
        assertEquals(listOf(4L, 2L), lists.want.map { it.id })
    }

    @Test
    fun `search looks in visits' notes too`() {
        val p = place(1, "2026-10-01", notes = listOf(VisitNote(LocalDate.parse("2026-10-01"), "Spicy miso ramen")))
        assertTrue(p.matches("station"))
        assertTrue(p.matches("MISO"))
        assertEquals("Spicy miso ramen", p.noteMatching("miso")?.note)
        assertFalse(p.matches("pizza"))
    }

    @Test
    fun `map link searches for the name and area when there's no saved link`() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=Place%201%20Station%20East", place(1).mapLink)
        assertEquals("https://maps.app.goo.gl/x", place(1).copy(mapUrl = "https://maps.app.goo.gl/x").mapLink)
    }
}

class BusTest {
    @Test
    fun distances() {
        assertEquals("600 m", Distances.format(604f))
        assertEquals("1.5 km", Distances.format(1500f))
        assertEquals("葵西", stopNameFromMaps("葵西（バス）"))
        assertEquals("Aoi Nishi", stopNameFromMaps("Aoi Nishi (Bus)"))
    }

    @Test
    fun `each person keeps their colour`() {
        val space = Space(listOf(Member("Fran", "f@x", true), Member("Sam", "s@x", false)), emptyList(), null)
        assertEquals(Tone.A, space.toneOf("Fran"))
        assertEquals(Tone.B, space.toneOf("Sam"))
        assertEquals("Sam", space.partner?.name)
        assertFalse(space.hasRoom)
    }
}
