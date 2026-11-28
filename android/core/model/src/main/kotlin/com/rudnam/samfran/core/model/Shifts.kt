package com.rudnam.samfran.core.model

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

enum class ShiftKind { Work, Off, Leave, Training, Other }

data class ShiftDay(
    val date: LocalDate,
    /** As on the sheet: "13F", "普", "有休"… */
    val code: String,
    val kind: ShiftKind,
    /** "Early", "Late", "Night", "Day off"… (English, as the server writes it). */
    val label: String,
    /** With the offset of the shift's time zone, for a working day whose length is known. */
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    /** Why a working day has no times, e.g. "unknown length for Z". */
    val problem: String? = null,
    /** Others on the same shift (the same code that day), by their names as printed on the sheet. */
    val with: List<String> = emptyList(),
    /** Others on other shifts whose hours overlap this one, by when they start. */
    val others: List<ShiftThere> = emptyList(),
    /** Everyone kept around the day, there during it or not: [with] and [others] are worked out from them. */
    val people: List<ShiftCoworker> = emptyList(),
) {
    /** The colour it's drawn in. */
    val tone: ShiftTone
        get() = when (kind) {
            ShiftKind.Work -> when (label) {
                "Early" -> ShiftTone.Early
                "Middle" -> ShiftTone.Middle
                "Late" -> ShiftTone.Late
                "Night" -> ShiftTone.Night
                else -> ShiftTone.Other
            }
            ShiftKind.Off -> ShiftTone.Off
            ShiftKind.Leave -> ShiftTone.Leave
            ShiftKind.Training -> ShiftTone.Training
            ShiftKind.Other -> ShiftTone.Other
        }

    /** e.g. "22:00–6:00", as written in the shift's own time zone. */
    val times: String
        get() {
            val s = start ?: return ""
            val e = end ?: return ""
            return "${s.hour}:%02d–${e.hour}:%02d".format(s.minute, e.minute)
        }

    /** The day's label as the workplace says it. */
    val workplaceLabel: String get() = ShiftCodes.labelOf(label)

    /** What fits in a calendar square: 早, 遅, 夜, 休… or the code itself for anything else. */
    val mark: String get() = workplaceLabel.let { if (it == label) code else it.take(1) }
}

/** Someone else at work around a day, as read off the sheet (internal/shift/others.go). */
data class ShiftCoworker(
    val name: String,
    val code: String,
    /** -1: a night shift from the day before; 1: an early shift the next day; 0: the same day. */
    val dayOffset: Int = 0,
)

/** Someone else there during a shift, and when. */
data class ShiftThere(
    val name: String,
    val code: String,
    val dayOffset: Int = 0,
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
) {
    val coworker: ShiftCoworker get() = ShiftCoworker(name, code, dayOffset)

    /** e.g. "9:00–18:00". */
    val times: String
        get() {
            val s = start ?: return ""
            val e = end ?: return ""
            return "${s.hour}:%02d–${e.hour}:%02d".format(s.minute, e.minute)
        }
}

/** How far reading a photo of the sheet has got. */
sealed interface ReadProgress {
    data object Sending : ReadProgress

    /** [nameAsRead] once the row's found, then [daysRead] of [daysInMonth] (both 0 until the days start). */
    data class Reading(val nameAsRead: String? = null, val daysRead: Int = 0, val daysInMonth: Int = 0) : ReadProgress
}

/** The shift colours (web/src/styles.css, .shift-dot and .cal-day). */
enum class ShiftTone { Early, Middle, Late, Night, Off, Leave, Training, Other }

data class ShiftSettings(
    val personName: String = "",
    val nameOnSheet: String = "",
    /** Hours on site for each letter of a shift code. */
    val lengths: Map<String, Double> = emptyMap(),
    val timeZone: String = "",
    /** 締め申請通知: days of the month the hours are sent in; one past the month's end means its last day. */
    val closingDays: List<Int> = listOf(15, 31),
    /** Breakfast, lunch and dinner rules per shift kind (and "Off"). */
    val mealReminders: Map<String, DayMeals> = defaultMealReminders(),
    /** The calendar feed's path on the server, or "" while it's off. */
    val feedPath: String = "",
    /** Photos can be read on this server. */
    val reader: Boolean = false,
)

