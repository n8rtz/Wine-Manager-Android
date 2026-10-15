package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping is the one piece of arithmetic the enhancer and the crop geometry share, so these tests
 * pin the crop each recipe describes and the way a recognizer box is placed back on the photo.
 */
class LabelCropMappingTest {
    @Test
    fun theEnhancedRecipeDescribesACentreCropThatFitsThePhoto() {
        val mapping = LabelCropMapping.forRecipe(4000, 3000, LabelCropRecipe.ENHANCED)

        assertEquals(3360, mapping.cropWidth)
        assertEquals(1980, mapping.cropHeight)
        assertEquals(320, mapping.cropLeft)
        assertEquals(510, mapping.cropTop)
        // A large photo is never upscaled: the crop is already bigger than the recipe's target edge.
        assertEquals(3360, mapping.scaledWidth)
        assertEquals(1f, mapping.scaleX, 0.0001f)
        assertEquals(4000, mapping.sourceWidth)
    }

    @Test
    fun aSmallPhotoIsMagnifiedUpToButNotPastItsLimit() {
        val mapping = LabelCropMapping.forRecipe(800, 600, LabelCropRecipe.HIGH_CONTRAST)

        assertEquals(576, mapping.cropWidth)
        assertEquals(312, mapping.cropHeight)
        // 3000/576 would be 5.2x, which the recipe caps at 1.8x.
        assertEquals(1.8f, mapping.scaleX, 0.01f)
        assertEquals(1037, mapping.scaledWidth)
        assertEquals(562, mapping.scaledHeight)
    }

    @Test
    fun aRecognizerBoxIsPlacedBackOnThePhoto() {
        val region = LabelBox(left = 100, top = 100, right = 300, bottom = 200)
        val mapping = LabelCropMapping.forRegion(
            sourceWidth = 1000,
            sourceHeight = 800,
            region = region,
            settings = LabelZoomSettings(targetEdge = 800, maxUpscale = 3f, contrast = 1.15f)
        )

        // The 200x100 region is magnified 3x, the cap, giving a 600x300 bitmap.
        assertEquals(600, mapping.scaledWidth)
        assertEquals(3f, mapping.scaleX, 0.0001f)

        val box = LabelBox(left = 0, top = 0, right = 60, bottom = 30)
        assertEquals(LabelBox(100, 100, 120, 110), mapping.toSource(box))
    }

    @Test
    fun aBoxOutsideTheCropIsClampedToThePhoto() {
        val mapping = LabelCropMapping.forRegion(
            sourceWidth = 1000,
            sourceHeight = 800,
            region = LabelBox(100, 100, 300, 200),
            settings = LabelZoomSettings(targetEdge = 800, maxUpscale = 1f, contrast = 1.15f)
        )

        val outside = LabelBox(left = -500, top = -500, right = 2000, bottom = 2000)
        assertEquals(LabelBox(0, 0, 1000, 800), mapping.toSource(outside))
    }

    @Test
    fun aRegionIsClampedInsideThePhotoBeforeItIsCropped() {
        val mapping = LabelCropMapping.forRegion(
            sourceWidth = 1000,
            sourceHeight = 800,
            region = LabelBox(-30, -20, 400, 300),
            settings = LabelZoomSettings(targetEdge = 800, maxUpscale = 1f, contrast = 1.15f)
        )

        assertEquals(0, mapping.cropLeft)
        assertEquals(0, mapping.cropTop)
        assertEquals(400, mapping.cropWidth)
        assertEquals(300, mapping.cropHeight)
    }

    @Test
    fun aBoxGrowsForPaddingAndForAUsableCrop() {
        val line = LabelBox(left = 40, top = 100, right = 140, bottom = 120)

        // Padding is a share of the longer edge (100px here), so the line gains 10px on every side.
        val padded = line.pad(0.1f)
        assertEquals(LabelBox(30, 90, 150, 130), padded)

        val grown = padded.growToMinEdge(200)
        assertTrue("the crop is wide enough to magnify", grown.longestEdge >= 200)
        assertTrue("growing keeps the line inside the crop", grown.contains(padded))

        assertEquals(LabelBox(0, 0, 1000, 800), LabelBox(-5, -5, 1200, 900).clampTo(1000, 800))
    }

    @Test
    fun boxesUnionToCoverBothLines() {
        val first = LabelBox(0, 0, 200, 40)
        val second = LabelBox(0, 42, 180, 82)

        val union = first.union(second)

        assertEquals(LabelBox(0, 0, 200, 82), union)
        assertTrue(union.contains(first))
        assertTrue(union.contains(second))
        assertTrue("a real box is valid", first.isValid)
        assertFalse("an empty box is not", LabelBox(10, 10, 10, 10).isValid)
    }
}
