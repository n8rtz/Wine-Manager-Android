package com.n8rtz.winetracker.debug.graniteworkflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Standalone image preprocessor for the Dual OCR + Granite 4.2 workflow.
 *
 * Rules:
 * - Accepts images from Android Photo Picker / Gallery (`Uri`), CameraX capture (`File`), or `Bitmap`.
 * - Preserves aspect ratio and resizes ONLY if the longest edge exceeds `1280px`.
 * - NEVER downscales below `768px` (or when longest edge <= `1280px`), keeping 6pt vintage and ABV print sharp.
 * - Optional mild CLAHE (8x8 tiles, clipLimit = 2.0) + highlight glare roll-off for curved glass bottles.
 */
object GraniteWorkflowImagePreprocessor {
    const val MAX_LONGEST_EDGE_PX = 1280
    const val MIN_LONGEST_EDGE_PX = 768
    private const val CLAHE_TILES_X = 8
    private const val CLAHE_TILES_Y = 8
    private const val CLAHE_CLIP_LIMIT = 2.0f

    data class PreprocessedImage(
        val bitmap: Bitmap,
        val originalWidth: Int,
        val originalHeight: Int,
        val width: Int,
        val height: Int,
        val claheApplied: Boolean,
        val elapsedMs: Long
    )

    /**
     * Pure dimension calculation preserving aspect ratio:
     * - If longest edge <= 1280px, returns `(origWidth, origHeight)` unchanged (never downscales below 768px).
     * - If longest edge > 1280px, scales down proportionally so the longest edge is `1280px`.
     */
    fun computeTargetDimensions(origWidth: Int, origHeight: Int): Pair<Int, Int> {
        require(origWidth > 0 && origHeight > 0) {
            "Invalid image dimensions: ${origWidth}x${origHeight}"
        }
        val longestEdge = max(origWidth, origHeight)
        if (longestEdge <= MAX_LONGEST_EDGE_PX) {
            return origWidth to origHeight
        }
        val scale = MAX_LONGEST_EDGE_PX.toDouble() / longestEdge.toDouble()
        val targetWidth = (origWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (origHeight * scale).roundToInt().coerceAtLeast(1)
        // Guardrail: never allow downscaling to push longest edge below 768px
        val resultLongest = max(targetWidth, targetHeight)
        if (resultLongest < MIN_LONGEST_EDGE_PX && longestEdge >= MIN_LONGEST_EDGE_PX) {
            val minScale = MIN_LONGEST_EDGE_PX.toDouble() / longestEdge.toDouble()
            return (origWidth * minScale).roundToInt().coerceAtLeast(1) to
                (origHeight * minScale).roundToInt().coerceAtLeast(1)
        }
        return targetWidth to targetHeight
    }

    /**
     * Preprocess an image selected from the Android Photo Picker / Gallery.
     */
    fun preprocessUri(
        context: Context,
        uri: Uri,
        enableClahe: Boolean
    ): PreprocessedImage {
        val started = SystemClock.elapsedRealtime()

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        } ?: error("Unable to open image URI: $uri")

        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        require(rawW > 0 && rawH > 0) { "Failed to decode image bounds from URI: $uri" }

        val exifOrientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val sampleSize = computeSafeInSampleSize(rawW, rawH)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: error("Failed to decode bitmap from URI: $uri")

        val (origUprightW, origUprightH) = uprightDimensions(rawW, rawH, exifOrientation)
        return finishPreprocessing(
            decoded = decoded,
            exifOrientation = exifOrientation,
            originalWidth = origUprightW,
            originalHeight = origUprightH,
            enableClahe = enableClahe,
            startedRealtimeMs = started
        )
    }

    /**
     * Preprocess an image File from CameraX capture or local storage.
     */
    fun preprocessFile(
        file: File,
        enableClahe: Boolean
    ): PreprocessedImage {
        val started = SystemClock.elapsedRealtime()
        require(file.isFile && file.length() > 0L) { "Image file does not exist or is empty: ${file.absolutePath}" }

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        require(rawW > 0 && rawH > 0) { "Failed to decode image bounds from file: ${file.name}" }

        val exifOrientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val sampleSize = computeSafeInSampleSize(rawW, rawH)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: error("Failed to decode bitmap from file: ${file.name}")

        val (origUprightW, origUprightH) = uprightDimensions(rawW, rawH, exifOrientation)
        return finishPreprocessing(
            decoded = decoded,
            exifOrientation = exifOrientation,
            originalWidth = origUprightW,
            originalHeight = origUprightH,
            enableClahe = enableClahe,
            startedRealtimeMs = started
        )
    }

    /**
     * Preprocess an in-memory [Bitmap].
     */
    fun preprocessBitmap(
        source: Bitmap,
        enableClahe: Boolean
    ): PreprocessedImage {
        val started = SystemClock.elapsedRealtime()
        return finishPreprocessing(
            decoded = source,
            exifOrientation = ExifInterface.ORIENTATION_NORMAL,
            originalWidth = source.width,
            originalHeight = source.height,
            enableClahe = enableClahe,
            startedRealtimeMs = started
        )
    }

