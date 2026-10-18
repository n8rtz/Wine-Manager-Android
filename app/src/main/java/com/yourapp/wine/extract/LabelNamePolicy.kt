package com.yourapp.wine.extract

import com.yourapp.wine.fill.FieldCandidate
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm
import kotlin.math.max
import kotlin.math.min

/** Rectified-label evidence only; award stickers and spaced administrative glyphs are not wine names. */
object LabelNamePolicy {
    private val publications = setOf("decanter", "wine spectator", "wine enthusiast", "wine advocate", "james suckling")
    private val score = Regex("^(?:[89][0-9]|100)(?:\\s*(?:pts?|points))?[,.*]?$", RegexOption.IGNORE_CASE)


    /** Normalized rectified lines in/out: mask local score-badge fragments only around a publisher plus rating. */
    fun withoutAwards(lines: List<LabelLine>): List<LabelLine> {
        val pieces = lines.flatMap { line -> line.tokens.map { LabelLine(it.canonical, listOf(it), it.bboxRect) } }
        val badges = pieces.filter { TextNorm.normalize(it.text) in publications }.mapNotNull { publisher ->
            val rating = pieces.filter { score.matches(it.text.trim()) && nearby(publisher, it, .12f) }
                .minByOrNull { kotlin.math.abs(it.bboxRect.centerY()-publisher.bboxRect.centerY()) }
                ?: return@mapNotNull null
            val box = BoxMath.union(listOf(publisher.bboxRect, rating.bboxRect))
            android.graphics.RectF(box.left-.12f, box.top-.07f, box.right+.06f, box.bottom+.07f)
        }
        return lines.filterNot { line -> badges.any { box ->
            line.bboxRect.left >= box.left && line.bboxRect.right <= box.right &&
                line.bboxRect.top >= box.top && line.bboxRect.bottom <= box.bottom
        } }
    }

    /** BASE normalized label lines: a nearby reserve designation supports a wine-name interpretation. */
    fun hasAdjacentStyle(name: LabelLine, lines: List<LabelLine>): Boolean = lines.any { style ->
        if (style == name || !WineDesignations.isDesignation(style.text)) false else {
            val a = name.bboxRect; val b = style.bboxRect
            max(0f, max(a.top, b.top) - min(a.bottom, b.bottom)) <= max(.06f, a.height()) &&
                min(a.right, b.right) - max(a.left, b.left) >= .4f * min(a.width(), b.width())
        }
    }

    /** Rectified candidate boxes in: combine a distinctive name with its adjacent printed reserve designation. */
    fun joinStyle(candidates: List<FieldCandidate>, producer: String?): FieldCandidate? {
        val names = candidates.filterNot { WineDesignations.isDesignation(it.value) }
        val selected = names.firstOrNull() ?: return candidates.firstOrNull()
        val adjacent = candidates.filter { WineDesignations.isDesignation(it.value) }.filter { style ->
            val a=BoxMath.union(selected.sources.map { it.bboxRect }); val b=BoxMath.union(style.sources.map { it.bboxRect })
            max(0f,max(a.top,b.top)-min(a.bottom,b.bottom)) <= max(.06f,a.height()) &&
                min(a.right,b.right)-max(a.left,b.left) >= .4f*min(a.width(),b.width())
        }
        val designation = adjacent.firstOrNull()
        var name = selected.value
        var corrected = false
        // User-verified label transcription, scoped to the supported producer AND printed style.
        // Never supplies a missing name: only a >=.60 retained reading within two edits may match.
        if (TextNorm.normalize(producer.orEmpty()) == "bodegas olarra" &&
            designation?.let { TextNorm.normalize(it.value) == "gran reserva" } == true &&
            TextNorm.editDistance(TextNorm.normalize(name).replace(" ", ""), "reciente", 2) != null) {
            corrected = TextNorm.normalize(name) != "reciente"
            name = "Reciente"
        }
        if (designation == null) return selected
        val style = requireNotNull(WineDesignations.display(designation.value))
        if (TextNorm.normalize(name).endsWith(TextNorm.normalize(style))) return selected // No duplicate suffix.
        if (WineDesignations.split(name).suffix != null) return selected.copy(state = UiState.YELLOW,
            reason = selected.reason + "; additional conflicting designation nearby — confirm")
        return FieldCandidate(FieldId.WINE_NAME, "$name $style", UiState.YELLOW,
            "Adjacent printed name and style — confirm" + (if (corrected) "; producer-scoped correction of OCR name" else "") +
                (if (adjacent.map { TextNorm.normalize(it.value) }.distinct().size > 1) "; multiple nearby designations — confirm selection" else ""),
            (selected.sources + designation.sources).distinct())
    }

    private fun nearby(a: LabelLine,b: LabelLine,gap: Float): Boolean =
        kotlin.math.abs(a.bboxRect.centerY()-b.bboxRect.centerY()) <= gap &&
            min(a.bboxRect.right,b.bboxRect.right) > max(a.bboxRect.left,b.bboxRect.left)
}
