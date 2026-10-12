package com.n8rtz.winetracker.data.local

import com.n8rtz.winetracker.ui.components.datePickerMillisToTimestamp
import com.n8rtz.winetracker.ui.components.timestampToDatePickerMillis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class VintageAndDatePickerUtilsTest {
    @Test
    fun normalizesNonVintageAliasesWithoutChangingYearVintages() {
        assertEquals("NV", normalizeVintage(null))
        assertEquals("NV", normalizeVintage(""))
        assertEquals("NV", normalizeVintage("(NV)"))
        assertEquals("NV", normalizeVintage("nv"))
        assertEquals("2024", normalizeVintage(" 2024 "))
    }

    @Test
    fun datePickerRoundTripKeepsLocalDateAcrossDaylightSavingBoundary() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val selectedDate = LocalDate.of(2026, 3, 8)
            val localMidnight = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val pickerMillis = timestampToDatePickerMillis(localMidnight)
            assertEquals(selectedDate, Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate())
            assertEquals(localMidnight, datePickerMillisToTimestamp(pickerMillis))
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }
}
