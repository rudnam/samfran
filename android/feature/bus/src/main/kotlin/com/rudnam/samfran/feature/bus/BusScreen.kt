package com.rudnam.samfran.feature.bus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.rudnam.samfran.core.model.mapLink
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
    // The practice run's buzz shows a notification too: ask for that first (Android 13+), then practise either way.
    val askForPractice = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.practice() }
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
        onMap = { context.openWeb(it) },
    )
}

/**
 * The bus-stop alarm (web/src/features/bus/BusPage.tsx). Choose the stop
 * once; on the bus, "I'm on the bus" starts a trip on the phone, which buzzes
 * before the stop until "Stop". The phone does the watching; this page
 * explains it, sets it up, starts it, shows it and stops it. "Try it now" is
 * a practice run that really buzzes.
 */
@Composable
fun BusScreen(
    state: BusUiState,
    actions: BusActions,
    onStartTrip: () -> Unit,
    onPractice: () -> Unit,
    onHere: () -> Unit,
    onMap: (String) -> Unit,
    map: @Composable (lat: Double, lng: Double, name: String) -> Unit = { lat, lng, name -> StopMap(lat, lng, name) },
) {
    val all = state.stops
    TabPage {
        item(key = "head") { PageTitle(stringResource(R.string.bus_alarm), subtitle = stringResource(R.string.bus_subtitle)) }
        if (state.locationDenied) item(key = "denied") { ErrorText(stringResource(R.string.location_denied)) }
        state.problem?.let { p -> item(key = "problem") { p.problem()?.let { ErrorText(it) } } }
        if (all == null) {
            item(key = "loading") { Skeleton(rows = 3) }
            return@TabPage
        }
        val stop = state.stop
        when {
            state.buzzing -> item(key = "buzz") { BuzzCard(stop ?: BusStop(set = false), practice = state.practice != null, onStop = actions::stopBuzz) }
            stop != null && !state.isEditing -> item(key = "ride") { RideCard(state, all, stop, actions, onStartTrip, onPractice, map) }
        }
        if (!state.ready) item(key = "how") { SamFranCard(spacing = 10) { CardTitle(stringResource(R.string.how_it_works)); Steps() } }
        if (state.isEditing) {
            item(key = "form") { StopFormCard(state, all, actions, onHere, onMap, map) }
        } else if (state.ready) {
            item(key = "settings") { AlarmSettings(state, all, actions) }
            item(key = "fold") {
                var open by rememberSaveable { mutableStateOf(false) }
                SamFranCard(onClick = { open = !open }, spacing = 10) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardTitle(stringResource(R.string.how_it_works), Modifier.weight(1f))
                        LineIcon(if (open) SamFranIcons.chevronLeft else SamFranIcons.chevronRight, null, size = 18.dp, tint = SamFranTheme.colors.muted)
                    }
                    AnimatedVisibility(open) { Steps() }
                }
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
                RouteLine(1f - practice.left)
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
                Eyebrow(stringResource(R.string.on_the_bus), color = colors.accentInk)
                Text(
                    if (d >= 0) stringResource(R.string.distance_to, Distances.format(d), name) else stringResource(R.string.finding_you),
                    style = MaterialTheme.typography.headlineSmall,
                )
                RouteLine(Distances.progress(state.from, d, alert))
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
                    Hint(stringResource(R.string.buzzes_before, Distances.format(alert.toFloat())) + " " + stringResource(earlyText(Distances.early(alert))))
                }
                map(stop.lat, stop.lng, stop.name)
                PrimaryButton(stringResource(R.string.on_bus), onStartTrip, Modifier.fillMaxWidth(), big = true)
                SecondaryButton(stringResource(R.string.try_it_now), onPractice, Modifier.fillMaxWidth())
                Hint(stringResource(R.string.on_bus_hint), Modifier.fillMaxWidth())
            }
        }
    }
}

