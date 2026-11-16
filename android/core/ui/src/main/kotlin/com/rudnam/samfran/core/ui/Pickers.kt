package com.rudnam.samfran.core.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme

/** One choice of a picker: its value, and its label. */
data class Choice<T>(val value: T, val label: String)

/**
 * A small menu of choices, as the website's selects (language, appearance,
 * how far before the stop): the current label, and a menu when tapped.
 */
@Composable
fun <T> ChoicePicker(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier) {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
            Text(choices.firstOrNull { it.value == selected }?.label.orEmpty(), style = MaterialTheme.typography.labelLarge, color = SamFranTheme.colors.text)
            Spacer(Modifier.width(4.dp))
            LineIcon(SamFranIcons.chevronRight, null, size = 16.dp, tint = SamFranTheme.colors.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in choices) {
                DropdownMenuItem(
                    text = { Text(c.label) },
                    onClick = {
                        open = false
                        onSelect(c.value)
                    },
                    trailingIcon = if (c.value == selected) ({ LineIcon(SamFranIcons.check, null, size = 18.dp) }) else null,
                )
            }
        }
    }
}

/**
 * The app's language: the phone's (Auto), English or 日本語. It's Android's
 * per-app language, so it's also in the phone's settings for the app.
 */
@Composable
fun LanguagePicker(modifier: Modifier = Modifier) {
    // Changing it remakes the activity, which reads it again.
    var tag by rememberSaveable { mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')) }
    ChoicePicker(
        choices = listOf(Choice("", stringResource(R.string.auto)), Choice("en", "English"), Choice("ja", "日本語")),
        selected = tag,
        onSelect = {
            tag = it
            AppCompatDelegate.setApplicationLocales(if (it.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(it))
        },
        description = stringResource(R.string.language),
        modifier = modifier,
    )
}
