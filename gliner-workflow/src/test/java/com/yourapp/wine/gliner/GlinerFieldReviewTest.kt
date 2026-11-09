package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlinerFieldReviewTest {
    @Test fun summaryExcludesFailuresExactCorpusAndIncompleteReviews() {
        val keys=listOf("producer","wine_name")
        val correct=keys.associateWith { GlinerFieldReview(confirmed=true) }
        val corrected=mapOf("producer" to GlinerFieldReview(corrected="Actual estate"),"wine_name" to GlinerFieldReview(expectedBlank=true))
        val partial=mapOf("producer" to GlinerFieldReview(confirmed=true))
        val summary=summarizeGlinerReviews(listOf(
            GlinerReviewCase(true,false,correct), GlinerReviewCase(true,false,corrected),
            GlinerReviewCase(true,false,partial), GlinerReviewCase(true,true,correct),
            GlinerReviewCase(false,false,correct)),keys)
        assertEquals(3,summary.processed)
        assertEquals(2,summary.fullyReviewed)
        assertEquals(1,summary.allConfirmed)
        assertEquals(5,summary.reviewedFields)
        assertEquals(3,summary.confirmedFields)
        assertEquals(1,summary.corpusMatches)
    }

    @Test fun emptyInputRemainsUnreviewedUntilAnExplicitDecision() {
        val unset=GlinerFieldReview()
        assertFalse(unset.reviewed)
        assertEquals("unreviewed",unset.verdict)
        assertFalse(GlinerFieldReview(corrected="  ").reviewed)
        assertEquals("correct",GlinerFieldReview(confirmed=true).verdict)
        assertEquals("incorrect",GlinerFieldReview(corrected="Correct value").verdict)
        assertEquals("incorrect",GlinerFieldReview(expectedBlank=true).verdict)
        assertTrue(GlinerFieldReview(expectedBlank=true).reviewed)
    }
}
