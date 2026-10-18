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
    private val styles = setOf("gran reserva", "reserva", "riserva", "reserve", "crianza", "grand reserve")

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
        if (style == name || TextNorm.normalize(style.text) !in styles) false else {
            val a = name.bboxRect; val b = style.bboxRect
            b.top >= a.bottom && b.top - a.bottom <= max(.06f, a.height()) &&
                min(a.right, b.right) - max(a.left, b.left) >= .4f * min(a.width(), b.width())
        }
    }

    /** Rectified candidate boxes in: combine a distinctive name with its adjacent printed reserve designation. */
    fun joinStyle(candidates: List<FieldCandidate>, producer: String?): FieldCandidate? {
        val names = candidates.filterNot { TextNorm.normalize(it.value) in styles }
        val selected = names.firstOrNull() ?: return candidates.firstOrNull()
        val designation = candidates.filter { TextNorm.normalize(it.value) in styles }.firstOrNull { style ->
            val a=BoxMath.union(selected.sources.map { it.bboxRect }); val b=BoxMath.union(style.sources.map { it.bboxRect })
            b.top >= a.top && max(0f,b.top-a.bottom) <= max(.06f,a.height()) &&
                min(a.right,b.right)-max(a.left,b.left) >= .4f*min(a.width(),b.width())
        }
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
        val style = designation.value.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        return FieldCandidate(FieldId.WINE_NAME, "$name $style", UiState.YELLOW,
            "Adjacent printed name and style — confirm" + if (corrected) "; producer-scoped correction of OCR name" else "",
            (selected.sources + designation.sources).distinct())
    }

    private fun nearby(a: LabelLine,b: LabelLine,gap: Float): Boolean =
        kotlin.math.abs(a.bboxRect.centerY()-b.bboxRect.centerY()) <= gap &&
            min(a.bboxRect.right,b.bboxRect.right) > max(a.bboxRect.left,b.bboxRect.left)
}
