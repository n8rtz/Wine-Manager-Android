package com.yourapp.wine.extract

import com.yourapp.wine.kb.BundledGrapes
import com.yourapp.wine.kb.BundledWineStyles
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm
import kotlin.math.max
import kotlin.math.min

/** Label-language cues rank readable OCR evidence; they are not a catalog of producer identities. */
class ProducerLineSelector {
    /** Normalized rectified-label lines in/out; only nearby, horizontally aligned marker/name lines may join. */
    fun candidates(lines: List<LabelLine>): List<LabelLine> {
        val names = lines.filter { !isMarker(it.text) && !isWineDescription(it.text) && !isAdministrative(it.text) }
        val wrapped = lines.filter { isMarker(it.text) }.mapNotNull { marker ->
            val nearby = names.filter { line ->
                val a = marker.bboxRect; val b = line.bboxRect
                val overlap = min(a.right, b.right) - max(a.left, b.left)
                val gap = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
                overlap > 0f && overlap / min(a.width(), b.width()).coerceAtLeast(.001f) >= .4f &&
                    gap <= max(a.height() * 3f, b.height() * .5f)
            }.minByOrNull { line ->
                kotlin.math.abs(marker.bboxRect.centerY() - line.bboxRect.centerY())
            } ?: return@mapNotNull null
            val ordered = listOf(marker, nearby).sortedBy { it.bboxRect.centerY() }
            LabelLine(ordered.joinToString(" ") { it.text }, ordered.flatMap { it.tokens },
                BoxMath.union(ordered.map { it.bboxRect }))
        }
        return names + wrapped
    }

    /** Normalized rectified-label candidates in; unknown producers stay OCR-only and must be confirmed. */
    fun unknown(candidates: List<LabelLine>): LabelLine? {
        val selected = candidates.sortedWith(
            compareByDescending<LabelLine> { producerCue.containsMatchIn(TextNorm.normalize(it.text)) }
                .thenByDescending { it.bboxRect.height() }.thenByDescending { it.vote }
        ).firstOrNull() ?: return null
        val normalized = TextNorm.normalize(selected.text)
        // "Penfolds" repeated in a tiny "Penfolds wines South Australia" footer supports the
        // prominent brand; the entire production/origin sentence is not the producer's name.
        return candidates.filter { title ->
            val name = TextNorm.normalize(title.text)
            title.bboxRect.height() > selected.bboxRect.height() && normalized.startsWith("$name ") &&
                producerFooter.containsMatchIn(normalized.removePrefix("$name "))
        }.maxByOrNull { it.bboxRect.height() } ?: selected
    }

    /** Explicit production marker protects a real unknown domaine from name/style ambiguity. */
    fun hasProducerCue(text: String): Boolean = producerCue.containsMatchIn(TextNorm.normalize(text))

    /** No coordinates: a bare administrative producer word is not an identity or a wine name. */
    fun isMarker(text: String): Boolean {
        val normalized = TextNorm.normalize(text)
        return normalized.none(Char::isDigit) && normalized.filter(Char::isLetter) in compactMarkers
    }

    /** No coordinates: these standalone production statements are not producer/cuvee identities. */
    fun isAdministrative(text: String): Boolean = TextNorm.normalize(text) in administrative

    /** No coordinates: generic varietal/style lines can name a wine, but cannot identify an unknown producer. */
    fun isWineDescription(text: String): Boolean {
        val normalized = TextNorm.normalize(WineTermSpacing.wineWords.repair(text).text)
        if (com.yourapp.wine.kb.ReferenceWineCatalog.isDescription(text)) return true
        if (WineDesignations.isAppellationName(text) || WineDesignations.isDesignation(text) || WineDesignations.hasNameAndDesignation(text)) return true
        if (normalized in styles || normalized.filter(Char::isLetterOrDigit) in compactStyles) return true
        var remaining = normalized
        var hasVarietal = false
        varietyPatterns.forEach { pattern ->
            if (pattern.containsMatchIn(remaining)) {
                hasVarietal = true
                remaining = pattern.replace(remaining, " ")
            }
        }
        return hasVarietal && remaining.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }.all { it in modifiers }
    }

    private companion object {
        val markers = setOf("chateau", "domaine", "winery", "wines", "bodega", "bodegas", "estate",
            "vineyard", "vineyards", "cellars", "weingut", "quinta", "finca", "tenuta", "cantina", "maison") +
            com.yourapp.wine.kb.ReferenceWineCatalog.names("producer_marker").map(TextNorm::normalize)
        val compactMarkers = markers.map { it.filter(Char::isLetter) }.toSet()
        val producerCue = Regex("\\b(?:${markers.joinToString("|")})\\b")
        val producerFooter = Regex("^(?:winery|wines|vineyards?|cellars|estate)\\s+\\S")
        val administrative = setOf("estate grown", "estate bottled", "bottled at the estate")
        val styles = setOf("reserve", "reserva", "riserva", "gran reserva", "grand reserve", "old vine",
            "red wine", "white wine", "rose wine", "dry red wine", "dry white wine", "red blend", "white blend") +
            BundledWineStyles.terms.flatMap { listOf(it.name) + it.aliases }.map(TextNorm::normalize)
        val compactStyles = styles.map { it.filter(Char::isLetterOrDigit) }.toSet()
        val modifiers = setOf("reserve", "reserva", "riserva", "gran", "grand", "old", "vine", "vines",
            "red", "white", "wine", "dry", "sweet", "blend", "oak", "aged", "and", "et", "estate", "grown")
        val varieties = BundledGrapes.terms.flatMap { listOf(it.name) + it.aliases }.map(TextNorm::normalize)
            .distinct().sortedByDescending { it.length }
        val varietyPatterns = varieties.map { variety ->
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(variety)}(?![\\p{L}\\p{N}])")
        }
    }
}
