package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelScanLadderTest {
    private val parser = WineLabelParser()
    private val dictionary = WineReferenceDictionary.builtIn()

    private fun report(
        vararg texts: String,
        merged: String = texts.first()
    ): LabelScanAssessment.Report {
        val consensus = requireNotNull(
            LabelOcrConsensus.merge(texts.map { LabelOcrHypothesis(it) }, parser, dictionary)
        )
        return LabelScanAssessment.assess(
            consensus = consensus,
            perPassResults = texts.map { parser.parse(it, dictionary) },
            mergedResult = parser.parse(merged, dictionary)
        )
    }

    @Test
    fun aConfidentReadingStillGetsTheCloseUpCropButNotAThirdPass() {
        val solid = report(
            "BODEGAS OLARRA\nGRAN RESERVA\nRIOJA\n2019",
            "BODEGAS OLARRA\nGRAN RESERVA\nRIOJA\n2019"
        )

        assertFalse(solid.needsReview)
        // The crop pass always runs: it is the pass that reads calligraphic words the full frame drops.
        assertTrue(LabelScanLadder.shouldEscalate(1, solid, identityResolved = false))
        // Nothing further once both have agreed, though.
        assertFalse(LabelScanLadder.shouldEscalate(2, solid, identityResolved = false))
    }

    @Test
    fun anUnrecognisedReadingEscalatesToTheNextRecipe() {
        val weak = report("LOREM IPSUM\nDOLOR SIT", "LOREM IPSUM\nDOLOR")

        assertTrue(weak.needsReview)
        assertTrue(LabelScanLadder.shouldEscalate(1, weak, identityResolved = false))
        assertEquals(LabelCropRecipe.ENHANCED, LabelScanLadder.recipeFor(1))
        assertTrue(LabelScanLadder.shouldEscalate(2, weak, identityResolved = false))
        assertEquals(LabelCropRecipe.HIGH_CONTRAST, LabelScanLadder.recipeFor(2))
    }

    @Test
    fun theLadderIsBoundedInPasses() {
        val weak = report("LOREM IPSUM\nDOLOR SIT", "LOREM IPSUM")

        assertFalse(LabelScanLadder.shouldEscalate(LabelScanLadder.MAX_OCR_PASSES, weak, identityResolved = false))
        assertNull("no recipe once the ladder is spent", LabelScanLadder.recipeFor(LabelScanLadder.MAX_OCR_PASSES))
        assertEquals(LabelScanLadder.MAX_OCR_PASSES, LabelScanLadder.CROP_RECIPES.size + 1)
    }

    @Test
    fun contradictoryPassesEscalateToBreakTheTie() {
        val contradictory = report(
            "BODEGAS OLARRA\nRIOJA\n2019",
            "BODEGAS OLARRA\nRIOJA\n2023"
        )

        assertTrue(contradictory.contradictions.isNotEmpty())
        assertTrue(LabelScanLadder.shouldEscalate(2, contradictory, identityResolved = false))
    }

    @Test
    fun aResolvedBarcodeIdentityStopsTheLadder() {
        val weak = report("LOREM IPSUM\nDOLOR SIT", "LOREM IPSUM")

        assertTrue(weak.needsReview)
        assertTrue("without a barcode the ladder would keep trying", LabelScanLadder.shouldEscalate(1, weak, false))
        assertFalse(
            "an exact barcode identity is worth more than another OCR pass",
            LabelScanLadder.shouldEscalate(1, weak, identityResolved = true)
        )
        assertFalse(
            "and it stays stopped on later passes",
            LabelScanLadder.shouldEscalate(2, weak, identityResolved = true)
        )
    }

    @Test
    fun theSecondRecipeIsSharperAndTighterThanTheFirst() {
        val gentle = LabelScanLadder.CROP_RECIPES.first()
        val hard = LabelScanLadder.CROP_RECIPES.last()

        assertTrue("higher contrast", hard.contrast > gentle.contrast)
        assertTrue("tighter crop", hard.widthFraction < gentle.widthFraction)
        assertTrue("tighter crop", hard.heightFraction < gentle.heightFraction)
    }
}
