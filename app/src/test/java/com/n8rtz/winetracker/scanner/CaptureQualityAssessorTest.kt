package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture-quality gate is calibrated against what real label photos measure, so these tests build
 * the same shapes: lit paper with printed text, then the same photo blurred, darkened or blown out.
 */
class CaptureQualityAssessorTest {
    @Test
    fun aSharpLabelPhotoIsAccepted() {
        val quality = assess(labelPhoto())

        assertEquals(CaptureQuality.Verdict.OK, quality.verdict)
        assertFalse(quality.needsRetake)
        assertTrue("printed text leaves strong local detail", quality.sharpness > 200f)
        assertEquals(0f, quality.glareShare, 0.001f)
    }

    @Test
    fun aBlurredPhotoAsksForARetake() {
        val sharp = labelPhoto()
        val quality = assess(sharp.blurred(WIDTH, HEIGHT, radius = 9))

        assertEquals(CaptureQuality.Verdict.BLURRY, quality.verdict)
        assertTrue(quality.needsRetake)
        assertTrue("blur destroys local detail", quality.sharpness < 200f)
        // The photo is still properly lit: the verdict must be about detail, not brightness.
        assertTrue(quality.meanLuma > 52f)
    }

    @Test
    fun aDarkPhotoIsReportedAsTooDarkRatherThanBlurry() {
        val quality = assess(labelPhoto(paper = 40, ink = 20))

        assertEquals(CaptureQuality.Verdict.TOO_DARK, quality.verdict)
        assertTrue(quality.meanLuma < 52f)
    }

    @Test
    fun aBlownHighlightIsReportedAsGlareButBrightPaperIsNot() {
        val glare = assess(labelPhoto().withBlownPatch())
        assertEquals(CaptureQuality.Verdict.GLARE, glare.verdict)
        assertTrue("a large flat white patch", glare.glareShare >= 0.15f)

        // Bright paper keeps its texture, which is what tells a lit label apart from lost detail.
        val brightPaper = assess(labelPhoto(paper = 245, ink = 120))
        assertEquals(CaptureQuality.Verdict.OK, brightPaper.verdict)
        assertEquals(0f, brightPaper.glareShare, 0.001f)
    }

    @Test
    fun aWashedOutPhotoWithNoDetailLeftIsReportedAsGlare() {
        val quality = assess(IntArray(WIDTH * HEIGHT) { argb(255) })

        assertEquals(CaptureQuality.Verdict.GLARE, quality.verdict)
    }

    @Test
    fun analysisSizeKeepsThePhotoInsideTheGrid() {
        assertEquals(320 to 240, CaptureQualityAssessor.analysisSize(4000, 3000))
        assertEquals(240 to 320, CaptureQualityAssessor.analysisSize(3000, 4000))
        assertEquals(240 to 320, CaptureQualityAssessor.analysisSize(300, 400))
        // A degenerate photo still yields a grid with at least one row and column.
        assertEquals(1 to 320, CaptureQualityAssessor.analysisSize(1, 400))
    }

    @Test
    fun aPhotoWithNoUsablePixelsIsNotJudged() {
        val quality = CaptureQualityAssessor.assess(IntArray(4), 2, 2)

        assertEquals(CaptureQuality.Verdict.OK, quality.verdict)
        assertFalse("an unmeasurable photo must never block a scan", quality.needsRetake)
    }

    private fun assess(pixels: IntArray): CaptureQuality =
        CaptureQualityAssessor.assess(pixels, WIDTH, HEIGHT)

    /** Lit paper with printed text bands: sharp edges, no clipped white, nothing to complain about. */
    private fun labelPhoto(paper: Int = 235, ink: Int = 30): IntArray = IntArray(WIDTH * HEIGHT) { index ->
        val x = index % WIDTH
        val y = index / WIDTH
        val printed = (y / 12) % 2 == 0 && x % 24 < 12
        argb(if (printed) ink else paper)
    }

    /** A flat clipped patch covering about a fifth of the frame, the way a glare hotspot lands. */
    private fun IntArray.withBlownPatch(): IntArray {
        val copy = copyOf()
        for (y in HEIGHT / 5 until HEIGHT * 3 / 5) {
            for (x in WIDTH / 5 until WIDTH * 4 / 5) {
                copy[y * WIDTH + x] = argb(255)
            }
        }
        return copy
    }

    private fun IntArray.blurred(width: Int, height: Int, radius: Int): IntArray {
        var pixels = this
        repeat(2) {
            pixels = pixels.horizontalBlur(width, height, radius).verticalBlur(width, height, radius)
        }
        return pixels
    }

    private fun IntArray.horizontalBlur(width: Int, height: Int, radius: Int): IntArray {
        val output = IntArray(size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dx in -radius..radius) {
                    val sample = x + dx
                    if (sample in 0 until width) {
                        sum += this[y * width + sample] and 0xFF
                        count++
                    }
                }
                output[y * width + x] = argb(sum / count)
            }
        }
        return output
    }

    private fun IntArray.verticalBlur(width: Int, height: Int, radius: Int): IntArray {
        val output = IntArray(size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dy in -radius..radius) {
                    val sample = y + dy
                    if (sample in 0 until height) {
                        sum += this[sample * width + x] and 0xFF
                        count++
                    }
                }
                output[y * width + x] = argb(sum / count)
            }
        }
        return output
    }

    private fun argb(value: Int): Int = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
    }
}
