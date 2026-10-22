package com.yourapp.wine

import android.graphics.PointF
import com.yourapp.wine.geom.CoordMapper
import com.yourapp.wine.geom.Matrix3
import com.yourapp.wine.ocr.RawToken
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class GeometryTest {
    @Test fun projectiveWarpRoundtripAndNormalizedBoxes() {
        val matrix = Matrix3(listOf(1.1, .1, -10.0, -.05, 1.2, 12.0, .0001, .0002, 1.0))
        val original = listOf(PointF(80f, 100f), PointF(240f, 100f), PointF(240f, 160f), PointF(80f, 160f))
        val rect = original.map(matrix::map)
        val mapper = CoordMapper(matrix, 400, 600, 500, 800)
        val token = mapper.toOriginal(RawToken("Château", .9f, rect, "mlkit", "V0"))!!
        original.zip(token.polyOrig).forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, .001f); assertEquals(expected.y, actual.y, .001f)
        }
        assertEquals(.2f, token.bboxOrig.left, .0001f)
        assertEquals(.6f, token.bboxOrig.right, .0001f)
        assertTrue(token.bboxRect.top in 0f..1f)
    }

    @Test(expected = IllegalArgumentException::class) fun singularCropsAreRejected() {
        Matrix3(List(9) { 0.0 }).inverse()
    }
}
