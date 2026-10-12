package com.n8rtz.winetracker.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Converts a locally displayed timestamp to the UTC-midnight value expected by Material DatePicker. */
internal fun timestampToDatePickerMillis(timestamp: Long): Long {
    val localDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/** Converts Material DatePicker's UTC-midnight date back to local midnight for storage/display. */
internal fun datePickerMillisToTimestamp(datePickerMillis: Long): Long {
    val selectedDate = Instant.ofEpochMilli(datePickerMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
