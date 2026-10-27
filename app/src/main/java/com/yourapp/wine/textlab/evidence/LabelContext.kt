package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.extract.IdentityRoles
import com.yourapp.wine.extract.LabelTextRoles
import com.yourapp.wine.extract.MetadataRules
import com.yourapp.wine.extract.WineTermSpacing
import com.yourapp.wine.kb.BundledOrigins
import com.yourapp.wine.kb.Place
import com.yourapp.wine.kb.PlaceGazetteer
import com.yourapp.wine.kb.PlaceHierarchy
import com.yourapp.wine.kb.ReferenceWineCatalog
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.TextNorm

/**
 * Label-wide context clues collected once per label in Lab extraction.
 *
 * Context clues never invent standalone fields (`DOCG` alone never populates `country = Italy`);
 * they only disambiguate `context_required` catalog origins on other phrases and supply country
 * constraints to bounded `PlaceRepair` when no explicit country/region line is printed.
 */
internal class LabelContext(
    val anchorsByPhrase: Map<Int, List<Place>>,
    private val explicitCountriesByPhrase: Map<Int, Set<String>>,
    private val explicitRegionsByPhrase: Map<Int, Set<String>>,
    private val singleCountryCuesByPhrase: Map<Int, Set<String>>,
    private val narrowingCountryCuesByPhrase: Map<Int, Set<String>>,
    private val producerCountriesByPhrase: Map<Int, Set<String>>,
) {
    private val allAnchors: List<Pair<Int, Place>> =
        anchorsByPhrase.flatMap { (id, places) -> places.map { id to it } }

    fun anchorsExcluding(phraseId: Int): List<Place> =
        allAnchors.filter { (id, _) -> id != phraseId }.map { it.second }.distinctBy { it.id }

    fun countryHintsExcluding(phraseId: Int): Set<String> {
        val explicit = explicitCountriesByPhrase.filterKeys { it != phraseId }.values.flatten().toSet()
        if (explicit.isNotEmpty()) return if (explicit.size == 1) explicit else emptySet()
        val singleLegal = singleCountryCuesByPhrase.filterKeys { it != phraseId }.values.flatten().toSet()
        if (singleLegal.isNotEmpty()) return if (singleLegal.size == 1) singleLegal else emptySet()
        val narrowing = narrowingCountryCuesByPhrase.filterKeys { it != phraseId }.values.flatten().toSet()
        val producer = producerCountriesByPhrase.filterKeys { it != phraseId }.values.flatten().toSet()
        if (narrowing.isNotEmpty() && producer.isNotEmpty()) {
            val overlap = narrowing.intersect(producer)
            if (overlap.isNotEmpty()) return overlap
        }
        if (producer.size == 1) return producer
        return narrowing
    }

    fun contextCountriesForPhrase(phraseId: Int): Set<String> {
        val explicit = explicitCountriesByPhrase.filterKeys { it != phraseId }.values.flatten().toSet()
        val samePhraseLegal = singleCountryCuesByPhrase[phraseId].orEmpty()
        if (explicit.isNotEmpty()) return explicit + samePhraseLegal
        val singleLegal = singleCountryCuesByPhrase.values.flatten().toSet()
        if (singleLegal.isNotEmpty()) return singleLegal
        val narrowing = narrowingCountryCuesByPhrase.values.flatten().toSet()
        val producer = producerCountriesByPhrase.filterKeys { it != phraseId }.values.flatten().toSet()
        if (narrowing.isNotEmpty() && producer.isNotEmpty()) {
            val overlap = narrowing.intersect(producer)
            if (overlap.isNotEmpty()) return overlap
        }
        return narrowing + (if (producer.size == 1) producer else emptySet())
    }

    fun contextRegionsForPhrase(phraseId: Int): Set<String> =
        explicitRegionsByPhrase.filterKeys { it != phraseId }.values.flatten().toSet()

    val traceSummary: String
        get() {
            val countries = (explicitCountriesByPhrase.values.flatten() + singleCountryCuesByPhrase.values.flatten()).toSortedSet()
            val regions = explicitRegionsByPhrase.values.flatten().toSortedSet()
            val narrowing = narrowingCountryCuesByPhrase.values.flatten().toSortedSet()
            val producers = producerCountriesByPhrase.values.flatten().toSortedSet()
            return "countries=${countries.joinToString(",")}; regions=${regions.joinToString(",")}; " +
                "narrowing=${narrowing.joinToString(",")}; kb_producer_countries=${producers.joinToString(",")}"
        }

    companion object {
        private val commercialOrWarning = Regex(
            "\\b(?:contains|sulfites|sulphites|bottled|mis en bouteille|imported|importer|imports|" +
                "distributed|distributor|barcode|gtin|ean|upc|volume|by vol|founded|established|est|since)\\b"
        )
        private val originStatementPrefix = Regex("^(?:product of|produced in|wine of|vin de|vino de|vino d|grand vin de|grand vin du|grand vin des)\\s+")
        private val frenchEstateBottled = Regex("\\bmis en bouteille au (?:chateau|domaine)\\b")
        private val frenchFramedCue = Regex("\\b(?:appellation\\b.*\\b(?:controlee|protegee)|grand vin de)\\b")
        private val italianFramedCue = Regex("\\b(?:denominazione di origine|indicazione geografica tipica|prodotto in italia)\\b")
        private val spanishFramedCue = Regex("\\b(?:denominacion de origen|vino de la tierra)\\b")
        private val usAvaCue = Regex("\\bamerican viticultural area\\b")
        private val zaWoCue = Regex("\\bwine of origin\\b")
        private val frenchDeptAddress = Regex(
            "(?:[-\\u2013\\u2014\\u00b7,(]\\s*(?:cote[- ']?d[- ']?or|gironde|yonne|saone[- ]et[- ]loire|" +
                "marne|aube|haut[- ]rhin|bas[- ]rhin|indre[- ]et[- ]loire|loir[- ]et[- ]cher|" +
                "maine[- ]et[- ]loire|loire[- ]atlantique|charente(?:-maritime)?|dordogne|" +
                "lot[- ]et[- ]garonne|pyrenees[- ]orientales|aude|herault|gard|vaucluse|" +
                "bouches[- ]du[- ]rhone|var|drome|ardeche)\\s*(?:[-\\u2013\\u2014\\u00b7,)]\\s*france\\b|\\)\\s*france\\b))"
        )
        private val frenchOperatorAddress = Regex(
            "^(?:[a-z]{0,4}oitant|exploitant|[a-z]{0,3}prietaire|proprietaire|negociant|recoltant|vigneron)\\s+a\\s+"
        )
        private val frenchPostalOrPhoneAddress = Regex(
            "(?:\\b\\d{5}\\b.*\\bfrance\\b|\\bfrance\\b.*\\btel\\b|\\btel\\.?\\s*0\\d)"
        )

        fun isFrenchHeadquartersAddress(norm: String): Boolean =
            frenchDeptAddress.containsMatchIn(norm) ||
                frenchOperatorAddress.containsMatchIn(norm) ||
                frenchPostalOrPhoneAddress.containsMatchIn(norm)

        fun postalAddressCountryCode(norm: String): String? =
            if (isFrenchHeadquartersAddress(norm) && wordPresent(norm, "france")) "FR" else null

        /**
         * True when a line is commercial, importer/bottler, warning, packaging, founding or alcohol
         * metadata that must never contribute wine geography or wine/producer identity.
         * Origin-framing boilerplate (`Product of France`, `Grand Vin de Bordeaux`, `Appellation ... Controlee`)
         * returns false here so geography can still be read while arbitrary identity stays blocked.
         */
        fun isNonOriginAdministrative(text: String, norm: String = TextNorm.normalize(text)): Boolean =
            MetadataRules.isPackaging(text) ||
                '%' in text ||
                EvidenceIdentityRoles.ownerGrowerLegalLine(text) ||
                ReferenceWineCatalog.isAdministrative(text) ||
                LabelTextRoles.isFoundingStatement(text) ||
                commercialOrWarning.containsMatchIn(norm) ||
                isFrenchHeadquartersAddress(norm)

        private fun wordPresent(norm: String, token: String): Boolean =
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(token)}(?![\\p{L}\\p{N}])").containsMatchIn(norm)

        private fun legalCues(norm: String): Pair<Set<String>, Set<String>> {
            val single = linkedSetOf<String>()
            val narrowing = linkedSetOf<String>()
            if (frenchEstateBottled.containsMatchIn(norm) || frenchFramedCue.containsMatchIn(norm)) single += "FR"
            if (italianFramedCue.containsMatchIn(norm) || wordPresent(norm, "docg") || wordPresent(norm, "igt")) single += "IT"
            if (spanishFramedCue.containsMatchIn(norm) || wordPresent(norm, "doca") || wordPresent(norm, "doq")) single += "ES"
            if (usAvaCue.containsMatchIn(norm) || wordPresent(norm, "ava")) single += "US"
            if (wordPresent(norm, "dac")) single += "AT"
            if (wordPresent(norm, "vqa")) single += "CA"
            if (zaWoCue.containsMatchIn(norm)) single += "ZA"
            if (wordPresent(norm, "aoc") || wordPresent(norm, "aop")) narrowing += listOf("FR", "CH", "LU")
            if (wordPresent(norm, "qualitatswein") || wordPresent(norm, "pradikatswein") || wordPresent(norm, "landwein") ||
                wordPresent(norm, "kabinett") || wordPresent(norm, "spatlese") || wordPresent(norm, "auslese")
            ) narrowing += listOf("DE", "AT")
            if (wordPresent(norm, "do")) narrowing += listOf("ES", "PT", "CL")
            if (wordPresent(norm, "doc")) narrowing += listOf("IT", "PT", "RO")
            return single to narrowing
        }

        fun collect(
            graph: EvidenceGraph,
            gazetteer: PlaceGazetteer,
            view: PlaceHierarchy.View,
            kb: WineKnowledgeBase,
            typedIdentity: Boolean,
            exactGeographyFastPath: Boolean = false,
        ): LabelContext {
            val anchors = linkedMapOf<Int, List<Place>>()
            val explicitCountries = linkedMapOf<Int, Set<String>>()
            val explicitRegions = linkedMapOf<Int, Set<String>>()
            val singleCues = linkedMapOf<Int, Set<String>>()
            val narrowingCues = linkedMapOf<Int, Set<String>>()
            val producerCountries = linkedMapOf<Int, Set<String>>()

            for (phrase in graph.phrases) {
                val sources = graph.observations.filter { it.id in phrase.observations }
                val supported = sources.any { it.record.score >= if (it.record.engineId.startsWith("paddle")) .75f else .60f }
                val corroboratedLowScore = !supported && exactGeographyFastPath &&
                    sources.any { it.record.score >= .45f } &&
                    graph.observations.any { other ->
                        sources.none { it.record.engineId == other.record.engineId } &&
                            other.record.score >= (if (other.record.engineId.startsWith("paddle")) .75f else .60f) &&
                            BoxMath.iou(other.record.bboxRect, phrase.box) > .10f
                    }
                if (!supported && !corroboratedLowScore) continue
                val text = WineTermSpacing.wineWords.repair(phrase.text).text
                val norm = TextNorm.normalize(text)
                if (supported) {
                    val (single, narrowing) = legalCues(norm)
                    val postalCode = postalAddressCountryCode(norm)
                    val combinedSingle = if (postalCode != null) single + postalCode else single
                    if (combinedSingle.isNotEmpty()) singleCues[phrase.id] = combinedSingle
                    if (narrowing.isNotEmpty()) narrowingCues[phrase.id] = narrowing
                }

                val nonOrigin = isNonOriginAdministrative(text, norm)
                val headingOnly = typedIdentity && EvidenceIdentityRoles.headingOnly(text)
                val namedEstate = typedIdentity && EvidenceIdentityRoles.namedEstate(text)
                if (nonOrigin || headingOnly) continue

                if (!namedEstate) {
                    val strippedOrigin = originStatementPrefix.replaceFirst(norm, "").trim()
                    val matched = gazetteer.matches(norm)
                    if (corroboratedLowScore) {
                        val place = matched.singleOrNull()
                        val key = EvidenceRanking.nameKey(strippedOrigin)
                        if (place != null && place.type != "country" && key.length >= 8 &&
                            ReferenceWineCatalog.origin(place)?.contextRequired != true &&
                            (listOf(place.name) + BundledOrigins.spellingForms(place)).any { EvidenceRanking.nameKey(it) == key }
                        ) {
                            val path = view.ancestors(place)
                            val ancestorAnchors = path.filter { it.type in setOf("country", "region") }
                            if (ancestorAnchors.isNotEmpty()) anchors[phrase.id] = ancestorAnchors
                            path.lastOrNull { it.type == "country" }?.let { explicitCountries[phrase.id] = setOf(it.countryCode) }
                            path.firstOrNull { it.type == "region" }?.let { explicitRegions[phrase.id] = setOf(it.nameNorm) }
                        }
                        continue
                    }
                    val anchorPlaces = matched.filter { place ->
                        place.type in setOf("country", "region") &&
                            (IdentityRoles.originOnly(text, listOf(place)) || strippedOrigin == place.nameNorm)
                    }
                    if (anchorPlaces.isNotEmpty()) {
                        anchors[phrase.id] = anchorPlaces
                    } else if (exactGeographyFastPath && matched.size == 1) {
                        val exactPlace = matched.single()
                        val strippedKey = EvidenceRanking.nameKey(strippedOrigin)
                        val isWholeExact = ReferenceWineCatalog.origin(exactPlace)?.contextRequired != true &&
                            (IdentityRoles.originOnly(text, listOf(exactPlace)) ||
                                (strippedKey.isNotEmpty() && (listOf(exactPlace.name) + BundledOrigins.spellingForms(exactPlace)).any {
                                    EvidenceRanking.nameKey(it) == strippedKey
                                }))
                        if (isWholeExact) {
                            val ancestorAnchors = view.ancestors(exactPlace).filter { it.type in setOf("country", "region") }
                            if (ancestorAnchors.isNotEmpty()) anchors[phrase.id] = ancestorAnchors
                        }
                    }
                    val countriesFromPlaces = linkedSetOf<String>()
                    val regionsFromPlaces = linkedSetOf<String>()
                    for (place in matched) {
                        // Unambiguous non-context-required places also contribute ancestor country/region context.
                        if (ReferenceWineCatalog.origin(place)?.contextRequired == true) continue
                        if (anchorPlaces.isEmpty() && matched.size > 1) continue
                        val path = view.ancestors(place)
                        path.lastOrNull { it.type == "country" }?.let { countriesFromPlaces += it.countryCode }
                        path.firstOrNull { it.type == "region" }?.let { regionsFromPlaces += it.nameNorm }
                    }
                    if (countriesFromPlaces.isNotEmpty()) explicitCountries[phrase.id] = countriesFromPlaces
                    if (regionsFromPlaces.isNotEmpty()) explicitRegions[phrase.id] = regionsFromPlaces
                }

                if (!MetadataRules.isBoilerplate(text) && MetadataRules.isIdentity(text)) {
                    val exactProducers = kb.findProducer(text).filter { it.editDistance == 0 }
                    val codes = exactProducers.map { it.producer.countryCode }.filter(String::isNotBlank).toSet()
                    if (codes.size == 1) producerCountries[phrase.id] = codes
                }
            }
            return LabelContext(anchors, explicitCountries, explicitRegions, singleCues, narrowingCues, producerCountries)
        }
    }
}
