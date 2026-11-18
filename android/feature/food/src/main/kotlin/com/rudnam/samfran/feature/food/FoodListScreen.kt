package com.rudnam.samfran.feature.food

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.model.distanceText
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.designsystem.component.EmptyState
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.PageTitle
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.Segmented
import com.rudnam.samfran.core.designsystem.component.Skeleton
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.Space
import com.rudnam.samfran.core.model.Tone
import com.rudnam.samfran.core.ui.Avatar
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.RatingChip
import com.rudnam.samfran.core.ui.TabPage
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.feature.food.navigation.FoodNavigation

@Composable
internal fun FoodListDestination(navigation: FoodNavigation, viewModel: FoodListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FoodListScreen(state, viewModel, onOpen = navigation::openPlace, onAdd = { navigation.addPlace(want = state.tab == FoodTab.Want) })
}

/** The Places tab (web/src/features/restaurants/RestaurantsPage.tsx): been there and want to go, all or one category's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodListScreen(state: FoodListUiState, actions: FoodListActions, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val locale = currentLocale
    val context = LocalContext.current
    // The map needs this build's Google Maps key; without one, it's a list.
    val canMap = remember { hasMapsKey(context) }
    val onMap = state.onMap && canMap
    // Nearby asks for the phone's location first; turned down, it says so and stays newest first.
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { actions.setNearby(true) }
    fun nearby() {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) actions.setNearby(true) else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // Opened on Nearby: find where the phone is.
    LaunchedEffect(state.nearby) { if (state.nearby && state.here == null) actions.findHere() }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions::refresh, modifier = Modifier.fillMaxSize()) {
            TabPage(bottomSpace = 96) {
                item(key = "head") {
                    Row(verticalAlignment = Alignment.Top) {
                        val all = state.all
                        val subtitle = if (all.isNullOrEmpty()) null else buildString {
                            append(pluralStringResource(R.plurals.n_places, all.size, all.size))
                            if (state.thisMonth > 0) append(" · ").append(stringResource(R.string.n_this_month, state.thisMonth))
                        }
                        PageTitle(stringResource(R.string.our_spots), Modifier.weight(1f), subtitle)
                        state.space?.let { Avatars(it, Modifier.padding(top = 6.dp)) }
                    }
                }
                item(key = "tabs") {
                    Segmented(
                        options = listOf(stringResource(R.string.been_there), stringResource(R.string.want_to_go)),
                        selected = state.tab.ordinal,
                        onSelect = { actions.setTab(FoodTab.entries[it]) },
                        counts = if (state.all == null) null else listOf(state.been.size.toString(), state.want.size.toString()),
                    )
                }
                if (state.filterable && state.all != null) {
                    item(key = "filter") { CategoryFilter(state, actions) }
                }
                if (!state.all.isNullOrEmpty()) {
                    item(key = "tools") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            PillSeg(
                                listOf(
                                    null to stringResource(if (state.tab == FoodTab.Been) R.string.sort_latest else R.string.sort_newest),
                                    SamFranIcons.nearby to stringResource(R.string.sort_nearby),
                                ),
                                selected = if (state.nearby) 1 else 0,
                                label = stringResource(R.string.order),
                            ) { if (it == 1) nearby() else actions.setNearby(false) }
                            if (canMap) {
                                PillSeg(
                                    listOf(SamFranIcons.list to stringResource(R.string.view_list), SamFranIcons.map to stringResource(R.string.view_map)),
                                    selected = if (onMap) 1 else 0,
                                    label = stringResource(R.string.view),
                                ) { actions.setOnMap(it == 1) }
                            }
                        }
                    }
                    if (state.nearby && state.locating != Locating.No) {
                        item(key = "locating") {
                            Hint(stringResource(if (state.locating == Locating.Asking) R.string.finding_location else R.string.location_off))
                        }
                    }
                }
                if (state.searchable) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = actions::setQuery,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            leadingIcon = { LineIcon(SamFranIcons.search, null, size = 20.dp) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = SamFranTheme.colors.surface,
                                focusedContainerColor = SamFranTheme.colors.surface,
                                unfocusedBorderColor = SamFranTheme.colors.border,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                state.problem?.let { p -> item(key = "problem") { p.problem()?.let { ErrorText(it) } } }
                when {
                    state.all == null -> item(key = "loading") { Skeleton(rows = 4) }
                    state.shown.isEmpty() -> item(key = "empty") {
                        val filter = state.activeFilter
                        when {
                            state.query.isNotBlank() -> EmptyState(stringResource(R.string.nothing_matches, state.query.trim()), null)
                            filter != null && state.onTab.isNotEmpty() -> Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                EmptyState(stringResource(R.string.nothing_in_category, stringResource(filter.short)), null)
                                TextButton(onClick = { actions.setFilter(null) }) { Text(stringResource(R.string.show_all)) }
                            }
                            state.tab == FoodTab.Been -> EmptyState(stringResource(R.string.no_places_yet), stringResource(R.string.add_first_place))
                            else -> EmptyState(stringResource(R.string.nothing_on_list), stringResource(R.string.save_places_to_try))
                        }
                    }
                    onMap -> item(key = "map") {
                        PlacesMap(state.shown, state.near, onOpen) { place, open ->
                            PlaceRow(place, state.space, state.tab, "", locale, state.today, state.near, onClick = open)
                        }
                    }
                    else -> items(state.shown, key = { it.id }) { place ->
                        PlaceRow(place, state.space, state.tab, state.query, locale, state.today, state.near, onClick = { onOpen(place.id) })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { LineIcon(SamFranIcons.add, null, stroke = 2.6f) },
            text = { Text(stringResource(R.string.add_a_place)) },
            containerColor = SamFranTheme.colors.accent,
            contentColor = SamFranTheme.colors.accentText,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

/** The two of you, overlapping (.avatars). */
@Composable
private fun Avatars(space: Space, modifier: Modifier = Modifier) {
    Row(modifier) {
        space.members.forEachIndexed { i, m ->
            Avatar(m.name, if (i == 1) Tone.B else Tone.A, Modifier.offset(x = (-8 * i).dp))
        }
    }
}

