package com.yourapp.wine.textlab

import android.graphics.RectF
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.FillEngine
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26],manifest=Config.NONE)
class TextLabFieldExtractorTest {
    private fun readings():List<TokenRecord> = javaClass.getResourceAsStream("/textlab/table-regression.tsv")!!.bufferedReader().useLines { lines ->
        lines.map { line ->
            val (text,coords)=line.split('\t');val v=coords.split(' ').map(String::toFloat)
            val box=RectF(v[0],v[1],v[2],v[3])
            TokenRecord(text,TextNorm.normalize(text),.5f,box,box,emptyList(),"mlkit_reference","single",true)
        }.toList()
    }
    @Test fun uploadedReadableLinesSurviveLabReportingButNotProductionMergeGate() {
        val records=readings();assertEquals(11,records.size)
        val kb=FixtureKnowledgeBase()
        // Reproduce the old adapter bug and ensure production thresholds remain intact.
        assertTrue(FillEngine(kb).fill(records,2026).fields.values.all { it.value.isBlank() })
        val result=TextLabFieldExtractor(kb).extract(records,2026)
        assertEquals("2023",result.fields.getValue(FieldId.VINTAGE).value)
        assertEquals("Spain",result.fields.getValue(FieldId.COUNTRY).value)
        assertTrue(result.fields.values.none { it.state==UiState.GREEN })
        assertTrue(result.fields.values.flatMap { it.sources }.flatMap { it.members }.all { it.score==.5f && it.scoreIsEstimated })
    }
    @Test fun emptyInputsStayEmptyAndMixedPassesAreRejected() {
        val extractor=TextLabFieldExtractor(FixtureKnowledgeBase())
        assertTrue(extractor.extract(emptyList(),2026).fields.values.all { it.value.isEmpty() && it.state==UiState.RED })
        val records=readings()
        assertThrows(IllegalArgumentException::class.java) {
            extractor.extract(records+records.first().copy(variantId="p02"),2026)
        }
    }
}
