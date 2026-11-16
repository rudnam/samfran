package com.rudnam.samfran.core.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A date, shown as the app writes dates, that opens Material's date picker
 * when tapped. Dates after [max] can't be picked (a visit can't be in the future).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate, max: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val presses = remember { MutableInteractionSource() }
    LaunchedEffect(presses) {
        presses.interactions.collect { if (it is PressInteraction.Release) open = true }
    }
    OutlinedTextField(
        value = Dates.short(date, currentLocale, max),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = { LineIcon(SamFranIcons.calendar, null) },
        interactionSource = presses,
        modifier = modifier.fillMaxWidth(),
    )
    if (open) {
        val maxMillis = max.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            yearRange = 2000..max.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= maxMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        ) { DatePicker(picker) }
    }
}
