package com.rudnam.samfran.feature.us

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.data.InviteLinkMade
import com.rudnam.samfran.core.datastore.Appearance
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.Me
import com.rudnam.samfran.core.testing.FakeActivityRepository
import com.rudnam.samfran.core.testing.FakeReminders
import com.rudnam.samfran.core.testing.FakeSpaceRepository
import com.rudnam.samfran.core.testing.FakeUserRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.core.testing.Samples
import com.rudnam.samfran.core.testing.TestPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** The Us tab at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h2000dp-xxhdpi")
class UsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { SamFranTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/us-$name.png")
    }

    private val actions = object : UsActions {
        override fun setName(name: String) {}
        override fun saveName() {}
        override fun createInviteLink() {}
        override fun cancelInviteLink() {}
        override fun uninvite(email: String) {}
        override fun showAll() {}
        override fun disconnect(token: Long) {}
        override fun setAlerts(on: Boolean) {}
        override fun setAppearance(appearance: Appearance) {}
        override fun signOut() {}
    }

    private val us = UsUiState(
        me = Me("fran@example.com"),
        space = Samples.space,
        name = "Fran",
        activity = Samples.activity,
        seenAtOpen = 56,
        tokens = FakeUserRepository().tokens,
        mcpAddress = "https://samfran.rudnam.com/mcp",
        alerts = true,
    )

    private fun screen(state: UsUiState): @Composable () -> Unit = { UsScreen(state, actions, {}, {}, {}, {}) }

    @Test
    fun us() = shot("us", content = screen(us))

    @Test
    fun usDark() = shot("us-dark", dark = true, content = screen(us))

    @Test
    @Config(qualifiers = "ja-w411dp-h2000dp-xxhdpi")
    fun usJapanese() = shot("us-ja", content = screen(us))

    @Test
    fun inviting() = shot("invite", content = screen(
        us.copy(space = Samples.spaceAlone, invite = InviteLinkMade("https://samfran.rudnam.com/join/k3x9", Instant.parse("2026-11-23T00:00:00Z"))),
    ))

    @Test
    fun invitedByEmail() = shot("invited", content = screen(us.copy(space = Samples.spaceInvited)))

    @Test
    fun demo() = shot("demo", content = screen(us.copy(me = Me("demo@example.com", demo = true))))
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `opening marks the changes seen, but they stay marked while it's open`() = runTest {
        val activity = FakeActivityRepository(seen = 52)
        val vm = UsViewModel(FakeUserRepository(), FakeSpaceRepository(), activity, FakeReminders(), TestPreferences.repository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(52L, vm.state.value.seenAtOpen)
        assertEquals(58L, activity.seenFlow.value)
    }

    @Test
    fun `your name, the invite link, and signing out`() = runTest {
        val user = FakeUserRepository()
        val space = FakeSpaceRepository(Samples.spaceAlone)
        val reminders = FakeReminders().apply { alerts.value = true }
        val vm = UsViewModel(user, space, FakeActivityRepository(), reminders, TestPreferences.repository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals("Fran", vm.state.value.name)
        vm.setName("Francis")
        vm.saveName()
        assertTrue(vm.state.value.nameSaved)
        assertEquals("Francis", space.spaceFlow.value?.members?.first()?.name)
        vm.createInviteLink()
        assertEquals("https://samfran.example/join/abc", vm.state.value.invite?.url)
        vm.signOut()
        assertTrue(user.signedOut)
        assertEquals(false, reminders.alerts.value)
    }
}
