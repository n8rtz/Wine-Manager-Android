package com.n8rtz.winetracker.scanner

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Finds the line a field was read from, so the next pass can magnify exactly that line.
 *
 * Only passes that know how their bitmap maps back to the photo can be used, which in practice means
 * the centre crops: the recognizer reports each line's box on the bitmap it was handed, and the
 * mapping turns that box back into photo coordinates.
 */
internal object LabelFieldGeometry {
    /** Fields whose lines are so long have plenty of identifying text; shorter tokens match too much. */
    const val MIN_TOKEN_LENGTH = 4

    /** Padding around the located line, as a share of its longer edge, so whole letters survive. */
    private const val PADDING_FRACTION = 0.08f

    /** A zoom smaller than this share of the shorter frame edge is not worth magnifying. */
    private const val MIN_REGION_FRACTION = 0.12f

    /**
     * How close a neighbouring line must be to belong to the same field, as a share of the line's own
     * height. Wrapped lines sit almost against each other; a separate field does not.
     */
    private const val RUN_GAP_FRACTION = 0.35f

    /**
     * How tall the located field may grow, as a multiple of the found line's height. Label blocks can
     * hold every line on the bottle, and a crop of the whole block is not a zoom at all.
     */
    private const val MAX_RUN_HEIGHT_FACTOR = 2.5f

    private val whitespaceRegex = Regex("\\s+")

    /** One pass's lines, with the mapping that places them back on the photo the crops come from. */
    data class MappedLines(val lines: List<LabelOcrLine>, val mapping: LabelCropMapping)

    /**
     * Where [field] sits on the photo, searching the most magnified pass first and returning null when
     * the field cannot be found on any of them.
     */
    fun locate(field: String?, views: List<MappedLines>): LabelBox? {
        val tokens = fieldTokens(field)
        if (tokens.isEmpty()) return null
        views.asReversed().forEach { view ->
            val index = view.lines.indexOfFirst { line ->
                val text = comparable(line.text)
                tokens.any { token -> text.contains(token) }
            }
            if (index < 0) return@forEach
            val line = view.lines[index]
            val box = line.box?.takeIf { it.isValid } ?: return@forEach

            val block = blockRun(view.lines, index).union(box)
            val sourceWidth = view.mapping.sourceWidth
            val sourceHeight = view.mapping.sourceHeight
            val minEdge = (minOf(sourceWidth, sourceHeight) * MIN_REGION_FRACTION).roundToInt()
            return view.mapping.toSource(block)
                .pad(PADDING_FRACTION)
                .growToMinEdge(minEdge)
                .clampTo(sourceWidth, sourceHeight)
        }
        return null
    }

    /**
     * The line plus the same block's neighbouring lines it touches. A cuvée split over two lines
     * ("Château" / "Margaux") is one field on the label, so both lines belong in the crop.
     */
    private fun blockRun(lines: List<LabelOcrLine>, index: Int): LabelBox {
        val found = lines[index]
        val box = requireNotNull(found.box)
        val tolerance = (box.height.coerceAtLeast(1) * RUN_GAP_FRACTION).roundToInt()
        val heightLimit = (box.height.coerceAtLeast(1) * MAX_RUN_HEIGHT_FACTOR).roundToInt()
        var run = box
        val sameBlock = lines.withIndex()
            .filter { (otherIndex, line) ->
                otherIndex != index && line.blockIndex == found.blockIndex && line.box != null
            }
            .sortedBy { (_, line) -> line.box!!.top }
        for ((_, line) in sameBlock) {
            val other = line.box ?: continue
            val verticalGap = when {
                other.bottom < run.top -> run.top - other.bottom
                other.top > run.bottom -> other.top - run.bottom
                else -> 0
            }
            if (verticalGap > tolerance) break
            if (other.right < run.left || other.left > run.right) continue
            val grown = run.union(other)
            if (grown.height > heightLimit) break
            run = grown
        }
        return run
    }

    /** The words of a field worth searching for, longest first so the most specific one matches. */
    fun fieldTokens(field: String?): List<String> = comparable(field.orEmpty())
        .split(" ")
        .filter { it.length >= MIN_TOKEN_LENGTH }
        .sortedByDescending { it.length }

    /** Diacritic- and case-insensitive form used to compare a field against a read line. */
    private fun comparable(text: String): String = Normalizer
        .normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(whitespaceRegex, " ")
}
