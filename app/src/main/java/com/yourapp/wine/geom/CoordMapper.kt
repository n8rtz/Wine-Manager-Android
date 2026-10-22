package com.yourapp.wine.geom

import android.graphics.PointF
import android.graphics.RectF
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.RawToken
import com.yourapp.wine.ocr.TokenRecord

class CoordMapper(private val originalToRect: Matrix3, private val origW: Int, private val origH: Int,
                  private val rectW: Int, private val rectH: Int) {
    private val inverse = originalToRect.inverse()

    /** Rectified-label pixels -> original-capture pixels, with both boxes normalized to [0,1]. */
    fun toOriginal(token: RawToken): TokenRecord? {
        if (!token.score.isFinite() || token.score !in 0f..1f) return null
        if (token.polygon.size < 3 || token.polygon.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val original = try { token.polygon.map(inverse::map) } catch (_: IllegalArgumentException) { return null }
        if (original.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val rectBox = normalizedBox(token.polygon, rectW, rectH)
        val origBox = normalizedBox(original, origW, origH)
        if (rectBox.width() <= 0f || rectBox.height() <= 0f || origBox.width() <= 0f || origBox.height() <= 0f) return null
        return TokenRecord(token.text, TextNorm.normalize(token.text), token.score,
            origBox, rectBox, original, token.engineId, token.variantId, token.scoreIsEstimated)
    }

    /** Original-capture pixels -> rectified-label pixels for roundtrip/debug diagnostics. */
    fun toRectified(polygonOrig: List<PointF>): List<PointF> = polygonOrig.map(originalToRect::map)

    private fun normalizedBox(points: List<PointF>, width: Int, height: Int): RectF = RectF(
        (points.minOf { it.x } / width).coerceIn(0f, 1f), (points.minOf { it.y } / height).coerceIn(0f, 1f),
        (points.maxOf { it.x } / width).coerceIn(0f, 1f), (points.maxOf { it.y } / height).coerceIn(0f, 1f))
}
