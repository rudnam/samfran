package com.rudnam.samfran.feature.bus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import com.rudnam.samfran.core.model.BusStops
import com.rudnam.samfran.core.model.ShiftCodes
import com.rudnam.samfran.core.model.ShiftKinds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.Distances
import com.rudnam.samfran.core.model.Early
import com.rudnam.samfran.core.model.StopKind
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
    // Switching a stop's question on: ask for notifications first (Android 13+), then switch it either way.
    val askForAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.toggleAsk() }
    // The practice run's buzz shows a notification too: ask for that first (Android 13+), then practise either way.
    val askForPractice = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.practice() }
    // Opened from "Start the ride alarm?": start once the stop is known.
    LaunchedEffect(state.autoStart, state.stops) {
        if (state.stops == null || !state.autoStart) return@LaunchedEffect
        viewModel.autoStartHandled()
        if (state.stop != null && !state.trip.running) startTrip()
    }
    BusScreen(
        state,
        viewModel,
        onStartTrip = startTrip,
        onPractice = {
            if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                askForPractice.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.practice()
            }
        },
        onHere = {
            if (granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) viewModel.useHere()
            else askForHere.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        onAsk = {
            if (state.pageStop?.ask != true && Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                askForAsk.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.toggleAsk()
            }
        },
        onMap = { context.openWeb(it) },
    )
}

/**
 * The ride alarm, for the bus or the train (web/src/features/bus/BusPage.tsx).
 * Choose the stop (a bus stop or a station) once; on board, "I'm on the
 * bus" (or train) starts a trip on the phone, which buzzes before the stop
 * until "Stop", adding a sound if it isn't stopped.
 *
 * Pages, so each shows only a little: the ride (where to get off, and
 * starting); Settings (the stops, and the sound); a stop's page (a small
 * map, then one row for each setting, each opening its choices from the
 * bottom); and the search for where a stop is. The first stop starts with
 * the search.
 */
