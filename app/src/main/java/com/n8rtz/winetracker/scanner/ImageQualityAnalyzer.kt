package com.n8rtz.winetracker.scanner

import android.graphics.Bitmap
import java.io.File

/**
 * Measures the captured photo before any OCR runs.
 *
 * This is the capture-quality gate: a photo too soft to resolve the label starts its scan already
 * lost, so the scan measures it up front, tells the ladder not to spend passes on it and tells the
 * user to retake it. Decoding and measuring stay off the main thread. The [Bitmap] overload is there
 * for the same measurement to run on a live preview frame before the user captures anything.
 */
internal object ImageQualityAnalyzer {
    /** Null when the photo cannot be decoded or measured, in which case nothing is claimed about it. */
    fun assess(file: File): CaptureQuality? {
        val grid = LabelImageEnhancer.createAnalysisGrid(file) ?: return null
        return try {
            assess(grid)
        } finally {
            grid.recycle()
        }
    }

    /** Measures a bitmap the caller owns; nothing is recycled here. */
    fun assess(grid: Bitmap): CaptureQuality {
        val width = grid.width
        val height = grid.height
        val pixels = IntArray(width * height)
        grid.getPixels(pixels, 0, width, 0, 0, width, height)
        return CaptureQualityAssessor.assess(pixels, width, height)
    }
}
