package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole-scan simulations: these drive the same session object `ScannerManager` drives, so the
 * escalation, merging and trust decisions are covered without a device or ML Kit.
 */
class LabelScanSessionTest {
    private fun session() = LabelScanSession(WineReferenceDictionary.builtIn())

    @Test
    fun rescuesTheCalligraphicWordFromTheCloseUpCrop() {
        val session = session()
        // Full frame: printed lines only — the decorative cuvée is unreadable at this size.
        session.addPass(LabelOcrHypothesis("BLACKSTONE\nWINERY\nCALIFORNIA\nALC 13.5% BY VOL"))

        assertEquals(LabelCropRecipe.ENHANCED, session.nextRecipe(identityResolved = false))
        session.addPass(LabelOcrHypothesis("BLACKSTONE\nWINERY\nMerlot\nCALIFORNIA"))

        val outcome = session.finish(barcode = null, identity = null)

        assertEquals("Blackstone Winery", outcome.wine.maker)
        assertEquals("Merlot", outcome.wine.name)
        assertEquals("California", outcome.wine.region)
        assertEquals("Red", outcome.wine.type)
        assertEquals(2, outcome.passesRun)
        assertFalse(outcome.wine.needsReview)
        assertNull("nothing left to try", session.nextRecipe(identityResolved = false))
    }

    @Test
    fun aBlankPhotoProducesNoSuggestionsAndAsksForReview() {
        val session = session()
        session.addPass(LabelOcrHypothesis("   "))

        assertTrue("a blank frame still gets the crop pass", session.nextRecipe(identityResolved = false) != null)
        session.addPass(LabelOcrHypothesis(""))

        val outcome = session.finish(barcode = null, identity = null)

        assertFalse(outcome.wine.hasSuggestions)
        assertTrue(outcome.wine.needsReview)
        assertFalse("no barcode means the ladder is finished", outcome.labelled)
    }

    @Test
    fun anUnreadableLabelEscalatesToTheHardContrastCrop() {
        val session = session()
        // Glare-washed first two passes: no wine word and no metadata recognised at all.
        session.addPass(LabelOcrHypothesis("OZV 94 POINTS\nWINE ENTHUSIAST"))
        session.addPass(LabelOcrHypothesis("150 YEARS\nESTATE GROWN"))

        // Nothing recognisable after two passes is worth one hard-contrast attempt.
        assertEquals(LabelCropRecipe.HIGH_CONTRAST, session.nextRecipe(identityResolved = false))
        session.addPass(LabelOcrHypothesis("OLD VINE\nZINFANDEL\nLODI\nV. 2022"))

        val outcome = session.finish(barcode = null, identity = null)

        assertEquals("Zinfandel", outcome.wine.name)
        assertEquals("2022", outcome.wine.vintage)
        assertEquals("Lodi", outcome.wine.subregion)
        assertEquals(3, outcome.passesRun)
        assertNull("the ladder is capped", session.nextRecipe(identityResolved = false))
    }

    @Test
    fun aBarcodeIdentityNamesTheBottleWithoutMoreOcr() {
        val session = session()
        session.addPass(LabelOcrHypothesis("KIRKLAND\nMALBEC\n2023"))

        // The registry already answered, so no crop pass is requested.
        assertNull(session.nextRecipe(identityResolved = true))
        val identity = WineBarcodeIdentity(
            barcode = "0036000291452",
            maker = "Kirkland Signature",
            name = "Malbec",
            country = "Argentina"
        )
        val outcome = session.finish(barcode = "0036000291452", identity = identity)

        assertEquals(1, outcome.passesRun)
        assertEquals("Kirkland Signature", outcome.wine.maker)
        assertEquals("Malbec", outcome.wine.name)
        assertEquals("2023", outcome.wine.vintage)
        assertEquals("Argentina", outcome.wine.country)
        assertEquals("0036000291452", outcome.wine.barcode)
        assertFalse("an exact barcode identity does not need review", outcome.wine.needsReview)
    }

    @Test
    fun aVisibleButUnresolvedBarcodeIsFlaggedForReview() {
        val session = session()
        session.addPass(LabelOcrHypothesis("SOME WINERY\nMERLOT\n2019"))
        session.addPass(LabelOcrHypothesis("SOME WINERY\nMERLOT\n2019"))

        val outcome = session.finish(barcode = "4006381333931", identity = null)

        assertEquals("4006381333931", outcome.wine.barcode)
        assertTrue("the identity is still a guess", outcome.wine.needsReview)
    }

    @Test
    fun theSummaryExplainsTheDecision() {
        val session = session()
        session.addPass(LabelOcrHypothesis("BODEGAS OLARRA\nRIOJA\n2019"))
        session.addPass(LabelOcrHypothesis("BODEGAS OLARRA\nRIOJA\n2023"))

        val outcome = session.finish(barcode = null, identity = null)
        val summary = outcome.summary(barcode = null, identity = null)

        assertTrue(summary.contains("passes=2"))
        assertTrue(summary.contains("barcode=none"))
        assertTrue(summary.contains("identity=none"))
        assertTrue(summary.contains("needsReview=true"))
        assertTrue("the clashing vintages are named", summary.contains("vintage=2019 / 2023"))
        assertEquals(listOf("vintage=2019 / 2023"), outcome.contradictions)
    }

    @Test
    fun theSummaryNamesTheRegistryIdentityWhenThereIsOne() {
        val session = session()
        session.addPass(LabelOcrHypothesis("MERLOT\nCALIFORNIA"))

        val identity = WineBarcodeIdentity(barcode = "0036000291452", maker = "Blackstone Winery", name = "Merlot")
        val summary = session.finish(barcode = "0036000291452", identity = identity)
            .summary("0036000291452", identity)

        assertTrue(summary.contains("barcode=0036000291452"))
        assertTrue(summary.contains("identity=Blackstone Winery / Merlot"))
        assertTrue(summary.contains("needsReview=false"))
        assertTrue(summary.contains("agreement=1.00") || summary.contains("agreement="))
    }
}
