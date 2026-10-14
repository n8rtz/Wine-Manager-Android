package com.n8rtz.winetracker.scanner

/**
 * Crop and enhancement settings for one OCR pass, plus the rule for when another pass is worth the
 * time. Pure data and policy so the ladder is unit tested; `LabelImageEnhancer` applies the recipe
 * and `ScannerManager` runs the passes.
 */
internal enum class LabelCropRecipe(
    val widthFraction: Float,
    val heightFraction: Float,
    val contrast: Float,
    val targetEdge: Int,
    val maxUpscale: Float
) {
    /** Centre crop with a mild contrast boost: the default second pass. */
    ENHANCED(widthFraction = 0.84f, heightFraction = 0.66f, contrast = 1.25f, targetEdge = 2_800, maxUpscale = 1.6f),

    /**
     * Tighter crop with a hard contrast push, for the two failures a gentle pass cannot fix:
     * low-contrast gold or embossed text, and glare-washed labels.
     */
    HIGH_CONTRAST(widthFraction = 0.72f, heightFraction = 0.52f, contrast = 2.0f, targetEdge = 3_000, maxUpscale = 1.8f)
}

/**
 * Decides whether a scan has produced a reading worth trusting, and if not, which extra OCR pass to
 * try. Escalation is what keeps the common case fast: pass one is the full frame, pass two the
 * enhanced crop, and the hard-contrast crop only runs when the first two left the reading
 * contradicted or unrecognised.
 */
internal object LabelScanLadder {
    /** OCR passes per scan, full frame included, to keep latency, heat and battery bounded. */
    const val MAX_OCR_PASSES = 3

    /**
     * Passes that run whether or not the first one looked convincing. The close-up crop is the pass
     * that recovers decorative words (the calligraphic "Merlot") that the full frame drops, so it is
     * not left to the confidence of the full-frame reading.
     */
    const val ALWAYS_RUN_PASSES = 2

    val CROP_RECIPES = listOf(LabelCropRecipe.ENHANCED, LabelCropRecipe.HIGH_CONTRAST)

    /**
     * How a field zoom magnifies the line it was pointed at. The crop is small, so the scale limit is
     * generous; the contrast stays mild because the crop has already been contrast-pushed.
     */
    val ZOOM_SETTINGS = LabelZoomSettings(targetEdge = 1_600, maxUpscale = 3.2f, contrast = 1.15f)

    /** Recipe for the pass after [passesRun] completed passes, or null when the ladder is spent. */
    fun recipeFor(passesRun: Int): LabelCropRecipe? = CROP_RECIPES.getOrNull(passesRun - 1)

    fun shouldEscalate(
        passesRun: Int,
        assessment: LabelScanAssessment.Report,
        identityResolved: Boolean,
        quality: CaptureQuality? = null
    ): Boolean {
        if (passesRun >= MAX_OCR_PASSES) return false
        // A photo too blurry to resolve the label has no detail left for another pass to find, so the
        // only useful answer is a retake. Dark or washed-out photos do escalate: contrast helps there.
        if (quality?.verdict == CaptureQuality.Verdict.BLURRY) return false
        // A barcode already named the bottle exactly; the full-frame reading is enough for the
        // vintage and region, so keep the scan fast.
        if (identityResolved) return false
        if (passesRun < ALWAYS_RUN_PASSES) return true
        return assessment.needsReview
    }

    /**
     * The next view to read, or null when this scan is finished escalating.
     *
     * Once the always-run passes are done there is one step left, and it is spent where it helps most:
     * on re-reading the contested field in place when the passes disagree about the text, otherwise on
     * the hard-contrast full view that rescues dark or glare-washed labels.
     */
    fun planFor(
        passesRun: Int,
        assessment: LabelScanAssessment.Report,
        identityResolved: Boolean,
        quality: CaptureQuality? = null,
        zoomRegion: LabelBox? = null
    ): LabelPassPlan? {
        if (!shouldEscalate(passesRun, assessment, identityResolved, quality)) return null
        val recipe = recipeFor(passesRun) ?: return null
        if (passesRun >= ALWAYS_RUN_PASSES && zoomRegion != null && assessment.contradictions.isNotEmpty()) {
            return LabelPassPlan.FieldZoom(
                region = zoomRegion,
                settings = ZOOM_SETTINGS,
                reason = "the passes disagreed, so re-read the line in place"
            )
        }
        return LabelPassPlan.FullView(
            recipe = recipe,
            reason = when (recipe) {
                LabelCropRecipe.ENHANCED -> "the close-up crop recovers words the full frame drops"
                LabelCropRecipe.HIGH_CONTRAST -> "the hard-contrast crop rescues dark or glare-washed text"
            }
        )
    }
}
