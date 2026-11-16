package com.rudnam.samfran.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme

/** How wide a page's content grows on a big screen (the website's 640px). */
val MaxContentWidth = 640.dp

/**
 * A tab's page: a list drawn edge to edge, its content clear of the status
 * bar and the sides' insets (the tab bar below takes care of the bottom).
 * Content is centred and at most [MaxContentWidth] wide.
 */
@Composable
fun TabPage(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    bottomSpace: Int = 24,
    content: LazyListScope.() -> Unit,
) {
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).asPaddingValues()
    val dir = LocalLayoutDirection.current
    // The page paints its own ground and text colour, so it looks right however it's shown.
    Box(modifier.fillMaxSize().background(SamFranTheme.colors.bg), contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalContentColor provides SamFranTheme.colors.text) {
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = MaxContentWidth),
            state = state,
            contentPadding = PaddingValues(
                start = insets.calculateStartPadding(dir) + 16.dp,
                end = insets.calculateEndPadding(dir) + 16.dp,
                top = insets.calculateTopPadding() + 12.dp,
                bottom = bottomSpace.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        }
    }
}

/**
 * A page opened over a tab: a bar with the way back (and [actions]), then
 * [content] in a scrolling column, clear of the system bars and the keyboard.
 */
@Composable
fun SubPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides SamFranTheme.colors.text) {
    Column(
        modifier
            .fillMaxSize()
            .background(SamFranTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().widthIn(max = MaxContentWidth).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { LineIcon(SamFranIcons.back, stringResource(R.string.back)) }
            Text(title.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = MaxContentWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp)
                .then(if (bottomBar == null) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        if (bottomBar != null) {
            Box(Modifier.fillMaxWidth().widthIn(max = MaxContentWidth).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) { bottomBar() }
        }
    }
}
}
