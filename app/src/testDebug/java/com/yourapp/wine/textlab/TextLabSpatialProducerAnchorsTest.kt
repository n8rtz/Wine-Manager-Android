package com.yourapp.wine.textlab

import android.graphics.RectF
import com.yourapp.wine.merge.SpatialPhraseMerger
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLabSpatialProducerAnchorsTest {
    private fun token(text:String,engine:String="paddle-v6-medium"):TokenRecord = TokenRecord(
        textRaw=text,
        textNorm=TextNorm.normalize(text),
        score=.91f,
        bboxOrig=RectF(0f,0f,10f,10f),
        bboxRect=RectF(0f,0f,10f,10f),
        polyOrig=emptyList(),
        engineId=engine,
        variantId="original",
    )

    @Test
    fun `producer anchor keeps only source observations spelling the selected producer`() {
        val producerWord=token("CHATEAU")
        val producerTail=token("D'AGASSAO")
        val appellation=token("2014 HAUT-MEDOC")
        val original=listOf(producerWord,producerTail,appellation)
        val proposal=TextLabSpatialPhrases.Proposal(
            engineId="paddle-v6-medium",
            variantId="original",
            phrase=SpatialPhraseMerger.Phrase(
                mergedText="CHATEAU D'AGASSAO 2014 HAUT-MEDOC",
                boundingBoxes=emptyList(),
                sourceIndices=listOf(0,1,2),
            ),
            sources=original,
        )

        val anchors=TextLabSpatialPreview.producerAnchorSpans("CHATEAU D'AGASSAO",original,listOf(proposal))
        assertEquals(1,anchors.size)
        assertEquals("paddle-v6-medium",anchors.single().engine)
        assertEquals(setOf(0,1),anchors.single().observationIds)
        assertTrue(!anchors.single().scoreIsEstimated)
    }

    @Test
    fun `producer anchor requires every producer word and original observations`() {
        val first=token("D'AGASSAO")
        val second=token("CHATEAU")
        val original=listOf(first,second)
        val proposal=TextLabSpatialPhrases.Proposal(
            engineId="paddle-v6-medium",
            variantId="original",
            phrase=SpatialPhraseMerger.Phrase("D'AGASSAO CHATEAU",emptyList(),listOf(0,1)),
            sources=original,
        )
        val reordered=TextLabSpatialPreview.producerAnchorSpans("CHATEAU D'AGASSAO",original,listOf(proposal))
        assertEquals(setOf(0,1),reordered.single().observationIds)

        val missingWord=proposal.copy(
            phrase=SpatialPhraseMerger.Phrase("CHATEAU",emptyList(),listOf(1)),
            sources=listOf(second),
        )
        assertTrue(TextLabSpatialPreview.producerAnchorSpans("CHATEAU D'AGASSAO",original,listOf(missingWord)).isEmpty())

        val copied=token("CHATEAU")
        val nonOriginal=proposal.copy(
            phrase=SpatialPhraseMerger.Phrase("CHATEAU",emptyList(),listOf(0)),
            sources=listOf(copied),
        )
        assertTrue(TextLabSpatialPreview.producerAnchorSpans("CHATEAU",original,listOf(nonOriginal)).isEmpty())
    }
}
