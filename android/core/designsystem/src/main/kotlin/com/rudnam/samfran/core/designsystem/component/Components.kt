package com.rudnam.samfran.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme

/** The website's .card: white (or dark) surface, 20dp corners, a hairline border. */
@Composable
fun SamFranCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    spacing: Int = 12,
    /** A tinted card (the next shift) has its own colour and no border. */
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = SamFranTheme.colors
    val shape = MaterialTheme.shapes.large
    val fill = color ?: colors.surface
    val border = if (color == null) BorderStroke(1.dp, colors.border) else null
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing.dp), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = fill, border = border, content = inner)
    } else {
        Surface(modifier = modifier, shape = shape, color = fill, border = border, content = inner)
    }
}

/** A card's heading (the website's h2). */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium)
}

/** A page's big heading (.display), with an optional line under it. */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.displaySmall)
        if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SamFranTheme.colors.muted)
    }
}

/** Small grey help under a field or control (.hint). */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = SamFranTheme.colors.muted) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A small capitalised label over a value (.eyebrow). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = SamFranTheme.colors.muted) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

/** Something that went wrong, said where it happened; read out when it appears. */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = SamFranTheme.colors.danger,
    )
}

/**
 * A notice across the page (.notice): a message, and buttons beside it
 * (Update / Later, Send again). [error] colours it as a problem.
 */
@Composable
fun Notice(
    modifier: Modifier = Modifier,
    error: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = SamFranTheme.colors
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = if (error) colors.a.bg else colors.highlight,
        contentColor = if (error) colors.a.text else colors.text,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Shrinks a little while pressed and springs back, like the website's button:active. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.97f): Modifier {
    val down by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down) pressed else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** The main action: ink, full width unless placed in a row. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, big: Boolean = false) {
    val colors = SamFranTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressScale(interaction).height(if (big) 64.dp else 48.dp),
        interactionSource = interaction,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        // Not ready yet (nothing typed): a faded orange, as the website fades a button, not Material's grey.
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.accentText,
            disabledContainerColor = colors.accent.copy(alpha = 0.4f),
            disabledContentColor = colors.accentText.copy(alpha = 0.8f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(text, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** A quieter action (.secondary): the sunken colour. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: String? = null) {
    val colors = SamFranTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressScale(interaction).height(44.dp),
        interactionSource = interaction,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.sunken,
            contentColor = colors.text,
            disabledContainerColor = colors.sunken.copy(alpha = 0.5f),
            disabledContentColor = colors.text.copy(alpha = 0.4f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (icon != null) {
            com.rudnam.samfran.core.designsystem.icon.LineIcon(icon, null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A text button (.link); [danger] for deleting, [quiet] in grey. */
@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    quiet: Boolean = false,
) {
    val colors = SamFranTheme.colors
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                danger -> colors.danger
                quiet -> colors.muted
                else -> colors.accentInk
            },
        )
    }
}

/**
 * Two or three choices side by side, one picked (.segmented, .tabs-seg):
 * Been there / Want to go, Calendar / List. [counts] go beside the labels.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    counts: List<String>? = null,
) {
    val colors = SamFranTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.sunken)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            // The picked one fades in rather than jumping.
            val fill by animateColorAsState(if (on) colors.surface else colors.surface.copy(alpha = 0f), label = "segment")
            val edge by animateColorAsState(if (on) colors.border else colors.border.copy(alpha = 0f), label = "segment-edge")
            val ink by animateColorAsState(if (on) colors.text else colors.muted, label = "segment-text")
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(fill)
                    .border(1.dp, edge, RoundedCornerShape(10.dp))
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
                counts?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = colors.muted)
                }
            }
        }
    }
}

/** Grey bars in the shape of what's coming, while a page loads for the first time (.skeleton). */
@Composable
fun Skeleton(rows: Int = 3, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(modifier.fillMaxWidth().alpha(pulse), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(rows) {
            Box(Modifier.fillMaxWidth().height(64.dp).clip(MaterialTheme.shapes.large).background(SamFranTheme.colors.sunken))
        }
    }
}

/** A list that's empty, with what to do about it (.empty). */
@Composable
fun EmptyState(title: String, body: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = SamFranTheme.colors.muted, textAlign = TextAlign.Center)
    }
}

/** A small rounded label, e.g. a shift code (.code-chip). */
@Composable
fun CodeChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SamFranTheme.colors.sunken)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = SamFranTheme.colors.muted,
    )
}

/** A dot of colour, e.g. a shift's kind (.shift-dot). */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

/** A row in a card that can be tapped: label, optional detail, and what's at its end. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Hint(detail)
        }
        trailing()
    }
}

/**
 * A page of the app, on the app's background: opaque, so that while the back
 * gesture slides it away the page under it doesn't show through.
 */
@Composable
fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(SamFranTheme.colors.bg)) { content() }
}
