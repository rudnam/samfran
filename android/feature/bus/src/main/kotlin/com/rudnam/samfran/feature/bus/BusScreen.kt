package com.rudnam.samfran.feature.bus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.designsystem.component.CardTitle
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.Eyebrow
import com.rudnam.samfran.core.designsystem.component.Hint
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.PageTitle
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.component.Skeleton
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Distances
import com.rudnam.samfran.core.model.mapLink
import com.rudnam.samfran.core.ui.Choice
import com.rudnam.samfran.core.ui.ChoicePicker
import com.rudnam.samfran.core.ui.TabPage
import com.rudnam.samfran.core.ui.openWeb
import com.rudnam.samfran.core.ui.problem

private fun granted(c: Context, permission: String) = ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

@Composable
internal fun BusDestination(viewModel: BusViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tripPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val askForTrip = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val location = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (location) viewModel.startTrip() else viewModel.locationDenied()
    }
    val startTrip = {
        val location = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val notify = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        if (location && notify) viewModel.startTrip() else askForTrip.launch(tripPermissions.toTypedArray())
    }
    val askForHere = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) viewModel.useHere() else viewModel.locationDenied()
    }
    val askForPrompts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.setPrompts(true) }
    // Opened from "Start bus alarm?": start once the stop is known.
    LaunchedEffect(state.autoStart, state.stop) {
        val stop = state.stop ?: return@LaunchedEffect
        if (!state.autoStart) return@LaunchedEffect
        viewModel.autoStartHandled()
        if (stop.set && !state.trip.running) startTrip()
    }
    BusScreen(
        state,
        viewModel,
        onStartTrip = startTrip,
        onHere = {
            if (granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) viewModel.useHere()
            else askForHere.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        onPrompts = { on ->
            if (on && Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                askForPrompts.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.setPrompts(on)
            }
        },
        onMap = { context.openWeb(it) },
    )
}

/**
 * The bus-stop alarm (web/src/features/bus/BusPage.tsx): choose the stop
 * once, then "I'm on the bus" starts a trip on the phone, which buzzes
 * before the stop. The phone does the watching; this page starts, shows and stops it.
 */
@Composable
fun BusScreen(
    state: BusUiState,
    actions: BusActions,
    onStartTrip: () -> Unit,
    onHere: () -> Unit,
    onPrompts: (Boolean) -> Unit,
    onMap: (String) -> Unit,
) {
    val colors = SamFranTheme.colors
    val stop = state.stop
    TabPage {
        item(key = "head") { PageTitle(stringResource(R.string.bus), subtitle = stringResource(R.string.bus_subtitle)) }
        if (state.locationDenied) item(key = "denied") { ErrorText(stringResource(R.string.location_denied)) }
        state.problem?.let { p -> item(key = "problem") { p.problem()?.let { ErrorText(it) } } }

        if (state.trip.buzzing) {
            item(key = "buzzing") {
                SamFranCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.buzzing), style = MaterialTheme.typography.headlineMedium, color = colors.accent)
                    PrimaryButton(stringResource(R.string.im_up), actions::stopBuzz, Modifier.fillMaxWidth(), big = true)
                }
            }
        } else if (stop != null && state.ready) {
            item(key = "trip") {
                SamFranCard(Modifier.fillMaxWidth()) {
                    val name = stop.name.ifBlank { stringResource(R.string.your_stop_lower) }
                    if (state.trip.running) {
                        Eyebrow(stringResource(R.string.on_the_bus), color = colors.accentInk)
                        Text(
                            if (state.trip.distance >= 0) stringResource(R.string.distance_to, Distances.format(state.trip.distance), name) else stringResource(R.string.finding_you),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Hint(stringResource(R.string.trip_hint, Distances.format(stop.alertMeters.toFloat())))
                        SecondaryButton(stringResource(R.string.stop), actions::stopTrip, Modifier.fillMaxWidth())
                    } else {
                        PrimaryButton(stringResource(R.string.on_bus), onStartTrip, Modifier.fillMaxWidth(), big = true)
                        Hint(stringResource(R.string.on_bus_hint, Distances.format(stop.alertMeters.toFloat()), name))
                    }
                }
            }
        }

        if (state.ready) {
            item(key = "prompts") {
                SamFranCard {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onPrompts(!state.prompts) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.ask_when_shift_ends), style = MaterialTheme.typography.titleSmall)
                            Hint(stringResource(R.string.ask_when_shift_ends_hint))
                        }
                        Switch(state.prompts, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent))
                    }
                    if (state.promptsBlocked) ErrorText(stringResource(R.string.allow_notifications))
                }
            }
        }

        item(key = "stop") {
            when {
                stop == null -> Skeleton(rows = 2)
                state.editing -> StopFormCard(state, actions, onHere, onMap)
                else -> SamFranCard {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Eyebrow(stringResource(R.string.your_stop))
                            Text(stop.name.ifBlank { stringResource(R.string.unnamed_stop) }, style = MaterialTheme.typography.titleMedium)
                            LinkButton(stringResource(R.string.see_on_map), { onMap(stop.mapLink) }, Modifier.padding(start = 0.dp))
                        }
                        SecondaryButton(stringResource(R.string.change), actions::edit)
                    }
                    Hint(stringResource(R.string.buzzes_before, Distances.format(stop.alertMeters.toFloat())))
                    LinkButton(stringResource(R.string.test_buzz), actions::test, quiet = true)
                }
            }
        }
    }
}

