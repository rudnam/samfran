package com.rudnam.samfran.feature.food

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.designsystem.component.CardTitle
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Photo
import com.rudnam.samfran.core.model.Visit
import com.rudnam.samfran.core.ui.DateField
import com.rudnam.samfran.core.ui.Dates
import com.rudnam.samfran.core.ui.currentLocale
import com.rudnam.samfran.core.ui.problem
import java.time.LocalDate

/**
 * Each time you went, newest first (web/src/features/restaurants/Visits.tsx):
 * its date, a note and its photos, with Went again to add one. Photos go up
 * as they were taken; a tap opens the original full size.
 */
@Composable
internal fun Visits(state: PlaceUiState, actions: PlaceActions) {
    val visits = state.place?.visits.orEmpty()
    val locale = currentLocale
    // The photo open full size: its visit and which one.
    var viewing by rememberSaveable { mutableStateOf<Pair<Long, Int>?>(null) }
    SamFranCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CardTitle(stringResource(R.string.visits)) }
            if (state.adding == null) SecondaryButton(stringResource(R.string.went_again), actions::startAdding, enabled = !state.busy, icon = SamFranIcons.add)
        }
        state.adding?.let { date ->
            VisitForm(
                date = date,
                note = state.addNote,
                today = state.today,
                busy = state.busy,
                onDate = actions::setAdding,
                onNote = actions::setAddNote,
                onSave = actions::saveAdding,
                onCancel = actions::cancelAdding,
            )
        }
        visits.forEachIndexed { i, v ->
            if (i > 0) HorizontalDivider(color = SamFranTheme.colors.divider)
            VisitItem(state, actions, v, last = visits.size == 1, onOpen = { index -> viewing = v.id to index })
        }
    }
    val open = viewing?.let { (visit, index) -> visits.firstOrNull { it.id == visit }?.let { it to index } }
    if (open != null && open.first.photos.isNotEmpty()) {
        PhotoViewer(
            photos = open.first.photos,
            start = open.second.coerceAtMost(open.first.photos.size - 1),
            title = Dates.short(open.first.visitedOn, locale, state.today),
            coverId = state.place?.coverPhotoId,
            busy = state.busy,
            onCover = { photo -> actions.setCover(photo.id) },
            onClose = { viewing = null },
            onDelete = { photo ->
                if (open.first.photos.size == 1) viewing = null
                actions.deletePhoto(photo.id)
            },
        )
    }
}

/** A visit's date and note, as typed: Went again's, or a visit being changed. */
@Composable
private fun VisitForm(
    date: LocalDate,
    note: String,
    today: LocalDate,
    busy: Boolean,
    onDate: (LocalDate) -> Unit,
    onNote: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val colors = SamFranTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.sunken).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DateField(label = stringResource(R.string.when_did_you_go), date = date, max = today, onChange = onDate)
        OutlinedTextField(
            value = note,
            onValueChange = onNote,
            label = { Text(stringResource(R.string.note_optional)) },
            placeholder = { Text(stringResource(R.string.note_hint)) },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(stringResource(R.string.save), onSave, enabled = !busy)
            SecondaryButton(stringResource(R.string.cancel), onCancel, enabled = !busy)
            if (onDelete != null) LinkButton(stringResource(R.string.delete_visit), onDelete, enabled = !busy, danger = true)
        }
    }
}