/** Two or so choices in a small pill (.pill-seg): newest or nearby, list or map. */
@Composable
private fun PillSeg(options: List<Pair<String?, String>>, selected: Int, label: String, onSelect: (Int) -> Unit) {
    val colors = SamFranTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.sunken)
            .padding(3.dp)
            .selectableGroup()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, (icon, text) ->
            val on = i == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) colors.surface else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) })
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (on) colors.text else colors.muted
                icon?.let { LineIcon(it, null, tint = ink, size = 15.dp) }
                Text(text, color = ink, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The category chips (.cat-filter): all, or one category's places; a row that scrolls sideways. */
@Composable
private fun CategoryFilter(state: FoodListUiState, actions: FoodListActions) {
    val colors = SamFranTheme.colors
    val label = stringResource(R.string.category)
    @Composable
    fun Chip(selected: Boolean, icon: String, text: String, count: Int, onClick: () -> Unit) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) colors.text else colors.surface)
                .then(if (selected) Modifier else Modifier.border(1.dp, colors.border, RoundedCornerShape(50)))
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = if (selected) colors.bg else colors.text
            LineIcon(icon, null, tint = ink, size = 17.dp)
            Text(text, color = ink, style = MaterialTheme.typography.labelLarge)
            Text(count.toString(), color = ink.copy(alpha = 0.65f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(state.activeFilter == null, SamFranIcons.all, stringResource(R.string.category_all), state.onTab.size) { actions.setFilter(null) }
        for (c in state.chips) {
            Chip(state.activeFilter == c, c.icon, stringResource(c.short), state.counts[c] ?: 0) { actions.setFilter(c) }
        }
    }
}

/** One place on the list (.spot): its category's tile, name, ratings, what it is, and the last visit. */
@Composable
private fun PlaceRow(
    place: Restaurant,
    space: Space?,
    tab: FoodTab,
    query: String,
    locale: java.util.Locale,
    today: java.time.LocalDate,
    near: Pair<Double, Double>?,
    onClick: () -> Unit,
) {
    val colors = SamFranTheme.colors
    SamFranCard(onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryTile(place.category)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    for (r in place.ratings) RatingChip(r.name, r.stars, space?.toneOf(r.name) ?: Tone.A)
                    val meta = place.cuisine.ifBlank { place.area }
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    if (tab == FoodTab.Want && place.addedBy.isNotBlank()) {
                        Text("· " + stringResource(R.string.added_by, place.addedBy), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                }
                place.noteMatching(query)?.let { note ->
                    Text(
                        Dates.short(note.visitedOn, locale, today) + " · " + note.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val away = near?.let(place::metersFrom)
            if (away != null) {
                // Nearby: how far, and when you last went.
                Column(horizontalAlignment = Alignment.End) {
                    Text(distanceText(away), style = MaterialTheme.typography.labelMedium, color = colors.accentInk)
                    place.lastVisit?.let { Text(Dates.short(it, locale, today), style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                }
            } else {
                place.lastVisit?.let { last ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Dates.short(last, locale, today), style = MaterialTheme.typography.labelMedium)
                        Text(pluralStringResource(R.plurals.n_visits, place.visitCount, place.visitCount), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                }
            }
        }
    }
}