/** Choosing the stop: search by name, a Google Maps link to it, or where the phone is now. */
@Composable
private fun StopFormCard(state: BusUiState, actions: BusActions, onHere: () -> Unit, onMap: (String) -> Unit) {
    val colors = SamFranTheme.colors
    val f = state.form
    SamFranCard(spacing = 14) {
        CardTitle(stringResource(R.string.where_get_off))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = f.name,
                onValueChange = actions::setName,
                label = { Text(stringResource(R.string.stop_name)) },
                placeholder = { Text(stringResource(R.string.stop_name_hint)) },
                leadingIcon = { LineIcon(SamFranIcons.search, null, size = 20.dp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.saveStop() }),
                modifier = Modifier.fillMaxWidth(),
            )
            val found = f.found
            if (f.query.isNotEmpty() && !found.isNullOrEmpty()) {
                Column {
                    found.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = colors.divider)
                        Row(
                            Modifier.fillMaxWidth().clickable { actions.pick(s) }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(if (s.train) "🚉" else "🚏")
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall)
                                if (s.area.isNotBlank()) Hint(s.area)
                            }
                        }
                    }
                }
            }
            Hint(
                when {
                    f.query.isNotEmpty() && f.searchFailed -> stringResource(R.string.search_failed)
                    f.query.isNotEmpty() && found?.isEmpty() == true -> stringResource(R.string.no_stops)
                    f.query.isNotEmpty() && !found.isNullOrEmpty() -> stringResource(R.string.tap_your_stop) + " " + stringResource(R.string.osm)
                    f.position == null -> stringResource(R.string.start_typing)
                    else -> ""
                },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.or_maps_link), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = f.link,
                    onValueChange = actions::setLink,
                    placeholder = { Text(stringResource(R.string.paste_stop_link)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { actions.useLink() }),
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(stringResource(R.string.use_link), actions::useLink, enabled = !f.busy && f.link.isNotBlank())
            }
            LinkButton(stringResource(R.string.use_here), onHere, enabled = !f.busy)
            f.position?.let { (lat, lng) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("✓ " + stringResource(if (f.byName) R.string.found_by_name else R.string.found_it), Modifier.weight(1f, fill = false))
                    LinkButton(stringResource(R.string.check_on_map), { onMap(mapLink(lat, lng)) })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.buzz_how_far), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                ChoicePicker(
                    Distances.choices.map { Choice(it, Distances.format(it.toFloat())) },
                    selected = f.meters,
                    onSelect = actions::setMeters,
                    description = stringResource(R.string.buzz_how_far),
                )
            }
            Hint(stringResource(R.string.buzz_how_far_hint))
        }
        when (val p = f.problem) {
            null -> {}
            StopProblem.NoPosition -> ErrorText(stringResource(R.string.no_position))
            StopProblem.NoHere -> ErrorText(stringResource(R.string.no_here))
            is StopProblem.Server -> p.outcome.problem()?.let { ErrorText(it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            if (state.ready) SecondaryButton(stringResource(R.string.cancel), actions::cancelEdit)
            PrimaryButton(stringResource(R.string.save), actions::saveStop, enabled = !f.busy && f.position != null)
        }
    }
}
