package com.n8rtz.winetracker.scanner

import kotlin.math.absoluteValue

/**
 * What the capture-quality check saw in a photo, and whether it is worth acting on.
 *
 * A scan that starts from a blurry or unusably dark photo cannot be fixed by more OCR passes, so this
 * is measured once, up front, from the photo itself: it decides whether escalating is worth the time
 * and whether the user should be told to retake the shot.
 */
internal data class CaptureQuality(
    val verdict: Verdict,
    /** Mean luma of the analysis grid, 0-255. */
    val meanLuma: Float,
    /** Strongest local detail found on the analysis grid: low values mean the photo is soft. */
    val sharpness: Float,
    /** Share of the frame that is bright *and* featureless, which is what glare looks like. */
    val glareShare: Float,
    val analysedWidth: Int,
    val analysedHeight: Int
) {
    enum class Verdict(val token: String) {
        OK("ok"),
        BLURRY("blurry"),
        TOO_DARK("too_dark"),
        GLARE("glare")
    }

    val needsRetake: Boolean get() = verdict != Verdict.OK

    /** One line for the scan log: what was measured, not just what was concluded. */
    fun summary(): String =
        "${verdict.token} (luma=%.0f sharpness=%.0f glare=%.2f on ${analysedWidth}x${analysedHeight})"
            .format(meanLuma, sharpness, glareShare)

    companion object {
        /** Used when the photo could not be measured: no opinion, so nothing is blocked. */
        val unknown = CaptureQuality(Verdict.OK, 0f, 0f, 0f, 0, 0)
    }
}

/**
 * Judges whether a photo is good enough to read, from its pixels alone.
 *
 * Everything is measured on a small fixed grid, because the sharpness metric is a second derivative
 * and so scales with resolution: on a fixed grid a soft photo is soft at every camera resolution,
 * while a sharp one keeps crisp edges however many pixels it started with.
 *
 * The verdict is deliberately conservative. Only gross problems are reported, since wrongly asking
 * for a retake is worse than quietly using a photo the recognizer can read. Brightness is judged
 * before sharpness because a dark frame has little contrast left to measure detail with.
 */
internal object CaptureQualityAssessor {
    /** Longest edge of the grid the metrics are measured on. */
    const val ANALYSIS_EDGE = 320

    /** Grid of tiles used to find the best-detailed part of the frame. */
    const val TILE_GRID = 4

    private const val MIN_MEAN_LUMA = 52f
    private const val WASHED_MEAN_LUMA = 225f

    /** Only clipped white counts as glare; normally lit label paper stays below this. */
    private const val GLARE_LUMA = 250f

    /** A clipped pixel is only glare when it is also flat: blown-out areas have no local detail. */
    private const val GLARE_SMOOTHNESS = 12f
    /**
     * Only a large clipped patch counts. Measured on real label photos, a tenth of the frame can
     * clip simply because the label is white and the photo is bright, which is not worth a retake;
     * a patch this size is light that has actually eaten the label.
     */
    private const val GLARE_SHARE = 0.15f

    /** Below this much local detail, no OCR pass can recover the label's small print. */
    private const val BLUR_VARIANCE = 200f

    /** The analysis grid for a photo: the photo itself when small, otherwise scaled to [ANALYSIS_EDGE]. */
    fun analysisSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "photo has no pixels" }
        val longest = maxOf(width, height)
        if (longest <= ANALYSIS_EDGE) return width to height
        val scale = ANALYSIS_EDGE.toFloat() / longest
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    fun assess(pixels: IntArray, width: Int, height: Int): CaptureQuality {
        val count = width * height
        if (width < 3 || height < 3 || pixels.size < count) return CaptureQuality.unknown

        val luma = FloatArray(count)
        var lumaSum = 0f
        for (index in 0 until count) {
            val value = lumaOf(pixels[index])
            luma[index] = value
            lumaSum += value
        }
        val meanLuma = lumaSum / count

        val tileColumns = minOf(TILE_GRID, width)
        val tileRows = minOf(TILE_GRID, height)
        val variance = tileVariances(luma, width, height, tileColumns, tileRows)
        val sharpness = variance.maxOrNull() ?: 0f

        val glarePixels = countGlare(luma, width, height)
        val glareShare = glarePixels.toFloat() / count

        val verdict = when {
            meanLuma < MIN_MEAN_LUMA -> CaptureQuality.Verdict.TOO_DARK
            glareShare >= GLARE_SHARE -> CaptureQuality.Verdict.GLARE
            // A frame with nothing but bright flat pixels is a blown-out shot, not a lit label.
            meanLuma > WASHED_MEAN_LUMA && sharpness < BLUR_VARIANCE -> CaptureQuality.Verdict.GLARE
            sharpness < BLUR_VARIANCE -> CaptureQuality.Verdict.BLURRY
            else -> CaptureQuality.Verdict.OK
        }
        return CaptureQuality(
            verdict = verdict,
            meanLuma = meanLuma,
            sharpness = sharpness,
            glareShare = glareShare,
            analysedWidth = width,
            analysedHeight = height
        )
    }

    /**
     * Counts clipped-white pixels that sit in a flat neighbourhood.
     *
     * A glare patch is white with no texture left inside it, while a normally lit label keeps detail
     * in every bright pixel it has, so requiring both brightness and flatness avoids blaming a label
     * that simply has a white background.
     */
    private fun countGlare(luma: FloatArray, width: Int, height: Int): Int {
        var glarePixels = 0
        for (y in 1 until height - 1) {
            val rowStart = y * width
            for (x in 1 until width - 1) {
                val index = rowStart + x
                if (luma[index] < GLARE_LUMA) continue
                val laplacian = 4f * luma[index] - (
                    luma[index - 1] + luma[index + 1] + luma[index - width] + luma[index + width]
                    )
                if (laplacian.absoluteValue < GLARE_SMOOTHNESS) glarePixels++
            }
        }
        return glarePixels
    }

    private fun lumaOf(argb: Int): Float {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return 0.299f * red + 0.587f * green + 0.114f * blue
    }

    /**
     * Variance of the Laplacian (four times the centre minus its neighbours) per tile, measured over
     * interior pixels. A tile holding printed text or a label edge has a high variance; a soft or
     * empty tile has almost none, so the strongest tile answers "is *any* part of this photo sharp?".
     */
    private fun tileVariances(
        luma: FloatArray,
        width: Int,
        height: Int,
        tileColumns: Int,
        tileRows: Int
    ): FloatArray {
        val tiles = tileRows * tileColumns
        val sum = FloatArray(tiles)
        val sumSquares = FloatArray(tiles)
        val counts = IntArray(tiles)
        for (y in 1 until height - 1) {
            val rowStart = y * width
            for (x in 1 until width - 1) {
                val index = rowStart + x
                val laplacian = 4f * luma[index] - (
                    luma[index - 1] + luma[index + 1] + luma[index - width] + luma[index + width]
                    )
                val tile = tileIndex(x, y, width, height, tileColumns, tileRows)
                sum[tile] += laplacian
                sumSquares[tile] += laplacian * laplacian
                counts[tile]++
            }
        }
        return FloatArray(tiles) { tile ->
            val count = counts[tile]
            if (count == 0) {
                0f
            } else {
                val value = sum[tile] / count
                (sumSquares[tile] / count - value * value).coerceAtLeast(0f)
            }
        }
    }

    private fun tileIndex(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        tileColumns: Int,
        tileRows: Int
    ): Int = (y * tileRows / height) * tileColumns + (x * tileColumns / width)
}
