package com.yourapp.wine.geom

import android.graphics.PointF
import com.yourapp.wine.ocr.RawToken

class TextHullQuadDetector {
    /** WORKING probe polygons -> padded ORIGINAL label quad; weak boxes locate only, never validate field text. */
    fun detect(tokens: List<RawToken>, geom: FrameGeom): List<PointF>? {
        val group = TextHullClusterer().select(tokens, geom.workW, geom.workH)
        return LabelGeometryPolicy.textQuad(group, geom.workW, geom.workH, geom.origW, geom.origH)
    }
}