@Composable
fun BusScreen(
    state: BusUiState,
    actions: BusActions,
    onStartTrip: () -> Unit,
    onPractice: () -> Unit,
    onHere: () -> Unit,
    onAsk: () -> Unit,
    onMap: (String) -> Unit,
    map: @Composable (lat: Double, lng: Double, name: String, height: Dp) -> Unit = { lat, lng, name, height -> StopMap(lat, lng, name, height = height) },
) {
    val all = state.stops
    // Back closes a sheet, then goes back a page: a stop's search to its page, its page to Settings, Settings to the ride.
    BackHandler(enabled = state.sheet != null || (state.ready && (state.isEditing || state.where || state.settings))) { actions.back() }
    Box(Modifier.fillMaxSize()) {
        TabPage {
            when {
                all == null -> {
                    item(key = "head") { PageTitle(stringResource(R.string.bus_alarm)) }
                    item(key = "loading") { Skeleton(rows = 3) }
                    return@TabPage
                }
                // The first stop: how it works, then where.
                !state.ready && state.searching -> {
                    item(key = "head") { PageTitle(stringResource(R.string.bus_alarm)) }
                    problems(state)
                    item(key = "how") { SamFranCard(spacing = 10) { CardTitle(stringResource(R.string.how_it_works)); Steps() } }
                    item(key = "where") { WherePage(state, actions, onHere, map, title = null) }
                    return@TabPage
                }
                state.searching -> {
                    val moving = state.editingStop?.takeIf { state.where }
                    item(key = "back") { BackLink(moving?.name?.ifBlank { null } ?: stringResource(if (moving != null) R.string.unnamed_stop else R.string.settings), actions::back) }
                    problems(state)
                    item(key = "where") { WherePage(state, actions, onHere, map, title = moving?.name?.ifBlank { stringResource(R.string.unnamed_stop) } ?: stringResource(R.string.add_a_stop)) }
                    return@TabPage
                }
                state.stopPage -> {
                    val new = state.editing == BusUiState.NEW
                    if (state.ready || !new) item(key = "back") { BackLink(stringResource(if (new) R.string.add_a_stop else R.string.settings), actions::back) }
                    problems(state)
                    item(key = "stop") { StopPage(state, all, actions, onAsk, map) }
                    return@TabPage
                }
                state.settings -> {
                    item(key = "back") { BackLink(stringResource(R.string.bus_alarm), actions::back) }
                    item(key = "head") { PageTitle(stringResource(R.string.settings)) }
                    problems(state)
                    item(key = "stops") { StopsCard(all, state.added, actions) }
                    item(key = "fold") { HowItWorksFold() }
                    return@TabPage
                }
            }
            item(key = "head") {
                Row(verticalAlignment = Alignment.Top) {
                    PageTitle(stringResource(R.string.bus_alarm), Modifier.weight(1f))
                    if (!state.trip.running && !state.buzzing) {
                        IconButton(onClick = actions::openSettings) { LineIcon(SamFranIcons.settings, stringResource(R.string.settings)) }
                    }
                }
            }
            problems(state)
            val stop = state.stop ?: return@TabPage
            when {
                state.buzzing -> item(key = "buzz") { BuzzCard(stop, practice = state.practice != null, onStop = actions::stopBuzz) }
                state.choosing && !state.trip.running -> item(key = "choose") { ChooseCard(all!!, stop, actions, onStartTrip) }
                else -> item(key = "ride") { RideCard(state, all!!, stop, actions, onStartTrip, onPractice) { lat, lng, name -> map(lat, lng, name, 280.dp) } }
            }
        }
        val shown = state.pageStop
        if (state.sheet != null && shown != null && all != null) StopSheets(state.sheet, shown, all, state.editing, actions)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.problems(state: BusUiState) {
    if (state.locationDenied) item(key = "denied") { ErrorText(stringResource(R.string.location_denied)) }
    state.problem?.let { p -> item(key = "problem") { p.problem()?.let { ErrorText(it) } } }
}

/** "‹ Settings": back a page. */
@Composable
private fun BackLink(to: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LineIcon(SamFranIcons.chevronLeft, null, size = 18.dp, tint = SamFranTheme.colors.accentInk)
        Text(to, color = SamFranTheme.colors.accentInk, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun HowItWorksFold() {
    var open by rememberSaveable { mutableStateOf(false) }
    SamFranCard(onClick = { open = !open }, spacing = 10) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(stringResource(R.string.how_it_works), Modifier.weight(1f))
            LineIcon(if (open) SamFranIcons.chevronLeft else SamFranIcons.chevronRight, null, size = 18.dp, tint = SamFranTheme.colors.muted)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Steps()
                // The search's data, credited as OpenStreetMap's licence asks.
                Hint(stringResource(R.string.osm))
            }
        }
    }
}

/** It's buzzing: the stop, and one big Stop. In practice, what happens on a real ride. */
@Composable
private fun BuzzCard(stop: BusStop, practice: Boolean, onStop: () -> Unit) {
    val colors = SamFranTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.heart)
            .padding(20.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow(stringResource(if (practice) R.string.practice_run else R.string.wake_up), color = colors.heartText)
        Text(stringResource(R.string.your_stop_is_next), style = MaterialTheme.typography.headlineMedium, color = colors.heartText, textAlign = TextAlign.Center)
        Text(stop.name.ifBlank { stringResource(R.string.your_stop_lower) }, style = MaterialTheme.typography.titleMedium, color = colors.heartText, textAlign = TextAlign.Center)
        androidx.compose.material3.Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(72.dp),
            shape = MaterialTheme.shapes.medium,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.a.text),
        ) { Text(stringResource(R.string.stop), style = MaterialTheme.typography.headlineSmall) }
        if (practice) Text(stringResource(R.string.practice_feel), style = MaterialTheme.typography.bodySmall, color = colors.heartText, textAlign = TextAlign.Center)
    }
}

