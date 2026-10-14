package com.n8rtz.winetracker.scanner

import kotlin.math.roundToInt

/**
 * The shape of a wine label, and how much of the camera preview the guide box takes.
 *
 * A bottle's front label is portrait — taller than it is wide, about three quarters as wide as it is
 * tall on nearly every bottle — so the scanner's frame guide is a portrait box of that shape rather
 * than a wide one no bottle matches.
 *
 * The guide also has to sit inside the crops the OCR passes actually read. The preview shows a
 * centre-crop of the camera frame, so a box taking this share of the preview can never cover more
 * than this share of the photo, and both fractions stay below the tightest recipe's centred crop
 * (`HIGH_CONTRAST`). A label the user fits into the guide is therefore inside every pass: no pass can
 * end up looking at a crop that cut the label off. `WineLabelFramingTest` checks that with real photo
 * and screen dimensions, so changing either side of it has to be deliberate.
 */
internal object WineLabelFraming {
    /** Width over height of a typical bottle label: 3:4 portrait. */
    const val ASPECT_RATIO = 0.75f

    /** Share of the preview's height the guide box takes, leaving room for the bars around it. */
    const val GUIDE_HEIGHT_FRACTION = 0.46f

    /**
     * Widest share of the preview the guide may take. The height fraction and the label's own shape
     * already keep the box well inside this on every screen; it is the budget the crop recipes allow.
     */
    const val GUIDE_WIDTH_FRACTION = 0.62f

    /**
     * The guide box's region of the photo, for a preview that shows [previewWidth] x [previewHeight]
     * with the camera scaled to cover it (the way `PreviewView.FILL_CENTER` does).
     *
     * The preview fills its view from a centre-crop of the frame, so the scale is uniform: the box's
     * share of the axis the preview shows in full is its share of the photo in that axis too. Centred,
     * like the guide and every crop the ladder takes.
     */
    fun guideRegionInPhoto(
        photoWidth: Int,
        photoHeight: Int,
        previewWidth: Float,
        previewHeight: Float
    ): LabelBox {
        val scale = maxOf(previewWidth / photoWidth, previewHeight / photoHeight)
        val regionHeight = GUIDE_HEIGHT_FRACTION * previewHeight / scale
        val regionWidth = regionHeight * ASPECT_RATIO
        val halfWidth = (regionWidth / 2).roundToInt()
        val halfHeight = (regionHeight / 2).roundToInt()
        val centerX = photoWidth / 2
        val centerY = photoHeight / 2
        return LabelBox(
            left = centerX - halfWidth,
            top = centerY - halfHeight,
            right = centerX + halfWidth,
            bottom = centerY + halfHeight
        )
    }
}