/** The ride as a line: the bus moving towards the stop, and the stretch at the end where it buzzes. */
@Composable
private fun RouteLine(progress: Float) {
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
        ) { LineIcon(SamFranIcons.bus, null, size = 18.dp, tint = colors.accentInk, stroke = 2.4f) }
    }
}

/** What a buzz distance means on a ride. */
private fun earlyText(e: Early) = when (e) {
    Early.RightBefore -> R.string.early_right_before
    Early.OneStop -> R.string.early_one_stop
    Early.TwoStops -> R.string.early_two_stops
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

/** Six one-tap distances, each saying what it means. */
@Composable
private fun DistanceChips(value: Int, onPick: (Int) -> Unit, enabled: Boolean = true) {
    val colors = SamFranTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.buzz_how_far), style = MaterialTheme.typography.labelLarge)
        for (row in Distances.choices.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in row) {
                    val on = m == value
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (on) colors.accent else colors.sunken)
                            .clickable(enabled = enabled, role = Role.RadioButton) { onPick(m) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(Distances.format(m.toFloat()), color = if (on) colors.accentText else colors.text, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
                    }
                }
            }
        }
        Hint(stringResource(earlyText(Distances.early(value))) + " " + stringResource(R.string.fast_road))
    }
}

@Composable
private fun BuzzChips(value: Buzz, onPick: (Buzz) -> Unit, enabled: Boolean = true) {
    val colors = SamFranTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.buzz_how), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in Buzz.entries) {
                val on = b == value
                Box(
                    Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (on) colors.accent else colors.sunken)
                        .clickable(enabled = enabled, role = Role.RadioButton) { onPick(b) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(buzzName(b)), color = if (on) colors.accentText else colors.text, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
                }
            }
        }
        Hint(stringResource(buzzAbout(value)) + " " + stringResource(R.string.buzz_try))
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

/** Where to get off: one chip for each stop, with the shifts it's for, and one to add another. */
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
                if (on) Text("✓", color = ink, fontWeight = FontWeight.Bold)
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
        if (all.stops.size < BusStops.MAX) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .border(1.5.dp, colors.border, CircleShape)
                    .clickable(role = Role.Button) { actions.add() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Text(stringResource(R.string.add_a_stop_short), color = colors.muted) }
        }
    }
}

/** The stops (each changed in its own form) and how it buzzes (saved as it's picked). */
@Composable
private fun AlarmSettings(state: BusUiState, all: BusStops, actions: BusActions) {
    val colors = SamFranTheme.colors
    SamFranCard(spacing = 14) {
        CardTitle(stringResource(R.string.alarm_settings))
        Column {
            Eyebrow(stringResource(R.string.your_stops))
            for ((i, s) in all.stops.withIndex()) {
                if (i > 0) HorizontalDivider(color = colors.divider)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.name.ifBlank { stringResource(R.string.unnamed_stop) }, style = MaterialTheme.typography.titleMedium)
                        val when_ = if (s.shifts.isEmpty()) stringResource(R.string.when_picked) else stringResource(R.string.after_shifts, s.shifts.joinToString("・") { ShiftCodes.labelOf(it) })
                        Hint(when_ + " · " + Distances.format(s.alertMeters.toFloat()))
                    }
                    SecondaryButton(stringResource(R.string.change), { actions.edit(s.id) })
                }
            }
            if (all.stops.size < BusStops.MAX) SecondaryButton(stringResource(R.string.add_a_stop_short), actions::add, Modifier.fillMaxWidth().padding(top = 4.dp))
            Hint(stringResource(R.string.add_stop_hint), Modifier.padding(top = 6.dp))
        }
        BuzzChips(all.buzz, actions::setBuzz, enabled = !state.savingSettings)
    }
}

