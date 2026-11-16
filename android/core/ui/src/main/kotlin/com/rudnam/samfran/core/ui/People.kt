package com.rudnam.samfran.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.PersonColors
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Tone
import com.rudnam.samfran.core.model.initialOf

/** The colours a person keeps everywhere. */
@Composable
fun Tone.colors(): PersonColors = if (this == Tone.B) SamFranTheme.colors.b else SamFranTheme.colors.a

/** A round badge with the person's first letter (.avatar). */
@Composable
fun Avatar(name: String, tone: Tone, modifier: Modifier = Modifier, large: Boolean = false) {
    val c = tone.colors()
    val size = if (large) 44.dp else 30.dp
    Box(modifier.size(size).clip(CircleShape).background(c.bg), contentAlignment = Alignment.Center) {
        Text(initialOf(name), color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = if (large) 18.sp else 13.sp)
    }
}

/** A badge for someone invited who hasn't joined (.avatar.pending). */
@Composable
fun PendingAvatar(modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).clip(CircleShape).background(SamFranTheme.colors.sunken), contentAlignment = Alignment.Center) {
        Text("?", color = SamFranTheme.colors.muted, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

/** One person's rating on a list row: "F ★4" in their colour (.chip). */
@Composable
fun RatingChip(name: String, stars: Int, tone: Tone, modifier: Modifier = Modifier) {
    val c = tone.colors()
    val description = stringResource(R.string.rating_of, name, stars)
    Text(
        "${initialOf(name)} ★$stars",
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = description },
        color = c.text,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** Five stars to tap; tapping the current rating again clears it (StarPicker). */
@Composable
fun StarPicker(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = SamFranTheme.colors
    val label = stringResource(R.string.your_rating)
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        for (n in 1..5) {
            val stars = stringResource(R.string.n_stars, n)
            IconButton(
                onClick = { onChange(if (n == value) 0 else n) },
                enabled = enabled,
                modifier = Modifier.size(44.dp).semantics {
                    contentDescription = stars
                    if (n == value) stateDescription = stars
                },
            ) {
                Icon(SamFranIcons.filledStar, null, Modifier.size(30.dp), tint = if (n <= value) colors.star else colors.starOff)
            }
        }
    }
}

/** Someone else's stars, to look at (.stars-static). */
@Composable
fun Stars(stars: Int, modifier: Modifier = Modifier) {
    val colors = SamFranTheme.colors
    val description = stringResource(R.string.n_stars, stars)
    Row(modifier.clearAndSetSemantics { contentDescription = description }) {
        for (n in 1..5) Icon(SamFranIcons.filledStar, null, Modifier.size(22.dp), tint = if (n <= stars) colors.star else colors.starOff)
    }
}
