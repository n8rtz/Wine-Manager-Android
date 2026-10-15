package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelOcrConsensusTest {
    private val parser = WineLabelParser()
    private val dictionary = WineReferenceDictionary.builtIn()

    private fun merge(vararg texts: String): LabelOcrConsensus.Result {
        val result = LabelOcrConsensus.merge(
            hypotheses = texts.map { LabelOcrHypothesis(it) },
            parser = parser,
            dictionary = dictionary
        )
        assertNotNull("expected a merge result", result)
        return requireNotNull(result)
    }

    private fun assess(result: LabelOcrConsensus.Result, vararg texts: String): LabelScanAssessment.Report =
        LabelScanAssessment.assess(
            consensus = result,
            perPassResults = texts.map { parser.parse(it, dictionary) },
            mergedResult = parser.parse(result.text, dictionary)
        )

    @Test
    fun keepsTheDecorativeWordOnlyTheCropRead() {
        val fullFrame = "BLACKSTONE\nWINERY\nCALIFORNIA\nALC 13.5% BY VOL"
        val enhancedCrop = "BLACKSTONE\nWINERY\nMerlot\nCALIFORNIA"

        val merged = merge(fullFrame, enhancedCrop)

        // The agreed lines stay in label order and the crop's cuvée lands between its neighbours.
        assertEquals(
            "BLACKSTONE\nWINERY\nMerlot\nCALIFORNIA\nALC 13.5% BY VOL",
            merged.text
        )
        val wine = parser.parse(merged.text, dictionary)
        assertEquals("Blackstone Winery", wine.maker)
        assertEquals("Merlot", wine.name)
        assertEquals("California", wine.region)
        assertEquals("Red", wine.type)
        assertEquals(3.0 / 4.0, merged.agreement, 0.001)
        assertFalse(assess(merged, fullFrame, enhancedCrop).needsReview)
    }

    @Test
    fun keepsTheVocabularySpellingWhenThePassesDisagreeAboutALine() {
        val fullFrame = "BLACKSTONE\nWINERY\nMERL0T\nCALIFORNIA"
        val enhancedCrop = "BLACKSTONE\nWINERY\nMERLOT\nCALIFORNIA"

        val merged = merge(fullFrame, enhancedCrop)

        assertEquals("BLACKSTONE\nWINERY\nMERLOT\nCALIFORNIA", merged.text)
        assertEquals("Merlot", parser.parse(merged.text, dictionary).name)
    }

    @Test
    fun fallsBackToTheSingleBestPassWhenThePassesShareNothing() {
        val fullFrame = "BODEGAS MUGA\nRIOJA\n2019"
        val enhancedCrop = "LOREM IPSUM\nDOLOR SIT"

        val merged = merge(fullFrame, enhancedCrop)

        assertEquals(fullFrame, merged.text)
        assertEquals(0.0, merged.agreement, 0.001)
        assertTrue(assess(merged, fullFrame, enhancedCrop).needsReview)
    }

    @Test
    fun ignoresABlankPass() {
        val merged = merge("", "RIOJA 2019")

        assertEquals("RIOJA 2019", merged.text)
        assertEquals(1.0, merged.agreement, 0.001)
        assertFalse(assess(merged, "RIOJA 2019").needsReview)
    }

    @Test
    fun flagsContradictingVintagesEvenWhenTheTextLooksClean() {
        val fullFrame = "BODEGAS OLARRA\nRIOJA\n2019"
        val enhancedCrop = "BODEGAS OLARRA\nRIOJA\n2023"

        val merged = merge(fullFrame, enhancedCrop)
        val report = assess(merged, fullFrame, enhancedCrop)

        // The merged draft keeps one vintage, but the disagreement must reach the reviewer.
        assertEquals("2019", parser.parse(merged.text, dictionary).vintage)
        assertEquals(listOf("vintage=2019 / 2023"), report.contradictions)
        assertTrue(report.needsReview)
    }

    @Test
    fun needsReviewWhenThePassesAgreeButTheLabelHasNoWineEvidence() {
        val text = "VINO DELICIOSO\nBUENO"

        val merged = merge(text, text)

        assertEquals(text, merged.text)
        assertEquals(1.0, merged.agreement, 0.001)
        assertTrue(assess(merged, text, text).needsReview)
    }

    @Test
    fun keepsTheNoisyFullFrameLinesWithoutLosingTheCropReading() {
        val fullFrame = "OZV 94 POINTS WINE ENTHUSIAST\nOLD VINE\nZINFANDEL\nLODI\nV. 2022"
        val enhancedCrop = "OLD VINE\nZINFANDEL\nLODI\nV. 2022"

        val merged = merge(fullFrame, enhancedCrop)

        assertEquals(fullFrame, merged.text)
        val wine = parser.parse(merged.text, dictionary)
        assertEquals("Zinfandel", wine.name)
        assertEquals("2022", wine.vintage)
        assertEquals("Lodi", wine.subregion)
        assertEquals("United States", wine.country)
        // The passes disagree about which line is the producer (the full frame's first line is the
        // award badge), so the reviewer must confirm the maker even though the rest is solid.
        assertTrue(assess(merged, fullFrame, enhancedCrop).needsReview)
    }

    @Test
    fun keepsBothReadingsWhenOnePassSplitsALineInTwo() {
        // Line-level voting cannot rejoin a line that one pass split, so both readings survive.
        // The producer is still resolved, which is what the draft needs.
        val fullFrame = "BODEGAS\nOLARRA\nRIOJA\n2019"
        val enhancedCrop = "BODEGAS OLARRA\nRIOJA\n2019"

        val merged = merge(fullFrame, enhancedCrop)

        assertTrue(merged.text.lineSequence().any { it == "BODEGAS OLARRA" })
        val wine = parser.parse(merged.text, dictionary)
        assertEquals("Bodegas Olarra", wine.maker)
        assertEquals("2019", wine.vintage)
        assertEquals("Rioja", wine.region)
    }

    @Test
    fun aTargetedZoomCorrectsALineWithoutTakingOverTheLabel() {
        val fullView = LabelOcrHypothesis("BLACKSTONE\nWINERY\nMERL0T\nCALIFORNIA")
        val zoom = LabelOcrHypothesis("MERLOT", role = LabelOcrHypothesis.Role.TARGETED)

        val merged = requireNotNull(LabelOcrConsensus.merge(listOf(fullView, zoom), parser, dictionary))

        assertTrue("the whole-label pass still says what the label reads", merged.text.startsWith("BLACKSTONE"))
        assertTrue("the magnified re-read wins the contested line", merged.text.contains("MERLOT"))
        assertFalse("and the misread spelling is gone", merged.text.contains("MERL0T"))
        assertEquals("every line the full view read survives", 4, merged.text.lines().size)
        assertEquals("only the line both passes read counts as supported", 1, merged.supportedLines)
    }

}
