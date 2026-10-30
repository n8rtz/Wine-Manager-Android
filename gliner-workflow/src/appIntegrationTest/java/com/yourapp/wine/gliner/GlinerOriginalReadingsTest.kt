package com.yourapp.wine.gliner

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GlinerOriginalReadingsTest {
    @Test fun originalEngineReadingsRemainSeparateAndDoNotLoseWords() {
        val box=RectF(0.1f,0.1f,0.8f,0.2f)
        val readings=listOf(
            GlinerFusion.Reading("mlkit","Chateau Ambe",box,emptyList(),0.8f,"V0",false),
            GlinerFusion.Reading("paddle-v6-medium","Chateau Ambe Tour Pourret",box,emptyList(),0.7f,"V0",true),
        )
        val records=GlinerLaneRecords.originalRecords(readings)
        assertEquals(listOf("Chateau Ambe","Chateau Ambe Tour Pourret"),records.map { it.textRaw })
        assertEquals(listOf("mlkit","paddle-v6-medium"),records.map { it.engineId })
        assertFalse(records[0].scoreIsEstimated)
        assertTrue(records[1].scoreIsEstimated)
        assertEquals(listOf("V0","V0"),records.map { it.variantId })
        assertEquals(0.8f,records[0].score,0.0001f)
        assertEquals(0.7f,records[1].score,0.0001f)
    }
}
