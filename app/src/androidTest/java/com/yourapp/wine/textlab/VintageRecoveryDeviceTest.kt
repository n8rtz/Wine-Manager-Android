package com.yourapp.wine.textlab

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid
import com.yourapp.wine.textlab.evidence.EvidenceExtractor
import com.yourapp.wine.textlab.evidence.EvidenceGraphBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Vintage OCR recovery stays experimental and cannot replace a populated saved baseline. */
@RunWith(AndroidJUnit4::class)
class VintageRecoveryDeviceTest {
    private fun token(
        text:String,
        engine:String="mlkit",
        left:Float=.1f,
        top:Float=.4f,
        right:Float=.9f,
        bottom:Float=.5f,
    )=TokenRecord(text,TextNorm.normalize(text),.95f,RectF(left,top,right,bottom),
        RectF(left,top,right,bottom),emptyList(),engine,"V0",true)

    @Test fun confusedAndSpacedYearsNormalizeOnlyWithinPlausibleRange() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("vintageRecovery")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            val extractor=EvidenceExtractor(kb)
            for((raw,expected) in listOf("I998" to "1998","l996" to "1996","2O20" to "2020","2 0 2 0" to "2020","I 9 9 8" to "1998")) {
                val result=extractor.extract(listOf(token(raw)),2026)
                assertEquals(expected,result.fill.fields.getValue(FieldId.VINTAGE).value)
            }
            assertEquals("",extractor.extract(listOf(token("2030")),2026).fill.fields.getValue(FieldId.VINTAGE).value)
            assertEquals("",extractor.extract(listOf(token("1899")),2026).fill.fields.getValue(FieldId.VINTAGE).value)
        } finally { kb.close() }
    }

    @Test fun onlySamePassSymmetricFragmentsFormASplitVintage() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("vintageRecovery")=="true")
        val left=token("20",left=.36f,right=.44f)
        val right=token("16",left=.56f,right=.64f)
        val labelCenter=token("LABEL",left=.2f,top=.2f,right=.8f,bottom=.3f)
        val graph=EvidenceGraphBuilder.build(listOf(left,right,labelCenter))
        val split=graph.phrases.single { it.construction=="split crest vintage" }
        assertEquals("2016",split.text)
        assertEquals(setOf(0,1),split.observations)

        val crossEngine=EvidenceGraphBuilder.build(listOf(left,token("16",engine="paddle-v6-medium",left=.56f,right=.64f)))
        assertTrue(crossEngine.phrases.none { it.construction=="split crest vintage" })
        val skewed=EvidenceGraphBuilder.build(listOf(left,token("16",left=.82f,right=.90f),
            token("LABEL",left=.2f,top=.2f,right=.8f,bottom=.3f)))
        assertTrue(skewed.phrases.none { it.construction=="split crest vintage" })
    }

    @Test fun hybridFillsOnlyAValidatedBlankVintageAndPreservesEveryPopulatedBaseline() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("vintageRecovery")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            val hybrid=EvidenceAnchorHybrid(kb)
            val blank=FieldId.entries.associateWith { "" }
            fun revised(vintage:String)=blank+(FieldId.VINTAGE to vintage)
            val config=EvidenceAnchorHybrid.Config(true)
            assertEquals("2020",hybrid.combine(blank,revised("2020"),emptyList(),config,2026)[FieldId.VINTAGE])
            assertEquals("2018",hybrid.combine(blank+(FieldId.VINTAGE to "2018"),revised("2020"),emptyList(),config,2026)[FieldId.VINTAGE])
            assertEquals("",hybrid.combine(blank,revised("I998"),emptyList(),config,2026)[FieldId.VINTAGE])
            assertEquals("",hybrid.combine(blank,revised("2027"),emptyList(),config,2026)[FieldId.VINTAGE])
        } finally { kb.close() }
    }
}
