package com.n8rtz.winetracker.ui.components

import kotlin.math.floor

/**
 * Picks the single font size every bottom-navigation label is drawn at.
 *
 * The bar gives each destination an equal share of the width, so the longest word is what decides how
 * large the labels can be. Sizing each label on its own — which is what Material's auto-sizing does —
 * shrinks only the long one and leaves the row uneven. Instead the width of the widest label, measured
 * at the largest size the design allows, is scaled to the width available, and that one size is used
 * for all of them: the row stays even and no label is ever larger than its neighbours.
 *
 * Pure arithmetic on measured widths, so the rule can be unit tested without laying out any UI.
 */
internal object BottomBarLabelSizing {
    /** The size the bar's labels are designed at: Material 3's label-medium token. */
    const val MAX_FONT_SIZE_SP = 12f

    /** Below this a label is no longer comfortably legible; the row would rather clip than shrink. */
    const val MIN_FONT_SIZE_SP = 8f

    /** Sizes come in halves, so the row never lands on a size nobody designed for. */
    const val STEP_SP = 0.5f

    /**
     * The largest step no bigger than [MAX_FONT_SIZE_SP] at which the widest label fits
     * [availableWidthPx].
     *
     * [widestLabelWidthPx] is that label's natural width measured at [MAX_FONT_SIZE_SP]. Text scales
     * with its font size, so that single measurement decides the whole row: a label that already fits
     * leaves every label at its designed size, and a space too narrow for even the smallest size stops
     * at [MIN_FONT_SIZE_SP] rather than shrinking the labels into nothing. A measurement of zero means
     * nothing was measured, and then the labels are left alone.
     */
    fun uniformFontSizeSp(widestLabelWidthPx: Float, availableWidthPx: Float): Float {
        if (widestLabelWidthPx <= 0f || availableWidthPx <= 0f) return MAX_FONT_SIZE_SP
        val scaled = MAX_FONT_SIZE_SP * (availableWidthPx / widestLabelWidthPx)
        val stepped = floor(scaled / STEP_SP) * STEP_SP
        return stepped.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    }
}
