package com.rudnam.samfran.feature.signin

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.samfran.core.data.GoogleReturn
import com.rudnam.samfran.core.data.GoogleReturns
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.testing.FakeAuthRepository
import com.rudnam.samfran.core.testing.MainDispatcherRule
import com.rudnam.samfran.feature.signin.navigation.SignInRoute
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignInViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val google = GoogleReturns()

    private fun viewModel(mode: String = "sign-in") = SignInViewModel(SavedStateHandle(SignInRoute(mode)), auth, google)

    @Test
    fun `signs in with email and password`() = runTest {
        val vm = viewModel()
        assertTrue(vm.state.value.options.google)
        vm.setEmail("fran@example.com")
        assertFalse(vm.state.value.ready)
        vm.setPassword("correct horse")
        vm.submit()
        assertEquals(listOf("fran@example.com"), auth.signedIn)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun `a refusal is shown in the server's words`() = runTest {
        auth.next = Outcome.Refused("email or password is incorrect")
        val vm = viewModel()
        vm.setEmail("fran@example.com")
        vm.setPassword("nope")
        vm.submit()
        assertEquals(SignInNotice.Problem(Outcome.Refused("email or password is incorrect")), vm.state.value.notice)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `leaving a demo opens on creating an account, and a forgotten password sends the link`() = runTest {
        val vm = viewModel("sign-up")
        assertEquals(SignInMode.SignUp, vm.state.value.mode)
        vm.setMode(SignInMode.Forgot)
        vm.setEmail("fran@example.com")
        vm.submit()
        assertEquals(SignInNotice.ResetSent, vm.state.value.notice)
    }

    @Test
    fun `google coming back finishes sign-in, or says why not`() = runTest {
        val vm = viewModel()
        google.send(GoogleReturn(code = null, error = "cancelled"))
        assertEquals(SignInNotice.Google("cancelled"), vm.state.value.notice)
        google.send(GoogleReturn(code = "abc", error = null))
        assertEquals(null, vm.state.value.notice)
    }
}
