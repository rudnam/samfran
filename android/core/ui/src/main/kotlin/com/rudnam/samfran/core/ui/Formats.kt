package com.rudnam.samfran.core.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** The app's language now (Android's per-app language, else the phone's). */
val currentLocale: Locale
    @Composable @ReadOnlyComposable
    get() = LocalConfiguration.current.locales[0]

/** Dates as the website writes them, in the app's language. */
object Dates {
    /** "Nov 12" (11月12日), with the year when it isn't this year. */
    fun short(date: LocalDate, locale: Locale, today: LocalDate = LocalDate.now()): String {
        val pattern = when {
            locale.language == "ja" && date.year == today.year -> "M月d日"
            locale.language == "ja" -> "yyyy年M月d日"
            date.year == today.year -> "MMM d"
            else -> "MMM d, yyyy"
        }
        return date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    /** "Wed 4" (4日(水)) for a day of a month. */
    fun day(date: LocalDate, locale: Locale): String {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        return if (locale.language == "ja") "${date.dayOfMonth}日($weekday)" else "$weekday ${date.dayOfMonth}"
    }

    /** "November 2026" (2026年11月). */
    fun month(month: YearMonth, locale: Locale): String =
        if (locale.language == "ja") "${month.year}年${month.monthValue}月" else month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))

    /** "Nov 12, 3:04 PM": when something happened. */
    fun moment(at: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        val pattern = if (locale.language == "ja") "M月d日 H:mm" else "MMM d, h:mm a"
        return at.atZone(zone).format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    /** A day in the phone's format. */
    fun medium(at: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        at.atZone(zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    /** The weekdays' names, Sunday first (the calendar's heading): "Su", "Mo", "Tu" (one letter leaves T and S twice), 日, 月, 火 in Japanese. */
    fun shortWeekdays(locale: Locale): List<String> =
        (0..6).map {
            val day = java.time.DayOfWeek.SUNDAY.plus(it.toLong())
            if (locale.language == "ja") day.getDisplayName(TextStyle.NARROW, locale) else day.getDisplayName(TextStyle.SHORT, locale).take(2)
        }
}

/** Opens a web page in a Custom Tab (Maps links open in Google Maps when it's installed). */
fun Context.openWeb(url: String) {
    val uri = Uri.parse(url)
    val maps = uri.host?.let { it.contains("google.") || it == "maps.app.goo.gl" || it == "goo.gl" } == true
    if (maps) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
    } catch (_: ActivityNotFoundException) {
        // No browser at all: nothing to open it with.
    }
}

/** Android's share sheet with [text]. */
fun Context.shareText(text: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    if (title != null) send.putExtra(Intent.EXTRA_TITLE, title)
    startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Copies [text]; Android 13+ shows that it did. */
fun Context.copyText(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("SamFran", text))
}
