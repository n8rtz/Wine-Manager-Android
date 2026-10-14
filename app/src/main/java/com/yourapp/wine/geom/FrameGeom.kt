package com.yourapp.wine.geom

import android.graphics.PointF
import org.opencv.core.Mat

/** Original pixels mean the EXIF-upright capture; H maps those pixels to rectified-label pixels. */
data class FrameGeom(
    val origW: Int,
    val origH: Int,
    val workW: Int,
    val workH: Int,
    val downscale: Float,
    val labelQuadOrig: List<PointF>?,
    val H_origToRect: Mat?,
)
