package com.yourapp.wine.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.roundToInt

/** Original dimensions are EXIF-upright JPEG dimensions, not dimensions of a resized preview. */
data class DecodedCapture(val bitmap: Bitmap, val origW: Int, val origH: Int)

class CaptureDecoder {
    /** Original local image bytes -> bounded EXIF-upright RGB; camera and selected photos use identical decoding. */
    fun decode(file: File, workspace: BitmapWorkspace): DecodedCapture {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid capture JPEG" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "Capture dimensions exceed safety limit" }
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (edge / (sample * 2) >= 1920) sample *= 2
        // Density scaling happens inside the decoder, so no oversized full-resolution Bitmap is kept.
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
            if (edge > 1920) {
                inScaled = true
                inDensity = edge
                inTargetDensity = 1918 * sample // leave rounding room; actual edge remains <=1920
            }
        }
        val decoded = workspace.own(requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)))
        val exif = ExifInterface(file)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        val upright = if (matrix.isIdentity) decoded else workspace.own(Bitmap.createBitmap(decoded, 0, 0,
            decoded.width, decoded.height, matrix, true)).also { workspace.release(decoded) }
        val swap = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
        return DecodedCapture(upright, if (swap) bounds.outHeight else bounds.outWidth,
            if (swap) bounds.outWidth else bounds.outHeight)
    }
}
