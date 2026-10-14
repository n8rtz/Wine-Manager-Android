package com.yourapp.wine.ocr

import android.graphics.PointF
import android.graphics.RectF

/** One token per OCR line, in rectified pixels (working pixels only for the explicitly mapped quad probe). */
data class RawToken(
    val text: String,
    val score: Float,
    val polygon: List<PointF>,
    val engineId: String,
    val variantId: String,
    val scoreIsEstimated: Boolean = false,
)

/** Boxes are normalized in their named spaces; polyOrig retains original EXIF-upright pixels. */
data class TokenRecord(
    val textRaw: String,
    val textNorm: String,
    val score: Float,
    val bboxOrig: RectF,
    val bboxRect: RectF,
    val polyOrig: List<PointF>,
    val engineId: String,
    val variantId: String,
    val scoreIsEstimated: Boolean = false,
)

/** Fused observations with a normalized rectified-label box and independent-engine vote. */
data class MergedToken(
    val canonical: String,
    val members: List<TokenRecord>,
    val vote: Float,
    val bboxRect: RectF,
) {
    /** No coordinates: estimated scores may retain editable evidence, never create validated support. */
    val hasReliableSupport: Boolean get() {
        val actual = members.filterNot { it.scoreIsEstimated }.groupBy { it.engineId }
            .mapValues { (_, reads) -> reads.maxOf { it.score.coerceIn(0f, 1f) } }
        if (actual.isEmpty()) return false
        if (actual.size == 1) return actual.values.single() >=
            if (actual.keys.single().startsWith("paddle")) .75f else .60f
        return 1f - actual.values.fold(1f) { product, score -> product * (1f - score) } >= .60f
    }
}