/** The stop and starting a ride; or the ride, or the practice run, as it goes. */
@Composable
private fun RideCard(
    state: BusUiState,
    all: BusStops,
    stop: BusStop,
    actions: BusActions,
    onStartTrip: () -> Unit,
    onPractice: () -> Unit,
    map: @Composable (Double, Double, String) -> Unit,
) {
    val colors = SamFranTheme.colors
    val name = stop.name.ifBlank { stringResource(R.string.your_stop_lower) }
    val alert = stop.alertMeters
    val practice = state.practice
    val on = practice is Practice.Riding || state.riding
    SamFranCard(Modifier.fillMaxWidth().semantics { if (on) liveRegion = LiveRegionMode.Polite }) {
        when {
            practice is Practice.Riding -> {
                val start = alert + 1800f
                val d = alert + (start - alert) * practice.left
                Eyebrow(stringResource(R.string.practice_run), color = colors.accentInk)
                Text(stringResource(R.string.distance_to, Distances.format(d), name), style = MaterialTheme.typography.headlineSmall)
                RouteLine(1f - practice.left, stop.kind)
                Hint(stringResource(R.string.practice_hint, Distances.format(alert.toFloat())))
                SecondaryButton(stringResource(R.string.end_practice), actions::endPractice, Modifier.fillMaxWidth())
            }
            practice == Practice.Done -> {
                Text(stringResource(R.string.thats_it), style = MaterialTheme.typography.headlineSmall)
                Hint(stringResource(R.string.thats_it_hint))
                SecondaryButton(stringResource(R.string.done), actions::endPractice, Modifier.fillMaxWidth())
            }
            state.riding -> {
                val d = state.trip.distance
                Eyebrow(stringResource(if (stop.kind == StopKind.Train) R.string.on_the_train else R.string.on_the_bus), color = colors.accentInk)
                Text(
                    if (d >= 0) stringResource(R.string.distance_to, Distances.format(d), name) else stringResource(R.string.finding_you),
                    style = MaterialTheme.typography.headlineSmall,
                )
                RouteLine(Distances.progress(state.from, d, alert), stop.kind)
                if (d >= 0) Text(stringResource(R.string.buzzes_in, Distances.format(maxOf(0f, d - alert))), style = MaterialTheme.typography.titleSmall)
                Hint(stringResource(R.string.lock_hint))
                SecondaryButton(stringResource(R.string.stop_alarm), actions::stopTrip, Modifier.fillMaxWidth())
            }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow(stringResource(R.string.get_off_at))
                    StopChips(all, stop, actions)
                    val shift = state.shift
                    if (state.byShift && shift?.end != null) {
                        Text(
                            stringResource(R.string.because_shift, shift.workplaceLabel, "${shift.end!!.hour}:%02d".format(shift.end!!.minute), name),
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.highlight).padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (state.picked != null && all.forShift(shift) != null && all.forShift(shift) != stop) {
                        Hint(stringResource(R.string.just_this_ride))
                    }
                    Hint(stringResource(R.string.buzzes_before, Distances.format(alert.toFloat())) + " " + stringResource(earlyText(Distances.early(alert, stop.kind))))
                }
                map(stop.lat, stop.lng, stop.name)
                PrimaryButton(stringResource(if (stop.kind == StopKind.Train) R.string.on_train else R.string.on_bus), onStartTrip, Modifier.fillMaxWidth(), big = true)
                SecondaryButton(stringResource(R.string.try_it_now), onPractice, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The ride as a line: the bus or train moving towards the stop, and the stretch at the end where it buzzes. */
@Composable
private fun RouteLine(progress: Float, kind: StopKind) {
    val colors = SamFranTheme.colors
    val zone = 0.24f
    val at = progress.coerceIn(0f, 1f) * (1f - zone)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(36.dp)) {
        val w = maxWidth
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.sunken))
        Box(Modifier.align(Alignment.CenterStart).size(width = w * at, height = 6.dp).clip(CircleShape).background(colors.accentInk.copy(alpha = 0.35f)))
        Box(Modifier.align(Alignment.CenterEnd).size(width = w * zone, height = 6.dp).clip(CircleShape).background(colors.heart.copy(alpha = 0.7f)))
        Box(Modifier.align(Alignment.CenterEnd).size(14.dp).clip(CircleShape).background(colors.heart))
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = (w * at - 14.dp).coerceAtLeast(0.dp)).size(28.dp).clip(CircleShape).background(colors.surface),
            contentAlignment = Alignment.Center,
        ) { LineIcon(vehicle(kind), null, size = 18.dp, tint = colors.accentInk, stroke = 2.4f) }
    }
}

