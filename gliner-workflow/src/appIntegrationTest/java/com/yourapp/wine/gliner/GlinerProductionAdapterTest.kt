package com.yourapp.wine.gliner

import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.ui.ScanFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GlinerProductionAdapterTest {
    @Test fun mapsAllSixFieldsAndNeverClaimsProductionValidation() {
        val fields=mapOf("producer" to "Estate", "wine_name" to "Reserve", "country" to "France",
            "region" to "Bordeaux", "subregion" to "", "vintage" to "2019")
        val states=mapOf("producer" to "GREEN", "wine_name" to "YELLOW", "country" to "GREEN",
            "region" to "YELLOW", "subregion" to "RED", "vintage" to "GREEN")
        val result=GlinerProductionAdapter.fill(fields,states,listOf("one guarded change"))
        assertEquals(FieldId.entries.toSet(),result.fields.keys)
        assertEquals("Estate",result.fields.getValue(FieldId.PRODUCER).value)
        assertEquals("2019",result.fields.getValue(FieldId.VINTAGE).value)
        assertEquals(UiState.RED,result.fields.getValue(FieldId.SUBREGION).state)
        result.fields.values.filter { it.value.isNotBlank() }.forEach { assertEquals(UiState.YELLOW,it.state) }
        assertEquals(listOf("GLiNER: one guarded change"),result.postProcessing)
    }

    @Test fun redUnknownAndBlankValuesNeverBecomeSuggestions() {
        val result=GlinerProductionAdapter.fill(mapOf("producer" to "unsafe", "wine_name" to "unknown", "vintage" to " "),
            mapOf("producer" to "RED", "vintage" to "GREEN"),emptyList())
        result.fields.values.forEach { assertEquals(UiState.RED,it.state);assertEquals("",it.value) }
    }

    @Test fun userEditsAreKeptAndSuggestionsRequireConfirmation() {
        val result=GlinerProductionAdapter.fill(mapOf("producer" to "New Estate", "wine_name" to "New Wine"),
            mapOf("producer" to "GREEN", "wine_name" to "YELLOW"),emptyList())
        val form=ScanFormState().edit(FieldId.PRODUCER,"My Estate")
            .apply(result,mapOf(FieldId.PRODUCER to "My Estate"))
        assertEquals("My Estate",form.fields.getValue(FieldId.PRODUCER).value)
        assertEquals("New Wine",form.fields.getValue(FieldId.WINE_NAME).value)
        assertFalse(form.fields.getValue(FieldId.WINE_NAME).confirmed)
    }
}

class GlinerProductionRoutingTest {
    @Test fun successfulModelDoesNotRunFallback() = kotlinx.coroutines.runBlocking {
        val original=com.yourapp.wine.capture.ScanReport.red("test")
        var called=false
        val actual=com.yourapp.wine.capture.ProductionLabelScanner.select(GlinerProductionScanner.Attempt.Success(original)) {
            called=true;com.yourapp.wine.capture.ScanReport.red("fallback")
        }
        assertFalse(called)
        assertEquals(original,actual)
    }

    @Test fun unavailableModelRunsFallbackWithReason() = kotlinx.coroutines.runBlocking {
        var reason=""
        val actual=com.yourapp.wine.capture.ProductionLabelScanner.select(GlinerProductionScanner.Attempt.Unavailable("missing")) {
            reason=it;com.yourapp.wine.capture.ScanReport.red("fallback")
        }
        assertEquals("missing",reason)
        assertEquals("fallback",actual.fill.overallHint)
    }

    @Test fun cancellationIsNotConvertedIntoSuccess() = kotlinx.coroutines.runBlocking {
        try {
            com.yourapp.wine.capture.ProductionLabelScanner.select(GlinerProductionScanner.Attempt.Unavailable("missing")) {
                throw kotlinx.coroutines.CancellationException("user cancelled")
            }
            throw AssertionError("Cancellation must propagate")
        } catch(expected:kotlinx.coroutines.CancellationException) {
            assertEquals("user cancelled",expected.message)
        }
    }
}
