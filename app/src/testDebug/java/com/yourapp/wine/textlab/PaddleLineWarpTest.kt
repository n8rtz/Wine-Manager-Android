package com.yourapp.wine.textlab

import android.graphics.PointF
import com.yourapp.wine.capture.BitmapWorkspace
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
// Real Skia Matrix/Canvas behavior is required for four-point perspective warps;
// legacy graphics shadows do not implement the full Android transform contract.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],manifest=Config.NONE)
class PaddleLineWarpTest {
    @Test fun detectorGeometry()=PaddleDetectionChecks.run()
    @Test fun perspectiveInverseAndTwoBitmapBudget() {
        BitmapWorkspace().use { workspace ->
            val base=workspace.create(800,1000)
            val quad=listOf(PointF(100f,200f),PointF(500f,220f),PointF(490f,300f),PointF(110f,280f))
            val line=PaddleLineWarp.create(base,quad,240,48,workspace)
            val corners=listOf(PointF(0f,0f),PointF(240f,0f),PointF(240f,48f),PointF(0f,48f))
            corners.zip(quad).forEach { (input,expected) ->
                val actual=line.lineToBase.map(input)
                check(abs(actual.x-expected.x)<.01f && abs(actual.y-expected.y)<.01f)
                val inverse=line.lineToBase.inverse().map(actual)
                check(abs(input.x-inverse.x)<.01f && abs(input.y-inverse.y)<.01f)
            }
            check(workspace.aliveCount==2 && workspace.peakCount==2)
            workspace.release(line.bitmap)
            check(workspace.aliveCount==1 && !base.isRecycled)
        }
    }
}