private fun vehicle(kind: StopKind) = if (kind == StopKind.Train) SamFranIcons.train else SamFranIcons.bus

/** Bus stops green, stations blue (.kind-tile on the web), so the two can be told apart at a glance. */
@Composable
private fun kindColors(kind: StopKind) = if (kind == StopKind.Train) SamFranTheme.colors.tileBlue else SamFranTheme.colors.tileGreen

@Composable
private fun kindName(kind: StopKind) = stringResource(if (kind == StopKind.Train) R.string.kind_train else R.string.kind_bus)

/** A bus or a train in its colour on a rounded tile: in the search, and in the list of stops. */
@Composable
private fun KindTile(kind: StopKind) {
    val c = kindColors(kind)
    Box(Modifier.size(38.dp).clip(MaterialTheme.shapes.small).background(c.bg), contentAlignment = Alignment.Center) {
        LineIcon(vehicle(kind), null, size = 22.dp, tint = c.ink)
    }
}

/** "Bus stop" or "Station" in its colour, then what else there is to say, quieter. */
@Composable
private fun KindLine(kind: StopKind, rest: String?) {
    Row {
        Text(kindName(kind), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = kindColors(kind).ink)
        if (!rest.isNullOrBlank()) Text(" · $rest", style = MaterialTheme.typography.bodySmall, color = SamFranTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What a buzz distance means on a ride. */
private fun earlyText(e: Early) = when (e) {
    Early.RightBefore -> R.string.early_right_before
    Early.OneStop -> R.string.early_one_stop
    Early.TwoStops -> R.string.early_two_stops
    Early.OneMinute -> R.string.early_one_minute
    Early.OneToTwoMinutes -> R.string.early_one_two_minutes
    Early.TwoToThreeMinutes -> R.string.early_two_three_minutes
    Early.Plenty -> R.string.early_plenty
}

/** The three steps of how it works. */
@Composable
private fun Steps() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Step(1, R.string.step1_title, R.string.step1_body)
        Step(2, R.string.step2_title, R.string.step2_body)
        Step(3, R.string.step3_title, R.string.step3_body)
    }
}

@Composable
private fun Step(n: Int, title: Int, body: Int) {
    val colors = SamFranTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(colors.a.bg), contentAlignment = Alignment.Center) {
            Text(n.toString(), color = colors.a.text, fontWeight = FontWeight.ExtraBold)
        }
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Hint(stringResource(body))
        }
    }
}

/** A row of one-tap choices, the one picked filled in. */
@Composable
private fun <T> ChoiceRow(choices: List<Pair<T, String>>, value: T, onPick: (T) -> Unit, enabled: Boolean = true) {
    val colors = SamFranTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((v, label) in choices) {
            val on = v == value
            Box(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (on) colors.accent else colors.sunken)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onPick(v) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) colors.accentText else colors.text, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
            }
        }
    }
}

/** A bus stop or a station. */
@Composable
private fun KindChips(value: StopKind, onPick: (StopKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.kind_label), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(listOf(StopKind.Bus to stringResource(R.string.kind_bus), StopKind.Train to stringResource(R.string.kind_train)), value, onPick)
    }
}

private fun buzzName(b: Buzz) = when (b) {
    Buzz.Gentle -> R.string.buzz_gentle
    Buzz.Strong -> R.string.buzz_strong
    Buzz.Strongest -> R.string.buzz_strongest
}

private fun buzzAbout(b: Buzz) = when (b) {
    Buzz.Gentle -> R.string.buzz_gentle_about
    Buzz.Strong -> R.string.buzz_strong_about
    Buzz.Strongest -> R.string.buzz_strongest_about
}

