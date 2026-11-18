package com.rudnam.samfran.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class ServerTextTest {
    private val ja = Locale.JAPANESE

    @Test
    fun `known messages are translated, patterns too, and English starts with a capital`() {
        assertEquals("場所が見つかりません", ServerText.translate("no such restaurant", ja))
        assertEquals("パスワードは10〜72文字にしてください", ServerText.translate("password must be 10 to 72 characters", ja))
        assertEquals("勤務表にオトル サマンタが見つかりませんでした。設定の名前を確認してください", ServerText.translate("couldn't find オトル サマンタ on the sheet; check the name in settings", ja))
        assertEquals("something new", ServerText.translate("something new", ja))
        assertEquals("No such restaurant", ServerText.translate("no such restaurant", Locale.ENGLISH))
    }

    /** The table is the website's (web/src/locales/ja.ts, jaServer): a message added there is added here. */
    @Test
    fun `the table is the website's`() {
        val source = File("../../../web/src/locales/ja.ts").readText()
        val block = source.substring(source.indexOf("export const jaServer")).substringBefore("\n} as const")
        val entry = Regex("""^\s*(['"])((?:\\.|(?!\1).)*)\1\s*:\s*\n?\s*(['"])((?:\\.|(?!\3).)*)\3,""", RegexOption.MULTILINE)
        val website = entry.findAll(block).associate { m ->
            m.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") to m.groupValues[4].replace("\\'", "'").replace("\\\"", "\"")
        }
        assertEquals(website, ServerText.JA)
    }
}

class DatesTest {
    private val today = LocalDate.of(2026, 11, 16)

    @Test
    fun `dates as the website writes them`() {
        assertEquals("Nov 12", Dates.short(LocalDate.of(2026, 11, 12), Locale.ENGLISH, today))
        assertEquals("Dec 30, 2025", Dates.short(LocalDate.of(2025, 12, 30), Locale.ENGLISH, today))
        assertEquals("11月12日", Dates.short(LocalDate.of(2026, 11, 12), Locale.JAPANESE, today))
        assertEquals("Wed 4", Dates.day(LocalDate.of(2026, 11, 4), Locale.ENGLISH))
        assertEquals("4日(水)", Dates.day(LocalDate.of(2026, 11, 4), Locale.JAPANESE))
        assertEquals("November 2026", Dates.month(YearMonth.of(2026, 11), Locale.ENGLISH))
        assertEquals("2026年11月", Dates.month(YearMonth.of(2026, 11), Locale.JAPANESE))
    }
}
