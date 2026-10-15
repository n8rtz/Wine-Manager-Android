package com.n8rtz.winetracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AromaChipDragTest {

    private val width50: (Int) -> Int = { 50 }

    @Test
    fun staysPutBelowTheNeighbourHalfwayPoint() {
        assertEquals(1 to 20f, aromaChipDragTarget(1, 20f, 5, width50))
        assertEquals(2 to -20f, aromaChipDragTarget(2, -20f, 5, width50))
    }

    @Test
    fun crossesRightNeighbourAtHalfItsWidth() {
        val (index, offset) = aromaChipDragTarget(1, 26f, 5, width50)
        assertEquals(2, index)
        assertEquals(-24f, offset, 1e-4f)
    }

    @Test
    fun crossesLeftNeighbourAtHalfItsWidth() {
        val (index, offset) = aromaChipDragTarget(2, -26f, 5, width50)
        assertEquals(1, index)
        assertEquals(24f, offset, 1e-4f)
    }

    @Test
    fun chainsAcrossSeveralNeighboursInOneStep() {
        // Each swap subtracts the crossed neighbour's full width: 130px right over
        // 50px chips = three midpoint crossings (index 3, offset -20), and the
        // mirrored leftward drag lands on index 1 with +20.
        val (index, offset) = aromaChipDragTarget(0, 130f, 6, width50)
        assertEquals(3, index)
        assertEquals(-20f, offset, 1e-4f)
        val (leftIndex, leftOffset) = aromaChipDragTarget(4, -130f, 6, width50)
        assertEquals(1, leftIndex)
        assertEquals(20f, leftOffset, 1e-4f)
    }

    @Test
    fun neverLeavesTheBoundsOfTheRow() {
        assertEquals(0 to -100f, aromaChipDragTarget(0, -100f, 5, width50))
        assertEquals(4 to 100f, aromaChipDragTarget(4, 100f, 5, width50))
    }

    @Test
    fun handlesUnevenChipWidths() {
        val widths = mapOf(0 to 80, 1 to 30, 2 to 120, 3 to 40)
        val (index, offset) = aromaChipDragTarget(0, 45f, 4) { widths.getValue(it) }
        // The crossing threshold is the NEIGHBOUR's half-width: 45 >= 30/2 -> move to
        // index 1, offset 45 - 30 = 15; 15 < 120/2 -> stop.
        assertEquals(1, index)
        assertEquals(15f, offset, 1e-4f)
    }

    @Test
    fun degenerateInputsAreReturnedUnchanged() {
        assertEquals(-1 to 5f, aromaChipDragTarget(-1, 5f, 5, width50))
        assertEquals(7 to 5f, aromaChipDragTarget(7, 5f, 5, width50))
        assertEquals(0 to 0f, aromaChipDragTarget(0, 0f, 0, width50))
    }

    @Test
    fun zeroWidthNeighboursCannotTrapTheDrag() {
        val (index, offset) = aromaChipDragTarget(0, 10f, 3) { 0 }
        // Zero width is coerced to 1px, so a 10px drag walks to the end of the row.
        assertEquals(2, index)
        assertEquals(8f, offset, 1e-4f)
    }
}
