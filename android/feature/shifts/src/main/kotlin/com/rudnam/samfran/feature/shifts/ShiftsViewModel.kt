package com.rudnam.samfran.feature.shifts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import com.rudnam.samfran.core.model.ReadProgress
import com.rudnam.samfran.core.model.ShiftDay
import com.rudnam.samfran.core.model.ShiftMonth
import com.rudnam.samfran.feature.shifts.navigation.ShiftsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

data class ShiftsUiState(
    /** The month asked for; null for the current one (the server picks it). */
    val asked: YearMonth? = null,
    val data: ShiftMonth? = null,
    val next: ShiftDay? = null,
    val asList: Boolean = false,
    /** Everyone there during a shift, not only those on the same one. */
    val everyone: Boolean = false,
    /** The day tapped on the calendar. */
    val picked: LocalDate? = null,
    /** A photo being read: how far it's got; null when none is. */
    val progress: ReadProgress? = null,
    val problem: Outcome<Any>? = null,
    val today: LocalDate = LocalDate.now(),
) {
    /** The month shown: as answered, else as asked. */
    val shown: YearMonth? get() = data?.month ?: asked

    /** Photos can be read here, once the name on the sheet is known. */
    val canRead: Boolean get() = data?.settings?.reader == true
    val needsName: Boolean get() = data != null && data.settings.nameOnSheet.isBlank()

    val reading: Boolean get() = progress != null
}

interface ShiftsActions {
    fun showMonth(month: YearMonth)
    fun setList(list: Boolean)
    fun setEveryone(on: Boolean)
    fun pick(date: LocalDate)
    /** A photo of the sheet (from the camera or the picker): read, then checked in the editor. */
    fun readPhoto(uri: Uri)
    fun refresh()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShiftsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val shifts: ShiftsRepository,
    private val preferences: UserPreferencesRepository,
    @ApplicationContext private val context: Context,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val photos: SheetPhotos,
) : ViewModel(), ShiftsActions {
    private val today = LocalDate.now(clock)
    private val local = MutableStateFlow(
        ShiftsUiState(asked = savedState.toRoute<ShiftsRoute>().month?.let(YearMonth::parse), today = today, picked = today),
    )

    /** The photo just read: the editor opens on its month. */
    val readMonth = MutableStateFlow<YearMonth?>(null)

    /**
     * The next shift as the phone already has it (this month, else next), so
     * its card is there when the page opens: arriving later, after the server
     * answers, it pushed the page down under a finger about to tap.
     */
    private val savedNext: Flow<ShiftDay?> = shifts.month(null).flatMapLatest { current ->
        val now = clock.instant()
        fun upcoming(m: ShiftMonth?) = m?.days?.firstOrNull { d -> d.end?.toInstant()?.isAfter(now) == true }
        when {
            current == null -> flowOf(null)
            upcoming(current) != null -> flowOf(upcoming(current))
            else -> shifts.month(current.month.plusMonths(1)).map(::upcoming)
        }
    }

    val state: StateFlow<ShiftsUiState> = combine(
        local,
        local.map { it.asked }.flatMapLatest { shifts.month(it) },
        preferences.preferences,
        savedNext,
    ) { s, data, prefs, next -> s.copy(data = data, asList = prefs.shiftsAsList, everyone = prefs.shiftsEveryone, next = next ?: s.next) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        refresh()
        // Asks the server too (and keeps what it says, for savedNext): the answer if the phone has none.
        viewModelScope.launch { local.update { it.copy(next = shifts.nextShift(clock.instant())) } }
        // A photo from the camera or the picker (through the activity), even one that came back while this page was gone.
        viewModelScope.launch { photos.photo.filterNotNull().collect { photos.take()?.let(::readPhoto) } }
    }

    /** Opens the camera or the photo picker; the photo comes back through [SheetPhotos]. */
    fun askPhoto(source: SheetPhotos.Source) = photos.ask(source)

    override fun refresh() {
        val asked = local.value.asked
        viewModelScope.launch {
            val r = shifts.refresh(asked)
            local.update { s -> if (s.asked == asked) s.copy(problem = r.takeIf { it !is Outcome.Done }) else s }
        }
    }

    override fun showMonth(month: YearMonth) {
        local.update { it.copy(asked = month, data = null, problem = null, picked = if (YearMonth.from(it.today) == month) it.today else null) }
        refresh()
    }

    override fun setList(list: Boolean) {
        viewModelScope.launch { preferences.setShiftsAsList(list) }
    }

    override fun setEveryone(on: Boolean) {
        viewModelScope.launch { preferences.setShiftsEveryone(on) }
    }

    override fun pick(date: LocalDate) = local.update { it.copy(picked = date) }

    override fun readPhoto(uri: Uri) {
        if (local.value.reading) return
        local.update { it.copy(progress = ReadProgress.Sending, problem = null) }
        viewModelScope.launch {
            val jpeg = withContext(io) { runCatching { shrink(uri) }.getOrNull() }
            val result: Outcome<ShiftMonth> = if (jpeg == null) {
                Outcome.Refused(PHOTO_UNREADABLE)
            } else {
                shifts.readPhoto(jpeg) { p -> local.update { s -> if (s.progress != null) s.copy(progress = p) else s } }
            }
            local.update { it.copy(progress = null, problem = result.takeIf { r -> r !is Outcome.Done }) }
            if (result is Outcome.Done) readMonth.value = result.value.month
        }
    }

    fun readOpened() {
        readMonth.value = null
    }

    /**
     * Shrinks a photo before it's sent (phones take 10+ MB pictures): longest
     * side 2400 px, JPEG. Plenty to read a printed sheet (as the website does).
     */
    private fun shrink(uri: Uri): ByteArray {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
            val longest = max(decoded.width, decoded.height)
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
            } else {
                decoded
            }
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    companion object {
        private const val MAX_SIDE = 2400

        /** Said when the photo itself can't be opened (shown as the app's own words). */
        const val PHOTO_UNREADABLE = "photo-unreadable"
    }
}
