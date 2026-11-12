package com.n8rtz.winetracker.data.local

import kotlin.math.abs
import kotlin.math.roundToInt

/** Stable W-061 phase IDs. Labels are display-only and must never be persisted as IDs. */
enum class TastingPhase(val label: String) {
    // Pool for aromas picked on the wheel before the user drags them to a
    // real phase in the tasting note.
    UNCATEGORIZED("Uncategorized"),
    AROMA_BEFORE_TASTING("Aroma"),
    ENTRY("Entry"),
    MID_PALATE("Mid-palate"),
    FINISH("Finish");

    val sectionLabel: String
        get() = when (this) {
            UNCATEGORIZED -> "Uncategorized"
            AROMA_BEFORE_TASTING -> "Visual / Aroma"
            ENTRY -> "Entry"
            MID_PALATE -> "Mid-palate"
            FINISH -> "Finish"
        }
}

/** One opaque HSV color. No ARGB or palette ID is stored. */
data class HsvColor(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val value: Float = 1f
) {
    fun isValid(): Boolean =
        hue in 0f..360f && saturation in 0f..1f && value in 0f..1f &&
            !hue.isNaN() && !saturation.isNaN() && !value.isNaN() &&
            !hue.isInfinite() && !saturation.isInfinite() && !value.isInfinite()

    /** Pure JVM HSV -> ARGB conversion so UI code needs no Android graphics class. */
    fun toArgb(): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val c = value * saturation
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = value - c
        val sector = (h / 60f).toInt() % 6
        val (r, g, b) = when (sector) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255f).roundToInt().coerceIn(0, 255)
        val gi = ((g + m) * 255f).roundToInt().coerceIn(0, 255)
        val bi = ((b + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

/**
 * One descriptor-phase observation; descriptors may recur across phases.
 * `displayOrder` preserves order and breaks intensity ties.
 */
data class AromaObservation(
    val descriptorId: String = "",
    val phase: TastingPhase = TastingPhase.AROMA_BEFORE_TASTING,
    val intensity: Float? = null,
    val displayOrder: Int = 0
)
