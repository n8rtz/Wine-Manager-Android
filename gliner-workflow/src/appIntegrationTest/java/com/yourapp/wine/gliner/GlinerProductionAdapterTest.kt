package com.yourapp.wine.gliner

import com.yourapp.wine.capture.ProductionLabelScanner
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.ui.ScanFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GlinerProductionAdapterTest {
    @Test fun mapsAllSixFieldsUsingTheWorkflowsOwnReviewStates() {
        val fields=mapOf("producer" to "Estate", "wine_name" to "Reserve", "country" to "France",
            "region" to "Bordeaux", "subregion" to "", "vintage" to "2019")
        val states=mapOf("producer" to "GREEN", "wine_name" to "YELLOW", "country" to "GREEN",
            "region" to "YELLOW", "subregion" to "RED", "vintage" to "GREEN")
        val result=GlinerProductionAdapter.fill(fields,states,listOf("one guarded change"))
        assertEquals(FieldId.entries.toSet(),result.fields.keys)
        assertEquals("Estate",result.fields.getValue(FieldId.PRODUCER).value)
        assertEquals("2019",result.fields.getValue(FieldId.VINTAGE).value)
        assertEquals(UiState.GREEN,result.fields.getValue(FieldId.PRODUCER).state)
        assertEquals(UiState.YELLOW,result.fields.getValue(FieldId.WINE_NAME).state)
        assertEquals(UiState.RED,result.fields.getValue(FieldId.SUBREGION).state)
        assertEquals(listOf("one guarded change"),result.postProcessing)
        assertNull(result.overallHint)
    }

    @Test fun redUnknownAndBlankValuesNeverBecomeSuggestions() {
        val result=GlinerProductionAdapter.fill(mapOf("producer" to "unsafe", "wine_name" to "unknown", "vintage" to " "),
            mapOf("producer" to "RED", "vintage" to "GREEN"),emptyList())
        result.fields.values.forEach { assertEquals(UiState.RED,it.state);assertEquals("",it.value) }
    }

    @Test fun userEditsAreKeptAndYellowStillRequiresConfirmation() {
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
    @Test fun successfulModelReturnsOriginalResult() {
        val original=ScanReport.red("test")
        val actual=ProductionLabelScanner.report(GlinerProductionScanner.Attempt.Success(original),10,ScanInputSource.FILE,false)
        assertEquals(original,actual)
    }

    @Test fun missingModelFailsRatherThanSilentlyUsingAnotherScanner() {
        val actual=ProductionLabelScanner.report(GlinerProductionScanner.Attempt.Unavailable("model missing"),10,ScanInputSource.FILE,false)
        assertEquals("Could not read the label — retake or enter details manually",actual.fill.overallHint)
        assertEquals("gliner",actual.diagnostics.pipeline)
        assertEquals("model missing",actual.diagnostics.engines.single().reason)
        actual.fill.fields.values.forEach { assertEquals(UiState.RED,it.state) }
    }

    @Test fun timeoutIsReportedWithoutReplacingThePipeline() {
        val actual=ProductionLabelScanner.report(GlinerProductionScanner.Attempt.Unavailable("timed out"),90_000,ScanInputSource.FILE,true)
        assertEquals(true,actual.deadlineCancelled)
        assertEquals("gliner",actual.diagnostics.pipeline)
    }
}
