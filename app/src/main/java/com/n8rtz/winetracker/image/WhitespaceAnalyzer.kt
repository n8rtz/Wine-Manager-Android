package com.n8rtz.winetracker.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class WhitespaceResult(
    val url: String,
    val whitespaceScore: Double,   // 0.0 to 1.0 — higher = more white background
    val passesThreshold: Boolean   // true if score >= MINIMUM_WHITESPACE
) {
    companion object {
        // Minimum whitespace to accept without checking other sources
        const val GOOD_THRESHOLD = 0.70

        // Absolute minimum — below this, reject entirely even if best available
        const val MINIMUM_THRESHOLD = 0.05
    }
}

class WhitespaceAnalyzer(context: Context) {

    private val imageLoader = ImageLoader.Builder(context)
        .crossfade(false)
        .build()

    /**
     * Downloads a single image and measures how much of the border
     * area is white/near-white. Returns a score from 0.0 to 1.0.
     *
     * Only call this on the SINGLE BEST metadata-scored candidate
     * from each source — never on all candidates.
     */
    suspend fun analyze(url: String, context: Context): WhitespaceResult? {
        return withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)  // Need software bitmap for pixel access
                    .size(200, 300)        // Downsample — we only need border pixels
                    .build()

                val result = imageLoader.execute(request)
                if (result !is SuccessResult) return@withContext null

                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    ?: return@withContext null

                val score = measureWhitespace(bitmap)

                WhitespaceResult(
                    url = url,
                    whitespaceScore = score,
                    passesThreshold = score >= WhitespaceResult.GOOD_THRESHOLD
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Measures whitespace by sampling pixels around all four borders
     * of the image. Stock product photos have white/near-white borders.
     * User photos have shelves, tables, dark backgrounds.
     *
     * Also checks the four corners specifically — stock photos almost
     * always have pure white corners.
     */
    private fun measureWhitespace(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height

        if (w < 10 || h < 10) return 0.0

        val samplePoints = mutableListOf<Int>()
        val margin = 0.08  // Sample outer 8% of image
        val xMargin = (w * margin).toInt().coerceAtLeast(2)
        val yMargin = (h * margin).toInt().coerceAtLeast(2)
        val step = 3  // Sample every 3rd pixel for speed

        // Top edge
        for (x in 0 until w step step) {
            for (y in 0 until yMargin) {
                samplePoints.add(bitmap.getPixel(x, y))
            }
        }
        // Bottom edge
        for (x in 0 until w step step) {
            for (y in (h - yMargin) until h) {
                samplePoints.add(bitmap.getPixel(x, y))
            }
        }
        // Left edge
        for (y in 0 until h step step) {
            for (x in 0 until xMargin) {
                samplePoints.add(bitmap.getPixel(x, y))
            }
        }
        // Right edge
        for (y in 0 until h step step) {
            for (x in (w - xMargin) until w) {
                samplePoints.add(bitmap.getPixel(x, y))
            }
        }

        if (samplePoints.isEmpty()) return 0.0

        // Count how many border pixels are "white-ish"
        // White = high R, high G, high B, all similar to each other
        val whiteCount = samplePoints.count { pixel ->
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val brightness = (r + g + b) / 3
            val uniformity = maxOf(abs(r - g), abs(g - b), abs(r - b))

            // Bright (>200) and uniform color (not tinted strongly)
            brightness > 200 && uniformity < 30
        }

        val borderScore = whiteCount.toDouble() / samplePoints.size

        // Bonus: check the four corners specifically
        // Stock photos have pure white corners almost always
        val cornerPixels = listOf(
            bitmap.getPixel(1, 1),                     // Top-left
            bitmap.getPixel(w - 2, 1),                 // Top-right
            bitmap.getPixel(1, h - 2),                 // Bottom-left
            bitmap.getPixel(w - 2, h - 2)              // Bottom-right
        )

        val whiteCorners = cornerPixels.count { pixel ->
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            r > 230 && g > 230 && b > 230
        }

        val cornerScore = whiteCorners.toDouble() / 4.0

        // Weighted: border analysis matters most, corners are a bonus signal
        return (borderScore * 0.75) + (cornerScore * 0.25)
    }
}
