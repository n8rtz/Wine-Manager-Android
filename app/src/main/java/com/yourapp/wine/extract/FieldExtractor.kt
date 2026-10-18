package com.yourapp.wine.extract

import com.yourapp.wine.fill.FieldCandidate
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.kb.Place
import com.yourapp.wine.kb.BundledProducerNames
import com.yourapp.wine.kb.PlaceHierarchy
import com.yourapp.wine.kb.PlaceGazetteer
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.MergedToken

/** Place claims preserve explicit OCR contradictions instead of silently correcting a country. */
data class PlaceClaim(val place: Place, val sources: List<MergedToken>)
data class ExtractionResult(val candidates: Map<FieldId, List<FieldCandidate>>, val placeClaims: List<PlaceClaim>)

class FieldExtractor(private val kb: WineKnowledgeBase, private val vintage: VintageExtractor = VintageExtractor(),
                     private val hierarchy: PlaceHierarchy = PlaceHierarchy(kb),
                     private val normalizer: WineTextNormalizer = WineTextNormalizer(kb)) {
    private val gazetteer = PlaceGazetteer(kb)
    private val producerLines = ProducerLineSelector()
    init { gazetteer.matches(""); normalizer.repair("") } // App-scoped Default initialization, not a first-scan regex/trie burst.
    /** Extracts from line strings, then whole-label text; evidence remains normalized rectified-label boxes. */
    fun extract(rawLines: List<LabelLine>, currentYear: Int): ExtractionResult {
        val normalized = rawLines.map { line -> line.copy(text = normalizer.repair(line.text).text) }
        val contextual = ContextualAppellationRepair(gazetteer, hierarchy).repair(normalized)
        val lines = contextual.lines
        val result = mutableMapOf<FieldId, List<FieldCandidate>>()
        result[FieldId.VINTAGE] = vintage.extract(lines, currentYear)
        val geography = hierarchy.snapshot()
        val whole = lines.joinToString(" ") { it.text }
        // Importer/distributor and warning lines are not wine-origin evidence, in any catalog language.
        val originLines = lines.filterNot { com.yourapp.wine.kb.ReferenceWineCatalog.isAdministrative(it.text) }
        val claims = (originLines.flatMap { matchPlaces(it.text, it.tokens) } +
            matchPlaces(originLines.joinToString(" ") { it.text }, originLines.flatMap { it.tokens }))
            .distinctBy { it.place.id }.sortedWith(compareByDescending<PlaceClaim> { geography.ancestors(it.place).size }
                .thenByDescending { it.place.nameNorm.length }.thenByDescending { vote(it.sources) })
        val primary = claims.firstOrNull()
        if (primary != null) {
            val path = geography.ancestors(primary.place)
            for (field in listOf(FieldId.COUNTRY, FieldId.REGION, FieldId.SUBREGION)) {
                val explicit = claims.filter { fieldFor(it.place) == field }
                val inherited = when (field) {
                    FieldId.COUNTRY -> path.firstOrNull { it.type == "country" }
                    FieldId.REGION -> if ((com.yourapp.wine.kb.ReferenceWineCatalog.origin(primary.place)?.regions?.size ?: 0) > 1)
                        null else path.firstOrNull { it.type == "region" }
                    else -> path.firstOrNull { it.type in setOf("subregion", "appellation") }
                }
                val candidates = explicit.map { claim -> placeCandidate(field, claim.place, claim.sources, "Label gazetteer match") } +
                    listOfNotNull(inherited?.let { placeCandidate(field, it, primary.sources, "KB parent of ${primary.place.name}") })
                result[field] = candidates.distinctBy { TextNorm.normalize(it.value) }
            }
        }
        val placeLines = lines.filter { line -> !WineDesignations.isAppellationName(line.text) && matchPlaces(line.text, line.tokens).any { claim ->
            val cleaned = TextNorm.normalize(line.text).replace(claim.place.nameNorm, "").trim()
            cleaned.isEmpty() || MetadataRules.isBoilerplate(cleaned)
        } }.toSet()
        val identityLines = LabelNamePolicy.withoutAwards(lines).filter { it !in placeLines }.mapNotNull { line ->
            val cleaned = MetadataRules.identityText(line.text)
            if (MetadataRules.isIdentity(cleaned) && !producerLines.isAdministrative(cleaned)) line.copy(text = cleaned) else null
        }
        val producerCandidates = producerLines.candidates(identityLines)
        // FTS is a shortlist, not evidence that an unknown winery is a familiar producer.
        // Require a bounded full-name match here; an exact whole-label phrase can still match below.
        val knownLines = (identityLines + producerCandidates).distinct().flatMap { line ->
            kb.findProducer(line.text).filter { hit -> hit.editDistance?.let { it in 0..2 } == true }.take(3).map { hit ->
                hit to FieldCandidate(FieldId.PRODUCER, hit.producer.name,
                    if (hit.editDistance == 0 && line.vote >= 0.6f) UiState.GREEN else UiState.YELLOW,
                    if (hit.editDistance == 0) "Normalized exact producer in local KB"
                    else "Producer fuzzy edit distance ${hit.editDistance}; confirm", line.tokens)
            }
        }
        // Whole-label fallback can recover a producer split across lines or sharing a line with a year.
        val normalizedWhole = TextNorm.normalize(whole)
        val knownWhole = kb.findProducer(whole).mapNotNull { hit ->
            if (!Regex("(?<![\\p{L}\\p{N}])${Regex.escape(hit.matchedNorm)}(?![\\p{L}\\p{N}])").containsMatchIn(normalizedWhole)) return@mapNotNull null
            val words = hit.matchedNorm.split(' ').toSet()
            val sources = lines.flatMap { it.tokens }.filter { token -> TextNorm.normalize(token.canonical).split(' ').any { it in words } }
            hit.copy(editDistance = 0) to FieldCandidate(FieldId.PRODUCER, hit.producer.name,
                if (vote(sources) >= .6f) UiState.GREEN else UiState.YELLOW, "Normalized exact producer in whole-label text", sources)
        }
        val known = (knownLines + knownWhole).sortedWith(
            compareBy<Pair<com.yourapp.wine.kb.ProducerHit, FieldCandidate>> { it.first.editDistance ?: Int.MAX_VALUE }
                .thenByDescending { vote(it.second.sources) })
        val producerId = known.firstOrNull()?.first?.producer?.id
        if (known.isNotEmpty()) {
            val bestDistance = known.first().first.editDistance
            result[FieldId.PRODUCER] = known.filter { it.first.editDistance == bestDistance }.map { it.second }
                .distinctBy { TextNorm.normalize(it.value) }
        } else {
            // Do not promote an ambiguous cuvee-plus-reserve heading to maker merely because it is prominent.
            // Exact KB matches above, curated producer names and explicit domaine/winery cues retain priority.
            val unknown = producerLines.unknown(producerCandidates.filterNot { candidate ->
                !producerLines.hasProducerCue(candidate.text) && BundledProducerNames.exactName(candidate.text) == null &&
                    LabelNamePolicy.hasAdjacentStyle(candidate, identityLines)
            })
            if (unknown != null) {
                val curated = BundledProducerNames.exactName(unknown.text)
                result[FieldId.PRODUCER] = listOf(FieldCandidate(FieldId.PRODUCER, curated ?: unknown.text,
                    UiState.YELLOW, if (curated == null) "OCR-only producer; boutique/unknown wines are allowed"
                    else "Exact authored producer-name alias; origin not inferred — confirm", unknown.tokens))
            }
        }
        val producerSources = result[FieldId.PRODUCER]?.firstOrNull()?.sources.orEmpty().toSet()
        val selectedProducer = result[FieldId.PRODUCER]?.firstOrNull()?.value
        val producerSpellings = known.firstOrNull()?.first?.producer?.let { listOf(it.name) + it.aliases } ?: listOfNotNull(selectedProducer)
        val wines = identityLines.mapNotNull { line ->
            if (producerLines.isMarker(line.text)) return@mapNotNull null
            var distinctive = line.text
            producerSpellings.sortedByDescending { it.length }.forEach { producerName ->
                distinctive = removeNormalizedPhrase(distinctive, producerName)
            }
            // A fuzzy-only producer line must not be duplicated into the wine-name field.
            if (distinctive == line.text && line.tokens.any { it in producerSources }) return@mapNotNull null
            // A printed appellation may itself name the wine. Keep its observed suffix (even truncated),
            // rather than stripping the geographic core and promoting the leftover fragment.
            if (!WineDesignations.isAppellationName(distinctive)) {
                claims.sortedByDescending { it.place.name.length }.forEach { claim ->
                    distinctive = removeNormalizedPhrase(distinctive, claim.place.name)
                }
            }
            distinctive = distinctive.replace(Regex("\\s+"), " ").trim()
            if (!MetadataRules.isIdentity(distinctive)) return@mapNotNull null
            val hit = producerId?.let { kb.findCuvee(it, distinctive) }
            FieldCandidate(FieldId.WINE_NAME, WineDesignations.orderName(hit?.cuvee?.name ?: distinctive),
                if (hit?.editDistance == 0 && line.vote >= 0.6f) UiState.GREEN else UiState.YELLOW,
                if (hit == null) "Distinctive OCR-only wine name; confirm" +
                    (if (line.tokens.any { normalizer.repair(it.canonical).changed }) "; verified term spacing normalized" else "")
                else "Known cuvée for selected producer (edit ${hit.editDistance})",
                line.tokens)
        }.sortedWith(compareBy<FieldCandidate> { if (it.state == UiState.GREEN) 0 else 1 }
            .thenBy { if (producerLines.isWineDescription(it.value)) 1 else 0 }
            .thenByDescending { (vote(it.sources) * 1000).toInt() }
            .thenByDescending { it.sources.maxOfOrNull { t -> t.bboxRect.height() } ?: 0f })
        // A printed style/appellation can be the only wine name (e.g. Prosecco or Champagne).
        // Reuse actual accepted label text only, never infer a cuvee from its geography or producer.
        val styleFallback = if (wines.isEmpty()) placeLines.mapNotNull { line ->
            val raw = rawLines.firstOrNull { it.tokens == line.tokens }?.text ?: line.text
            val display = WineTermSpacing.wineWords.repair(MetadataRules.identityText(raw)).text
            if (!producerLines.isWineDescription(display) || (selectedProducer == null && !WineDesignations.isAppellationName(display))) null else FieldCandidate(FieldId.WINE_NAME, display,
                UiState.YELLOW, "Printed wine style/appellation used as name — confirm", line.tokens)
        }.sortedByDescending { vote(it.sources) } else emptyList()
        result[FieldId.WINE_NAME] = listOfNotNull(LabelNamePolicy.joinStyle(wines + styleFallback, selectedProducer))
        // A lexical suggestion is not measured evidence: cap all dependent fields and inherited geography.
        if (contextual.repaired.isNotEmpty()) result.replaceAll { _, candidates -> candidates.map { candidate ->
            if (candidate.sources.any { it in contextual.repaired }) candidate.copy(state = UiState.YELLOW,
                reason = candidate.reason + "; contextual appellation spelling suggestion supported by separate geography — confirm; raw OCR unchanged")
            else candidate
        } }
        val referenceSources = claims.filter { com.yourapp.wine.kb.ReferenceWineCatalog.requiresConfirmation(it.place) }
            .flatMap { it.sources }.toSet()
        if (referenceSources.isNotEmpty()) result.replaceAll { _, candidates -> candidates.map { candidate ->
            if (candidate.state != UiState.RED && candidate.sources.any { it in referenceSources }) candidate.copy(state = UiState.YELLOW,
                reason = candidate.reason + "; expanded reference-catalog origin — confirm; not a legal certification")
            else candidate
        } }
        return ExtractionResult(result, claims)
    }

    private fun removeNormalizedPhrase(raw: String, phrase: String): String {
        // Preserve display characters: locate spans by normalized word sequences, not normalized-string offsets.
        val words = Regex("[\\p{L}\\p{M}\\p{N}'’-]+").findAll(raw).toList()
        val expected = Regex("[\\p{L}\\p{M}\\p{N}'’-]+").findAll(TextNorm.normalize(phrase)).map { it.value }.toList()
        if (expected.isEmpty() || words.size < expected.size) return raw
        for (start in 0..words.size - expected.size) {
            if (words.subList(start, start + expected.size).map { TextNorm.normalize(it.value) } == expected) {
                return raw.removeRange(words[start].range.first, words[start + expected.size - 1].range.last + 1).trim()
            }
        }
        return raw
    }

    private fun matchPlaces(text: String, sources: List<MergedToken>): List<PlaceClaim> {
        val normalized = TextNorm.normalize(text)
        return gazetteer.matches(normalized).map { p ->
            val words = p.nameNorm.split(' ').toSet()
            val evidence = sources.filter { token -> TextNorm.normalize(token.canonical).split(' ').any { it in words } }
            PlaceClaim(p, evidence.ifEmpty { sources })
        }
    }

    private fun fieldFor(place: Place): FieldId = when (place.type) {
        "country" -> FieldId.COUNTRY
        "region" -> FieldId.REGION
        else -> FieldId.SUBREGION
    }

    private fun placeCandidate(field: FieldId, p: Place, sources: List<MergedToken>, reason: String) =
        FieldCandidate(field, p.name, if (vote(sources) >= 0.6f) UiState.GREEN else UiState.YELLOW, reason, sources)

    private fun vote(tokens: List<MergedToken>): Float = tokens.minOfOrNull { it.vote } ?: 0f
}
