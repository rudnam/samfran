package com.rudnam.samfran.feature.shifts

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photos of the shift sheet, between the Shifts page and the activity. The
 * activity opens the camera or the photo picker (its launchers are registered
 * when it's created, so the answer arrives even if Android remade the app while
 * the camera was open), and the photo waits here until the Shifts page reads it.
 */
@Singleton
class SheetPhotos @Inject constructor() {
    enum class Source { Camera, Picker }

    private val asks = Channel<Source>(Channel.CONFLATED)

    /** What the Shifts page asked for, for the activity to open. */
    val requests: Flow<Source> = asks.receiveAsFlow()

    private val _photo = MutableStateFlow<Uri?>(null)

    /** The photo taken or picked, until [take]n. */
    val photo: StateFlow<Uri?> = _photo

    fun ask(source: Source) {
        asks.trySend(source)
    }

    /** From the activity: the camera or picker came back with a photo. */
    fun arrived(uri: Uri) {
        _photo.value = uri
    }

    /** The photo, once: null if there's none (or it's already been taken). */
    fun take(): Uri? = _photo.value.also { _photo.value = null }
}