/** Where to get off: one chip for each stop, with the shifts it's for. Stops are added in Alarm settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopChips(all: BusStops, stop: BusStop, actions: BusActions) {
    val colors = SamFranTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in all.stops) {
            val on = s.id == stop.id
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (on) colors.accent else colors.sunken)
                    .clickable(role = Role.RadioButton) { actions.pickStop(s.id) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val ink = if (on) colors.accentText else colors.text
                // The picked one's badge shows a check instead of the vehicle (still in the kind's colour), so picking doesn't change any chip's width.
                val k = kindColors(s.kind)
                Box(Modifier.size(22.dp).clip(CircleShape).background(k.bg), contentAlignment = Alignment.Center) {
                    LineIcon(if (on) SamFranIcons.check else vehicle(s.kind), kindName(s.kind), size = 14.dp, tint = k.ink, stroke = 2.4f)
                }
                Text(s.name.ifBlank { stringResource(R.string.unnamed_stop) }, color = ink, fontWeight = FontWeight.SemiBold)
                if (s.shifts.isNotEmpty()) {
                    Text(
                        s.shifts.joinToString("・") { ShiftCodes.labelOf(it) },
                        Modifier.clip(CircleShape).background(ink.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp),
                        color = ink,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** From "Other stop" on the question at the end of a shift: where to get off, then start. */
@Composable
private fun ChooseCard(all: BusStops, stop: BusStop, actions: BusActions, onStartTrip: () -> Unit) {
    val colors = SamFranTheme.colors
    SamFranCard(spacing = 4) {
        CardTitle(stringResource(R.string.where_today))
        for ((i, s) in all.stops.withIndex()) {
            if (i > 0) HorizontalDivider(color = colors.divider)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { actions.pickStop(s.id) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.name.ifBlank { stringResource(R.string.unnamed_stop) }, style = MaterialTheme.typography.titleMedium)
                    if (s.shifts.isNotEmpty()) Hint(stringResource(R.string.after_shifts, s.shifts.joinToString("・") { ShiftCodes.labelOf(it) }))
                }
                RadioButton(selected = s.id == stop.id, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.accent))
            }
        }
        PrimaryButton(stringResource(R.string.start_to, stop.name.ifBlank { stringResource(R.string.your_stop_lower) }), onStartTrip, Modifier.fillMaxWidth().padding(top = 8.dp), big = true)
        LinkButton(stringResource(R.string.cancel), actions::endChoosing, Modifier.align(Alignment.CenterHorizontally))
    }
}

/** The stops: one line each, saying bus stop or station in its colour, and the shifts it's for; a tap opens it. */
@Composable
private fun StopsCard(all: BusStops, added: Long?, actions: BusActions) {
    val colors = SamFranTheme.colors
    SamFranCard(spacing = 0) {
        Eyebrow(stringResource(R.string.your_stops), Modifier.padding(bottom = 4.dp))
        for ((i, s) in all.stops.withIndex()) {
            if (i > 0) HorizontalDivider(color = colors.divider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(if (s.id == added) colors.highlight else Color.Transparent)
                    .clickable(role = Role.Button) { actions.edit(s.id) }
                    .padding(vertical = 12.dp, horizontal = if (s.id == added) 8.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KindTile(s.kind)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.name.ifBlank { stringResource(R.string.unnamed_stop) }, style = MaterialTheme.typography.titleMedium)
                    KindLine(
                        s.kind,
                        when {
                            s.id == added -> stringResource(R.string.just_added)
                            s.shifts.isNotEmpty() -> stringResource(R.string.after_shifts, s.shifts.joinToString("・") { ShiftCodes.labelOf(it) })
                            else -> null
                        },
                    )
                }
                LineIcon(SamFranIcons.chevronRight, null, size = 18.dp, tint = colors.muted)
            }
        }
        if (all.stops.size < BusStops.MAX) LinkButton(stringResource(R.string.add_a_stop_short), actions::add, Modifier.padding(top = 4.dp))
    }
}

/** A row on a stop's page: what it is, what it's set to, and a tap to change it. */
@Composable
private fun SettingRow(icon: String, label: String, value: String, onClick: () -> Unit) {
    val colors = SamFranTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LineIcon(icon, null, size = 20.dp, tint = colors.muted)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(value, Modifier.widthIn(max = 170.dp), color = colors.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LineIcon(SamFranIcons.chevronRight, null, size = 18.dp, tint = colors.muted)
    }
}

