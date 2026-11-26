package com.rudnam.samfran.feature.food

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.samfran.core.common.ApplicationScope
import com.rudnam.samfran.core.data.FoodRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Photos
import com.rudnam.samfran.core.data.UnreadablePhotoException
import com.rudnam.samfran.core.data.SpaceRepository
import com.rudnam.samfran.core.model.Restaurant
import com.rudnam.samfran.core.model.Space
import com.rudnam.samfran.feature.food.navigation.PlaceRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** A visit being changed: its date and note as typed. */
data class VisitEdit(val id: Long, val date: LocalDate, val note: String)

/** A photo on its way up: where it was picked, and how much has gone (0 to 1). */
data class SendingPhoto(val uri: String, val sent: Float = 0f)

data class PlaceUiState(
    val place: Restaurant? = null,
    /** True once the server (or the phone's copy) has answered, place or none. */
    val loaded: Boolean = false,
    val space: Space? = null,
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    /** The date being picked for Mark as been, or null when closed. */
    val picking: LocalDate? = null,
    /** Went again: the new visit's date (null when closed) and note. */
    val adding: LocalDate? = null,
    val addNote: String = "",
    /** The visit being changed, or null. */
    val editing: VisitEdit? = null,
    /** Photos on their way up, by visit. */
    val sending: Map<Long, List<SendingPhoto>> = emptyMap(),
    /** What went wrong sending photos, by visit. */
    val photoProblem: Map<Long, Outcome<Any>> = emptyMap(),
    /** The comment being written. */
    val draft: String = "",
    /** The place is gone (deleted here): the page closes. */
    val deleted: Boolean = false,
    val today: LocalDate = LocalDate.now(),
) {
    /** When you last went; null if not yet. */
    val beenOn: LocalDate? get() = place?.lastVisit
}

interface PlaceActions {
    fun rate(stars: Int)
    /** Mark as been: opens the date, today. */
    fun startPicking()
    fun setPicking(date: LocalDate)
    fun cancelPicking()
    fun saveBeen()
    /** Went again: opens a new visit's date (today) and note. */
    fun startAdding()
    fun setAdding(date: LocalDate)
    fun setAddNote(text: String)
    fun cancelAdding()
    fun saveAdding()
    /** Opens a visit's date and note to change. */
    fun startEditing(visit: Long)
    fun setEditing(edit: VisitEdit)
    fun cancelEditing()
    fun saveEditing()
    fun deleteVisit(visit: Long)
    /** Sends photos picked on the phone to a visit, one after another, as they were taken. */
    fun addPhotos(visit: Long, uris: List<String>)
    fun deletePhoto(photo: Long)
    fun delete()
    fun setDraft(text: String)
    /** Posts [PlaceUiState.draft], which empties once it's saved. */
    fun postComment()
    fun deleteComment(id: Long)
}

/** As the server's. */
const val MAX_COMMENT = 1000
const val MAX_NOTE = 1000

