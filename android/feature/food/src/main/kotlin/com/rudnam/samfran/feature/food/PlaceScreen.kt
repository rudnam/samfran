package com.rudnam.samfran.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.designsystem.component.CardTitle
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.component.Skeleton
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Comment
import com.rudnam.samfran.core.model.Tone
import com.rudnam.samfran.core.ui.Avatar
import com.rudnam.samfran.core.ui.DateField
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.StarPicker
import com.rudnam.samfran.core.ui.Stars
import com.rudnam.samfran.core.ui.SubPage
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.core.ui.openWeb
import com.rudnam.samfran.core.ui.problem
import com.rudnam.samfran.feature.food.navigation.FoodNavigation

@Composable
internal fun PlaceDestination(navigation: FoodNavigation, snackbar: SnackbarHostState, viewModel: PlaceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.deleted) { if (state.deleted) navigation.back() }
    PlaceScreen(
        state,
        viewModel,
        onBack = navigation::back,
        onEdit = { state.place?.let { navigation.editPlace(it.id) } },
        onMap = { state.place?.let { context.openWeb(it.mapLink) } },
    )
}

/** One place (web/src/features/restaurants/RestaurantDetail.tsx): whether you've been, each visit with its photos, ratings, notes and comments. */
@Composable
fun PlaceScreen(state: PlaceUiState, actions: PlaceActions, onBack: () -> Unit, onEdit: () -> Unit, onMap: () -> Unit) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Edit is the button beside Open in Maps, as on the website, not a second pencil up here.
    SubPage(onBack = onBack) {
        val place = state.place
        if (place == null) {
            if (state.loaded) state.problem?.problem()?.let { ErrorText(it) } ?: ErrorText(stringResource(R.string.place_gone)) else Skeleton(rows = 5)
            return@SubPage
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(place.name, style = MaterialTheme.typography.displaySmall)
            // Its category, then its kind and area, as the website's page says them.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CategoryTile(place.category, size = 40.dp, cover = place.cover)
                val line = listOf(stringResource(place.category.title), place.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
                Text(line, style = MaterialTheme.typography.bodyLarge, color = colors.muted)
            }
            val beenOn = state.beenOn
            if (beenOn != null) {
                val date = Dates.short(beenOn, locale, state.today)
                Text(
                    if (place.visitCount > 1) stringResource(R.string.been_times, place.visitCount, date) else stringResource(R.string.been_on, date),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            if (beenOn == null && state.picking == null) {
                PrimaryButton(stringResource(R.string.mark_as_been), actions::startPicking, Modifier.fillMaxWidth(), enabled = !state.busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.open_in_maps), onMap, icon = SamFranIcons.map)
                SecondaryButton(stringResource(R.string.edit), onEdit, icon = SamFranIcons.edit)
            }
        }
        state.picking?.let { picking ->
            SamFranCard {
                DateField(label = stringResource(R.string.when_did_you_go), date = picking, max = state.today, onChange = actions::setPicking)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(stringResource(R.string.save), actions::saveBeen, enabled = !state.busy)
                    SecondaryButton(stringResource(R.string.cancel), actions::cancelPicking, enabled = !state.busy)
                }
            }
        }
        state.problem?.problem()?.let { ErrorText(it) }

        if (state.beenOn != null) Visits(state, actions)

        SamFranCard {
            CardTitle(stringResource(R.string.ratings))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.you), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                StarPicker(place.myStars, actions::rate, enabled = !state.busy)
            }
            val theirs = place.ratings.firstOrNull { !it.me }
            val partner = theirs?.name ?: state.space?.partner?.name
            if (partner != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(partner, state.space?.toneOf(partner) ?: Tone.B)
                    Spacer(Modifier.width(10.dp))
                    Text(partner, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (theirs != null) Stars(theirs.stars) else Hint(stringResource(R.string.not_rated_yet))
                }
            }
        }

        if (place.notes.isNotBlank()) {
            SamFranCard(spacing = 8) {
                CardTitle(stringResource(R.string.notes))
                Text(place.notes, style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (state.beenOn != null) Comments(state, actions)

        LinkButton(stringResource(R.string.delete_this_place), { confirmDelete = true }, enabled = !state.busy, danger = true)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_place_title, state.place?.name.orEmpty())) },
            text = { Text(stringResource(R.string.delete_place_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.delete()
                }) { Text(stringResource(R.string.delete), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = colors.surface,
        )
    }
}

/** What the two of you said about it, oldest first, and a box to add to it (web/src/features/restaurants/Comments.tsx). */
@Composable
private fun Comments(state: PlaceUiState, actions: PlaceActions) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val comments = state.place?.comments.orEmpty()
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    SamFranCard {
        CardTitle(stringResource(R.string.comments))
        if (comments.isEmpty()) {
            Hint(stringResource(R.string.no_comments))
        } else {
            comments.forEach { c: Comment ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(c.by, state.space?.toneOf(c.by) ?: Tone.B)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (c.me) stringResource(R.string.you) else c.by, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(Dates.moment(c.createdAt, locale), style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.weight(1f))
                            if (c.me) LinkButton(stringResource(R.string.delete), { deleting = c.id }, enabled = !state.busy, quiet = true)
                        }
                        Text(c.body, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.draft,
            onValueChange = actions::setDraft,
            placeholder = { Text(stringResource(R.string.add_comment)) },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PrimaryButton(stringResource(R.string.post), actions::postComment, enabled = !state.busy && state.draft.isNotBlank())
        }
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_comment_title)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.deleteComment(id)
                }) { Text(stringResource(R.string.delete), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            containerColor = colors.surface,
        )
    }
}
