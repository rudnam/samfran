package com.rudnam.samfran.feature.us

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.AppLock
import com.rudnam.samfran.core.model.InstalledApp
import com.rudnam.samfran.core.model.LockRequestKind
import com.rudnam.samfran.core.model.LockRequestStatus
import com.rudnam.samfran.core.model.LockSetup
import com.rudnam.samfran.core.testing.FakeAppLocks
import com.rudnam.samfran.core.testing.FakeLocksRepository
import com.rudnam.samfran.core.testing.LockSamples
import com.rudnam.samfran.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.ZoneOffset

/** Locked apps and the cover over a locked app, at phone size, into build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h2000dp-xxhdpi")
class LocksScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { SamFranTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/us-$name.png")
    }

    private val locksActions = object : LocksActions {
        override fun openUsageAccess() {}
        override fun openOverlay() {}
        override fun setAllowMinutes(request: Long, minutes: Int) {}
        override fun setReply(request: Long, reply: String) {}
        override fun answer(request: Long, allow: Boolean) {}
        override fun lock(app: InstalledApp, done: () -> Unit) {}
        override fun confirmUnlock(lock: AppLock?) {}
        override fun askUnlock() {}
        override fun cancel(request: Long) {}
        override fun setQuery(query: String) {}
    }

    private val coverActions = object : LockCoverActions {
        override fun setMinutes(minutes: Int) {}
        override fun setMessage(message: String) {}
        override fun ask() {}
        override fun takeBack() {}
        override fun askAgain() {}
        override fun confirmEmergency(show: Boolean) {}
        override fun openWithoutAsking() {}
        override fun open() {}
    }

    private val ready = LockSetup(usageAccess = true, overlay = true, notifications = true)
    private val locks = LocksUiState(page = LockSamples.page, setup = ready, now = LockSamples.now, allowMinutes = mapOf(10L to 5))

    private fun locksScreen(state: LocksUiState): @Composable () -> Unit = { LocksScreen(state, locksActions, {}, {}, {}) }

    @Test
    fun locks() = shot("locks", content = locksScreen(locks))

    @Test
    fun locksDark() = shot("locks-dark", dark = true, content = locksScreen(locks))

    @Test
    @Config(qualifiers = "ja-w411dp-h2000dp-xxhdpi")
    fun locksJapanese() = shot("locks-ja", content = locksScreen(locks))

    @Test
    fun locksSetup() = shot("locks-setup", content = locksScreen(
        LocksUiState(page = LockSamples.page.copy(locks = emptyList(), requests = emptyList()), setup = LockSetup(notifications = true), now = LockSamples.now),
    ))

    @Test
    fun locksPartnerNotCovered() = shot("locks-not-covered", content = locksScreen(locks.copy(page = LockSamples.page.copy(phones = listOf(LockSamples.samNotCovering)))))

    @Test
    fun locksUnlocking() = shot("locks-unlock", content = locksScreen(locks.copy(unlocking = LockSamples.instagram)))

    @Test
    fun lockApp() = shot("lock-app", content = { LockAppScreen(locks.copy(apps = LockSamples.apps), locksActions, {}, {}) })

    private val cover = LockCoverUiState(packageName = "com.instagram.android", label = "Instagram", lock = LockSamples.instagram, partner = "Sam", minutes = 15, message = "Mum sent photos")

    // The app's own icon, as on a phone that has it: a stand-in for Instagram's, given to Robolectric's package manager.
    private fun coverScreen(state: LockCoverUiState): @Composable () -> Unit = {
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.remember {
            val icon = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BL_TR,
                intArrayOf(0xFFF9A23B.toInt(), 0xFFE1306C.toInt(), 0xFF833AB4.toInt()),
            ).apply { cornerRadius = 22f; setSize(96, 96) }
            org.robolectric.Shadows.shadowOf(context.packageManager).setApplicationIcon("com.instagram.android", icon)
        }
        LockCover(state, coverActions) {}
    }

    @Test
    fun coverAsk() = shot("cover-ask", content = coverScreen(cover))

    @Test
    fun coverAskDark() = shot("cover-ask-dark", dark = true, content = coverScreen(cover))

    @Test
    @Config(qualifiers = "ja-w411dp-h2000dp-xxhdpi")
    fun coverAskJapanese() = shot("cover-ask-ja", content = coverScreen(cover))

    @Test
    fun coverWaiting() = shot("cover-waiting", content = coverScreen(cover.copy(request = LockSamples.franAsks)))

    @Test
    fun coverAllowed() = shot("cover-allowed", content = coverScreen(cover.copy(request = LockSamples.franAllowed.copy(label = "Instagram"))))

    @Test
    fun coverDenied() = shot("cover-denied", content = coverScreen(cover.copy(request = LockSamples.franDenied)))

    @Test
    fun coverEmergencyWait() = shot("cover-emergency-hidden", content = coverScreen(cover.copy(emergencyIn = 12)))

    @Test
    fun coverEmergency() = shot("cover-emergency", content = coverScreen(cover.copy(confirmEmergency = true)))
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocksViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = Clock.fixed(LockSamples.now, ZoneOffset.UTC)

    @Test
    fun `answering, locking and asking to unlock`() = runTest {
        val repo = FakeLocksRepository()
        val apps = FakeAppLocks()
        val vm = LocksViewModel(repo, apps, clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(1, vm.state.first { it.page != null }.page!!.waitingForMe.size)

        vm.setAllowMinutes(10, 5)
        vm.setReply(10, "one video")
        vm.answer(10, allow = true)
        val answered = vm.state.first { it.page!!.waitingForMe.isEmpty() }
        assertEquals(listOf(10L to true), repo.answers)
        assertEquals(5, answered.page!!.requests.first { it.id == 10L }.minutes)
        assertTrue(answered.replies.isEmpty())

        vm.loadApps()
        val shown = vm.state.first { it.apps != null }.shownApps!!.map { it.label }
        assertEquals(listOf("Chrome", "Maps", "Netflix", "YouTube"), shown) // Instagram and TikTok are locked already
        var done = false
        vm.lock(InstalledApp("com.netflix.mediaclient", "Netflix")) { done = true }
        vm.state.first { s -> s.page!!.mine.any { it.label == "Netflix" } }
        assertTrue(done)
        assertTrue(apps.syncs > 0)

        vm.confirmUnlock(LockSamples.instagram)
        vm.askUnlock()
        assertNull(vm.state.first { !it.busy && it.unlocking == null }.unlocking)
        assertEquals(LockRequestKind.Remove, repo.asked.last().second)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LockCoverViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = Clock.fixed(LockSamples.now, ZoneOffset.UTC)

    @Test
    fun `asking, then the answer opens the app`() = runTest {
        // No request waiting for Instagram yet.
        val repo = FakeLocksRepository(LockSamples.page.copy(requests = emptyList()))
        val vm = LockCoverViewModel(repo, clock)
        val opened = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.toOpen.collect { opened += it } }
        vm.show("com.instagram.android", "Instagram")
        assertEquals("Sam", vm.state.first { it.lock != null }.partner)

        vm.setMinutes(30)
        vm.setMessage("Mum sent photos")
        vm.ask()
        val asked = vm.state.first { it.request != null }.request!!
        assertTrue(asked.pending)
        assertEquals(Triple(1L, LockRequestKind.Open, 30), repo.asked.single())

        // Sam allows it; the next check finds out.
        repo.requestAnswer = asked.copy(status = LockRequestStatus.Allowed, openUntil = LockSamples.now.plusSeconds(1800))
        advanceTimeBy(LockCoverViewModel.POLL_MS + 100)
        assertEquals(LockRequestStatus.Allowed, vm.state.first { it.request?.pending == false }.request!!.status)
        vm.open()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("com.instagram.android"), opened)
    }

    @Test
    fun `an app that's open already isn't covered`() = runTest {
        val vm = LockCoverViewModel(FakeLocksRepository(), clock)
        val opened = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.toOpen.collect { opened += it } }
        vm.show("com.zhiliaoapp.musically", "TikTok") // open for ten more minutes
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("com.zhiliaoapp.musically"), opened)
    }

    @Test
    fun `opening without asking`() = runTest {
        val repo = FakeLocksRepository(LockSamples.page.copy(requests = emptyList()))
        val vm = LockCoverViewModel(repo, clock)
        val opened = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.toOpen.collect { opened += it } }
        vm.show("com.instagram.android", "Instagram")
        vm.state.first { it.lock != null }
        vm.setMinutes(5)
        // Not for the first 30 seconds.
        vm.openWithoutAsking()
        vm.confirmEmergency(true)
        assertEquals(false, vm.state.value.confirmEmergency)
        assertTrue(opened.isEmpty())
        advanceTimeBy(LockCoverViewModel.EMERGENCY_WAIT_SECONDS * 1_000L + 100)
        assertEquals(0, vm.state.value.emergencyIn)
        vm.confirmEmergency(true)
        assertTrue(vm.state.value.confirmEmergency)
        vm.openWithoutAsking()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("com.instagram.android"), opened)
        assertTrue(repo.pageFlow.value!!.lockOf("com.instagram.android")!!.isOpen(LockSamples.now))
    }
}
