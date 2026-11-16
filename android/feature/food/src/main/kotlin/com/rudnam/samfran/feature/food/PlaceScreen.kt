package com.rudnam.samfran.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
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
    // A visit removed: Undo for a few seconds, then it's deleted for real.
    val removed = stringResource(R.string.visit_removed)
    val undo = stringResource(R.string.undo)
    LaunchedEffect(state.removing) {
        if (state.removing == null) return@LaunchedEffect
        when (snackbar.showSnackbar(removed, actionLabel = undo, duration = SnackbarDuration.Short)) {
            SnackbarResult.ActionPerformed -> viewModel.undoRemove()
            SnackbarResult.Dismissed -> viewModel.commitRemove()
        }
    }
    PlaceScreen(
        state,
        viewModel,
        onBack = navigation::back,
        onEdit = { state.place?.let { navigation.editPlace(it.id) } },
        onMap = { state.place?.let { context.openWeb(it.mapLink) } },
    )
}

/** One place (web/src/features/restaurants/RestaurantDetail.tsx): ratings, notes, every visit, and logging another. */
@Composable
fun PlaceScreen(state: PlaceUiState, actions: PlaceActions, onBack: () -> Unit, onEdit: () -> Unit, onMap: () -> Unit) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    SubPage(
        onBack = onBack,
        actions = {
            if (state.place != null) IconButton(onClick = onEdit) { LineIcon(SamFranIcons.edit, stringResource(R.string.edit)) }
        },
    ) {
        val place = state.place
        if (place == null) {
            if (state.loaded) state.problem?.problem()?.let { ErrorText(it) } ?: ErrorText(stringResource(R.string.place_gone)) else Skeleton(rows = 5)
            return@SubPage
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(place.name, style = MaterialTheme.typography.displaySmall)
            if (place.subtitle.isNotEmpty()) Text(place.subtitle, style = MaterialTheme.typography.bodyLarge, color = colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.open_in_maps), onMap, icon = SamFranIcons.map)
                SecondaryButton(stringResource(R.string.edit), onEdit, icon = SamFranIcons.edit)
            }
        }
        state.problem?.problem()?.let { ErrorText(it) }

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

        SamFranCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle(stringResource(R.string.visits), Modifier.weight(1f))
                Hint(if (state.visitCount > 0) pluralStringResource(R.plurals.n_visits, state.visitCount, state.visitCount) else stringResource(R.string.not_yet))
            }
            DateField(
                label = stringResource(R.string.date),
                date = state.visitOn,
                max = state.today,
                onChange = actions::setVisitOn,
            )
            OutlinedTextField(
                value = state.visitNote,
                onValueChange = actions::setVisitNote,
                placeholder = { Text(stringResource(R.string.visit_note_hint)) },
                label = { Text(stringResource(R.string.visit_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                stringResource(if (state.visitCount > 0) R.string.log_visit else R.string.we_went),
                actions::logVisit,
                Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
            if (state.visits.isNotEmpty()) {
                Column {
                    state.visits.forEachIndexed { i, v ->
                        if (i > 0) HorizontalDivider(color = colors.divider)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(Dates.short(v.visitedOn, locale, state.today), Modifier.width(96.dp), style = MaterialTheme.typography.labelLarge)
                            Text(
                                v.note.ifBlank { if (v.addedBy.isNotBlank()) stringResource(R.string.logged_by, v.addedBy) else "" },
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (v.note.isBlank()) colors.muted else colors.text,
                            )
                            LinkButton(stringResource(R.string.remove), { actions.removeVisit(v.id) }, enabled = !state.busy, quiet = true)
                        }
                    }
                }
            }
        }

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
