package com.yourapp.wine.geom

import android.graphics.Bitmap
import android.graphics.PointF
import com.yourapp.wine.capture.BitmapWorkspace
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/** Caller owns geom.H_origToRect and must release it in finally; the workspace owns bitmap. */
data class RectifiedLabel(val bitmap: Bitmap, val geom: FrameGeom, val originalToRect: Matrix3)

object Warp {
    /** Working pixels + original quad -> upright rectified pixels (width <=1280, long edge <=1920), with invertible H. */
    fun perspective(working: Bitmap, geom: FrameGeom, workspace: BitmapWorkspace): RectifiedLabel {
        val quad = geom.labelQuadOrig?.map { p -> PointF(p.x * geom.workW / geom.origW, p.y * geom.workH / geom.origH) }
            ?: listOf(PointF(0f, 0f), PointF(geom.workW.toFloat(), 0f), PointF(geom.workW.toFloat(), geom.workH.toFloat()), PointF(0f, geom.workH.toFloat()))
        val naturalW = (distance(quad[0], quad[1]) + distance(quad[2], quad[3])) / 2
        val naturalH = (distance(quad[0], quad[3]) + distance(quad[1], quad[2])) / 2
        val size = LabelGeometryPolicy.rectifiedSize(naturalW, naturalH)
        val width = size.width
        val height = size.height
        val source = MatOfPoint2f(*quad.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val destination = MatOfPoint2f(Point(0.0, 0.0), Point(width.toDouble(), 0.0),
            Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble()))
        val input = Mat(); val output = Mat()
        var workToRect: Mat? = null; var originalToRectMat: Mat? = null; var transferred = false
        try {
            workToRect = Imgproc.getPerspectiveTransform(source, destination)
            val coefficients = DoubleArray(9); workToRect.get(0, 0, coefficients)
            val originalToRect = Matrix3(coefficients.toList()) * Matrix3.scale(geom.workW.toDouble() / geom.origW, geom.workH.toDouble() / geom.origH)
            originalToRect.inverse() // prove invertibility BEFORE performing OCR
            originalToRectMat = Mat(3, 3, CvType.CV_64F).apply { put(0, 0, *originalToRect.values.toDoubleArray()) }
            Utils.bitmapToMat(working, input)
            Imgproc.warpPerspective(input, output, workToRect, Size(width.toDouble(), height.toDouble()),
                if (width > naturalW || height > naturalH) Imgproc.INTER_CUBIC else Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE)
            input.release()
            val bitmap = workspace.create(width, height)
            Utils.matToBitmap(output, bitmap)
            val result = RectifiedLabel(bitmap, geom.copy(H_origToRect = originalToRectMat), originalToRect)
            transferred = true
            return result
        } finally {
            source.release(); destination.release(); input.release(); output.release(); workToRect?.release()
            if (!transferred) originalToRectMat?.release()
        }
    }

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}
