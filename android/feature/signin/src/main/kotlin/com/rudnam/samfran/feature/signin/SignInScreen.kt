package com.rudnam.samfran.feature.signin

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.samfran.core.designsystem.component.ErrorText
import com.rudnam.samfran.core.designsystem.component.LinkButton
import com.rudnam.samfran.core.designsystem.component.Notice
import com.rudnam.samfran.core.designsystem.component.PrimaryButton
import com.rudnam.samfran.core.designsystem.component.SamFranCard
import com.rudnam.samfran.core.designsystem.component.SecondaryButton
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.ui.LanguagePicker
import com.rudnam.samfran.core.ui.problem

@Composable
internal fun SignInDestination(viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SignInScreen(
        state = state,
        actions = viewModel,
        onGoogle = {
            // Google refuses sign-in inside an app's own views: the phone's browser, in a Custom Tab.
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(viewModel.googleUrl()))
        },
        languagePicker = { LanguagePicker() },
    )
}

/** The sign-in page (web/src/components/AuthForm.tsx), drawn from [state]. */
@Composable
fun SignInScreen(
    state: SignInUiState,
    actions: SignInActions,
    onGoogle: () -> Unit,
    languagePicker: @Composable () -> Unit = {},
) {
    val colors = SamFranTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().widthIn(max = 440.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SamFran", Modifier.weight(1f), color = colors.accent, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
            languagePicker()
        }
        Image(painterResource(R.drawable.signin_pair), null, Modifier.padding(top = 8.dp, bottom = 16.dp).size(120.dp))
        if (state.startingDemo) {
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = colors.accent)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.setting_up_demo), color = colors.muted)
            return@Column
        }
        SamFranCard(Modifier.fillMaxWidth().widthIn(max = 440.dp), spacing = 14) {
            when (state.mode) {
                SignInMode.Forgot -> ForgotForm(state, actions)
                else -> SignInForm(state, actions, onGoogle)
            }
        }
        if (state.mode != SignInMode.Forgot && state.options.demo) {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.just_looking), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                Spacer(Modifier.width(12.dp))
                SecondaryButton(stringResource(R.string.try_demo), actions::startDemo, enabled = !state.busy)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SignInForm(state: SignInUiState, actions: SignInActions, onGoogle: () -> Unit) {
    val signingIn = state.mode == SignInMode.SignIn
    Text(
        stringResource(if (signingIn) R.string.sign_in else R.string.create_an_account),
        Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
    NoticeOf(state.notice)
    if (state.options.google) {
        SecondaryButton(stringResource(R.string.continue_with_google), onGoogle, Modifier.fillMaxWidth(), enabled = !state.busy)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = SamFranTheme.colors.border)
            Text(stringResource(R.string.or), Modifier.padding(horizontal = 12.dp), color = SamFranTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.weight(1f), color = SamFranTheme.colors.border)
        }
    }
    EmailField(state, actions)
    OutlinedTextField(
        value = state.password,
        onValueChange = actions::setPassword,
        label = { Text(stringResource(R.string.password)) },
        supportingText = if (!signingIn) ({ Text(stringResource(R.string.password_rule)) }) else null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { actions.submit() }),
        modifier = Modifier.fillMaxWidth().semantics { contentType = if (signingIn) ContentType.Password else ContentType.NewPassword },
    )
    PrimaryButton(
        stringResource(if (signingIn) R.string.sign_in else R.string.create_account),
        actions::submit,
        Modifier.fillMaxWidth(),
        enabled = state.ready && !state.busy,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (signingIn && state.options.reset) LinkButton(stringResource(R.string.forgot_password), { actions.setMode(SignInMode.Forgot) })
        LinkButton(
            stringResource(if (signingIn) R.string.new_here else R.string.have_account),
            { actions.setMode(if (signingIn) SignInMode.SignUp else SignInMode.SignIn) },
        )
    }
}

@Composable
private fun ForgotForm(state: SignInUiState, actions: SignInActions) {
    Text(stringResource(R.string.reset_password), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    if (state.notice == null) Text(stringResource(R.string.reset_intro), color = SamFranTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
    NoticeOf(state.notice)
    EmailField(state, actions, last = true)
    val sent = state.notice == SignInNotice.ResetSent
    PrimaryButton(stringResource(if (sent) R.string.send_again else R.string.send_link), actions::submit, Modifier.fillMaxWidth(), enabled = state.ready && !state.busy)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LinkButton(stringResource(R.string.back_to_sign_in), { actions.setMode(SignInMode.SignIn) })
    }
}

@Composable
private fun EmailField(state: SignInUiState, actions: SignInActions, last: Boolean = false) {
    OutlinedTextField(
        value = state.email,
        onValueChange = actions::setEmail,
        label = { Text(stringResource(R.string.email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = if (last) ImeAction.Go else ImeAction.Next),
        keyboardActions = KeyboardActions(onGo = { actions.submit() }),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
    )
}

@Composable
private fun NoticeOf(notice: SignInNotice?) {
    when (notice) {
        null -> {}
        SignInNotice.ResetSent -> Notice { Text(stringResource(R.string.reset_sent), style = MaterialTheme.typography.bodyMedium) }
        is SignInNotice.Google -> ErrorText(stringResource(googleReason(notice.reason)))
        is SignInNotice.Problem -> notice.outcome.problem()?.let { ErrorText(it) }
    }
}

/** What went wrong with Google sign-in, by the server's reason code (web/src/notice.ts). */
private fun googleReason(reason: String) = when (reason) {
    "cancelled" -> R.string.google_cancelled
    "expired" -> R.string.google_expired
    "closed" -> R.string.google_closed
    "unverified" -> R.string.google_unverified
    "linked" -> R.string.google_linked
    "busy" -> R.string.google_busy
    else -> R.string.google_failed
}
