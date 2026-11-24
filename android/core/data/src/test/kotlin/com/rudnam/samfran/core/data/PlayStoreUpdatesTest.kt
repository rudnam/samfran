package com.rudnam.samfran.core.data

import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.rudnam.samfran.core.model.AppUpdate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Play's side of the update notice, against the library's own fake Play. */
@RunWith(RobolectricTestRunner::class)
class PlayStoreUpdatesTest {
    private val play = FakeAppUpdateManager(ApplicationProvider.getApplicationContext())
    private val updates = PlayStoreUpdates(play)

    @Test
    fun aNewerBuildOnPlayIsOffered() = runTest {
        play.setUpdateAvailable(42, AppUpdateType.IMMEDIATE)
        assertEquals(AppUpdate(42, "", "", fromPlay = true), updates.available(skipped = 0))
    }

    @Test
    fun nothingNewOnPlayOffersNothing() = runTest {
        play.setUpdateNotAvailable()
        assertNull(updates.available(skipped = 0))
    }

    @Test
    fun laterHidesThatBuildButNotANewerOne() = runTest {
        play.setUpdateAvailable(42, AppUpdateType.IMMEDIATE)
        assertNull(updates.available(skipped = 42))
        play.setUpdateAvailable(43, AppUpdateType.IMMEDIATE)
        assertEquals(43, updates.available(skipped = 42)?.versionCode)
    }

    @Test
    fun anUpdateOnlyPlayCanStartInTheBackgroundIsNotOffered() = runTest {
        play.setUpdateAvailable(42, AppUpdateType.FLEXIBLE)
        assertNull(updates.available(skipped = 0))
    }
}
