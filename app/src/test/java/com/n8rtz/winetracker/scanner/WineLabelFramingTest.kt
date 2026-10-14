package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The camera guide has to be the shape of a real bottle label, and everything the user fits in it has
 * to survive every crop the scanner takes. Both are properties of the same few numbers, so they are
 * pinned here rather than left to how the scan screen happens to be laid out.
 */
class WineLabelFramingTest {
    @Test
    fun theGuideIsPortraitLikeABottleLabel() {
        assertTrue(
            "a wine label is taller than it is wide",
            WineLabelFraming.ASPECT_RATIO < 1f
        )
        assertTrue(
            "and about three quarters as wide as it is tall",
            WineLabelFraming.ASPECT_RATIO in 0.65f..0.8f
        )
        assertTrue(
            "the box leaves room for the bars above and below it",
            WineLabelFraming.GUIDE_HEIGHT_FRACTION in 0.2f..0.6f
        )
    }

    @Test
    fun aLabelFramedInTheGuideIsInsideEveryCropTheScannerTakes() {
        // A 12 MP phone photo of a bottle, shown in a typical full-screen preview.
        val photoWidth = 3_072
        val photoHeight = 4_096
        val previewWidth = 360f
        val previewHeight = 740f

        val framed = WineLabelFraming.guideRegionInPhoto(photoWidth, photoHeight, previewWidth, previewHeight)

        LabelScanLadder.CROP_RECIPES.forEach { recipe ->
            val crop = centeredCrop(photoWidth, photoHeight, recipe)
            assertTrue(
                "${recipe.name} cuts the label the user framed " +
                    "(framed=$framed, crop=$crop)",
                crop.contains(framed)
            )
        }
    }

    @Test
    fun aLabelFramedInTheGuideSurvivesTheCropsOnAShortWideScreenToo() {
        // Landscape rotation: the preview shows the full width and crops the photo's height instead.
        val photoWidth = 3_072
        val photoHeight = 4_096
        val previewWidth = 740f
        val previewHeight = 360f

        val framed = WineLabelFraming.guideRegionInPhoto(photoWidth, photoHeight, previewWidth, previewHeight)

        LabelScanLadder.CROP_RECIPES.forEach { recipe ->
            val crop = centeredCrop(photoWidth, photoHeight, recipe)
            assertEquals("${recipe.name} view is centred on the frame", photoWidth / 2, (crop.left + crop.right) / 2)
            assertTrue("${recipe.name} cuts the framed label (framed=$framed, crop=$crop)", crop.contains(framed))
        }
    }

    @Test
    fun theGuideNeverTakesMoreOfThePreviewThanTheCropsAllow() {
        // The preview only ever shows part of the frame, so the framed region can never be a bigger
        // share of the photo than this. The tightest pass crop has to stay wider than that share.
        val tightest = LabelScanLadder.CROP_RECIPES.last()
        val framed = WineLabelFraming.GUIDE_HEIGHT_FRACTION * WineLabelFraming.ASPECT_RATIO

        assertTrue(
            "the guide's share of the photo height fits inside ${tightest.name}",
            WineLabelFraming.GUIDE_HEIGHT_FRACTION < tightest.heightFraction
        )
        assertTrue(
            "so does its share of the photo width",
            framed < tightest.widthFraction
        )
        assertTrue(
            "and it stays within the width budget the guide allows itself",
            framed < WineLabelFraming.GUIDE_WIDTH_FRACTION
        )
    }

    /** The centre crop a recipe describes, in the photo's pixels. */
    private fun centeredCrop(width: Int, height: Int, recipe: LabelCropRecipe): LabelBox {
        val cropWidth = (width * recipe.widthFraction).roundToInt()
        val cropHeight = (height * recipe.heightFraction).roundToInt()
        return LabelBox(
            left = (width - cropWidth) / 2,
            top = (height - cropHeight) / 2,
            right = (width - cropWidth) / 2 + cropWidth,
            bottom = (height - cropHeight) / 2 + cropHeight
        )
    }
}
