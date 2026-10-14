package com.n8rtz.winetracker.scanner

import kotlin.math.roundToInt

/** How hard one targeted re-read magnifies and enhances the region it was pointed at. */
internal data class LabelZoomSettings(
    val targetEdge: Int,
    val maxUpscale: Float,
    val contrast: Float
)

/**
 * The transformation [LabelImageEnhancer] applied to build a pass's bitmap, so a box the recognizer
 * reported on that bitmap can be placed back on the photo the crops are taken from.
 *
 * Both sides call the same functions here, which is what keeps the enhancer's crop arithmetic and the
 * geometry that points the next crop at a line from drifting apart. Pure arithmetic, no SDK.
 */
internal data class LabelCropMapping(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val scaledWidth: Int,
    val scaledHeight: Int
) {
    val scaleX: Float get() = scaledWidth.toFloat() / cropWidth
    val scaleY: Float get() = scaledHeight.toFloat() / cropHeight

    /** Where a box from this pass's bitmap sits on the decoded source frame. */
    fun toSource(box: LabelBox): LabelBox = LabelBox(
        left = cropLeft + (box.left / scaleX).roundToInt(),
        top = cropTop + (box.top / scaleY).roundToInt(),
        right = cropLeft + (box.right / scaleX).roundToInt(),
        bottom = cropTop + (box.bottom / scaleY).roundToInt()
    ).clampTo(sourceWidth, sourceHeight)

    companion object {
        /** The centre crop a [LabelCropRecipe] describes, mirroring the enhancer exactly. */
        fun forRecipe(sourceWidth: Int, sourceHeight: Int, recipe: LabelCropRecipe): LabelCropMapping {
            val cropWidth = (sourceWidth * recipe.widthFraction).roundToInt().coerceAtLeast(1)
            val cropHeight = (sourceHeight * recipe.heightFraction).roundToInt().coerceAtLeast(1)
            return of(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                cropLeft = (sourceWidth - cropWidth) / 2,
                cropTop = (sourceHeight - cropHeight) / 2,
                cropWidth = cropWidth,
                cropHeight = cropHeight,
                targetEdge = recipe.targetEdge,
                maxUpscale = recipe.maxUpscale
            )
        }

        /** The crop [region] describes, taken from the source frame instead of from its centre. */
        fun forRegion(
            sourceWidth: Int,
            sourceHeight: Int,
            region: LabelBox,
            settings: LabelZoomSettings
        ): LabelCropMapping {
            val clamped = region.clampTo(sourceWidth, sourceHeight)
            return of(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                cropLeft = clamped.left,
                cropTop = clamped.top,
                cropWidth = clamped.width.coerceAtLeast(1),
                cropHeight = clamped.height.coerceAtLeast(1),
                targetEdge = settings.targetEdge,
                maxUpscale = settings.maxUpscale
            )
        }

        private fun of(
            sourceWidth: Int,
            sourceHeight: Int,
            cropLeft: Int,
            cropTop: Int,
            cropWidth: Int,
            cropHeight: Int,
            targetEdge: Int,
            maxUpscale: Float
        ): LabelCropMapping {
            val scale = (targetEdge.toFloat() / maxOf(cropWidth, cropHeight)).coerceIn(1f, maxUpscale)
            val upscaled = scale > 1.01f
            return LabelCropMapping(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropWidth = cropWidth,
                cropHeight = cropHeight,
                scaledWidth = if (upscaled) (cropWidth * scale).roundToInt().coerceAtLeast(1) else cropWidth,
                scaledHeight = if (upscaled) (cropHeight * scale).roundToInt().coerceAtLeast(1) else cropHeight
            )
        }
    }
}
