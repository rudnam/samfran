package com.rudnam.samfran.feature.food

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.PageTitle
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.component.Segmented
import com.rudnam.samfran.core.designsystem.component.Skeleton
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.named
import com.rudnam.samfran.core.ui.DateField
import com.rudnam.samfran.core.ui.StarPicker
import com.rudnam.samfran.core.ui.SubPage
import com.rudnam.samfran.core.ui.openWeb
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.feature.food.navigation.FoodNavigation
import kotlinx.coroutines.launch

@Composable
internal fun PlaceFormDestination(navigation: FoodNavigation, viewModel: PlaceFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedId) { state.savedId?.let(navigation::placeSaved) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    PlaceFormScreen(
        state,
        viewModel,
        onBack = navigation::back,
        onOpen = navigation::openPlace,
        onPaste = {
            scope.launch {
                clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let(viewModel::takeLink)
            }
        },
    )
}

/** Adding a place (been there, or want to go) and editing one (web/src/features/restaurants/RestaurantForm.tsx). */
@Composable
fun PlaceFormScreen(state: PlaceFormUiState, actions: PlaceFormActions, onBack: () -> Unit, onPaste: () -> Unit, onOpen: (Long) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nameFocus = remember { FocusRequester() }
    SubPage(
        onBack = onBack,
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.cancel), onBack, Modifier.weight(1f).padding(top = 2.dp))
                PrimaryButton(stringResource(if (state.editing) R.string.save else R.string.add), actions::save, Modifier.weight(1f), enabled = state.ready)
            }
        },
    ) {
        PageTitle(stringResource(if (state.editing) R.string.edit_place else R.string.add_a_place))
        val fields = state.fields
        if (fields == null) {
            state.problem?.problem()?.let { ErrorText(it) } ?: Skeleton(rows = 3)
            return@SubPage
        }
        if (!state.editing && !state.fromShare) Hint(stringResource(R.string.share_hint))
        SamFranCard(spacing = 14) {
            if (!state.editing) {
                Segmented(
                    options = listOf(stringResource(R.string.weve_been), stringResource(R.string.want_to_go)),
                    selected = if (state.been) 0 else 1,
                    onSelect = { actions.setBeen(it == 0) },
                )
            }
            OutlinedTextField(
                value = fields.name,
                onValueChange = actions::setName,
                label = { Text(stringResource(R.string.name)) },
                placeholder = when {
                    state.lookingUp -> ({ Text(stringResource(R.string.looking_up)) })
                    !state.editing -> ({ Text(stringResource(R.string.name_search_hint)) })
                    else -> null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
            )
            if (!state.editing) PlaceSuggestions(state, actions, onOpen, onMap = { context.openWeb(it) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fields.area,
                    onValueChange = { actions.set(fields.copy(area = it.take(100))) },
                    label = { Text(stringResource(R.string.area)) },
                    placeholder = { Text(stringResource(R.string.area_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = fields.cuisine,
                    onValueChange = { actions.set(fields.copy(cuisine = it.take(60))) },
                    label = { Text(stringResource(R.string.food)) },
                    placeholder = { Text(stringResource(R.string.food_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
            }
            if (!state.editing && state.been) {
                DateField(stringResource(R.string.visited_when), state.visitedOn, state.today, actions::setVisitedOn)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.your_rating), style = MaterialTheme.typography.labelLarge, color = SamFranTheme.colors.muted)
                    StarPicker(state.stars, actions::setStars)
                }
            }
            MapLinkField(state, actions, onPaste)
            OutlinedTextField(
                value = fields.notes,
                onValueChange = { actions.set(fields.copy(notes = it.take(5000))) },
                label = { Text(stringResource(R.string.notes)) },
                placeholder = { Text(stringResource(R.string.notes_hint)) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            state.problem?.problem()?.let { ErrorText(it) }
        }
    }
    // A new place by hand starts on its name; one shared from Maps is already filled in.
    LaunchedEffect(Unit) { if (!state.editing && !state.fromShare) runCatching { nameFocus.requestFocus() } }
}

/** The Google Maps link: pasted whole, its name is looked up; typed, when the field is left. */
@Composable
private fun MapLinkField(state: PlaceFormUiState, actions: PlaceFormActions, onPaste: () -> Unit) {
    val fields = state.fields ?: return
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fields.mapUrl,
                onValueChange = { text ->
                    // A link arriving whole (pasted, autofilled) is looked up now.
                    if (text.length - fields.mapUrl.length > 5) actions.takeLink(text) else actions.set(fields.copy(mapUrl = text))
                },
                label = { Text(stringResource(R.string.maps_link)) },
                placeholder = { Text("https://maps.app.goo.gl/…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).onFocusChanged {
                    if (focused && !it.isFocused && fields.mapUrl.isNotBlank()) actions.takeLink(fields.mapUrl)
                    focused = it.isFocused
                },
            )
            if (!state.editing && fields.mapUrl.isEmpty()) {
                SecondaryButton(stringResource(R.string.paste), onPaste, Modifier.padding(top = 8.dp))
            }
        }
        Hint(
            stringResource(
                when {
                    state.editing -> R.string.maps_link_hint_edit
                    state.lookingUp -> R.string.looking_up
                    state.picked != null -> R.string.maps_link_hint_picked
                    else -> R.string.maps_link_hint
                },
            ),
        )
    }
}

/**
 * Places matching the name as it's typed (OpenStreetMap, near the bus stop
 * first), and what to do about them: pick one, or keep your own name; a place
 * already on the list says so and opens it, so it isn't added twice.
 */
/**
 * What the Food field says for OpenStreetMap's kind of place, when the kind
 * says something about the food (web: kindFood). Null for a plain restaurant:
 * the search doesn't say which kind of food it serves.
 */
@StringRes
internal fun foodFor(kind: String): Int? = when (kind) {
    "cafe" -> R.string.food_cafe
    "bar" -> R.string.food_bar
    "pub" -> R.string.food_pub
    "biergarten" -> R.string.food_beer_garden
    "fast_food" -> R.string.food_fast_food
    "food_court" -> R.string.food_food_court
    "ice_cream" -> R.string.food_ice_cream
    "bakery" -> R.string.food_bakery
    "pastry" -> R.string.food_pastries
    "confectionery" -> R.string.food_sweets
    else -> null
}

@Composable
private fun PlaceSuggestions(state: PlaceFormUiState, actions: PlaceFormActions, onOpen: (Long) -> Unit, onMap: (String) -> Unit) {
    val colors = SamFranTheme.colors
    val resources = androidx.compose.ui.platform.LocalResources.current
    val found = state.found
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.query.isNotEmpty() && !found.isNullOrEmpty()) {
            Column {
                found.forEachIndexed { i, p ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(color = colors.divider)
                    val already = state.saved.named(p.name)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { if (already != null) onOpen(already.id) else actions.pick(p, foodFor(p.kind)?.let(resources::getString).orEmpty()) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(p.icon)
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            val line = if (already != null) stringResource(R.string.already_on_list) else p.where
                            if (line.isNotBlank()) Hint(line)
                        }
                    }
                }
            }
        }
        val dup = state.duplicate
        val picked = state.picked
        when {
            dup != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(stringResource(R.string.already_on_list_colon))
                LinkButton(dup.name, { onOpen(dup.id) })
            }
            picked != null && state.query.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("✓ " + picked.where, Modifier.weight(1f, fill = false))
                LinkButton(stringResource(R.string.check_on_map), { onMap(picked.mapUrl) })
                LinkButton(stringResource(R.string.not_this_one), actions::unpick, quiet = true)
            }
            state.query.isNotEmpty() && state.searchFailed -> Hint(stringResource(R.string.place_search_failed))
            state.query.isNotEmpty() && found?.isEmpty() == true -> Hint(stringResource(R.string.place_not_found))
            state.query.isNotEmpty() && !found.isNullOrEmpty() -> Hint(stringResource(R.string.tap_the_place) + " " + stringResource(R.string.places_osm))
        }
    }
}
