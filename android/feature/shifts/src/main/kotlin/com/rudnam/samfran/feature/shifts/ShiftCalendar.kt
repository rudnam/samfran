package com.rudnam.samfran.feature.shifts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.rudnam.samfran.core.designsystem.theme.Jakarta
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

/** 締め申請通知: a day the hours are sent in. A closing day past the month's end means its last day. */
fun isClosingDay(date: LocalDate, closing: List<Int>): Boolean = closing.any { minOf(it, date.lengthOfMonth()) == date.dayOfMonth }

/** The red dot that marks a 締め申請通知 day (.cal-flag). */
@Composable
private fun ClosingFlag(modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(SamFranTheme.colors.danger))
}

/**
 * The month as a calendar (web/src/features/shifts/ShiftCalendar.tsx): one
 * square per day, coloured by the kind of shift, today ringed. Tapping a day
 * shows its times underneath.
 */
@Composable
fun ShiftCalendar(month: YearMonth, days: List<ShiftDay>, today: LocalDate, picked: LocalDate?, onPick: (LocalDate) -> Unit, closingDays: List<Int> = emptyList()) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val byDate = days.associateBy { it.date }
    val lead = month.atDay(1).dayOfWeek.value % 7 // squares before the 1st; weeks start on Sunday
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val nothing = stringResource(R.string.nothing_entered)
    val hoursDue = stringResource(R.string.hours_due)
    // The squares are a fixed size, so their two lines are sized with the square
    // (dp, not the phone's text size) and given tight, centred line boxes: with
    // the theme's 24sp lines, or larger system text, they overflowed a narrow
    // phone's squares and the kanji's bottom was cut off. The day's line
    // underneath still follows the phone's text size.
    val density = LocalDensity.current
    val cellNumber = with(density) { cellText(12.dp.toSp(), FontWeight.SemiBold) }
    val cellMark = with(density) { cellText(15.dp.toSp(), FontWeight.ExtraBold) }
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
                    val closing = isClosingDay(date, closingDays)
                    val what = listOfNotNull(
                        d?.let { listOf(it.workplaceLabel, it.times).filter(String::isNotEmpty).joinToString(" ") } ?: nothing,
                        hoursDue.takeIf { closing },
                    ).joinToString(", ")
                    Box(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
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
                        Text(date.dayOfMonth.toString(), color = ink, style = cellNumber)
                        Text(d?.mark.orEmpty(), color = ink, style = cellMark, maxLines = 1)
                    }
                    if (closing) ClosingFlag(Modifier.align(Alignment.TopEnd).padding(5.dp))
                    }
                }
            }
        }
        if (picked != null && YearMonth.from(picked) == month) {
            DayLine(picked, byDate[picked], Modifier.padding(top = 4.dp))
            if (isClosingDay(picked, closingDays)) {
                Row(Modifier.padding(start = 74.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ClosingFlag()
                    Text(hoursDue, color = colors.danger, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** A calendar square's line: one line box just taller than the glyphs, which sit in its middle. */
private fun cellText(size: TextUnit, weight: FontWeight) = TextStyle(
    fontFamily = Jakarta,
    fontSize = size,
    fontWeight = weight,
    lineHeight = size * 1.25f,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    textAlign = TextAlign.Center,
)

/**
 * One day: its date, the kind of shift, its times and its code (the list's row,
 * and under the calendar). On one line when it fits; on a narrow phone or with
 * large text, the times move under the kind of shift instead of everything
 * wrapping into its own column. The date never breaks across lines.
 */
@Composable
fun DayLine(date: LocalDate, day: ShiftDay?, modifier: Modifier = Modifier) {
    val colors = SamFranTheme.colors
    val type = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val dayText = Dates.day(date, currentLocale)
    BoxWithConstraints(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        val gap = 10.dp
        fun width(text: String, style: TextStyle) = with(density) { measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toDp() }
        val dateWidth = maxOf(64.dp, width(dayText, type.labelLarge))
        val oneLine = day == null || dateWidth + gap + 10.dp + gap + width(day.workplaceLabel, type.bodyMedium) + gap +
            width(day.times, type.bodyMedium) + gap + width(day.code, type.labelMedium.copy(fontWeight = FontWeight.Bold)) + 16.dp <= maxWidth
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                Text(dayText, Modifier.width(dateWidth), style = type.labelLarge, softWrap = false, color = if (day == null || day.kind != com.rudnam.samfran.core.model.ShiftKind.Work) colors.muted else colors.text)
                if (day == null) {
                    Text(stringResource(R.string.nothing_entered), Modifier.weight(1f), color = colors.muted, style = type.bodyMedium)
                    return@Row
                }
                Dot(day.tone.dot())
                Column(Modifier.weight(1f)) {
                    Text(day.workplaceLabel, style = type.bodyMedium)
                    if (!oneLine) Text(day.times, style = type.bodyMedium, color = colors.muted, softWrap = false)
                    if (day.problem != null) Text(stringResource(R.string.unknown_length), color = colors.danger, style = type.bodySmall)
                }
                if (oneLine) Text(day.times, style = type.bodyMedium, color = colors.muted, softWrap = false)
                CodeChip(day.code)
            }
            WithNames(day?.with.orEmpty(), Modifier.padding(start = dateWidth + gap))
        }
    }
}

/**
 * Who else is on a shift: "With タナカ ユイ, スズキ ケン" (WithNames.tsx). Each
 * name is laid out whole, so a wrapped line never splits one (Japanese text
 * would otherwise break between any two kana). [trailing] goes after the last.
 */
@Composable
fun WithNames(names: List<String>, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    if (names.isEmpty()) return
    val sep = if (currentLocale.language == "ja") "、" else ", "
    // "With %s" around the names: the words before them stay with the first.
    val (before, after) = stringResource(R.string.with_names, "\u0000").split('\u0000').let { it[0] to it.getOrElse(1) { "" } }
    val style = MaterialTheme.typography.bodySmall
    val color = SamFranTheme.colors.muted
    FlowRow(modifier, itemVerticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, name ->
            val text = (if (i == 0) before else "") + name + (if (i < names.lastIndex) sep else after)
            Text(text, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}
    Text(stringResource(R.string.with_names, list), modifier, style = MaterialTheme.typography.bodySmall, color = SamFranTheme.colors.muted)
}
