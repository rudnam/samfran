package com.rudnam.samfran.feature.food

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.rudnam.samfran.core.model.PlaceCategory
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    // Search is a button until it's wanted; open while there's something typed.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searching = state.searchable && (searchOpen || state.query.isNotEmpty())
    // Nearby asks for the phone's location first; turned down, it says so and stays newest first.
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { actions.setOrder(PlaceOrder.Nearby) }
    fun nearby() {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) actions.setOrder(PlaceOrder.Nearby) else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // Opened on Nearby: find where the phone is.
    LaunchedEffect(state.nearby) { if (state.nearby && state.here == null) actions.findHere() }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions::refresh, modifier = Modifier.fillMaxSize()) {
            TabPage(bottomSpace = 96) {
                item(key = "head") { PageTitle(stringResource(R.string.our_spots)) }
                item(key = "tabs") {
                    Segmented(
                        options = listOf(stringResource(R.string.been_there), stringResource(R.string.want_to_go)),
                        selected = state.tab.ordinal,
                        onSelect = { actions.setTab(FoodTab.entries[it]) },
                        counts = if (state.all == null) null else listOf(state.been.size.toString(), state.want.size.toString()),
                    )
                }
                if (!state.all.isNullOrEmpty()) {
                    // One toolbar (.toolbar): the category menu, then search, the order (a menu) and Map as icon buttons.
                    item(key = "tools") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { if (state.filterable) CategoryMenu(state, actions) }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (state.searchable) {
                                    IconToggle(SamFranIcons.search, stringResource(R.string.search), searching) {
                                        if (searching) actions.setQuery("")
                                        searchOpen = !searching
                                    }
                                }
                                OrderMenu(state.order) { if (it == PlaceOrder.Nearby) nearby() else actions.setOrder(it) }
                                if (canMap) IconToggle(SamFranIcons.map, stringResource(R.string.view_map), onMap) { actions.setOnMap(!onMap) }
                            }
                        }
                    }
                    if (searching) {
                        item(key = "search") {
                            val focus = remember { FocusRequester() }
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
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                            // Opened by its button: ready to type.
                            LaunchedEffect(Unit) { if (searchOpen) runCatching { focus.requestFocus() } }
                        }
                    }
                    if (state.nearby && state.locating != Locating.No) {
                        item(key = "locating") {
                            Hint(stringResource(if (state.locating == Locating.Asking) R.string.finding_location else R.string.location_off))
                        }
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
                            PlaceRow(place, state.space, state.tab, locale, state.today, state.near, onClick = open)
                        }
                    }
                    else -> items(state.shown, key = { it.id }) { place ->
                        PlaceRow(place, state.space, state.tab, locale, state.today, state.near, onClick = { onOpen(place.id) })
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

/** An icon button that's on or off (.icon-toggle): dark when on. */
@Composable
private fun IconToggle(icon: String, label: String, on: Boolean, onClick: () -> Unit) {
    val colors = SamFranTheme.colors
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (on) colors.text else colors.surface)
            .then(if (on) Modifier else Modifier.border(1.dp, colors.border, RoundedCornerShape(13.dp)))
            .toggleable(value = on, role = Role.Switch, onValueChange = { onClick() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(icon, null, tint = if (on) colors.bg else colors.text, size = 21.dp)
    }
}

/** How to order the list: an icon button (dark unless newest first) opening a menu of the ways. */
@Composable
private fun OrderMenu(order: PlaceOrder, onPick: (PlaceOrder) -> Unit) {
    val colors = SamFranTheme.colors
    var open by remember { mutableStateOf(false) }
    val on = order != PlaceOrder.Newest
    val label = stringResource(R.string.order_is, stringResource(order.title))
    Box {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (on) colors.text else colors.surface)
                .then(if (on) Modifier else Modifier.border(1.dp, colors.border, RoundedCornerShape(13.dp)))
                .clickable(onClickLabel = label, role = Role.Button) { open = true }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            LineIcon(if (order == PlaceOrder.Nearby) SamFranIcons.nearby else SamFranIcons.sort, null, tint = if (on) colors.bg else colors.text, size = 21.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface, shape = RoundedCornerShape(16.dp)) {
            for (o in PlaceOrder.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(o.title), fontWeight = FontWeight.SemiBold) },
                    onClick = {
                        open = false
                        onPick(o)
                    },
                    modifier = if (o == order) Modifier.background(colors.highlight) else Modifier,
                )
            }
        }
    }
}

/** The category to show (.cat-menu): a button saying which, opening a menu of the categories with places on this tab. */
@Composable
private fun CategoryMenu(state: FoodListUiState, actions: FoodListActions) {
    val colors = SamFranTheme.colors
    var open by remember { mutableStateOf(false) }
    val active = state.activeFilter
    val name = if (active == null) stringResource(R.string.all_places) else stringResource(active.title)
    val label = stringResource(R.string.category_is, name)
    Box {
        Row(
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(13.dp))
                .clickable(onClickLabel = label) { open = true }
                .semantics { contentDescription = label }
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineIcon(active?.icon ?: SamFranIcons.all, null, size = 17.dp)
            Text(if (active == null) stringResource(R.string.all_places) else stringResource(active.short), style = MaterialTheme.typography.labelLarge)
            Text((if (active == null) state.onTab.size else state.counts[active] ?: 0).toString(), style = MaterialTheme.typography.labelLarge, color = colors.muted, fontWeight = FontWeight.Normal)
            LineIcon(SamFranIcons.chevronDown, null, size = 16.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface, shape = RoundedCornerShape(16.dp)) {
            @Composable
            fun Option(category: PlaceCategory?, text: String, count: Int) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (category == null) LineIcon(SamFranIcons.all, null, size = 18.dp) else CategoryTile(category, size = 30.dp)
                            Text(text, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold)
                            Text(count.toString(), color = colors.muted)
                        }
                    },
                    onClick = {
                        actions.setFilter(category)
                        open = false
                    },
                    modifier = if (category == active) Modifier.background(colors.highlight) else Modifier,
                )
            }
            Option(null, stringResource(R.string.all_places), state.onTab.size)
            for (c in PlaceCategory.entries.filter { (state.counts[it] ?: 0) > 0 || it == active }) {
                Option(c, stringResource(c.title), state.counts[c] ?: 0)
            }
        }
    }
}

/** One place on the list (.spot): its category's tile, name, ratings, what it is, and when you went. */
@Composable
private fun PlaceRow(
    place: Restaurant,
    space: Space?,
    tab: FoodTab,
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
            }
            val away = near?.let(place::metersFrom)
            if (away != null) {
                // Nearby: how far, and when you went.
                Column(horizontalAlignment = Alignment.End) {
                    Text(distanceText(away), style = MaterialTheme.typography.labelMedium, color = colors.accentInk)
                    place.lastVisit?.let { Text(Dates.short(it, locale, today), style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                }
            } else {
                place.lastVisit?.let { Text(Dates.short(it, locale, today), style = MaterialTheme.typography.bodySmall, color = colors.muted) }
            }
        }
    }
}
