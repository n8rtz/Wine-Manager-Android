package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotScanGateTest {
    @Test
    fun onlyOneFrameIsClaimedForEachRequest() {
        val gate = OneShotScanGate()

        assertTrue(gate.request(1L))
        assertEquals(1L, gate.claimNextFrame() ?: -1L)
        assertNull(gate.claimNextFrame())
        assertTrue(gate.finish(1L))

        assertTrue(gate.request(2L))
        assertEquals(2L, gate.claimNextFrame() ?: -1L)
        assertTrue(gate.finish(2L))
    }

    @Test
    fun cancellingBeforeAFrameArrivesAllowsAnotherRequest() {
        val gate = OneShotScanGate()

        assertTrue(gate.request(1L))
        assertTrue(gate.cancel(1L))
        assertNull(gate.claimNextFrame())
        assertTrue(gate.request(2L))
    }

    @Test
    fun cancelledInFlightScanIsDiscardedAndBlocksOverlapUntilFinished() {
        val gate = OneShotScanGate()

        assertTrue(gate.request(1L))
        assertEquals(1L, gate.claimNextFrame() ?: -1L)
        assertTrue(gate.cancel(1L))
        assertFalse(gate.request(2L))
        assertFalse(gate.finish(1L))
        assertTrue(gate.request(2L))
    }
}
