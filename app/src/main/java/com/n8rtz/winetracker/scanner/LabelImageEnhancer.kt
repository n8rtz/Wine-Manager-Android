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

/**
 * Turns a captured photo into the bitmap one OCR pass should read, and the mapping that places the
 * recognizer's line boxes back onto the photo.
 *
 * Which crop to cut and how hard to push the contrast come from a [LabelPassPlan]; every coordinate
 * comes from [LabelCropMapping]. Keeping the arithmetic in that one pure place is what lets the
 * geometry that points the next pass at a line stay in step with the crops themselves.
 */
internal object LabelImageEnhancer {
    /** Photos are decoded no larger than this: more pixels cost memory without helping recognition. */
    private const val MAX_DECODE_EDGE = 2_400

    /** Decode size for the capture-quality grid: comfortably above the grid itself, and cheap. */
    private const val ANALYSIS_DECODE_EDGE = 640

    /** One prepared pass: the bitmap to recognise, and how its boxes map back onto the photo. */
    class PreparedView(val bitmap: Bitmap, val mapping: LabelCropMapping)

    /**
     * Builds the bitmap for [plan], or null when the photo cannot be decoded. The caller owns the
     * returned bitmap and must recycle it.
     */
    fun createView(file: File, plan: LabelPassPlan): PreparedView? {
        val photo = decodeOriented(file, MAX_DECODE_EDGE) ?: return null
        try {
            val mapping = when (plan) {
                is LabelPassPlan.FullView ->
                    LabelCropMapping.forRecipe(photo.width, photo.height, plan.recipe)
                is LabelPassPlan.FieldZoom ->
                    LabelCropMapping.forRegion(photo.width, photo.height, plan.region, plan.settings)
            }
            val contrast = when (plan) {
                is LabelPassPlan.FullView -> plan.recipe.contrast
                is LabelPassPlan.FieldZoom -> plan.settings.contrast
            }
            val bitmap = buildCrop(photo, mapping, contrast) ?: return null
            return PreparedView(bitmap, mapping)
        } finally {
            photo.recycle()
        }
    }

    /**
     * The grid the capture-quality metrics are measured on, or null when the photo cannot be decoded.
     * The caller owns the returned bitmap and must recycle it.
     */
    fun createAnalysisGrid(file: File): Bitmap? {
        val photo = decodeOriented(file, ANALYSIS_DECODE_EDGE) ?: return null
        val (width, height) = CaptureQualityAssessor.analysisSize(photo.width, photo.height)
        if (width == photo.width && height == photo.height) return photo
        val grid = Bitmap.createScaledBitmap(photo, width, height, true)
        photo.recycle()
        return grid
    }

    /** Cuts the crop [mapping]describes, magnifies it by the mapping's own factor and boosts contrast. */
    private fun buildCrop(photo: Bitmap, mapping: LabelCropMapping, contrast: Float): Bitmap? {
        val cropped = Bitmap.createBitmap(
            photo,
            mapping.cropLeft,
            mapping.cropTop,
            mapping.cropWidth,
            mapping.cropHeight
        )
        var working = cropped
        var owned = cropped !== photo
        try {
            if (mapping.scaledWidth != mapping.cropWidth || mapping.scaledHeight != mapping.cropHeight) {
                val scaled = Bitmap.createScaledBitmap(working, mapping.scaledWidth, mapping.scaledHeight, true)
                if (owned && scaled !== working) working.recycle()
                working = scaled
                owned = scaled !== photo
            }
            return applyContrast(working, contrast)
        } finally {
            if (owned && !working.isRecycled) working.recycle()
        }
    }

    /** Renders a grayscale, contrast-scaled copy of [source]. The caller owns the result. */
    private fun applyContrast(source: Bitmap, contrast: Float): Bitmap {
        val enhanced = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val offset = -(0.5f * contrast - 0.5f) * 255f
        val luminanceRed = 0.299f * contrast
        val luminanceGreen = 0.587f * contrast
        val luminanceBlue = 0.114f * contrast
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
            Canvas(enhanced).drawBitmap(source, 0f, 0f, paint)
        } catch (error: Exception) {
            enhanced.recycle()
            throw error
        }
        return enhanced
    }

    /** Decodes the photo at no more than [maxEdge] on its longest side, already rotated upright. */
    private fun decodeOriented(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxEdge) {
            sampleSize *= 2
        }

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null

        val oriented = applyExifOrientation(file, decoded)
        if (oriented !== decoded) decoded.recycle()
        return oriented
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