/**
 * A stop's page: a small map, then one row for each setting, each opening
 * its choices from the bottom. A saved stop saves each change as it's
 * made; a new one is added by "Add stop", so backing out leaves nothing.
 */
@Composable
private fun StopPage(state: BusUiState, all: BusStops, actions: BusActions, onAsk: () -> Unit, map: @Composable (Double, Double, String, Dp) -> Unit) {
    val colors = SamFranTheme.colors
    val stop = state.pageStop ?: return
    val new = state.editing == BusUiState.NEW
    val name = stop.name.ifBlank { stringResource(R.string.unnamed_stop) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle(name)
        map(stop.lat, stop.lng, stop.name, 150.dp)
        SamFranCard(spacing = 0, padding = PaddingValues(horizontal = 18.dp, vertical = 2.dp)) {
            SettingRow(vehicle(stop.kind), stringResource(R.string.stop_where), name, actions::openWhere)
            HorizontalDivider(color = colors.divider)
            SettingRow(SamFranIcons.ruler, stringResource(R.string.buzz_before), Distances.format(stop.meters.toFloat())) { actions.openSheet(StopSheet.Distance) }
            HorizontalDivider(color = colors.divider)
            SettingRow(SamFranIcons.bell, stringResource(R.string.buzz_how), stringResource(buzzName(stop.buzz))) { actions.openSheet(StopSheet.Buzz) }
            HorizontalDivider(color = colors.divider)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Switch) { actions.setSound(stop.soundAfter == 0) }.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.sound_switch), style = MaterialTheme.typography.titleSmall)
                    Hint(stringResource(R.string.sound_switch_hint))
                }
                Switch(stop.soundAfter > 0, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.accentInk))
            }
            LinkButton(stringResource(R.string.hear_sound), actions::hearSound, Modifier.padding(bottom = 4.dp))
        }
        SamFranCard(spacing = 0, padding = PaddingValues(horizontal = 18.dp, vertical = 2.dp)) {
            SettingRow(
                SamFranIcons.calendar,
                stringResource(R.string.use_after_short),
                if (stop.shifts.isEmpty()) stringResource(R.string.no_shifts) else stop.shifts.joinToString("・") { ShiftCodes.labelOf(it) },
            ) { actions.openSheet(StopSheet.Shifts) }
            HorizontalDivider(color = colors.divider)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Switch, onClick = onAsk).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.ask_when_these_end), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(stop.ask, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.accentInk))
            }
            if (stop.ask && state.askBlocked) ErrorText(stringResource(R.string.allow_notifications), Modifier.padding(bottom = 12.dp))
        }
        if (new) {
            PrimaryButton(stringResource(R.string.add_stop), actions::addStop, Modifier.fillMaxWidth(), enabled = !state.saving, big = true)
        } else {
            LinkButton(stringResource(R.string.remove_stop), { confirmRemove = true }, Modifier.align(Alignment.CenterHorizontally), enabled = !state.saving, danger = true)
        }
    }
    if (confirmRemove && !new) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_stop_title, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    actions.removeStop()
                }) { Text(stringResource(R.string.remove), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = colors.surface,
        )
    }
}

