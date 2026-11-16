package com.rudnam.samfran.feature.shifts

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import androidx.test.core.app.ApplicationProvider
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.testing.FakeShiftsRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.core.testing.TestPreferences
import com.rudnam.samfran.feature.shifts.navigation.ShiftsRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

/** A photo of the sheet, from the camera or the picker through the activity, is read. */
@RunWith(RobolectricTestRunner::class)
class ReadPhotoFlowTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val photos = SheetPhotos()

    private fun viewModel() = ShiftsViewModel(
        SavedStateHandle(ShiftsRoute()),
        FakeShiftsRepository(),
        TestPreferences.repository(),
        ApplicationProvider.getApplicationContext(),
        Dispatchers.Unconfined,
        Clock.fixed(ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")).toInstant(), ZoneId.of("Asia/Tokyo")),
        photos,
    )

    // The photo can't be opened in a test, so each read stops at once with
    // "photo-unreadable": that's how the tests see it was tried.

    @Test
    fun `asking for a photo opens the camera or the picker`() = runTest {
        viewModel().askPhoto(SheetPhotos.Source.Picker)
        assertEquals(SheetPhotos.Source.Picker, photos.requests.first())
    }

    @Test
    fun `a photo that comes back is read, once`() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} } // as the page does
        photos.arrived(Uri.parse("content://media/picker/0/1"))
        assertEquals(ShiftsViewModel.PHOTO_UNREADABLE, (vm.state.value.problem as? Outcome.Refused)?.message)
        assertNull(photos.photo.value)
    }

    @Test
    fun `a photo that came back while the page was gone is read when it opens`() = runTest(UnconfinedTestDispatcher()) {
        // Android remade the app while the camera was open: the activity has the photo before any Shifts page exists.
        photos.arrived(Uri.parse("content://com.rudnam.samfran.files/photos/sheet.jpg"))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} } // as the page does
        assertNotNull(vm.state.value.problem)
        assertNull(photos.photo.value)
    }
}
