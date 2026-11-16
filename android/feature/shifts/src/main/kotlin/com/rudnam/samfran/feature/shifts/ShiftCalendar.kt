package com.rudnam.samfran.feature.shifts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rudnam.samfran.core.designsystem.component.CodeChip
import com.rudnam.samfran.core.designsystem.component.Dot
import com.rudnam.samfran.core.designsystem.theme.Pair2
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.designsystem.theme.ShiftDotColors
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.model.ShiftTone
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.currentLocale
import java.time.LocalDate
import java.time.YearMonth

/** A kind of shift's dot (.shift-dot). */
fun ShiftTone.dot(): Color = when (this) {
    ShiftTone.Early -> ShiftDotColors.early
    ShiftTone.Middle -> ShiftDotColors.middle
    ShiftTone.Late -> ShiftDotColors.late
    ShiftTone.Night -> ShiftDotColors.night
    ShiftTone.Off -> ShiftDotColors.off
    ShiftTone.Leave -> ShiftDotColors.leave
    ShiftTone.Training -> ShiftDotColors.training
    ShiftTone.Other -> Color.Transparent
}

/** A calendar square's colours (.cal-day.*); null for the quiet kinds (off, other). */
@Composable
private fun ShiftTone.square(): Pair2? {
    val c = SamFranTheme.colors
    return when (this) {
        ShiftTone.Early -> c.calEarly
        ShiftTone.Middle -> c.calMiddle
        ShiftTone.Late -> c.calLate
        ShiftTone.Night -> c.calNight
        ShiftTone.Leave -> c.calLeave
        ShiftTone.Training -> c.calTraining
        else -> null
    }
}

/**
 * The month as a calendar (web/src/features/shifts/ShiftCalendar.tsx): one
 * square per day, coloured by the kind of shift, today ringed. Tapping a day
 * shows its times underneath.
 */
@Composable
fun ShiftCalendar(month: YearMonth, days: List<ShiftDay>, today: LocalDate, picked: LocalDate?, onPick: (LocalDate) -> Unit) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val byDate = days.associateBy { it.date }
    val lead = month.atDay(1).dayOfWeek.value % 7 // squares before the 1st; weeks start on Sunday
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val nothing = stringResource(R.string.nothing_entered)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in Dates.narrowWeekdays(locale)) {
                Text(w, Modifier.weight(1f), color = colors.muted, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        for (week in cells.chunked(7)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until 7) {
                    val date = week.getOrNull(i)
                    if (date == null) {
                        Box(Modifier.weight(1f))
                        continue
                    }
                    val d = byDate[date]
                    val square = d?.tone?.square()
                    val shape = RoundedCornerShape(10.dp)
                    val what = d?.let { listOf(it.workplaceLabel, it.times).filter(String::isNotEmpty).joinToString(" ") } ?: nothing
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.86f)
                            .clip(shape)
                            .background(square?.bg ?: Color.Transparent)
                            .then(if (d == null) Modifier.border(1.dp, colors.divider, shape) else Modifier)
                            .then(if (date == today) Modifier.border(2.5.dp, colors.heart, shape) else Modifier)
                            .then(if (date == picked) Modifier.border(2.5.dp, colors.text, shape) else Modifier)
                            .clickable(role = Role.Button) { onPick(date) }
                            .clearAndSetSemantics {
                                contentDescription = "${Dates.day(date, locale)}: $what"
                                selected = date == picked
                                role = Role.Button
                            }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val ink = square?.ink ?: colors.muted
                        Text(date.dayOfMonth.toString(), color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(d?.mark.orEmpty(), color = ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                }
            }
        }
        if (picked != null && YearMonth.from(picked) == month) DayLine(picked, byDate[picked], Modifier.padding(top = 4.dp))
    }
}

/** One day: its date, the kind of shift, its times and its code (the list's row, and under the calendar). */
@Composable
fun DayLine(date: LocalDate, day: ShiftDay?, modifier: Modifier = Modifier) {
    val colors = SamFranTheme.colors
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DayRow(date, day, colors)
        WithNames(day?.with.orEmpty(), Modifier.padding(start = 74.dp))
    }
}

@Composable
private fun DayRow(date: LocalDate, day: ShiftDay?, colors: com.rudnam.samfran.core.designsystem.theme.SamFranColors) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(Dates.day(date, currentLocale), Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge, color = if (day == null || day.kind != com.rudnam.samfran.core.model.ShiftKind.Work) colors.muted else colors.text)
        if (day == null) {
            Text(stringResource(R.string.nothing_entered), Modifier.weight(1f), color = colors.muted, style = MaterialTheme.typography.bodyMedium)
            return@Row
        }
        Dot(day.tone.dot())
        Column(Modifier.weight(1f)) {
            Text(day.workplaceLabel, style = MaterialTheme.typography.bodyMedium)
            if (day.problem != null) Text(stringResource(R.string.unknown_length), color = colors.danger, style = MaterialTheme.typography.bodySmall)
        }
        Text(day.times, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        CodeChip(day.code)
    }
}

/**
 * Who else is on a shift: "With タナカ ユイ, スズキ ケン" (WithNames.tsx). Each
 * name stays in one piece when the line wraps.
 */
@Composable
fun WithNames(names: List<String>, modifier: Modifier = Modifier) {
    if (names.isEmpty()) return
    val sep = if (currentLocale.language == "ja") "、" else ", "
    // Non-breaking spaces inside a name keep it on one line.
    val list = names.joinToString(sep) { it.replace(' ', '\u00A0').replace('\u3000', '\u00A0') }
    Text(stringResource(R.string.with_names, list), modifier, style = MaterialTheme.typography.bodySmall, color = SamFranTheme.colors.muted)
}
