package com.n8rtz.winetracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AromaWheelGeometryTest {

    @Test
    fun clockwiseAngleFromTopMapsQuadrantsCorrectly() {
        val cx = 100f
        val cy = 100f
        assertEquals(0f, AromaWheelGeometry.clockwiseAngleFromTopDeg(100f, 40f, cx, cy), 0.01f)
        assertEquals(90f, AromaWheelGeometry.clockwiseAngleFromTopDeg(160f, 100f, cx, cy), 0.01f)
        assertEquals(180f, AromaWheelGeometry.clockwiseAngleFromTopDeg(100f, 160f, cx, cy), 0.01f)
        assertEquals(270f, AromaWheelGeometry.clockwiseAngleFromTopDeg(40f, 100f, cx, cy), 0.01f)
    }

    @Test
    fun angleAtCenterIsZeroWithoutCrashing() {
        assertEquals(0f, AromaWheelGeometry.clockwiseAngleFromTopDeg(50f, 50f, 50f, 50f), 0.0001f)
    }

    @Test
    fun segmentIndexSplitsTheCircleEvenly() {
        assertEquals(0, AromaWheelGeometry.segmentIndex(0f, 4))
        assertEquals(1, AromaWheelGeometry.segmentIndex(95f, 4))
        assertEquals(2, AromaWheelGeometry.segmentIndex(180f, 4))
        assertEquals(3, AromaWheelGeometry.segmentIndex(270f, 4))
        // Just below a full turn wraps to the last segment.
        assertEquals(3, AromaWheelGeometry.segmentIndex(359.9f, 4))
        // Negative angles wrap forward.
        assertEquals(3, AromaWheelGeometry.segmentIndex(-0.1f, 4))
    }

    @Test
    fun segmentIndexHandlesDegenerateCounts() {
        assertEquals(-1, AromaWheelGeometry.segmentIndex(90f, 0))
        assertEquals(-1, AromaWheelGeometry.segmentIndex(90f, -3))
        assertEquals(0, AromaWheelGeometry.segmentIndex(240f, 1))
    }

    @Test
    fun midAnglePointsToTheSameSegment() {
        for (count in 1..19) {
            for (index in 0 until count) {
                val mid = AromaWheelGeometry.segmentMidAngleDeg(index, count)
                assertEquals(
                    "count=$count index=$index",
                    index,
                    AromaWheelGeometry.segmentIndex(mid, count)
                )
            }
        }
    }

    @Test
    fun canvasStartAngleShiftsTwelveOclockToMinusNinety() {
        assertEquals(-90f, AromaWheelGeometry.canvasStartAngleDeg(0f), 0.0001f)
        assertEquals(0f, AromaWheelGeometry.canvasStartAngleDeg(90f), 0.0001f)
    }

    @Test
    fun directionDegGivesScreenSpaceUnitVectors() {
        val up = AromaWheelGeometry.directionDeg(0f)
        assertEquals(0f, up.first, 0.0001f)
        assertEquals(-1f, up.second, 0.0001f)
        val right = AromaWheelGeometry.directionDeg(90f)
        assertEquals(1f, right.first, 0.0001f)
        assertEquals(0f, right.second, 0.0001f)
        val down = AromaWheelGeometry.directionDeg(180f)
        assertEquals(0f, down.first, 0.0001f)
        assertEquals(1f, down.second, 0.0001f)
    }

    @Test
    fun radiusMeasuresDistanceFromCenter() {
        assertEquals(5f, AromaWheelGeometry.radius(3f, 4f, 0f, 0f), 0.0001f)
        assertEquals(0f, AromaWheelGeometry.radius(7f, 7f, 7f, 7f), 0.0001f)
    }

    @Test
    fun lightenAndDarkenBlendChannelsTowardTheTarget() {
        val midGray = 0xFF808080
        val lighter = AromaWheelGeometry.lightenArgb(midGray, 0.5f)
        val darker = AromaWheelGeometry.darkenArgb(midGray, 0.5f)
        assertEquals(0xFFBFBFBF, lighter)
        assertEquals(0xFF404040, darker)
        // Saturated blends never move the alpha byte.
        assertEquals(0xFFL, (lighter ushr 24) and 0xFF)
        // Fully blended endpoints converge on white/black.
        assertEquals(0xFFFFFFFF, AromaWheelGeometry.lightenArgb(midGray, 1f))
        assertEquals(0xFF000000, AromaWheelGeometry.darkenArgb(midGray, 1f))
        // Fractions outside 0..1 are clamped.
        assertEquals(midGray, AromaWheelGeometry.lightenArgb(midGray, -1f))
    }

    @Test
    fun shadeCyclesDeterministicallyAcrossIndices() {
        val base = 0xFFB04A5A
        val cycle = listOf(
            AromaWheelGeometry.shadeArgbAt(base, 0),
            AromaWheelGeometry.shadeArgbAt(base, 1),
            AromaWheelGeometry.shadeArgbAt(base, 2),
            AromaWheelGeometry.shadeArgbAt(base, 3)
        )
        assertEquals(cycle[0], AromaWheelGeometry.shadeArgbAt(base, 4))
        assertEquals(cycle[1], AromaWheelGeometry.shadeArgbAt(base, 5))
        assertEquals(base, cycle[0])
        // The three non-base shades are all distinct from each other and the base.
        assertEquals(4, cycle.toSet().size)
        // Negative indices wrap instead of crashing.
        assertEquals(cycle[3], AromaWheelGeometry.shadeArgbAt(base, -1))
    }

    @Test
    fun readableTextPicksContrastAgainstTheBackground() {
        assertEquals(0xFFFDFDFD, AromaWheelGeometry.readableTextArgb(0xFF722F37))
        assertEquals(0xFF141414, AromaWheelGeometry.readableTextArgb(0xFFFDFDFD))
        assertTrue(AromaWheelGeometry.relativeLuminance(0xFF000000) < 0.05f)
        assertTrue(AromaWheelGeometry.relativeLuminance(0xFFFFFFFF) > 0.95f)
    }
}
