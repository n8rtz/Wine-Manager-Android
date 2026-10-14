package com.yourapp.wine.capture

import android.graphics.Bitmap
import android.graphics.PointF
import com.yourapp.wine.fill.UiState
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/** Quality measurements do not retain image buffers. Thresholds must be calibrated on real devices. */
data class QualityAssessment(val state: UiState, val hint: String?, val laplacianVariance: Double, val meanLuminance: Double)

class ImageQualityGate(private val blurThreshold: Double = 28.0) {
    /** Measures working RGB pixels on <=320px grayscale before any heavy OCR; caller has loaded OpenCV. */
    fun assess(bitmap: Bitmap): QualityAssessment {
        val rgba = Mat(); val gray = Mat(); val small = Mat(); val lap = Mat()
        val mean = MatOfDouble(); val deviation = MatOfDouble()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val scale = minOf(1.0, 320.0 / maxOf(gray.cols(), gray.rows()))
            Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.Laplacian(small, lap, CvType.CV_64F)
            Core.meanStdDev(lap, mean, deviation)
            val variance = deviation.toArray()[0].let { it * it }
            val luminance = Core.mean(small).`val`[0]
            return when {
                luminance < 18.0 -> QualityAssessment(UiState.RED, "Add light or enter details manually", variance, luminance)
                variance < blurThreshold -> QualityAssessment(UiState.RED, "Hold steady", variance, luminance)
                luminance < 50.0 -> QualityAssessment(UiState.YELLOW, "Add light", variance, luminance)
                luminance > 252.0 -> QualityAssessment(UiState.YELLOW, "Avoid glare", variance, luminance)
                else -> QualityAssessment(UiState.GREEN, null, variance, luminance)
            }
        } finally { listOf(rgba, gray, small, lap, mean, deviation).forEach { it.release() } }
    }

    /** Tests a quad in original-capture pixels against the ORIGINAL frame area, not a cropped/rectified frame. */
    fun labelTooSmall(quadOrig: List<PointF>, origW: Int, origH: Int): Boolean {
        require(quadOrig.size == 4)
        val twiceArea = quadOrig.indices.sumOf { i ->
            val a = quadOrig[i]; val next = quadOrig[(i + 1) % 4]
            (a.x * next.y - next.x * a.y).toDouble()
        }
        return abs(twiceArea) / 2.0 / (origW.toDouble() * origH) < 0.12
    }
}
