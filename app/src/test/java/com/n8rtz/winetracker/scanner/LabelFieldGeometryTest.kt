package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pointing the zoom at the right line is what makes the third pass worth running: these tests drive
 * the search over recognizer boxes the way a scan does, using mappings instead of a camera.
 */
class LabelFieldGeometryTest {
    @Test
    fun locatesTheLineTheNameWasReadOn() {
        val view = view(
            LabelOcrLine("BLACKSTONE", LabelBox(20, 20, 320, 70), blockIndex = 0),
            LabelOcrLine("MERLOT", LabelBox(20, 90, 220, 140), blockIndex = 0),
            LabelOcrLine("CALIFORNIA", LabelBox(20, 160, 340, 210), blockIndex = 0)
        )

        val region = LabelFieldGeometry.locate("Merlot", listOf(view))

        assertNotNull(region)
        val merlotOnPhoto = view.mapping.toSource(LabelBox(20, 90, 220, 140))
        assertTrue("the crop covers the line it was pointed at", requireNotNull(region).contains(merlotOnPhoto))
        assertTrue("and stays a crop, not the whole photo", region.width < view.mapping.sourceWidth / 2)
        assertTrue("the crop is big enough to magnify", region.longestEdge >= 96)
    }

    @Test
    fun joinsTheLinesOfOneBlockSoATwoLineNameStaysWhole() {
        val view = view(
            LabelOcrLine("CHATEAU", LabelBox(20, 100, 300, 150), blockIndex = 0),
            LabelOcrLine("MARGAUX", LabelBox(20, 156, 280, 206), blockIndex = 0)
        )

        val region = LabelFieldGeometry.locate("Château Margaux", listOf(view))

        assertNotNull(region)
        val first = view.mapping.toSource(LabelBox(20, 100, 300, 150))
        val second = view.mapping.toSource(LabelBox(20, 156, 280, 206))
        assertTrue(requireNotNull(region).contains(first))
        assertTrue(region.contains(second))
    }

    @Test
    fun returnsNothingWhenTheFieldIsNotOnTheLabelOrTooShortToSearch() {
        val view = view(LabelOcrLine("BLACKSTONE", LabelBox(20, 20, 320, 70)))

        assertNull(LabelFieldGeometry.locate("Zinfandel", listOf(view)))
        assertNull(LabelFieldGeometry.locate(null, listOf(view)))
        assertNull("three letters match too many lines", LabelFieldGeometry.locate("OZV", listOf(view)))
        assertNull("a pass without boxes cannot be zoomed into", LabelFieldGeometry.locate("Merlot", emptyList()))
    }

    @Test
    fun prefersTheMostMagnifiedPassesBox() {
        val fullView = view(
            LabelOcrLine("MERLOT", LabelBox(20, 90, 120, 110)),
            scale = 1f
        )
        val magnified = view(
            LabelOcrLine("MERLOT", LabelBox(200, 400, 500, 480)),
            scale = 3f
        )

        val region = LabelFieldGeometry.locate("Merlot", listOf(fullView, magnified))

        assertNotNull(region)
        val magnifiedOnPhoto = magnified.mapping.toSource(LabelBox(200, 400, 500, 480))
        val fullViewOnPhoto = fullView.mapping.toSource(LabelBox(20, 90, 120, 110))
        assertTrue(requireNotNull(region).contains(magnifiedOnPhoto))
        assertFalse("the wider, sloppier box is not the one used", region.contains(fullViewOnPhoto))
    }

    @Test
    fun doesNotSwallowTheWholeLabelWhenOneBlockHoldsEveryLine() {
        // ML Kit often reports every line on a bottle as one block, so the crop must not grow to it.
        val view = view(
            LabelOcrLine("BLACKSTONE", LabelBox(20, 20, 340, 70), blockIndex = 0),
            LabelOcrLine("WINERY", LabelBox(20, 90, 220, 140), blockIndex = 0),
            LabelOcrLine("MERLOT", LabelBox(20, 160, 200, 210), blockIndex = 0),
            LabelOcrLine("CALIFORNIA", LabelBox(20, 230, 360, 280), blockIndex = 0)
        )

        val region = LabelFieldGeometry.locate("Merlot", listOf(view))

        assertNotNull(region)
        val name = view.mapping.toSource(LabelBox(20, 160, 200, 210))
        val farLine = view.mapping.toSource(LabelBox(20, 20, 340, 70))
        assertTrue(requireNotNull(region).contains(name))
        assertFalse("the crop stays on the field, not the whole block", region.contains(farLine))
    }

    @Test
    fun neverPointsOutsideThePhoto() {
        val view = view(LabelOcrLine("MERLOT", LabelBox(0, 0, 40, 40)))
        val mapping = view.mapping

        val region = LabelFieldGeometry.locate("Merlot", listOf(view))

        assertNotNull(region)
        assertTrue(requireNotNull(region).left >= 0)
        assertTrue(region.top >= 0)
        assertTrue(region.right <= mapping.sourceWidth)
        assertTrue(region.bottom <= mapping.sourceHeight)
    }

    @Test
    fun fieldTokensIgnoreCaseAccentsAndShortWords() {
        assertEquals(listOf("montalcino", "brunello"), LabelFieldGeometry.fieldTokens("Brunello di Montalcino"))
        assertEquals(emptyList<String>(), LabelFieldGeometry.fieldTokens("OZV"))
        assertEquals(emptyList<String>(), LabelFieldGeometry.fieldTokens(null))
    }

    /** A pass whose boxes are in a magnified crop's space, mapped back onto a 1000x800 photo. */
    private fun view(vararg lines: LabelOcrLine, scale: Float = 2f): LabelFieldGeometry.MappedLines {
        val settings = LabelZoomSettings(targetEdge = 1600, maxUpscale = scale, contrast = 1.15f)
        val mapping = LabelCropMapping.forRegion(
            sourceWidth = 1000,
            sourceHeight = 800,
            region = LabelBox(0, 0, 1000, 800),
            settings = settings
        )
        return LabelFieldGeometry.MappedLines(lines.toList(), mapping)
    }
}