/** One visit: its date and note (Edit changes them), and its photos with a tile to add more. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisitItem(state: PlaceUiState, actions: PlaceActions, visit: Visit, last: Boolean, onOpen: (Int) -> Unit) {
    val colors = SamFranTheme.colors
    val locale = currentLocale
    val date = Dates.short(visit.visitedOn, locale, state.today)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        actions.addPhotos(visit.id, uris.map { it.toString() })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val editing = state.editing?.takeIf { it.id == visit.id }
        if (editing != null) {
            VisitForm(
                date = editing.date,
                note = editing.note,
                today = state.today,
                busy = state.busy,
                onDate = { actions.setEditing(editing.copy(date = it)) },
                onNote = { actions.setEditing(editing.copy(note = it)) },
                onSave = actions::saveEditing,
                onCancel = actions::cancelEditing,
                onDelete = { confirmDelete = true },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                val describe = stringResource(R.string.edit_visit, date)
                LinkButton(
                    stringResource(R.string.edit),
                    { actions.startEditing(visit.id) },
                    Modifier.semantics { contentDescription = describe },
                    enabled = !state.busy,
                    quiet = true,
                )
            }
            if (visit.note.isNotBlank()) Text(visit.note, style = MaterialTheme.typography.bodyLarge)
        }
        val sending = state.sending[visit.id].orEmpty()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4) {
            val tile = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            visit.photos.forEachIndexed { i, p ->
                val describe = stringResource(R.string.photo_n_of, i + 1, visit.photos.size)
                AsyncImage(
                    model = p.thumbUrl,
                    contentDescription = describe,
                    contentScale = ContentScale.Crop,
                    modifier = tile.background(colors.sunken).clickable { onOpen(i) },
                )
            }
            sending.forEach { s ->
                val describe = stringResource(R.string.sending_photo)
                Box(tile.background(colors.sunken).semantics { contentDescription = describe }) {
                    AsyncImage(model = s.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.5f })
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(s.sent.coerceIn(0.02f, 1f)).height(4.dp).background(colors.accent))
                }
            }
            Column(
                tile.border(1.5.dp, colors.divider, RoundedCornerShape(8.dp))
                    .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LineIcon(SamFranIcons.camera, null, tint = colors.muted, size = 22.dp)
                Text(
                    stringResource(if (visit.photos.isEmpty() && sending.isEmpty()) R.string.add_photos else R.string.add),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
            // Empty places keep the tiles a quarter of the row wide.
            repeat((4 - (visit.photos.size + sending.size + 1) % 4) % 4) { Box(Modifier.weight(1f)) }
        }
        state.photoProblem[visit.id]?.let { p ->
            ErrorText(if (p is Outcome.Refused && p.message == PlaceViewModel.UNREADABLE) stringResource(R.string.photo_unreadable) else p.problem().orEmpty())
        }
    }

    if (confirmDelete) {
        val body = listOfNotNull(
            visit.photos.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.delete_visit_photos, it, it) },
            if (last) stringResource(R.string.delete_visit_last, state.place?.name.orEmpty()) else null,
            stringResource(R.string.cant_undo),
        ).joinToString(" ")
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_visit_title, date)) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.deleteVisit(visit.id)
                }) { Text(stringResource(R.string.delete), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = colors.surface,
        )
    }
}

/** How many photos the picker lets you choose at once. */
private const val MAX_PICK = 20

/**
 * A visit's photos full size, one at a time: the original as it was taken,
 * over its thumbnail until it's in. Swipe between them, pinch or double-tap
 * to zoom; back closes it.
 */
@Composable
private fun PhotoViewer(
    photos: List<Photo>,
    start: Int,
    title: String,
    coverId: Long?,
    busy: Boolean,
    onCover: (Photo) -> Unit,
    onClose: () -> Unit,
    onDelete: (Photo) -> Unit,
) {
    val pager = rememberPagerState(initialPage = start) { photos.size }
    var confirmDelete by remember { mutableStateOf<Photo?>(null) }
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0D0B09))) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].id }) { page ->
                ZoomablePhoto(photos[page], stringResource(R.string.photo_n_of, page + 1, photos.size))
            }
            val current = photos.getOrNull(pager.currentPage.coerceAtMost(photos.size - 1))
            Row(
                Modifier.fillMaxWidth().background(Color(0x66000000)).safeDrawingPadding().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$title · ${pager.currentPage + 1} / ${photos.size}" + (current?.addedBy?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFF4EFE8),
                )
                // The place's cover is the photo the list shows for it; any can be.
                if (current != null && current.id == coverId) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineIcon(SamFranIcons.check, null, tint = Color(0xFFF4EFE8), size = 16.dp)
                        Text(" " + stringResource(R.string.cover), style = MaterialTheme.typography.labelLarge, color = Color(0xFFF4EFE8))
                    }
                } else if (current != null) {
                    TextButton(onClick = { onCover(current) }, enabled = !busy) {
                        Text(stringResource(R.string.make_cover), color = Color(0xFFF4EFE8))
                    }
                }
                IconButton(onClick = { confirmDelete = current }) {
                    LineIcon(SamFranIcons.trash, stringResource(R.string.delete), tint = Color(0xFFFF9C8F))
                }
                IconButton(onClick = onClose) {
                    LineIcon(SamFranIcons.close, stringResource(R.string.close), tint = Color(0xFFF4EFE8))
                }
            }
        }
    }
    confirmDelete?.let { photo ->
        val colors = SamFranTheme.colors
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_photo_title)) },
            text = { Text(stringResource(R.string.delete_photo_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDelete(photo)
                }) { Text(stringResource(R.string.delete), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
            containerColor = colors.surface,
        )
    }
}

/** One photo, fit to the screen; pinch or double-tap zooms in, and a drag then moves around it. */
@Composable
private fun ZoomablePhoto(photo: Photo, description: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val zoom = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, 6f)
        if (scale > 1f) {
            offsetX += pan.x
            offsetY += pan.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }
    SubcomposeAsyncImage(
        model = photo.url,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        loading = { AsyncImage(model = photo.thumbUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(photo.id) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offsetX = 0f
                    offsetY = 0f
                })
            }
            // Zoomed out, a swipe belongs to the pager.
            .transformable(state = zoom, canPan = { scale > 1f })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
    )
}