data class ShiftMonth(
    val month: YearMonth,
    val settings: ShiftSettings,
    val days: List<ShiftDay>,
    /** From a photo: the name on the row that was read. Null when it's the saved month. */
    val nameAsRead: String? = null,
    /** From a photo: the reader's doubts. */
    val notes: String = "",
    /** This month's 締め申請通知 days, if the server said (it can set them per month). */
    val closingDays: List<Int>? = null,
) {
    val fromPhoto: Boolean get() = nameAsRead != null

    /** The days this month's hours are sent in: its own, else the usual ones. */
    val closing: List<Int> get() = closingDays ?: usualClosing

    /** The usual days (from the settings) as they fall in this month: a day past its end is its last day. */
    val usualClosing: List<Int> get() = settings.closingDays.map { minOf(it, month.lengthOfMonth()) }.distinct().sorted()
}

/**
 * The shift codes, as internal/shift/codes.go reads them (and
 * web/src/features/shifts/codes.ts), so the edit screen can show what a
 * code means while it's typed. The server's reading is the one that counts.
 */
object ShiftCodes {
    // Letters before the hour ("HPA 13LE") are kept but have no length; those after it do.
    private val work = Regex("^[A-Z]{0,4}?([0-9]{1,2})([A-Z]{1,3})$")

    /** The codes offered as one-tap chips while editing. */
    val common = listOf("7F", "9F", "13F", "22F", "普", "法", "有休")

    /** Full-width letters and digits as ASCII, upper case, no spaces (as the server does). */
    fun normalize(code: String): String = buildString {
        for (c in code.trim()) {
            when (c) {
                in '０'..'９', in 'Ａ'..'Ｚ', in 'ａ'..'ｚ' -> append(c - 0xFEE0)
                ' ', '\t', '\n', '　' -> {}
                else -> append(c)
            }
        }
    }.uppercase()

    /** What a code means. */
    data class Meaning(val kind: ShiftKind, val label: String, val hours: Pair<Double, Double>? = null, val problem: String? = null)

    fun explain(code: String, lengths: Map<String, Double>): Meaning {
        val c = normalize(code)
        work.matchEntire(c)?.let { m ->
            val start = m.groupValues[1].toInt()
            val label = when {
                start < 9 -> "Early"
                start < 12 -> "Middle"
                start < 18 -> "Late"
                else -> "Night"
            }
            if (start > 23) return Meaning(ShiftKind.Work, label, problem = "no such start hour")
            val len = lengths[m.groupValues[2]] ?: 0.0
            if (len <= 0) return Meaning(ShiftKind.Work, label, problem = "unknown length for ${m.groupValues[2]}")
            return Meaning(ShiftKind.Work, label, hours = start.toDouble() to start + len)
        }
        return when (c) {
            "普", "法", "休", "公", "公休" -> Meaning(ShiftKind.Off, "Day off")
            "有休", "有", "有給" -> Meaning(ShiftKind.Leave, "Paid leave")
            "トレ", "研修" -> Meaning(ShiftKind.Training, "Training")
            else -> Meaning(ShiftKind.Other, c)
        }
    }

    // The workplace's own words, in every language of the app: they're
    // what's on the sheet and what Sam says (the calendar feed uses them too).
    private val labels = mapOf(
        "Early" to "早番",
        "Middle" to "中番",
        "Late" to "遅番",
        "Night" to "夜勤",
        "Day off" to "休み",
        "Paid leave" to "有休",
        "Training" to "研修",
    )

    fun labelOf(label: String): String = labels[label] ?: label

    /**
     * When someone is there, in hours from the start of the day being edited:
     * their code's hours moved a day back or on ([dayOffset] -1 or 1). Null
     * for a code without known hours.
     */
    fun hoursAround(code: String, dayOffset: Int, lengths: Map<String, Double>): Pair<Double, Double>? =
        explain(code, lengths).hours?.let { (from, to) -> from + dayOffset * 24 to to + dayOffset * 24 }

    /** Whether two spans of hours overlap (as internal/shift/others.go decides). */
    fun overlaps(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean = a.first < b.second && a.second > b.first

    /** "13:00"; a shift that ends the next morning just shows "7:00", and one from the night before "22:00". */
    fun clock(hour: Double): String {
        val h = ((hour % 24) + 24) % 24
        val whole = h.toInt()
        val min = Math.round((h - whole) * 60).toInt()
        return "$whole:%02d".format(min)
    }
}
