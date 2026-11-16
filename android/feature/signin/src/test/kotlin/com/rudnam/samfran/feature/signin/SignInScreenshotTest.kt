package com.rudnam.samfran.feature.signin

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.AuthOptions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sign-in page at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SignInScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = object : SignInActions {
        override fun setMode(mode: SignInMode) {}
        override fun setEmail(email: String) {}
        override fun setPassword(password: String) {}
        override fun submit() {}
        override fun startDemo() {}
        override fun googleUrl() = ""
    }
    private val all = AuthOptions(google = true, demo = true, reset = true)

    private fun shot(name: String, state: SignInUiState, dark: Boolean = false) {
        compose.setContent { SamFranTheme(darkTheme = dark) { SignInScreen(state, actions, onGoogle = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signin-$name.png")
    }

    @Test
    fun signIn() = shot("sign-in", SignInUiState(SignInMode.SignIn, options = all))

    @Test
    fun dark() = shot("sign-in-dark", SignInUiState(SignInMode.SignIn, email = "fran@example.com", password = "secret", options = all), dark = true)

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun japanese() = shot("sign-in-ja", SignInUiState(SignInMode.SignIn, options = all))

    @Test
    fun signUpRefused() = shot(
        "sign-up",
        SignInUiState(SignInMode.SignUp, email = "fran@example.com", options = all, notice = SignInNotice.Problem(Outcome.Refused("an account with this email already exists"))),
    )

    @Test
    fun forgot() = shot("forgot", SignInUiState(SignInMode.Forgot, email = "fran@example.com", options = all, notice = SignInNotice.ResetSent))
}
