package com.n8rtz.winetracker.scanner

/**
 * What one escalation step should read, and why it is worth the time.
 *
 * The ladder has two kinds of step. A full view reads the whole label (the frame or a centre crop of
 * it) and therefore defines the label's reading order. A field zoom re-reads a single line at high
 * magnification: it can only correct or add detail to that line, never reorder the label.
 */
internal sealed interface LabelPassPlan {
    val reason: String

    data class FullView(val recipe: LabelCropRecipe, override val reason: String) : LabelPassPlan

    data class FieldZoom(
        val region: LabelBox,
        val settings: LabelZoomSettings,
        override val reason: String
    ) : LabelPassPlan

    /** How this step's reading may be used when the passes are merged. */
    val role: LabelOcrHypothesis.Role
        get() = when (this) {
            is FullView -> LabelOcrHypothesis.Role.FULL_VIEW
            is FieldZoom -> LabelOcrHypothesis.Role.TARGETED
        }

    val logLabel: String
        get() = when (this) {
            is FullView -> recipe.name
            is FieldZoom -> "FIELD_ZOOM"
        }
}
