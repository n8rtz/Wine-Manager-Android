package com.n8rtz.winetracker.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import kotlin.math.roundToInt

/**
 * Builds a close, grayscale, higher-contrast view of the label for one OCR pass. Which crop and how
 * hard the contrast pushes are described by a [LabelCropRecipe], so the escalation ladder can ask
 * for a different view of the same photo without changing any of this code.
 */
internal object LabelImageEnhancer {
    private const val MAX_DECODE_EDGE = 2_400

    fun createLabelCrop(file: File, recipe: LabelCropRecipe = LabelCropRecipe.ENHANCED): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_EDGE) {
            sampleSize *= 2
        }

        var bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null

        try {
            val oriented = applyExifOrientation(file, bitmap)
            if (oriented !== bitmap) bitmap.recycle()
            bitmap = oriented

            val cropWidth = (bitmap.width * recipe.widthFraction).roundToInt().coerceAtLeast(1)
            val cropHeight = (bitmap.height * recipe.heightFraction).roundToInt().coerceAtLeast(1)
            val cropLeft = (bitmap.width - cropWidth) / 2
            val cropTop = (bitmap.height - cropHeight) / 2
            val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
            if (cropped !== bitmap) bitmap.recycle()
            bitmap = cropped

            val scale = (recipe.targetEdge.toFloat() / maxOf(bitmap.width, bitmap.height))
                .coerceIn(1f, recipe.maxUpscale)
            if (scale > 1.01f) {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            val enhanced = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val offset = -(0.5f * recipe.contrast - 0.5f) * 255f
            val luminanceRed = 0.299f * recipe.contrast
            val luminanceGreen = 0.587f * recipe.contrast
            val luminanceBlue = 0.114f * recipe.contrast
            val colorMatrix = ColorMatrix(
                floatArrayOf(
                    luminanceRed, luminanceGreen, luminanceBlue, 0f, offset,
                    luminanceRed, luminanceGreen, luminanceBlue, 0f, offset,
                    luminanceRed, luminanceGreen, luminanceBlue, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            try {
                Canvas(enhanced).drawBitmap(bitmap, 0f, 0f, paint)
            } catch (error: Exception) {
                enhanced.recycle()
                throw error
            }
            bitmap.recycle()
            return enhanced
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun applyExifOrientation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