    private fun finishPreprocessing(
        decoded: Bitmap,
        exifOrientation: Int,
        originalWidth: Int,
        originalHeight: Int,
        enableClahe: Boolean,
        startedRealtimeMs: Long
    ): PreprocessedImage {
        val upright = applyExifOrientation(decoded, exifOrientation)
        val (targetW, targetH) = computeTargetDimensions(upright.width, upright.height)

        val resized = if (targetW != upright.width || targetH != upright.height) {
            Bitmap.createScaledBitmap(upright, targetW, targetH, true)
        } else if (upright.config != Bitmap.Config.ARGB_8888) {
            upright.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            upright
        }

        val finalBitmap = if (enableClahe) {
            applyMildClaheAndGlareReduction(resized)
        } else {
            resized
        }

        val elapsed = (SystemClock.elapsedRealtime() - startedRealtimeMs).coerceAtLeast(1L)
        return PreprocessedImage(
            bitmap = finalBitmap,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            width = finalBitmap.width,
            height = finalBitmap.height,
            claheApplied = enableClahe,
            elapsedMs = elapsed
        )
    }

    /**
     * Mild 8x8 tile-interpolated CLAHE on the luminance channel + specular highlight roll-off
     * to reduce foil/glass glare on curved wine bottles while preserving fine 6pt print.
     */
    fun applyMildClaheAndGlareReduction(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 8 || height < 8) return source

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Extract luminance [0..255] with gentle specular highlight compression above 232
        val lum = IntArray(width * height)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            var y = (77 * r + 150 * g + 29 * b) ushr 8
            if (y > 232) {
                // Softly compress harsh foil glare spikes in [233..255]
                y = 232 + ((y - 232) * 14) / 23
            }
            lum[i] = y.coerceIn(0, 255)
        }

        val tilesX = CLAHE_TILES_X
        val tilesY = CLAHE_TILES_Y
        val luts = Array(tilesY * tilesX) { IntArray(256) }

        for (ty in 0 until tilesY) {
            val y0 = (ty * height) / tilesY
            val y1 = ((ty + 1) * height) / tilesY
            for (tx in 0 until tilesX) {
                val x0 = (tx * width) / tilesX
                val x1 = ((tx + 1) * width) / tilesX
                val tilePixels = (x1 - x0) * (y1 - y0)
                if (tilePixels <= 0) continue

                val hist = IntArray(256)
                for (y in y0 until y1) {
                    val rowOffset = y * width
                    for (x in x0 until x1) {
                        hist[lum[rowOffset + x]]++
                    }
                }

                // Clip histogram at CLAHE_CLIP_LIMIT * average bin count and redistribute excess
                val clipThreshold = max(1, ((CLAHE_CLIP_LIMIT * tilePixels) / 256f).roundToInt())
                var excess = 0
                for (bin in 0..255) {
                    if (hist[bin] > clipThreshold) {
                        excess += hist[bin] - clipThreshold
                        hist[bin] = clipThreshold
                    }
                }
                val redistrib = excess / 256
                val remainder = excess % 256
                for (bin in 0..255) {
                    hist[bin] += redistrib + if (bin < remainder) 1 else 0
                }

                // Build CDF LUT blended 65% CLAHE + 35% identity for mild, natural contrast
                val lut = luts[ty * tilesX + tx]
                var cdf = 0
                for (bin in 0..255) {
                    cdf += hist[bin]
                    val eq = ((cdf.toLong() * 255L) / tilePixels.toLong()).toInt().coerceIn(0, 255)
                    lut[bin] = ((eq * 65 + bin * 35) / 100).coerceIn(0, 255)
                }
            }
        }

        val tileW = width.toFloat() / tilesX.toFloat()
        val tileH = height.toFloat() / tilesY.toFloat()

        for (y in 0 until height) {
            val gy = (y + 0.5f) / tileH - 0.5f
            val ty0 = gy.toInt().coerceIn(0, tilesY - 1)
            val ty1 = (ty0 + 1).coerceIn(0, tilesY - 1)
            val fy = (gy - ty0).coerceIn(0f, 1f)
            val rowOffset = y * width

            for (x in 0 until width) {
                val gx = (x + 0.5f) / tileW - 0.5f
                val tx0 = gx.toInt().coerceIn(0, tilesX - 1)
                val tx1 = (tx0 + 1).coerceIn(0, tilesX - 1)
                val fx = (gx - tx0).coerceIn(0f, 1f)

                val idx = rowOffset + x
                val oldY = lum[idx]

                val v00 = luts[ty0 * tilesX + tx0][oldY]
                val v10 = luts[ty0 * tilesX + tx1][oldY]
                val v01 = luts[ty1 * tilesX + tx0][oldY]
                val v11 = luts[ty1 * tilesX + tx1][oldY]

                val topInterp = v00 + fx * (v10 - v00)
                val botInterp = v01 + fx * (v11 - v01)
                val newY = (topInterp + fy * (botInterp - topInterp)).roundToInt().coerceIn(0, 255)

                val px = pixels[idx]
                val a = px and -0x1000000
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF

                val delta = newY - oldY
                val nr = (r + delta).coerceIn(0, 255)
                val ng = (g + delta).coerceIn(0, 255)
                val nb = (b + delta).coerceIn(0, 255)
                pixels[idx] = a or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun computeSafeInSampleSize(rawWidth: Int, rawHeight: Int): Int {
        val longest = max(rawWidth, rawHeight)
        var inSampleSize = 1
        while (longest / (inSampleSize * 2) >= MAX_LONGEST_EDGE_PX) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun uprightDimensions(width: Int, height: Int, orientation: Int): Pair<Int, Int> {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> height to width
            else -> width to height
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
