package com.rudnam.samfran.feature.shifts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.designsystem.theme.ShiftDotColors
import com.rudnam.samfran.core.model.ReadProgress
import com.rudnam.samfran.core.model.ShiftDay
import java.time.OffsetDateTime

/**
 * "Everyone there" beside Calendar/List (WhoThere.tsx): a small people icon,
 * filled in the accent colour when it's on.
 */
@Composable
fun EveryoneToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = SamFranTheme.colors
    val fill by animateColorAsState(if (on) colors.accent else colors.sunken, label = "everyone")
    val ink by animateColorAsState(if (on) colors.accentText else colors.muted, label = "everyone-ink")
    val label = stringResource(R.string.everyone_there)
    Box(
        modifier
            .width(48.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { LineIcon(SamFranIcons.us, null, tint = ink, size = 20.dp) }
}

/**
 * Who's at work with Sam on a day. Normally those on exactly her shift, with
 * a quiet "+3 there" when others on other shifts overlap it (tapping it turns
 * [everyone] on). With [everyone] on, everyone there during her shift, by
 * when they start, each with a bar of when they're there against it.
 */
@Composable
fun WhoThere(day: ShiftDay, who: String, everyone: Boolean, onEveryone: (Boolean) -> Unit, modifier: Modifier = Modifier, ink: Color? = null) {
    val start = day.start
    val end = day.end
    if (!everyone || start == null || end == null) {
        if (day.with.isEmpty() && day.others.isEmpty()) return
        val more = @Composable {
            if (day.others.isNotEmpty()) {
                Text(
                    stringResource(R.string.n_there, day.others.size),
                    Modifier.padding(start = if (day.with.isEmpty()) 0.dp else 8.dp).clickable(role = Role.Button) { onEveryone(true) },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = SamFranTheme.colors.accentInk,
                )
            }
        }
        if (day.with.isEmpty()) Box(modifier) { more() } else WithNames(day.with, modifier, trailing = more, color = ink)
        return
    }

    data class Line(val name: String, val start: OffsetDateTime?, val end: OffsetDateTime?, val me: Boolean, val same: Boolean, val note: String?)
    val nightBefore = stringResource(R.string.night_before)
    val nextMorning = stringResource(R.string.next_morning)
    val lines = buildList {
        add(Line(who, start, end, me = true, same = false, note = null))
        day.with.forEach { add(Line(it, start, end, me = false, same = true, note = null)) }
        day.others.forEach { o ->
            add(Line(o.name, o.start, o.end, me = false, same = false, note = when (o.dayOffset) { -1 -> nightBefore; 1 -> nextMorning; else -> null }))
        }
    }.sortedWith(compareBy<Line> { it.start?.toEpochSecond() ?: Long.MAX_VALUE }.thenBy { !it.me })

    // Bars against Sam's shift: 0 at her start, 1 at her end.
    val from = start.toEpochSecond()
    val span = (end.toEpochSecond() - from).coerceAtLeast(1).toFloat()
    fun at(t: OffsetDateTime?, fallback: Float) = t?.let { ((it.toEpochSecond() - from) / span).coerceIn(0f, 1f) } ?: fallback
    val colors = SamFranTheme.colors
    val textColor = ink ?: colors.text
    val mutedColor = ink ?: colors.muted
    val everyoneLabel = stringResource(R.string.everyone_there)
    Column(modifier.fillMaxWidth().semantics { contentDescription = everyoneLabel }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { l ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(Modifier.weight(1f), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(l.name, style = MaterialTheme.typography.bodySmall, fontWeight = if (l.me) FontWeight.Bold else null, color = textColor)
                    val tag = if (l.same) stringResource(R.string.same_shift) else l.note
                    if (tag != null) {
                        Text(
                            tag,
                            Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (l.same) colors.accentInk else mutedColor,
                        )
                    }
                }
                val left = at(l.start, 0f)
                val width = (at(l.end, 1f) - left).coerceAtLeast(0.04f)
                BoxWithConstraints(Modifier.width(56.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (ink != null) Color.Black.copy(alpha = 0.08f) else colors.sunken)) {
                    Box(
                        Modifier
                            .offset(x = maxWidth * left)
                            .width(maxWidth * width)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (l.me || l.same) ShiftDotColors.late.copy(alpha = 0.9f) else colors.muted.copy(alpha = 0.55f)),
                    )
                }
                Text(
                    listOf(l.start, l.end).joinToString("–") { t -> t?.let { "${it.hour}:%02d".format(it.minute) }.orEmpty() },
                    Modifier.widthIn(min = 92.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                    textAlign = TextAlign.End,
                    softWrap = false,
                )
            }
        }
    }
}

/** How far reading the photo has got: sent, found, then a square for each day as it's read. */
@Composable
fun ReadingCard(progress: ReadProgress, modifier: Modifier = Modifier) {
    val colors = SamFranTheme.colors
    val reading = progress as? ReadProgress.Reading
    val found = reading?.nameAsRead
    val read = reading?.daysRead ?: 0
    val of = reading?.daysInMonth ?: 0
    val title = stringResource(R.string.reading_sheet)
    SamFranCard(modifier.semantics(mergeDescendants = true) { contentDescription = title }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Step(done = reading != null, now = reading == null, stringResource(R.string.sending_photo))
                Step(done = found != null, now = reading != null && found == null, if (found != null) stringResource(R.string.found_on_sheet, found) else stringResource(R.string.finding_row))
                Step(done = false, now = found != null, if (of > 0) stringResource(R.string.reading_days_of, read, of) else stringResource(R.string.reading_days))
            }
            if (of > 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(of) { i ->
                        Box(Modifier.size(width = 14.dp, height = 12.dp).clip(RoundedCornerShape(3.dp)).background(if (i < read) colors.accent else colors.sunken))
                    }
                }
            }
            Hint(stringResource(R.string.reading_hint))
        }
    }
}

@Composable
private fun Step(done: Boolean, now: Boolean, text: String) {
    val colors = SamFranTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (done) "✓" else if (now) "●" else "○",
            Modifier.width(16.dp),
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = if (done) ShiftDotColors.middle else if (now) colors.accentInk else colors.muted,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (done || now) colors.text else colors.muted)
    }
}
