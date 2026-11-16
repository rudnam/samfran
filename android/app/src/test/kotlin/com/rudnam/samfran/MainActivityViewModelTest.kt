package com.rudnam.samfran

import com.rudnam.samfran.core.datastore.SessionStore
import com.rudnam.samfran.core.model.AppUpdate
import com.rudnam.samfran.core.testing.FakeActivityRepository
import com.rudnam.samfran.core.testing.FakeReminders
import com.rudnam.samfran.core.testing.FakeSpaceRepository
import com.rudnam.samfran.core.testing.FakeUpdateRepository
import com.rudnam.samfran.core.testing.FakeUserRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.core.testing.TestPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class MainActivityViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val newer = AppUpdate(11, "2.0.11", "https://example.com/download/samfran.apk")
    private val updates = FakeUpdateRepository(newer)
    private val clock = MovableClock()

    /** The stored session is read in the background, so it isn't known yet when the activity first resumes. */
    private val token = MutableStateFlow<String?>(null)
    private val session = object : SessionStore {
        override val token = this@MainActivityViewModelTest.token
        override fun currentToken() = token.value
        override fun save(token: String) { this.token.value = token }
        override fun clear() { token.value = null }
        override fun keepGoogleVerifier(verifier: String) {}
        override fun takeGoogleVerifier(): String? = null
    }

    private fun viewModel() = MainActivityViewModel(
        session, TestPreferences.repository(), FakeUserRepository(), updates, FakeReminders(),
        FakeActivityRepository(), FakeSpaceRepository(), clock,
    )

    @Test
    fun `a newer build is offered at launch, even though the first resume comes before the session is read`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(main.dispatcher) { vm.shell.collect {} }
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        vm.resumed() // too early: nobody is known to be signed in yet
        assertNull(vm.shell.value.update)

        token.value = "session"
        assertEquals(newer, vm.shell.value.update)
        assertEquals(1, updates.checks)
    }

    @Test
    fun `coming back checks again at most hourly`() = runTest {
        token.value = "session"
        val vm = viewModel()
        backgroundScope.launch(main.dispatcher) { vm.shell.collect {} }
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        vm.resumed()
        assertEquals(1, updates.checks) // the launch check counts for this resume too

        clock.advance(30 * 60 * 1000)
        vm.resumed()
        assertEquals(1, updates.checks)

        clock.advance(31 * 60 * 1000)
        vm.resumed()
        assertEquals(2, updates.checks)
    }

    private class MovableClock(private var millis: Long = 1_763_000_000_000) : Clock() {
        fun advance(ms: Long) { millis += ms }
        override fun millis() = millis
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
