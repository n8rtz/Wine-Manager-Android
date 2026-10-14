package com.yourapp.wine.extract

import com.yourapp.wine.fill.FieldCandidate
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm

class VintageExtractor {
    private val year = Regex("""\b(19|20)\d{2}\b""")
    private val foundation = Regex("""\b(?:est\.?|founded|established|since|depuis|fonde[e]?|first\s+vintage|first\s+harvest)\s*[:.]?\s*$""")

    /** Line boxes/evidence are normalized rectified-label coordinates; years use the supplied current year. */
    fun extract(lines: List<LabelLine>, currentYear: Int): List<FieldCandidate> {
        data class Read(val value: String, val vote: Float, val foundation: Boolean, val line: LabelLine)
        val reads = lines.flatMap { line -> year.findAll(line.text).mapNotNull { match ->
            val number = match.value.toInt()
            if (number !in 1900..currentYear) return@mapNotNull null
            val prefix = TextNorm.normalize(line.text.substring(0, match.range.first).takeLast(30))
            val adjacentFoundation = foundation.containsMatchIn(prefix)
            val matching = line.tokens.filter { year.find(it.canonical)?.value == match.value }
            Read(match.value, matching.maxOfOrNull { it.vote } ?: line.vote, adjacentFoundation, line)
        }.toList() }
        // Historical dates do not create a false disagreement with a clear actual vintage.
        val actual = reads.filterNot { it.foundation }
        val candidates = if (actual.any { it.vote >= .6f }) actual else reads
        return candidates.sortedWith(compareBy<Read> { it.foundation }.thenByDescending { it.vote })
            .distinctBy { it.value }.map { read ->
                FieldCandidate(FieldId.VINTAGE, read.value,
                    if (read.vote >= 0.6f && !read.foundation) UiState.GREEN else UiState.YELLOW,
                    if (read.foundation) "Year adjacent to establishment/first-vintage text; confirm it is the vintage" else "Valid vintage year with vote ${read.vote}",
                    read.line.tokens.filter { year.containsMatchIn(it.canonical) }.ifEmpty { read.line.tokens })
            }
    }
}
