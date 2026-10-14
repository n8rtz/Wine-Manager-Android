package com.yourapp.wine

import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.capture.ImageQualityGate
import android.graphics.PointF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class BitmapWorkspaceTest {
    @Test fun twoBitmapLimitAndFinallyRecycling() {
        val workspace = BitmapWorkspace()
        val raw = workspace.create(16, 16)
        val extra = workspace.create(16, 16)
        try {
            try { workspace.create(16, 16); fail("Third working bitmap must be rejected before allocation") }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, workspace.peakCount)
        } finally { workspace.close() }
        assertTrue(raw.isRecycled && extra.isRecycled)
        assertEquals(0, workspace.aliveCount)
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedBitmapRejectedBeforeAllocation() {
        BitmapWorkspace().create(1921, 16)
    }

    @Test fun labelAreaUsesOriginalFrameNotRectifiedFrame() {
        val gate = ImageQualityGate()
        assertTrue(gate.labelTooSmall(listOf(PointF(0f, 0f), PointF(100f, 0f), PointF(100f, 100f), PointF(0f, 100f)), 1000, 1000))
        assertFalse(gate.labelTooSmall(listOf(PointF(0f, 0f), PointF(500f, 0f), PointF(500f, 500f), PointF(0f, 500f)), 1000, 1000))
    }
}
