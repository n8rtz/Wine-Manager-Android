package com.yourapp.wine.ocr

import android.graphics.PointF
import kotlin.math.roundToInt

/** Explicit inverse of a whole-label resize; no OCR text, score, engine identity or evidence vote is modified. */
object OcrVariantGeometry {
    /** Base rectified-label height in -> aspect-rescue input height; no extra crop or full-size Bitmap copy. */
    fun aspectHeight(labelHeight: Int): Int {
        require(labelHeight > 0)
        return (labelHeight * .5f).roundToInt().coerceAtLeast(16)
    }

    /** OCR variant pixels -> BASE rectified-label pixels, before the shared original-image homography is inverted. */
    fun toLabel(token: RawToken, inputW: Int, inputH: Int, labelW: Int, labelH: Int): RawToken {
        require(inputW > 0 && inputH > 0 && labelW > 0 && labelH > 0)
        if (inputW == labelW && inputH == labelH) return token
        val sx = labelW.toDouble()/inputW; val sy = labelH.toDouble()/inputH
        return token.copy(polygon = token.polygon.map { PointF((it.x*sx).toFloat(), (it.y*sy).toFloat()) })
    }
}
