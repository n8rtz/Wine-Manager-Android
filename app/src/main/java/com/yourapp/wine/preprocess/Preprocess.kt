package com.yourapp.wine.preprocess

import android.graphics.Bitmap
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.ocr.OcrRescuePolicy
import com.yourapp.wine.ocr.OcrVariant
import com.yourapp.wine.ocr.OcrVariantGeometry
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** V0/V1 plus one evidence-gated unsharp/aspect rescue; raw + at most one extra Bitmap stays invariant. */
class Preprocess(private val enableV2: Boolean = false, private val lastResortThreshold: Boolean = false) {
    /** BASE rectified pixels -> lazy variants; any V2 resize is explicitly inverse-mapped by the ensemble. */
    fun variants(rectified: Bitmap, workspace: BitmapWorkspace): List<OcrVariant> = buildList {
        add(OcrVariant("V0", { rectified }, {}))
        add(OcrVariant("V1", { enhance(rectified, workspace, "foil") }, workspace::release))
        if (enableV2) {
            val labelW = rectified.width; val labelH = rectified.height
            var aspect = false
            add(OcrVariant("V2", { enhance(rectified, workspace,
                if (lastResortThreshold) "threshold" else if (aspect) "aspect" else "unsharp") }, workspace::release,
                enabled = { readings ->
                    aspect = !lastResortThreshold && OcrRescuePolicy.needsAspectRescue(readings)
                    OcrRescuePolicy.shouldRun(readings)
                }, toLabel = { token, w, h -> OcrVariantGeometry.toLabel(token, w, h, labelW, labelH) },
                description = { w, h -> if (h != labelH || w != labelW)
                    "aspect rescue ${w}x${h} -> base ${labelW}x${labelH}; polygons inverse-scaled"
                    else if (lastResortThreshold) "explicit threshold rescue" else "unsharp rescue" }))
        }
    }

    private fun enhance(raw: Bitmap, workspace: BitmapWorkspace, mode: String): Bitmap {
        val rgba = Mat(); val rgb = Mat(); val lab = Mat(); val output = Mat(); val blur = Mat(); val lut = Mat(1, 256, CvType.CV_8U)
        val channels = mutableListOf<Mat>()
        try {
            Utils.bitmapToMat(raw, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            rgba.release()
            when (mode) {
                "foil" -> {
                    Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
                    Core.split(lab, channels)
                    lut.put(0, 0, ByteArray(256) { value -> (if (value > 200) 200 + (value - 200) * .55 else value.toDouble()).toInt().toByte() })
                    Core.LUT(channels[0], lut, channels[0])
                    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                    try { clahe.apply(channels[0], channels[0]) }
                    finally { clahe.collectGarbage(); clahe.clear() }
                    Core.merge(channels, lab)
                    Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
                    Imgproc.cvtColor(rgb, output, Imgproc.COLOR_RGB2RGBA)
                }
                "threshold" -> {
                    // Explicit last-resort opt-in only; normal V2 uses color-preserving unsharp after sparse/weak readings.
                    Imgproc.cvtColor(rgb, blur, Imgproc.COLOR_RGB2GRAY)
                    Imgproc.adaptiveThreshold(blur, output, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 7.0)
                }
                else -> {
                    Imgproc.medianBlur(rgb, rgb, 3)
                    Imgproc.GaussianBlur(rgb, blur, Size(0.0, 0.0), .7)
                    Core.addWeighted(rgb, 1.35, blur, -.35, 0.0, rgb)
                    Imgproc.cvtColor(rgb, output, Imgproc.COLOR_RGB2RGBA)
                }
            }
            val targetHeight = if (mode == "aspect") OcrVariantGeometry.aspectHeight(raw.height) else raw.height
            // Resize the native Mat BEFORE allocating the single variant Bitmap; never create a third Bitmap.
            val bitmapSource = if (mode == "aspect") {
                Imgproc.resize(output, blur, Size(raw.width.toDouble(), targetHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                blur
            } else output
            val bitmap = workspace.create(raw.width, targetHeight)
            Utils.matToBitmap(bitmapSource, bitmap)
            return bitmap
        } finally { (listOf(rgba, rgb, lab, output, blur, lut) + channels).forEach { it.release() } }
    }
}
