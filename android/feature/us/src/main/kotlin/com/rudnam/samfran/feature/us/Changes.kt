package com.rudnam.samfran.feature.us

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.rudnam.samfran.core.model.ActivityEntry
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.currentLocale
import java.time.YearMonth

private fun hours(h: Double?) = h?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()

/**
 * What happened, worded (web/src/features/us/ActivityCard.tsx's describe);
 * "" for a kind this version doesn't know, which isn't shown.
 */
@Composable
@ReadOnlyComposable
fun describe(e: ActivityEntry): String {
    val d = e.details
    return when (e.kind) {
        "shifts_saved" -> {
            val month = runCatching { YearMonth.parse(d.month) }.getOrNull()
            stringResource(R.string.change_shifts_saved, if (month != null) Dates.month(month, currentLocale) else d.month)
        }
        "shift_settings" -> buildList {
            for ((letter, change) in d.lengths.entries.sortedBy { it.key }) {
                val (from, to) = change
                add(
                    when {
                        to == null -> stringResource(R.string.change_removed_letter, letter)
                        from != null && from > 0 -> stringResource(R.string.change_letter_was, letter, hours(to), hours(from))
                        else -> stringResource(R.string.change_letter, letter, hours(to))
                    },
                )
            }
            for (key in listOf("person_name", "name_on_sheet", "time_zone", "closing_days")) {
                val change = d.settings[key] ?: continue
                val what = when (key) {
                    "person_name" -> stringResource(R.string.setting_person)
                    "name_on_sheet" -> stringResource(R.string.setting_on_sheet)
                    "closing_days" -> stringResource(R.string.setting_closing_days)
                    else -> stringResource(R.string.setting_time_zone)
                }
                add(stringResource(R.string.change_setting, what, change.second))
            }
            if (d.mealRemindersChanged) {
                add(stringResource(R.string.change_meal_reminders))
            }
        }.joinToString(", ")
        "calendar_link_new" -> stringResource(R.string.change_calendar_new)
        "calendar_link_off" -> stringResource(R.string.change_calendar_off)
        "restaurant_added" -> stringResource(if (d.been) R.string.change_added_been else R.string.change_added_want, d.name)
        "restaurant_edited" -> stringResource(R.string.change_edited, d.name)
        "restaurant_deleted" -> stringResource(R.string.change_deleted, d.name)
        "visit_logged" -> stringResource(R.string.change_visit, d.name)
        "visit_removed" -> stringResource(R.string.change_visit_removed, d.name)
        "photos_added" -> stringResource(R.string.change_photos, d.name)
        "commented" -> stringResource(R.string.change_commented, d.name)
        "been_date_changed" -> stringResource(R.string.change_been_date, d.name)
        "been_cleared" -> stringResource(R.string.change_been_cleared, d.name)
        "rated" -> if (d.stars > 0) stringResource(R.string.change_rated, d.name, d.stars) else stringResource(R.string.change_unrated, d.name)
        "invited" -> stringResource(R.string.change_invited, d.email)
        "invite_link" -> stringResource(R.string.change_invite_link)
        "invite_cancelled" -> stringResource(R.string.change_invite_cancelled, d.email)
        else -> ""
    }
}