@HiltViewModel
class PlaceViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val food: FoodRepository,
    private val photos: Photos,
    spaces: SpaceRepository,
    clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel(), PlaceActions {
    private val id = savedState.toRoute<PlaceRoute>().id
    private val today = LocalDate.now(clock)
    private val local = MutableStateFlow(PlaceUiState(today = today))

    val state: StateFlow<PlaceUiState> = combine(local, food.restaurant(id), spaces.space) { s, place, space ->
        s.copy(place = place ?: s.place.takeIf { s.deleted }, space = space, loaded = s.loaded || place != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            val result = food.refresh(id)
            local.update { it.copy(loaded = true, problem = result.takeIf { r -> r !is Outcome.Done }) }
        }
    }

    private fun change(block: suspend () -> Outcome<Any>, after: (PlaceUiState) -> PlaceUiState = { it }) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val result = block()
            local.update { s ->
                val next = s.copy(busy = false, problem = result.takeIf { it !is Outcome.Done })
                if (result is Outcome.Done) after(next) else next
            }
        }
    }

    override fun rate(stars: Int) = change({ food.rate(id, stars) })

    override fun startPicking() = local.update { it.copy(picking = it.today) }

    override fun setPicking(date: LocalDate) = local.update { it.copy(picking = minOf(date, it.today)) }

    override fun cancelPicking() = local.update { it.copy(picking = null) }

    override fun saveBeen() {
        val on = local.value.picking ?: return
        change({ food.setBeen(id, on) }) { it.copy(picking = null) }
    }

    override fun startAdding() = local.update { it.copy(adding = it.today, addNote = "", editing = null) }

    override fun setAdding(date: LocalDate) = local.update { it.copy(adding = minOf(date, it.today)) }

    override fun setAddNote(text: String) = local.update { it.copy(addNote = text.take(MAX_NOTE)) }

    override fun cancelAdding() = local.update { it.copy(adding = null, addNote = "") }

    override fun saveAdding() {
        val on = local.value.adding ?: return
        val note = local.value.addNote
        change({ food.addVisit(id, on, note) }) { it.copy(adding = null, addNote = "") }
    }

    override fun startEditing(visit: Long) {
        val v = state.value.place?.visits?.firstOrNull { it.id == visit } ?: return
        local.update { it.copy(editing = VisitEdit(v.id, v.visitedOn, v.note), adding = null) }
    }

    override fun setEditing(edit: VisitEdit) =
        local.update { s -> s.copy(editing = edit.copy(date = minOf(edit.date, s.today), note = edit.note.take(MAX_NOTE))) }

    override fun cancelEditing() = local.update { it.copy(editing = null) }

    override fun saveEditing() {
        val e = local.value.editing ?: return
        change({ food.updateVisit(id, e.id, e.date, e.note) }) { it.copy(editing = null) }
    }

    override fun deleteVisit(visit: Long) = change({ food.deleteVisit(id, visit) }) { it.copy(editing = null) }

    override fun addPhotos(visit: Long, uris: List<String>) {
        if (uris.isEmpty()) return
        local.update { s ->
            s.copy(sending = s.sending + (visit to s.sending[visit].orEmpty() + uris.map { SendingPhoto(it) }), photoProblem = s.photoProblem - visit)
        }
        // Outlives the page: leaving it mustn't stop photos half sent.
        appScope.launch {
            for (uri in uris) {
                val result = try {
                    food.addPhoto(id, visit, photos.read(uri)) { sent -> progress(visit, uri, sent) }
                } catch (e: UnreadablePhotoException) {
                    Outcome.Refused(UNREADABLE)
                }
                local.update { s ->
                    val left = s.sending[visit].orEmpty().filter { it.uri != uri }
                    s.copy(
                        sending = if (left.isEmpty()) s.sending - visit else s.sending + (visit to left),
                        photoProblem = if (result is Outcome.Done) s.photoProblem else s.photoProblem + (visit to result),
                    )
                }
            }
        }
    }

    private fun progress(visit: Long, uri: String, sent: Float) = local.update { s ->
        s.copy(sending = s.sending + (visit to s.sending[visit].orEmpty().map { if (it.uri == uri) it.copy(sent = sent) else it }))
    }

    override fun deletePhoto(photo: Long) = change({ food.deletePhoto(id, photo) })

    override fun delete() = change({ food.delete(id) }) { it.copy(deleted = true) }

    override fun setDraft(text: String) = local.update { it.copy(draft = text.take(MAX_COMMENT)) }

    override fun postComment() {
        val text = local.value.draft.trim()
        if (text.isEmpty()) return
        change({ food.addComment(id, text) }) { it.copy(draft = "") }
    }

    override fun deleteComment(id: Long) = change({ food.deleteComment(this.id, id) })

    companion object {
        /** The words for a photo the phone can't open; the screen translates them. */
        const val UNREADABLE = "that photo can't be opened"
    }
}
