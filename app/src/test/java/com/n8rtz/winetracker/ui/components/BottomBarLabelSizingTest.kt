package com.n8rtz.winetracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps the five bottom-bar labels at one size. The numbers come from measuring the
 * labels on a 360dp-wide phone, where each destination gets about 65dp.
 */
class BottomBarLabelSizingTest {
    @Test
    fun labelsKeepTheirDesignedSizeWhenTheWidestAlreadyFits() {
        // "Rankings" at 12sp is about 53dp on the default text size: comfortably inside 65dp.
        assertEquals(
            12f,
            BottomBarLabelSizing.uniformFontSizeSp(widestLabelWidthPx = 53f, availableWidthPx = 65f)
        )
    }

    @Test
    fun everyLabelShrinksTogetherWhenTheWidestDoesNotFit() {
        // A large system text size: "Rankings" now needs about 69dp of the 65dp it is given.
        val size = BottomBarLabelSizing.uniformFontSizeSp(widestLabelWidthPx = 69f, availableWidthPx = 65f)

        assertEquals("one step down, and no further", 11f, size)
        assertTrue(
            "at that size the widest label fits: ${69f * (size / 12f)}dp",
            69f * (size / 12f) <= 65f
        )
    }

    @Test
    fun theSizeIsAlwaysAWholeHalfStep() {
        listOf(52f, 60f, 69f, 74f, 81f, 96f, 120f).forEach { widest ->
            val size = BottomBarLabelSizing.uniformFontSizeSp(widestLabelWidthPx = widest, availableWidthPx = 65f)
            assertEquals(
                "$widest -> $size is not a ${BottomBarLabelSizing.STEP_SP}sp step",
                0f,
                size % BottomBarLabelSizing.STEP_SP,
                0.0001f
            )
        }
    }

    @Test
    fun theSizeIsNeverLargerThanTheDesignedOneOrSmallerThanTheFloor() {
        val huge = BottomBarLabelSizing.uniformFontSizeSp(widestLabelWidthPx = 1f, availableWidthPx = 1_000f)
        val impossible = BottomBarLabelSizing.uniformFontSizeSp(widestLabelWidthPx = 400f, availableWidthPx = 5f)

        assertEquals(BottomBarLabelSizing.MAX_FONT_SIZE_SP, huge)
        assertEquals(BottomBarLabelSizing.MIN_FONT_SIZE_SP, impossible)
    }

    @Test
    fun anUnmeasuredLabelIsLeftAlone() {
        // Before the bar has been laid out there is no width to scale to, and guessing would make the
        // labels jump on the first frame.
        assertEquals(
            BottomBarLabelSizing.MAX_FONT_SIZE_SP,
            BottomBarLabelSizing.uniformFontSizeSp(widestLabelWidthPx = 0f, availableWidthPx = 65f)
        )
        assertEquals(
            BottomBarLabelSizing.MAX_FONT_SIZE_SP,
            BottomBarLabelSizing.uniformFontSizeSp(widestLabelWidthPx = 53f, availableWidthPx = 0f)
        )
    }
}