/** Which kinds of shift a stop is for: four toggles, saying what they mean. */
@Composable
private fun ShiftToggles(all: BusStops, editing: Long?, f: StopForm, actions: BusActions) {
    val colors = SamFranTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.use_after), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in ShiftKinds) {
                val on = k in f.shifts
                Box(
                    Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (on) colors.accent else colors.sunken)
                        .clickable(role = Role.Checkbox) { actions.toggleShift(k) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ShiftCodes.labelOf(k), color = if (on) colors.accentText else colors.text, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
                }
            }
        }
        val name = f.name.trim().ifEmpty { stringResource(R.string.this_stop) }
        var hint = if (f.shifts.isEmpty()) stringResource(R.string.use_after_none) else stringResource(R.string.use_after_some, name)
        val taken = all.stops.filter { s -> s.id != editing && s.shifts.any { it in f.shifts } }
        if (taken.isNotEmpty()) hint += " " + stringResource(R.string.use_after_taken, taken.joinToString("、") { it.name })
        Hint(hint)
    }
}

/** Choosing the stop: search by name, or (folded away) a Google Maps link to it or where the phone is now. */
@Composable
private fun StopFormCard(state: BusUiState, all: BusStops, actions: BusActions, onHere: () -> Unit, onMap: (String) -> Unit, map: @Composable (Double, Double, String) -> Unit) {
    val colors = SamFranTheme.colors
    val f = state.form
    val first = !state.ready
    val editing = all.byId(state.editing ?: BusUiState.NEW)
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    SamFranCard(spacing = 14) {
        if (first) Eyebrow(stringResource(R.string.step_1_of_2), color = colors.accentInk)
        CardTitle(
            when {
                editing != null -> editing.name.ifBlank { stringResource(R.string.unnamed_stop) }
                first -> stringResource(R.string.where_get_off)
                else -> stringResource(R.string.add_a_stop)
            },
        )
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
            val hint = when {
                f.query.isNotEmpty() && f.searchFailed -> stringResource(R.string.search_failed)
                f.query.isNotEmpty() && found?.isEmpty() == true -> stringResource(R.string.no_stops)
                f.query.isNotEmpty() && !found.isNullOrEmpty() -> stringResource(R.string.tap_your_stop) + " " + stringResource(R.string.osm)
                else -> ""
            }
            if (hint.isNotEmpty()) Hint(hint)
            val pos = f.position
            if (pos != null && f.query.isEmpty()) {
                map(pos.first, pos.second, f.name)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("✓ " + stringResource(if (f.byName) R.string.found_by_name else R.string.found_it), Modifier.weight(1f, fill = false))
                    LinkButton(stringResource(R.string.check_on_map), { onMap(mapLink(pos.first, pos.second)) })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { actions.setOtherWays(!f.otherWays) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.other_ways), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                LineIcon(if (f.otherWays) SamFranIcons.chevronLeft else SamFranIcons.chevronRight, null, size = 18.dp, tint = colors.muted)
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
        }
        if (first) Eyebrow(stringResource(R.string.step_2_of_2), color = colors.accentInk)
        DistanceChips(f.meters, actions::setMeters)
        if (!first) ShiftToggles(all, state.editing, f, actions)
        when (val p = f.problem) {
            null -> {}
            StopProblem.NoPosition -> ErrorText(stringResource(R.string.no_position))
            StopProblem.NoHere -> ErrorText(stringResource(R.string.no_here))
            is StopProblem.Server -> p.outcome.problem()?.let { ErrorText(it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing != null) LinkButton(stringResource(R.string.remove_stop), { confirmRemove = true }, enabled = !f.busy, danger = true)
            Spacer(Modifier.weight(1f))
            if (state.ready) SecondaryButton(stringResource(R.string.cancel), actions::cancelEdit)
            PrimaryButton(stringResource(R.string.save), actions::saveStop, enabled = !f.busy && f.position != null)
        }
    }
    if (confirmRemove && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_stop_title, editing.name.ifBlank { stringResource(R.string.unnamed_stop) })) },
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