/** One choice, from the bottom of the screen over a dimmed page; a tap outside closes it. */
@Composable
private fun Sheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = SamFranTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(colors.surface)
                // Taps inside don't close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(colors.border))
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** The sheet open on a stop's page. */
@Composable
private fun StopSheets(sheet: StopSheet, stop: StopDraft, all: BusStops, editing: Long?, actions: BusActions) {
    val name = stop.name.ifBlank { stringResource(R.string.unnamed_stop) }
    when (sheet) {
        StopSheet.Distance -> Sheet(stringResource(R.string.buzz_before_title, name), actions::closeSheet) {
            for (row in Distances.choices(stop.kind).chunked(3)) {
                ChoiceRow(row.map { it to Distances.format(it.toFloat()) }, stop.meters, actions::setMeters)
            }
            Hint(stringResource(earlyText(Distances.early(stop.meters, stop.kind))))
        }
        StopSheet.Buzz -> Sheet(stringResource(R.string.buzz_how_title, name), actions::closeSheet) {
            ChoiceRow(Buzz.entries.map { it to stringResource(buzzName(it)) }, stop.buzz, actions::setBuzz)
            Hint(stringResource(buzzAbout(stop.buzz)))
        }
        StopSheet.Shifts -> Sheet(stringResource(R.string.use_after_title, name), actions::closeSheet) {
            val colors = SamFranTheme.colors
            var value by rememberSaveable { mutableStateOf(stop.shifts) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in ShiftKinds) {
                    val on = k in value
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (on) colors.accent else colors.sunken)
                            .clickable(role = Role.Checkbox) { value = if (on) value - k else ShiftKinds.filter { it in value || it == k } }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(ShiftCodes.labelOf(k), color = if (on) colors.accentText else colors.text, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
                    }
                }
            }
            val taken = all.stops.filter { s -> s.id != editing && s.shifts.any { it in value } }
            if (taken.isNotEmpty()) Hint(stringResource(R.string.use_after_taken, taken.joinToString("、") { it.name }))
            PrimaryButton(stringResource(R.string.done), { actions.setShifts(value) }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * Where a stop is: search by name (and town), each result's icon saying
 * bus stop or station; or, folded away, a Google Maps link or where the
 * phone is, then its name and kind.
 */
@Composable
private fun WherePage(state: BusUiState, actions: BusActions, onHere: () -> Unit, map: @Composable (Double, Double, String, Dp) -> Unit, title: String?) {
    val colors = SamFranTheme.colors
    val f = state.search
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (title != null) PageTitle(title)
        SamFranCard(spacing = 12) {
            if (title == null) CardTitle(stringResource(R.string.where_get_off))
            OutlinedTextField(
                value = f.query,
                onValueChange = actions::setQuery,
                label = { Text(stringResource(R.string.stop_name)) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { LineIcon(SamFranIcons.search, null, size = 20.dp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { f.found?.firstOrNull()?.let(actions::pick) }),
                modifier = Modifier.fillMaxWidth(),
            )
            val found = f.found
            if (f.query.isNotEmpty() && !found.isNullOrEmpty()) {
                Column {
                    found.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = colors.divider)
                        Row(
                            Modifier.fillMaxWidth().clickable { actions.pick(s) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            KindTile(s.kind)
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall)
                                KindLine(s.kind, s.area)
                            }
                        }
                    }
                }
            }
            if (f.query.isNotEmpty() && f.failed) Hint(stringResource(R.string.search_failed))
            if (f.query.isNotEmpty() && found?.isEmpty() == true) Hint(stringResource(R.string.no_stops))
            Row(Modifier.fillMaxWidth().clickable { actions.setOtherWays(!f.otherWays) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.other_ways), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.accentInk)
                LineIcon(if (f.otherWays) SamFranIcons.chevronDown else SamFranIcons.chevronRight, null, size = 18.dp, tint = colors.muted)
            }
            AnimatedVisibility(f.otherWays) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    SecondaryButton(stringResource(R.string.use_here), onHere, Modifier.fillMaxWidth(), enabled = !f.busy, icon = SamFranIcons.location)
                }
            }
            // From a link or where the phone is: the place, then its name and kind.
            f.place?.let { p ->
                map(p.lat, p.lng, p.name, 150.dp)
                OutlinedTextField(
                    value = p.name,
                    onValueChange = actions::setPlaceName,
                    label = { Text(stringResource(R.string.place_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                KindChips(p.kind, actions::setPlaceKind)
                PrimaryButton(stringResource(R.string.use_this), actions::usePlace, Modifier.fillMaxWidth())
            }
            when (val p = f.problem) {
                null -> {}
                StopProblem.NoPosition -> ErrorText(stringResource(R.string.no_position))
                StopProblem.NoHere -> ErrorText(stringResource(R.string.no_here))
                is StopProblem.Server -> p.outcome.problem()?.let { ErrorText(it) }
            }
        }
    }
}
