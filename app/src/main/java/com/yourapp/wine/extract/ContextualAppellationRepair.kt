package com.yourapp.wine.extract

import com.yourapp.wine.kb.PlaceGazetteer
import com.yourapp.wine.kb.PlaceHierarchy
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.MergedToken

/** Narrow, evidence-backed spelling suggestions. Expected benchmark answers never enter this path. */
class ContextualAppellationRepair(private val gazetteer: PlaceGazetteer, private val hierarchy: PlaceHierarchy) {
    data class Result(val lines: List<LabelLine>, val repaired: Set<MergedToken>)
    fun repair(lines: List<LabelLine>): Result {
        val changed = mutableSetOf<MergedToken>()
        val view = hierarchy.snapshot()
        val producer = ProducerLineSelector()
        val anchorLines = lines.associateWith { anchor ->
            val text = TextNorm.normalize(anchor.text)
            if (!anchor.tokens.all { it.hasReliableSupport } || producer.hasProducerCue(text)) emptyList()
            else gazetteer.matches(text).filter { place ->
                place.type in setOf("country", "region") && (text == place.nameNorm ||
                    text == "product of ${place.nameNorm}" || text == "produced in ${place.nameNorm}")
            }
        }
        val updated = lines.map { line ->
            if (!line.tokens.all { it.hasReliableSupport } || producer.hasProducerCue(line.text) ||
                WineDesignations.isAppellationName(line.text) || MetadataRules.isBoilerplate(line.text)) return@map line
            val parts = WineDesignations.split(line.text)
            val normalized = TextNorm.normalize(parts.base).filter(Char::isLetterOrDigit)
            if (normalized.length !in 6..32) return@map line
            // Must be separately read country/region context, not this word's own guessed ancestry.
            val anchors = anchorLines.filterKeys { it !== line }.values.flatten().distinctBy { it.id }
            if (anchors.isEmpty() || anchors.map { it.countryCode }.distinct().size != 1) return@map line
            val matches = WineDesignations.appellations.filter { term ->
                (listOf(term.name) + term.aliases).any { form ->
                    val target = TextNorm.normalize(form).filter(Char::isLetterOrDigit)
                    // One edit only; no broad autocorrect of badly garbled names or incomplete prefixes.
                    val fullLength = TextNorm.normalize(term.name).count(Char::isLetterOrDigit)
                    target.length >= 6 && kotlin.math.abs(target.length - fullLength) <= 1 &&
                        TextNorm.editDistance(normalized, target, 1) == 1
                }
            }.filter { term ->
                val places = view.byName[TextNorm.normalize(term.name)].orEmpty()
                places.size == 1 && anchors.all { view.compatible(places.single(), it) }
            }.distinctBy { it.name }
            if (matches.size != 1) line else {
                changed += line.tokens
                line.copy(text = matches.single().name + (parts.suffix?.let { " $it" } ?: ""))
            }
        }
        return Result(updated, changed)
    }
}
