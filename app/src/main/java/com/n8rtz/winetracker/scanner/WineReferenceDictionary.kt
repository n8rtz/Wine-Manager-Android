package com.n8rtz.winetracker.scanner

import com.n8rtz.winetracker.data.local.WineDefinition
import java.text.Normalizer
import java.util.Locale

internal enum class WineTermKind {
    PRODUCER,
    WINE_NAME,
    GRAPE,
    APPELLATION,
    WINE_STYLE,
    COUNTRY,
    REGION,
    SUBREGION,
    WINE_TYPE,
    LABEL_MARKER
}

internal data class WineLexiconEntry(
    val canonical: String,
    val kind: WineTermKind,
    val aliases: List<String> = emptyList(),
    val country: String? = null,
    val region: String? = null,
    val subregion: String? = null,
    val wineType: String? = null,
    val maker: String? = null,
    val sourceWeight: Int = 0,
    val useAsName: Boolean = false,
    val associatedGrapes: Set<String> = emptySet(),
    val grapesAreDefining: Boolean = false
)

internal data class WineOcrToken(
    val text: String,
    val key: String,
    val start: Int,
    val end: Int,
    val lineIndex: Int
)

internal data class WineLexiconMatch(
    val startToken: Int,
    val endTokenExclusive: Int,
    val startOffset: Int,
    val endOffset: Int,
    val entry: WineLexiconEntry,
    val editDistance: Int,
    val similarity: Double
)

internal data class WineDictionaryCorrection(
    val correctedText: String,
    val tokens: List<WineOcrToken>,
    val matches: List<WineLexiconMatch>
)

/**
 * Local wine vocabulary used to repair plausible OCR errors without sending label text anywhere.
 * It combines broad wine terminology with every definition retained in the user's Room database.
 */
internal class WineReferenceDictionary private constructor(entries: List<WineLexiconEntry>) {
    private data class AliasCandidate(
        val entry: WineLexiconEntry,
        val key: String
    )

    private data class CandidateMatch(
        val candidate: AliasCandidate,
        val startToken: Int,
        val endTokenExclusive: Int,
        val distance: Int,
        val similarity: Double
    )

    private val candidatesByLength: Map<Int, List<AliasCandidate>> = entries
        .flatMap { entry ->
            (listOf(entry.canonical) + entry.aliases).mapNotNull { alias ->
                val key = normalizedKey(alias)
                if (key.length < MIN_TERM_LENGTH) null else AliasCandidate(entry, key)
            }
        }
        .distinctBy { candidate ->
            listOf(
                candidate.entry.kind.name,
                candidate.entry.canonical,
                candidate.key,
                candidate.entry.country.orEmpty(),
                candidate.entry.region.orEmpty(),
                candidate.entry.subregion.orEmpty(),
                candidate.entry.maker.orEmpty()
            ).joinToString("|")
        }
        .groupBy { it.key.length }
    private val candidateLengths = candidatesByLength.keys.sorted()
    private val grapeCandidatesByKey = candidatesByLength.values
        .flatten()
        .filter { it.entry.kind == WineTermKind.GRAPE }
        .associateBy { it.key }
    private val producerFormSets = entries
        .filter { it.kind == WineTermKind.PRODUCER }
        .map { entry ->
            (listOf(entry.canonical) + entry.aliases)
                .map { normalizedKey(it) }
                .filter(String::isNotEmpty)
                .toSet()
        }

    fun producerNamesEquivalent(first: String, second: String): Boolean {
        val firstKey = normalizedKey(canonicalProducerName(first) ?: first)
        val secondKey = normalizedKey(canonicalProducerName(second) ?: second)
        return firstKey == secondKey || producerFormSets.any { forms ->
            firstKey in forms && secondKey in forms
        }
    }

    /** Hard compatibility only for appellations with a defining grape, not broad regions. */
    fun isCompatibleWithWineContext(context: WineLexiconEntry, wineTerm: WineLexiconEntry): Boolean {
        if (
            !context.grapesAreDefining ||
            (wineTerm.kind != WineTermKind.GRAPE && wineTerm.kind != WineTermKind.WINE_NAME)
        ) {
            return true
        }

        val contextColor = wineColor(context.wineType)
        val candidateColor = wineColor(wineTerm.wineType)
        if (contextColor != null && candidateColor != null && contextColor != candidateColor) return false

        val candidateGrapeKeys = grapeKeysForWineTerm(wineTerm)
        if (candidateGrapeKeys.isNotEmpty()) {
            val expectedGrapeKeys = contextualGrapes(context).map(::normalizedKey).toSet()
            return candidateGrapeKeys.any { it in expectedGrapeKeys }
        }
        return true
    }

    /** Typical regional grapes gently boost contextual matches without excluding valid outliers. */
    fun grapeContextBonus(context: WineLexiconEntry?, wineTerm: WineLexiconEntry): Double {
        if (
            context == null ||
            (wineTerm.kind != WineTermKind.GRAPE && wineTerm.kind != WineTermKind.WINE_NAME)
        ) return 0.0
        val expectedGrapes = contextualGrapes(context)
        if (expectedGrapes.isEmpty()) return 0.0
        val candidateKeys = grapeKeysForWineTerm(wineTerm)
        if (candidateKeys.isEmpty()) return 0.0
        return if (expectedGrapes.any { normalizedKey(it) in candidateKeys }) {
            MATCHING_GRAPE_CONTEXT_BONUS
        } else if (context.grapesAreDefining) {
            CONFLICTING_GRAPE_CONTEXT_PENALTY
        } else {
            UNCOMMON_GRAPE_CONTEXT_PENALTY
        }
    }

    private fun contextualGrapes(context: WineLexiconEntry): Set<String> =
        context.associatedGrapes.ifEmpty {
            grapesForGeography(context.country, context.region, context.subregion)
        }

    private fun grapeKeysForWineTerm(wineTerm: WineLexiconEntry): Set<String> {
        val termKeys = (listOf(wineTerm.canonical) + wineTerm.aliases).map(::normalizedKey)
        if (wineTerm.kind == WineTermKind.GRAPE) {
            return termKeys.filter { it in grapeCandidatesByKey }.toSet()
        }
        return (listOf(wineTerm.canonical) + wineTerm.aliases)
            .flatMap { phrase ->
                val words = TOKEN_REGEX.findAll(phrase).map { normalizedKey(it.value) }.toList()
                (1..words.size).flatMap { span ->
                    (0..(words.size - span)).map { start ->
                        words.subList(start, start + span).joinToString("")
                    }
                }
            }
            .filter { it in grapeCandidatesByKey }
            .toSet()
    }

    private fun wineColor(value: String?): String? = when (normalizedKey(value.orEmpty())) {
        "red", "redwine" -> "red"
        "white", "whitewine" -> "white"
        "rose", "rosewine" -> "rose"
        else -> null
    }

    fun correct(text: String): WineDictionaryCorrection {
        val tokens = TOKEN_REGEX.findAll(text).map { match ->
            WineOcrToken(
                text = match.value,
                key = normalizedKey(match.value),
                start = match.range.first,
                end = match.range.last + 1,
                lineIndex = text.substring(0, match.range.first).count { it == '\n' }
            )
        }.toList()

        if (tokens.isEmpty() || candidatesByLength.isEmpty()) {
            return WineDictionaryCorrection(text, tokens, emptyList())
        }

        val matches = mutableListOf<WineLexiconMatch>()
        var tokenIndex = 0
        while (tokenIndex < tokens.size) {
            val best = bestMatchAt(tokens, tokenIndex)
            if (best == null) {
                tokenIndex++
            } else {
                val start = tokens[best.startToken]
                val end = tokens[best.endTokenExclusive - 1]
                matches += WineLexiconMatch(
                    startToken = best.startToken,
                    endTokenExclusive = best.endTokenExclusive,
                    startOffset = start.start,
                    endOffset = end.end,
                    entry = best.candidate.entry,
                    editDistance = best.distance,
                    similarity = best.similarity
                )
                tokenIndex = best.endTokenExclusive
            }
        }

        return WineDictionaryCorrection(
            correctedText = applyMatches(text, matches),
            tokens = tokens,
            matches = matches
        )
    }

    private fun bestMatchAt(tokens: List<WineOcrToken>, startToken: Int): CandidateMatch? {
        var best: CandidateMatch? = null
        val lastTokenExclusive = minOf(tokens.size, startToken + MAX_PHRASE_TOKENS)
        var combinedKey = ""

        for (endTokenExclusive in (startToken + 1)..lastTokenExclusive) {
            combinedKey += tokens[endTokenExclusive - 1].key
            if (combinedKey.length < MIN_TERM_LENGTH) continue

            val candidateLengths = candidateLengthsToCheck(combinedKey.length)
            for (candidateLength in candidateLengths) {
                for (candidate in candidatesByLength[candidateLength].orEmpty()) {
                    val maxEdits = maximumEditDistance(candidate.key.length)
                    if (kotlin.math.abs(combinedKey.length - candidate.key.length) > maxEdits) continue
                    val distance = levenshteinDistance(combinedKey, candidate.key, maxEdits)
                    if (distance > maxEdits) continue
                    val similarity = 1.0 - distance.toDouble() / maxOf(combinedKey.length, candidate.key.length)
                    if (distance > 0 && similarity < MIN_FUZZY_SIMILARITY) continue

                    val match = CandidateMatch(
                        candidate = candidate,
                        startToken = startToken,
                        endTokenExclusive = endTokenExclusive,
                        distance = distance,
                        similarity = similarity
                    )
                    if (best == null || compareMatches(match, best) > 0) best = match
                }
            }
        }
        return best
    }

    private fun compareMatches(left: CandidateMatch, right: CandidateMatch): Int {
        val leftSpan = left.endTokenExclusive - left.startToken
        val rightSpan = right.endTokenExclusive - right.startToken
        if (
            left.candidate.entry.kind == right.candidate.entry.kind &&
            left.candidate.key == right.candidate.key
        ) {
            compareValues(left.candidate.entry.sourceWeight, right.candidate.entry.sourceWeight)
                .takeIf { it != 0 }?.let { return it }
        }
        if (
            left.candidate.key == right.candidate.key &&
            setOf(left.candidate.entry.kind, right.candidate.entry.kind) ==
            setOf(WineTermKind.WINE_NAME, WineTermKind.GRAPE)
        ) {
            val cellarNameWeight = if (left.candidate.entry.kind == WineTermKind.WINE_NAME) {
                left.candidate.entry.sourceWeight
            } else {
                right.candidate.entry.sourceWeight
            }
            val grapeWeight = if (left.candidate.entry.kind == WineTermKind.GRAPE) {
                left.candidate.entry.sourceWeight
            } else {
                right.candidate.entry.sourceWeight
            }
            compareValues(cellarNameWeight, grapeWeight)
                .takeIf { it != 0 }?.let { comparison ->
                    return if (left.candidate.entry.kind == WineTermKind.WINE_NAME) comparison else -comparison
                }
        }
        compareValues(rankedSimilarity(left, leftSpan), rankedSimilarity(right, rightSpan))
            .takeIf { it != 0 }?.let { return it }
        compareValues(left.similarity, right.similarity).takeIf { it != 0 }?.let { return it }
        compareValues(kindPriority(left.candidate.entry.kind), kindPriority(right.candidate.entry.kind))
            .takeIf { it != 0 }?.let { return it }
        compareValues(leftSpan, rightSpan).takeIf { it != 0 }?.let { return it }
        compareValues(left.candidate.entry.sourceWeight, right.candidate.entry.sourceWeight)
            .takeIf { it != 0 }?.let { return it }
        return compareValues(right.distance, left.distance)
    }

    /**
     * Rank semantic wine terms above incidental location/label text, while still requiring a
     * strong OCR similarity. Longer near-exact phrases get a small completeness bonus.
     */
    private fun rankedSimilarity(match: CandidateMatch, tokenSpan: Int): Double {
        val phraseBonus = if (tokenSpan > 1 && match.similarity >= MIN_PHRASE_MATCH_SIMILARITY) {
            PHRASE_MATCH_BONUS * (tokenSpan - 1).coerceAtMost(MAX_PHRASE_BONUS_SPAN - 1)
        } else {
            0.0
        }
        val sourceBonus = match.candidate.entry.sourceWeight.coerceIn(0, 30) / 1_000.0
        return match.similarity + phraseBonus + kindWeight(match.candidate.entry.kind) + sourceBonus
    }

    private fun kindWeight(kind: WineTermKind): Double = when (kind) {
        WineTermKind.APPELLATION -> 0.18
        WineTermKind.GRAPE -> 0.15
        WineTermKind.WINE_STYLE -> 0.13
        WineTermKind.PRODUCER -> 0.14
        WineTermKind.WINE_NAME -> 0.08
        WineTermKind.SUBREGION -> 0.04
        WineTermKind.REGION -> 0.04
        WineTermKind.WINE_TYPE -> 0.02
        WineTermKind.COUNTRY -> 0.0
        WineTermKind.LABEL_MARKER -> 0.0
    }

    private fun candidateLengthsToCheck(inputLength: Int): List<Int> = candidateLengths.filter { candidateLength ->
        kotlin.math.abs(inputLength - candidateLength) <= maximumEditDistance(candidateLength)
    }

    private fun applyMatches(text: String, matches: List<WineLexiconMatch>): String {
        if (matches.isEmpty()) return text
        return buildString {
            var cursor = 0
            matches.forEach { match ->
                if (match.startOffset < cursor) return@forEach
                append(text, cursor, match.startOffset)
                append(match.entry.canonical)
                cursor = match.endOffset
            }
            append(text, cursor, text.length)
        }
    }

    private fun kindPriority(kind: WineTermKind): Int = when (kind) {
        WineTermKind.WINE_NAME -> 100
        WineTermKind.PRODUCER -> 90
        WineTermKind.APPELLATION -> 80
        WineTermKind.GRAPE -> 75
        WineTermKind.WINE_STYLE -> 70
        WineTermKind.SUBREGION -> 55
        WineTermKind.REGION -> 45
        WineTermKind.COUNTRY -> 35
        WineTermKind.WINE_TYPE -> 25
        WineTermKind.LABEL_MARKER -> 15
    }

    companion object {
        private const val MIN_TERM_LENGTH = 3
        private const val MAX_PHRASE_TOKENS = 7
        private const val MIN_FUZZY_SIMILARITY = 0.82
        private const val MIN_PHRASE_MATCH_SIMILARITY = 0.90
        private const val PHRASE_MATCH_BONUS = 0.12
        private const val MAX_PHRASE_BONUS_SPAN = 3
        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        private const val MATCHING_GRAPE_CONTEXT_BONUS = 0.12
        private const val UNCOMMON_GRAPE_CONTEXT_PENALTY = -0.10
        private const val CONFLICTING_GRAPE_CONTEXT_PENALTY = -0.65
        private val builtInDictionary by lazy { build(emptyList(), emptyMap()) }

        private data class RegionalGrapeProfile(
            val country: String,
            val region: String,
            val grapes: Set<String>,
            val subregion: String? = null
        )

        /** Common regional varieties provide soft context; a known appellation can be stricter. */
        private val REGIONAL_GRAPE_PROFILES = listOf(
            RegionalGrapeProfile("Italy", "Tuscany", setOf("Sangiovese", "Vernaccia", "Vermentino")),
            RegionalGrapeProfile("Italy", "Piedmont", setOf("Nebbiolo", "Barbera", "Dolcetto", "Cortese", "Moscato")),
            RegionalGrapeProfile("Italy", "Veneto", setOf("Corvina", "Corvinone", "Rondinella", "Glera", "Garganega")),
            RegionalGrapeProfile("Italy", "Sicily", setOf("Nero d'Avola", "Nerello Mascalese", "Nerello Cappuccio", "Grillo", "Carricante")),
            RegionalGrapeProfile("Italy", "Campania", setOf("Aglianico", "Fiano", "Greco")),
            RegionalGrapeProfile("Italy", "Umbria", setOf("Sagrantino", "Grechetto")),
            RegionalGrapeProfile("France", "Burgundy", setOf("Pinot Noir", "Chardonnay", "Aligoté", "Gamay")),
            RegionalGrapeProfile("France", "Bordeaux", setOf("Cabernet Sauvignon", "Merlot", "Cabernet Franc", "Sauvignon Blanc", "Sémillon")),
            RegionalGrapeProfile("France", "Rhone", setOf("Syrah", "Grenache", "Mourvèdre", "Viognier")),
            RegionalGrapeProfile("France", "Loire", setOf("Sauvignon Blanc", "Chenin Blanc", "Cabernet Franc", "Melon de Bourgogne")),
            RegionalGrapeProfile("France", "Champagne", setOf("Chardonnay", "Pinot Noir", "Pinot Meunier")),
            RegionalGrapeProfile("France", "Beaujolais", setOf("Gamay")),
            RegionalGrapeProfile("France", "Provence", setOf("Grenache", "Mourvèdre", "Cinsault", "Syrah")),
            RegionalGrapeProfile("Spain", "Rioja", setOf("Tempranillo", "Garnacha", "Graciano", "Viura")),
            RegionalGrapeProfile("Spain", "Castilla y Leon", setOf("Tempranillo", "Verdejo")),
            RegionalGrapeProfile("Spain", "Galicia", setOf("Albariño", "Godello")),
            RegionalGrapeProfile("Spain", "Catalonia", setOf("Garnacha", "Carignan", "Macabeo", "Xarel·lo", "Parellada")),
            RegionalGrapeProfile("Portugal", "Douro", setOf("Touriga Nacional", "Touriga Franca", "Tinta Roriz", "Tinta Barroca")),
            RegionalGrapeProfile("Germany", "Mosel", setOf("Riesling", "Müller-Thurgau", "Elbling")),
            RegionalGrapeProfile("Germany", "Rheingau", setOf("Riesling", "Spätburgunder")),
            RegionalGrapeProfile("Germany", "Rheinhessen", setOf("Riesling", "Müller-Thurgau", "Silvaner")),
            RegionalGrapeProfile("Germany", "Pfalz", setOf("Riesling", "Gewürztraminer", "Spätburgunder")),
            RegionalGrapeProfile("United States", "California", setOf("Cabernet Sauvignon", "Chardonnay", "Merlot", "Pinot Noir", "Sauvignon Blanc", "Zinfandel")),
            RegionalGrapeProfile("United States", "California", setOf("Cabernet Sauvignon", "Chardonnay", "Merlot", "Pinot Noir", "Sauvignon Blanc", "Zinfandel"), "Napa Valley"),
            RegionalGrapeProfile("United States", "California", setOf("Pinot Noir", "Chardonnay", "Syrah", "Zinfandel"), "Sonoma Coast"),
            RegionalGrapeProfile("United States", "North Coast", setOf("Cabernet Sauvignon", "Chardonnay", "Pinot Noir", "Sauvignon Blanc", "Zinfandel")),
            RegionalGrapeProfile("United States", "Central Coast", setOf("Pinot Noir", "Chardonnay", "Syrah", "Cabernet Sauvignon")),
            RegionalGrapeProfile("United States", "Central Valley", setOf("Zinfandel", "Cabernet Sauvignon", "Chardonnay", "Merlot")),
            RegionalGrapeProfile("United States", "Oregon", setOf("Pinot Noir", "Pinot Gris", "Chardonnay", "Riesling")),
            RegionalGrapeProfile("United States", "Oregon", setOf("Pinot Noir", "Pinot Gris", "Chardonnay", "Riesling"), "Willamette Valley"),
            RegionalGrapeProfile("United States", "Columbia Valley", setOf("Cabernet Sauvignon", "Merlot", "Syrah", "Riesling", "Chardonnay")),
            RegionalGrapeProfile("United States", "Washington", setOf("Cabernet Sauvignon", "Merlot", "Syrah", "Riesling", "Chardonnay")),
            RegionalGrapeProfile("United States", "Washington", setOf("Cabernet Sauvignon", "Merlot", "Syrah", "Riesling", "Chardonnay"), "Walla Walla Valley"),
            RegionalGrapeProfile("Australia", "South Australia", setOf("Shiraz", "Cabernet Sauvignon", "Grenache", "Riesling")),
            RegionalGrapeProfile("Australia", "Western Australia", setOf("Cabernet Sauvignon", "Chardonnay", "Sauvignon Blanc", "Sémillon")),
            RegionalGrapeProfile("Australia", "Victoria", setOf("Pinot Noir", "Chardonnay", "Shiraz")),
            RegionalGrapeProfile("Australia", "New South Wales", setOf("Sémillon", "Shiraz", "Chardonnay")),
            RegionalGrapeProfile("New Zealand", "Marlborough", setOf("Sauvignon Blanc", "Pinot Noir", "Chardonnay")),
            RegionalGrapeProfile("New Zealand", "Central Otago", setOf("Pinot Noir", "Riesling", "Pinot Gris")),
            RegionalGrapeProfile("Argentina", "Mendoza", setOf("Malbec", "Bonarda", "Cabernet Sauvignon", "Chardonnay")),
            RegionalGrapeProfile("Chile", "Central Valley", setOf("Cabernet Sauvignon", "Carménère", "Merlot", "Syrah")),
            RegionalGrapeProfile("South Africa", "Coastal Region", setOf("Chenin Blanc", "Pinotage", "Cabernet Sauvignon", "Syrah"))
        )

        private val DEFINING_APPELLATION_GRAPES = mapOf(
            normalizedKey("Brunello di Montalcino") to setOf("Sangiovese"),
            normalizedKey("Rosso di Montalcino") to setOf("Sangiovese"),
            normalizedKey("Barolo") to setOf("Nebbiolo"),
            normalizedKey("Barbaresco") to setOf("Nebbiolo"),
            normalizedKey("Gavi") to setOf("Cortese"),
            normalizedKey("Chablis") to setOf("Chardonnay"),
            normalizedKey("Beaujolais") to setOf("Gamay"),
            normalizedKey("Vernaccia di San Gimignano") to setOf("Vernaccia")
        )

        private fun canonicalWineCountry(country: String?): String? {
            val value = country?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return when (normalizedKey(value)) {
                "california", "oregon", "washington", "unitedstatesofamerica", "usa", "us" -> "United States"
                else -> value
            }
        }

        private fun grapesForGeography(country: String?, region: String?, subregion: String?): Set<String> {
            val countryKey = normalizedKey(canonicalWineCountry(country).orEmpty())
            val subregionKey = normalizedKey(subregion.orEmpty())
            val regionKey = normalizedKey(region.orEmpty())
            val sameCountry = REGIONAL_GRAPE_PROFILES.filter {
                normalizedKey(it.country) == countryKey
            }
            return sameCountry.firstOrNull {
                it.subregion != null && normalizedKey(it.subregion) == subregionKey
            }?.grapes ?: sameCountry.firstOrNull {
                it.subregion == null && normalizedKey(it.region) == regionKey
            }?.grapes.orEmpty()
        }

        fun builtIn(): WineReferenceDictionary = builtInDictionary

        internal fun canonicalProducerName(value: String?): String? {
            val producer = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return if (normalizedKey(producer) == "kirkland") "Kirkland Signature" else producer
        }

        fun forCellar(
            definitions: List<WineDefinition>,
            locationData: Map<String, Map<String, List<String>>>
        ): WineReferenceDictionary = build(definitions, locationData)

        private fun build(
            definitions: List<WineDefinition>,
            locationData: Map<String, Map<String, List<String>>>
        ): WineReferenceDictionary {
            val entries = mutableListOf<WineLexiconEntry>()
            entries += grapeEntries()
            entries += styleEntries()
            entries += producerEntries()
            val appellations = appellationEntries()
            entries += appellations
            entries += curatedGeographyEntries(appellations)
            entries += labelMarkerEntries()
            entries += wineTypeEntries()
            val knownAppellationKeys = entries
                .filter { it.kind == WineTermKind.APPELLATION }
                .flatMap { listOf(it.canonical) + it.aliases }
                .map { normalizedKey(it) }
                .toSet()

            // Treat U.S. states as regions, not countries, even in older location-data hierarchies.
            locationData.forEach { (country, regions) ->
                val canonicalCountry = canonicalWineCountry(country) ?: country
                val countryAliases = if (normalizedKey(canonicalCountry) == normalizedKey(country)) {
                    emptyList()
                } else {
                    listOf(country)
                }
                entries += WineLexiconEntry(
                    canonical = canonicalCountry,
                    kind = WineTermKind.COUNTRY,
                    aliases = countryAliases,
                    country = canonicalCountry,
                    sourceWeight = 30
                )
                regions.forEach { (region, subregions) ->
                    entries += WineLexiconEntry(
                        canonical = region,
                        kind = WineTermKind.REGION,
                        aliases = locationAliases(region),
                        country = canonicalCountry,
                        region = region,
                        sourceWeight = 30,
                        associatedGrapes = grapesForGeography(country, region, null)
                    )
                    subregions.forEach { subregion ->
                        entries += WineLexiconEntry(
                            canonical = subregion,
                            kind = WineTermKind.SUBREGION,
                            country = canonicalCountry,
                            region = region,
                            subregion = subregion,
                            sourceWeight = 30,
                            useAsName = normalizedKey(subregion) in knownAppellationKeys,
                            associatedGrapes = grapesForGeography(country, region, subregion)
                        )
                    }
                }
            }

            val distinctMakers = definitions
                .mapNotNull { canonicalProducerName(it.maker) }
                .distinctBy { normalizedKey(it) }
            val uniqueFirstWords = distinctMakers
                .groupBy { normalizedKey(it.substringBefore(' ')) }
                .filter { (firstWord, makers) -> firstWord.length >= 5 && makers.size == 1 }
                .keys

            definitions.filter { it.name.trim().isNotEmpty() }
                .groupBy { normalizedKey(it.name.trim()) }
                .values
                .forEach { sameNameDefinitions ->
                    val exemplar = sameNameDefinitions.first()
                    fun uniqueValue(values: List<String?>): String? = values
                        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                        .distinctBy { normalizedKey(it) }
                        .singleOrNull()

                    val nameKey = normalizedKey(exemplar.name)
                    val curatedAppellation = entries.firstOrNull { entry ->
                        entry.kind == WineTermKind.APPELLATION &&
                            (normalizedKey(entry.canonical) == nameKey || entry.aliases.any { normalizedKey(it) == nameKey })
                    }
                    val country = curatedAppellation?.country
                        ?: canonicalWineCountry(uniqueValue(sameNameDefinitions.map { it.country }))
                    val region = curatedAppellation?.region ?: canonicalRegion(
                        uniqueValue(sameNameDefinitions.map { it.region }),
                        country,
                        locationData
                    )

                    entries += WineLexiconEntry(
                        canonical = exemplar.name.trim(),
                        kind = WineTermKind.WINE_NAME,
                        country = country,
                        region = region,
                        subregion = curatedAppellation?.subregion
                            ?: uniqueValue(sameNameDefinitions.map { it.subregion }),
                        wineType = curatedAppellation?.wineType
                            ?: uniqueValue(sameNameDefinitions.map { it.type }),
                        maker = uniqueValue(sameNameDefinitions.map { canonicalProducerName(it.maker) }),
                        sourceWeight = 20,
                        useAsName = true,
                        associatedGrapes = curatedAppellation?.associatedGrapes
                            ?: grapesForGeography(country, region, uniqueValue(sameNameDefinitions.map { it.subregion })),
                        grapesAreDefining = curatedAppellation?.grapesAreDefining == true
                    )
                }

            distinctMakers.forEach { maker ->
                val makerAliases = maker.substringBefore(' ')
                    .takeIf { normalizedKey(it) in uniqueFirstWords }
                    ?.let { listOf(it) }
                    .orEmpty()
                entries += WineLexiconEntry(
                    canonical = maker,
                    kind = WineTermKind.PRODUCER,
                    aliases = makerAliases,
                    sourceWeight = 20
                )
            }

            definitions.forEach { definition ->
                definition.type.trim().takeIf(String::isNotEmpty)?.let { type ->
                    entries += WineLexiconEntry(
                        canonical = type,
                        kind = WineTermKind.WINE_TYPE,
                        wineType = type,
                        sourceWeight = 20
                    )
                }
                definition.country.trim().takeIf(String::isNotEmpty)?.let { country ->
                    val canonicalCountry = canonicalWineCountry(country) ?: country
                    val countryAliases = if (normalizedKey(canonicalCountry) == normalizedKey(country)) {
                        emptyList()
                    } else {
                        listOf(country)
                    }
                    entries += WineLexiconEntry(
                        canonical = canonicalCountry,
                        kind = WineTermKind.COUNTRY,
                        aliases = countryAliases,
                        country = canonicalCountry,
                        sourceWeight = 20
                    )
                }
                definition.region?.trim()?.takeIf(String::isNotEmpty)?.let { region ->
                    entries += WineLexiconEntry(
                        canonical = region,
                        kind = WineTermKind.REGION,
                        country = canonicalWineCountry(definition.country),
                        region = region,
                        sourceWeight = 20,
                        associatedGrapes = grapesForGeography(definition.country, region, null)
                    )
                }
                definition.subregion?.trim()?.takeIf(String::isNotEmpty)?.let { subregion ->
                    entries += WineLexiconEntry(
                        canonical = subregion,
                        kind = WineTermKind.SUBREGION,
                        country = canonicalWineCountry(definition.country),
                        region = definition.region,
                        subregion = subregion,
                        sourceWeight = 20,
                        useAsName = normalizedKey(subregion) in knownAppellationKeys,
                        associatedGrapes = grapesForGeography(definition.country, definition.region, subregion)
                    )
                }
            }

            return WineReferenceDictionary(entries.distinctBy { entry ->
                listOf(
                    entry.kind.name,
                    normalizedKey(entry.canonical),
                    entry.country.orEmpty(),
                    entry.region.orEmpty(),
                    entry.subregion.orEmpty(),
                    entry.maker.orEmpty()
                ).joinToString("|")
            })
        }

        private fun grapeEntries(): List<WineLexiconEntry> = buildList {
            addGrapes(RED_GRAPES, "Red")
            addGrapes(WHITE_GRAPES, "White")
            addGrapes(OTHER_GRAPES, null)
        }

        private fun MutableList<WineLexiconEntry>.addGrapes(source: String, type: String?) {
            source.trimIndent().lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
                val pieces = line.split('|', limit = 2)
                val canonical = pieces.first().trim()
                val aliases = pieces.getOrNull(1)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                add(
                    WineLexiconEntry(
                        canonical = canonical,
                        kind = WineTermKind.GRAPE,
                        aliases = aliases,
                        wineType = type,
                        useAsName = true
                    )
                )
            }
        }

        private fun styleEntries(): List<WineLexiconEntry> = WINE_STYLES.trimIndent()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val pieces = line.split('|', limit = 2)
                val canonical = pieces.first().trim()
                val aliases = pieces.getOrNull(1)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                WineLexiconEntry(
                    canonical = canonical,
                    kind = WineTermKind.WINE_STYLE,
                    aliases = aliases,
                    wineType = styleType(canonical),
                    useAsName = true
                )
            }
            .toList()

        private fun producerEntries(): List<WineLexiconEntry> = PRODUCERS.trimIndent()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val pieces = line.split('|', limit = 2)
                WineLexiconEntry(
                    canonical = pieces.first().trim(),
                    kind = WineTermKind.PRODUCER,
                    aliases = pieces.getOrNull(1)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                    sourceWeight = 30
                )
            }
            .toList()

        private fun appellationEntries(): List<WineLexiconEntry> = APPELLATIONS.trimIndent()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val pieces = line.split('|')
                val canonical = pieces[0].trim()
                val country = pieces.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
                val region = pieces.getOrNull(2)?.trim()?.takeIf(String::isNotEmpty)
                val subregion = pieces.getOrNull(3)?.trim()?.takeIf(String::isNotEmpty) ?: canonical
                // A dash marks broad regions with no single default wine color or style.
                val type = pieces.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
                val aliases = pieces.getOrNull(5)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                val definingGrapes = DEFINING_APPELLATION_GRAPES[normalizedKey(canonical)]
                WineLexiconEntry(
                    canonical = canonical,
                    kind = WineTermKind.APPELLATION,
                    aliases = aliases,
                    country = country,
                    region = region,
                    subregion = subregion,
                    wineType = type,
                    sourceWeight = 10,
                    useAsName = true,
                    associatedGrapes = definingGrapes ?: grapesForGeography(country, region, subregion),
                    grapesAreDefining = definingGrapes != null
                )
            }
            .toList()

        private fun curatedGeographyEntries(appellations: List<WineLexiconEntry>): List<WineLexiconEntry> = buildList {
            appellations.mapNotNull { it.country }
                .distinctBy { normalizedKey(it) }
                .forEach { country ->
                    add(
                        WineLexiconEntry(
                            canonical = country,
                            kind = WineTermKind.COUNTRY,
                            country = country,
                            sourceWeight = 15
                        )
                    )
                }
            appellations.mapNotNull { entry ->
                entry.region?.let { region -> Triple(entry.country, region, entry) }
            }.distinctBy { (country, region, _) -> "${normalizedKey(country.orEmpty())}|${normalizedKey(region)}" }
                .forEach { (country, region, _) ->
                    add(
                        WineLexiconEntry(
                            canonical = region,
                            kind = WineTermKind.REGION,
                            aliases = locationAliases(region),
                            country = country,
                            region = region,
                            sourceWeight = 15,
                            associatedGrapes = grapesForGeography(country, region, null)
                        )
                    )
                }
        }

        private fun labelMarkerEntries(): List<WineLexiconEntry> = LABEL_MARKERS.trimIndent()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val pieces = line.split('|', limit = 2)
                WineLexiconEntry(
                    canonical = pieces.first().trim(),
                    kind = WineTermKind.LABEL_MARKER,
                    aliases = pieces.getOrNull(1)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                )
            }
            .toList()

        private fun wineTypeEntries(): List<WineLexiconEntry> = listOf(
            WineLexiconEntry("Red", WineTermKind.WINE_TYPE, aliases = listOf("Red Wine"), wineType = "Red"),
            WineLexiconEntry("White", WineTermKind.WINE_TYPE, aliases = listOf("White Wine"), wineType = "White"),
            WineLexiconEntry("Rose", WineTermKind.WINE_TYPE, aliases = listOf("Rosé", "Rose Wine"), wineType = "Rose"),
            WineLexiconEntry("Sparkling", WineTermKind.WINE_TYPE, aliases = listOf("Sparkling Wine"), wineType = "Sparkling"),
            WineLexiconEntry("Dessert", WineTermKind.WINE_TYPE, aliases = listOf("Dessert Wine", "Sweet Wine"), wineType = "Dessert")
        )

        private fun locationAliases(location: String): List<String> = when (normalizedKey(location)) {
            "tuscany" -> listOf("Toscana", "Tuscana")
            else -> emptyList()
        }

        private fun canonicalRegion(
            value: String?,
            country: String?,
            locationData: Map<String, Map<String, List<String>>>
        ): String? {
            val region = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val regionKey = normalizedKey(region)
            val regions = locationData
                .filterKeys { country.isNullOrBlank() || it.equals(country, ignoreCase = true) }
                .values
                .flatMap { it.keys }
            return regions.firstOrNull { candidate ->
                normalizedKey(candidate) == regionKey || locationAliases(candidate).any { normalizedKey(it) == regionKey }
            } ?: region
        }

        private fun styleType(style: String): String? = when (normalizedKey(style)) {
            "champagne", "prosecco", "cava", "cremant", "franciacorta", "sekt", "sparkling" -> "Sparkling"
            "rose", "rosewine" -> "Rose"
            "sauternes", "icewine", "tokajiaszu", "port", "sherry", "madeira", "marsala", "vinsanto" -> "Dessert"
            else -> null
        }

        private fun maximumEditDistance(length: Int): Int = when {
            length <= 4 -> 0
            length <= 7 -> 1
            length <= 12 -> 2
            else -> (length / 6).coerceAtMost(4)
        }

        private fun levenshteinDistance(left: String, right: String, cutoff: Int): Int {
            if (kotlin.math.abs(left.length - right.length) > cutoff) return cutoff + 1
            var previous = IntArray(right.length + 1) { it }
            var current = IntArray(right.length + 1)
            for (leftIndex in left.indices) {
                current[0] = leftIndex + 1
                var rowMinimum = current[0]
                for (rightIndex in right.indices) {
                    val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                    current[rightIndex + 1] = minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        previous[rightIndex] + substitutionCost
                    )
                    rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
                }
                if (rowMinimum > cutoff) return cutoff + 1
                val temporary = previous
                previous = current
                current = temporary
            }
            return previous[right.length]
        }

        private fun normalizedKey(value: String): String = Normalizer
            .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .filter { it.isLetterOrDigit() }

        private val RED_GRAPES = """
            Aglianico
            Alicante Bouschet|Garnacha Tintorera;Alicante
            Alvarelhão
            Tempranillo|Tinta Roriz;Aragonez;Tinta de Toro
            Aramon
            Baga
            Barbera
            Blauburger
            Blaufränkisch|Lemberger;Kékfrankos;Frankovka
            Bobal
            Bonarda|Charbono
            Brachetto
            Brancellao
            Cabernet Franc
            Cabernet Sauvignon
            Caladoc
            Canaiolo Nero
            Carignan|Cariñena;Mazuelo;Carignane
            Carménère|Carmenere
            Cinsault|Cinsaut
            Colorino
            Corvina
            Corvinone
            Counoise
            Dolcetto
            Dornfelder
            Duras
            Durif|Petite Sirah;Petite Syrah
            Feteasca Neagra|Fetească Neagră
            Fer Servadou|Braucol
            Frappato
            Freisa
            Fumin
            Gamay
            Garnacha|Grenache;Grenache Noir;Garnacha Tinta
            Graciano|Tinta Miuda
            Grand Noir de la Calmette
            Grignolino
            Kadarka
            Lagrein
            Lambrusco
            Listán Negro|Listan Negro
            Malbec|Cot
            Mavrud
            Mencía|Mencia
            Merlot
            Molinara
            Mondeuse Noire
            Montepulciano
            Mourvèdre|Mourvedre;Monastrell;Mataro
            Nebbiolo
            Nerello Cappuccio
            Nerello Mascalese
            Nero d'Avola|Nero d Avola;Calabrese
            Nero di Troia|Uva di Troia
            Nielluccio|Niellucciu
            País|Pais;Listan Prieto
            Petit Verdot
            Pinot Meunier
            Pinot Noir|Pinot Nero;Spätburgunder;Spatburgunder
            Plavac Mali
            Poulsard|Ploussard
            Primitivo|Zinfandel
            Refosco dal Peduncolo Rosso|Refosco
            Rondinella
            Sagrantino
            Sangiovese
            Schiava|Vernatsch;Trollinger
            Sciacarello|Sciaccarellu
            St. Laurent|Sankt Laurent;Saint Laurent
            Susumaniello
            Syrah|Shiraz;Sirah
            Tannat
            Teroldego
            Tinta Barroca
            Tinta Cão|Tinta Cao
            Tinta Francisca
            Touriga Franca
            Touriga Nacional
            Trincadeira|Tinta Amarela
            Trousseau|Bastardo
            Vranac
            Xinomavro
            Zweigelt|Blauer Zweigelt;Rotburger
            Abouriou
            Acolon
            Agiorgitiko
            Aleatico
            Alibernet
            Ancellotta
            Arinarnoa
            Baga
            Black Muscat|Moscato Nero
            Boğazkere|Bogazkere
            Bonda
            Cabernet Dorsa
            Cabernet Mitos
            Castelao|Castelão;Periquita
            Catawba
            Cesanese
            Chambourcin
            Charbono
            Corinto Nero
            Croatina|Bonarda Piemontese
            Crljenak Kaštelanski|Tribidrag
            Dobričić|Dobricic
            Espadeiro
            Früburgunder|Pinot Noir Précoce
            Gaglioppo
            Gamza
            Grignolino
            Heroldrebe
            Kotsifali
            Limnio
            Mandilaria
            Maratheftiko
            Marzemino
            Maturana Tinta
            Mavrotragano
            Mazuelo
            Melnik
            Misket Varnenski
            Négrette|Negrette
            Ner d'Avola
            Negroamaro
            Norton
            Oseleta
            Pelaverga
            Petit Bouschet
            Pineau d'Aunis|Chenin Noir
            Pinotage
            Plavac Mali
            Rara Neagra|Rară Neagră
            Regent
            Rondo
            Rufete
            Saperavi
            Sercial|Esgana Cão
            Sercialinho
            Souvignier Gris
            Tannat
            Tinta Negra|Negramoll
            Tinta Roriz
            Vaccarèse|Vaccarese
            Vespolina
            Vranac
            Xinomavro
        """

        private val WHITE_GRAPES = """
            Albariño|Alvarinho
            Albarín Blanco|Albarin Blanco
            Aligoté|Aligote
            Arinto
            Arneis
            Assyrtiko|Asyrtiko
            Auxerrois
            Bacchus
            Bical
            Carricante
            Catarratto
            Chardonnay
            Chasselas|Fendant;Gutedel
            Chenin Blanc|Pineau de la Loire
            Clairette
            Cortese
            Elbling
            Encruzado
            Fiano
            Fernão Pires|Maria Gomes
            Feteasca Alba|Fetească Albă
            Feteasca Regala|Fetească Regală
            Furmint
            Friulano|Tocai Friulano
            Garganega
            Gewürztraminer|Gewurztraminer;Traminer Aromatico
            Glera
            Godello
            Greco
            Grechetto
            Grüner Veltliner|Gruner Veltliner;Veltliner
            Hondarrabi Zuri|Hondarribi Zuri
            Hárslevelű|Harslevelu
            Huxelrebe
            Inzolia|Ansonica
            Jacquère|Jacquere
            Kerner
            Koshu
            Loureiro
            Macabeo|Viura
            Malagousia|Malagouzia
            Malvasia Bianca
            Marsanne
            Melon de Bourgogne|Melon
            Misket
            Moschofilero
            Müller-Thurgau|Mueller-Thurgau
            Muscadelle
            Muscat|Moscato;Moscatel;Muscat Blanc
            Muscat Blanc à Petits Grains|Muscat à petits grains
            Muscat Ottonel
            Palomino
            Parellada
            Pedro Ximénez|Pedro Jimenez;PX
            Petit Manseng
            Picpoul Blanc|Piquepoul Blanc
            Pinot Blanc|Pinot Bianco;Weissburgunder
            Pinot Gris|Pinot Grigio;Grauburgunder
            Riesling|Rhine Riesling
            Rkatsiteli
            Roussanne
            Sauvignon Blanc|Fumé Blanc;Fume Blanc
            Savagnin|Naturé
            Scheurebe
            Sémillon|Semillon
            Silvaner|Sylvaner
            Solaris
            Souvignier Gris
            Sylvaner
            Tămâioasă Românească|Tamaioasa Romaneasca
            Torrontés|Torrontes
            Trebbiano|Ugni Blanc;Procanico
            Verdejo
            Verdelho
            Vermentino|Rolle;Favorita
            Vernaccia
            Viognier
            Welschriesling|Olaszrizling;Riesling Italico
            Xarel·lo|Xarel-lo;Pansa Blanca
            Zibibbo
            Albillo
            Arneis
            Bouvier
            Bual|Boal
            Cercial
            Colombard
            Duras Blanc
            Garganega
            Grillo
            Grüner Silvaner|Grüner Sylvaner
            Hárslevelű
            Hibernal
            Jacquez Blanc
            Kanzler
            Klevner
            Malvasia Fina
            Marsanne Blanche
            Mauzac
            Mtsvane|Kakhuri Mtsvane
            Ortrugo
            Pálava|Palava
            Pansa Rosada
            Picolit
            Pignoletto
            Pinot Auxerrois
            Pinot Meunier Blanc
            Ribolla Gialla|Rebula
            Roter Veltliner
            Sacy
            Saperavi Blanc
            Sercial
            Souvignier Gris
            Tressallier
            Verdejo Blanco
            Verdiso
            Viosinho
            Viura
            Welschriesling
            Xynisteri
            Zéta|Zeta
        """

        private val OTHER_GRAPES = """
            Cabernet Blanc
            Chambourcin
            Frontenac
            Marquette
            Muscat Hamburg
            Niagara
            Seyval Blanc
            Vidal Blanc
            Vignoles
            Villard Blanc
            Villard Noir
        """

        private val WINE_STYLES = """
            Amarone della Valpolicella|Amarone
            Beaujolais Nouveau
            Bordeaux Blend|Meritage
            Cava
            Champagne
            Chianti Classico
            Crémant|Cremant
            Franciacorta
            Ice Wine|Eiswein;Vin de glace
            Lambrusco
            Madeira
            Marsala
            Moscato d'Asti|Moscato d Asti
            Passito
            Port|Porto;Port Wine
            Prosecco
            Sauternes
            Sekt
            Sherry|Jerez;Xérès;Xeres
            Tokaji Aszú|Tokaji;Tokay
            Vin Santo
            Vinho Verde
            Orange Wine
            Pét-Nat|Pet Nat;Pétillant Naturel
            Crémant de Bourgogne
            Crémant d'Alsace
            Crémant de Loire
            Crémant de Limoux
            Brut Nature
            Blanc de Blancs
            Blanc de Noirs
            Rosé|Rose Wine
        """

        private val PRODUCERS = """
            Kirkland Signature|Kirkland;Kirkland Wines;Costco
            Château Ste. Michelle|Chateau Ste Michelle;Ste Michelle;Chateau St Michelle
            Robert Mondavi|Mondavi
            Bogle Vineyards|Bogle
            Kendall-Jackson|Kendall Jackson;KJ
            La Crema
            Duckhorn Vineyards|Duckhorn
            Caymus Vineyards|Caymus
            Silver Oak Cellars|Silver Oak
            Opus One
            Domaine de la Romanée-Conti|DRC;Domaine de la Romanee Conti
            Louis Jadot|Maison Louis Jadot
            Joseph Drouhin|Maison Joseph Drouhin
            Bouchard Père & Fils|Bouchard Pere et Fils
            E. Guigal|Guigal
            M. Chapoutier|Chapoutier
            Antinori|Marchesi Antinori
            Frescobaldi|Marchesi de' Frescobaldi
            Castello Banfi|Banfi
            Gaja
            Concha y Toro|Concha Y Toro;Casillero del Diablo
            Catena Zapata|Catena
            Penfolds
            Cloudy Bay
            Oyster Bay
            Kim Crawford
            Santa Margherita
            Ruffino
            Masi
            Meiomi
            Barefoot Cellars|Barefoot
            19 Crimes
            Josh Cellars|Josh Wine
            Decoy|Duckhorn Decoy
            Orin Swift|Orin Swift Cellars
            Rombauer Vineyards|Rombauer
            Heitz Cellar|Heitz
            Stag's Leap Wine Cellars|Stags Leap
            Schrader Cellars|Schrader
            Kistler Vineyards|Kistler
            Ridge Vineyards|Ridge
            Frog's Leap Winery|Frogs Leap
            Chateau Montelena|Château Montelena
            Silver Palm|Silver Palm Wines
            Chateau Ste. Michelle|Chateau Ste Michelle
            Veuve Clicquot|Veuve
            Moët & Chandon|Moet Chandon;Moet
            Dom Pérignon|Dom Perignon
            Taittinger
            Bollinger
            Louis Roederer
            Krug
            Ruinart
            Nicolas Feuillatte
            La Marca
            Mionetto
            J. Lohr
            St. Francis Winery|Saint Francis Winery
            Eberle Winery|Eberle
            Bonterra Organic Estates|Bonterra
            Bread & Butter Wines|Bread and Butter
            Prisoner Wine Company|The Prisoner
            Justin Vineyards & Winery|Justin
            Francis Ford Coppola Winery|Coppola
            Columbia Crest
            Chateau Ste. Michelle|Chateau Ste Michelle
            Ruffino
            Villa Maria
            Cloudy Bay Vineyards
            Torbreck Vintners|Torbreck
            Yalumba
            Henschke
            Leeuwin Estate
            Vasse Felix
            Catena Alta
            Montes|Montes Alpha
            Santa Rita|Santa Rita Estates
            Viña Concha y Toro|Vina Concha y Toro
            Bodega Catena Zapata
        """

        private val LABEL_MARKERS = """
            Origine Controllata|Denominazione di Origine Controllata;D.O.C.;DOC
            Origine Controllata e Garantita|Denominazione di Origine Controllata e Garantita;D.O.C.G.;DOCG
            Denominación de Origen Calificada|Denominacion de Origen Calificada;DOCa;DOCA
            Denominación de Origen|Denominacion de Origen;D.O.;DO
            Appellation d'Origine Contrôlée|Appellation d Origine Controlee;A.O.C.;AOC
            Appellation d'Origine Protégée|Appellation d Origine Protegee;A.O.P.;AOP
            Denominação de Origem Controlada|Denominacao de Origem Controlada
            Indicazione Geografica Tipica|I.G.T.;IGT
            Denominazione di Origine Protetta|DOP;PDO;Denominação de Origem Protegida;Protected Designation of Origin
            Protected Geographical Indication|Indicación Geográfica Protegida;Indication Géographique Protégée;IGP;PGI
            American Viticultural Area|AVA
            Vin de Qualité Supérieure|VDQS
            Vino de Pago
            Vin de Pays
            Vino da Tavola
            Vin de France
            Vin de Table
        """

        private val APPELLATIONS = """
            Brunello di Montalcino|Italy|Tuscany|Brunello di Montalcino|Red|Brunello Montalcino;Brunello di Montalcinu;Brunello di
            Rosso di Montalcino|Italy|Tuscany|Rosso di Montalcino|Red
            Barolo|Italy|Piedmont|Barolo|Red
            Barbaresco|Italy|Piedmont|Barbaresco|Red
            Chianti Classico|Italy|Tuscany|Chianti Classico|Red
            Chianti|Italy|Tuscany|Chianti|Red
            Vino Nobile di Montepulciano|Italy|Tuscany|Vino Nobile di Montepulciano|Red
            Bolgheri|Italy|Tuscany|Bolgheri|Red
            Amarone della Valpolicella|Italy|Veneto|Amarone della Valpolicella|Red
            Valpolicella Ripasso|Italy|Veneto|Valpolicella Ripasso|Red
            Valpolicella|Italy|Veneto|Valpolicella|Red
            Prosecco|Italy|Veneto|Prosecco|Sparkling
            Soave|Italy|Veneto|Soave|White
            Gavi|Italy|Piedmont|Gavi|White
            Barbera d'Alba|Italy|Piedmont|Barbera d'Alba|Red
            Barbera d'Asti|Italy|Piedmont|Barbera d'Asti|Red
            Franciacorta|Italy|Lombardy|Franciacorta|Sparkling
            Etna Rosso|Italy|Sicily|Etna Rosso|Red
            Cerasuolo di Vittoria|Italy|Sicily|Cerasuolo di Vittoria|Red
            Aglianico del Vulture|Italy|Basilicata|Aglianico del Vulture|Red
            Taurasi|Italy|Campania|Taurasi|Red
            Sagrantino di Montefalco|Italy|Umbria|Sagrantino di Montefalco|Red
            Vernaccia di San Gimignano|Italy|Tuscany|Vernaccia di San Gimignano|White
            Chablis|France|Burgundy|Chablis|White
            Gevrey-Chambertin|France|Burgundy|Gevrey-Chambertin|Red
            Chambolle-Musigny|France|Burgundy|Chambolle-Musigny|Red
            Vosne-Romanée|France|Burgundy|Vosne-Romanée|Red
            Meursault|France|Burgundy|Meursault|White
            Puligny-Montrachet|France|Burgundy|Puligny-Montrachet|White
            Pouilly-Fuissé|France|Burgundy|Pouilly-Fuissé|White
            Côte de Nuits|France|Burgundy|Côte de Nuits|Red
            Côte de Beaune|France|Burgundy|Côte de Beaune|Red
            Pauillac|France|Bordeaux|Pauillac|Red
            Margaux|France|Bordeaux|Margaux|Red
            Saint-Émilion|France|Bordeaux|Saint-Émilion|Red|St Emilion;St. Emilion
            Pomerol|France|Bordeaux|Pomerol|Red
            Saint-Estèphe|France|Bordeaux|Saint-Estèphe|Red|St Estephe;Saint Estephe
            Saint-Julien|France|Bordeaux|Saint-Julien|Red|St Julien
            Pessac-Léognan|France|Bordeaux|Pessac-Léognan|Red
            Sauternes|France|Bordeaux|Sauternes|Dessert
            Haut-Médoc|France|Bordeaux|Haut-Médoc|Red
            Médoc|France|Bordeaux|Médoc|Red|Medoc
            Graves|France|Bordeaux|Graves|Red
            Châteauneuf-du-Pape|France|Rhone|Châteauneuf-du-Pape|Red|Chateauneuf du Pape;Chateauneuf-du-Pape
            Côte-Rôtie|France|Rhone|Côte-Rôtie|Red|Cote Rotie
            Hermitage|France|Rhone|Hermitage|Red
            Crozes-Hermitage|France|Rhone|Crozes-Hermitage|Red|Crozes Hermitage
            Gigondas|France|Rhone|Gigondas|Red
            Condrieu|France|Rhone|Condrieu|White
            Sancerre|France|Loire|Sancerre|White
            Pouilly-Fumé|France|Loire|Pouilly-Fumé|White|Pouilly Fume
            Vouvray|France|Loire|Vouvray|White
            Chinon|France|Loire|Chinon|Red
            Champagne|France|Champagne|Champagne|Sparkling
            Beaujolais|France|Beaujolais|Beaujolais|Red
            Morgon|France|Beaujolais|Morgon|Red
            Fleurie|France|Beaujolais|Fleurie|Red
            Côtes du Rhône|France|Rhone|Côtes du Rhône|Red|Cotes du Rhone
            Bandol|France|Provence|Bandol|Red
            Muscadet|France|Loire|Muscadet|White
            Rioja|Spain|Rioja|Rioja|Red
            Ribera del Duero|Spain|Castilla y Leon|Ribera del Duero|Red
            Priorat|Spain|Catalonia|Priorat|Red
            Rías Baixas|Spain|Galicia|Rías Baixas|White|Rias Baixas
            Rueda|Spain|Castilla y Leon|Rueda|White
            Toro|Spain|Castilla y Leon|Toro|Red
            Cava|Spain|Catalonia|Cava|Sparkling
            Jerez-Xérès-Sherry|Spain|Andalusia|Jerez-Xérès-Sherry|Dessert|Jerez;Xeres;Sherry
            Penedès|Spain|Catalonia|Penedès|White|Penedes
            Navarra|Spain|Navarra|Navarra|Red
            Ribera del Guadiana|Spain|Extremadura|Ribera del Guadiana|Red
            Douro|Portugal|Douro|Douro|Red
            Porto|Portugal|Douro|Porto|Dessert|Port
            Vinho Verde|Portugal|Minho|Vinho Verde|White
            Dão|Portugal|Dao|Dão|Red|Dao
            Alentejo|Portugal|Alentejo|Alentejo|Red
            Madeira|Portugal|Madeira|Madeira|Dessert
            Mosel|Germany|Mosel|Mosel|White
            Rheingau|Germany|Rheingau|Rheingau|White
            Rheinhessen|Germany|Rheinhessen|Rheinhessen|White
            Pfalz|Germany|Pfalz|Pfalz|White
            Nahe|Germany|Nahe|Nahe|White
            Santorini|Greece|Aegean Islands|Santorini|White
            Nemea|Greece|Peloponnese|Nemea|Red
            Kakheti|Georgia|Kakheti|Kakheti|Red
            Napa Valley|United States|California|Napa Valley|-|Napa
            Sonoma Coast|United States|California|Sonoma Coast|-
            Alexander Valley|United States|California|Alexander Valley|-
            Russian River Valley|United States|California|Russian River Valley|-
            Oakville|United States|California|Oakville|-
            Rutherford|United States|California|Rutherford|-
            Paso Robles|United States|California|Paso Robles|-
            Santa Barbara|United States|California|Santa Barbara|-
            Santa Ynez Valley|United States|California|Santa Ynez Valley|-
            Willamette Valley|United States|Oregon|Willamette Valley|-
            Dundee Hills|United States|Oregon|Dundee Hills|-
            Walla Walla Valley|United States|Washington|Walla Walla Valley|-
            Red Mountain|United States|Washington|Red Mountain|-
            Barossa Valley|Australia|South Australia|Barossa Valley|Red
            McLaren Vale|Australia|South Australia|McLaren Vale|Red
            Margaret River|Australia|Western Australia|Margaret River|Red
            Coonawarra|Australia|South Australia|Coonawarra|Red
            Yarra Valley|Australia|Victoria|Yarra Valley|Red
            Hunter Valley|Australia|New South Wales|Hunter Valley|Red
            Marlborough|New Zealand|Marlborough|Marlborough|White
            Central Otago|New Zealand|Central Otago|Central Otago|Red
            Hawke's Bay|New Zealand|Hawke's Bay|Hawke's Bay|Red
            Stellenbosch|South Africa|Coastal Region|Stellenbosch|Red
            Swartland|South Africa|Coastal Region|Swartland|Red
            Constantia|South Africa|Coastal Region|Constantia|White
            Colchagua Valley|Chile|Central Valley|Colchagua Valley|Red
            Maipo Valley|Chile|Central Valley|Maipo Valley|Red
            Casablanca Valley|Chile|Aconcagua|Casablanca Valley|White
            Uco Valley|Argentina|Mendoza|Uco Valley|Red
            Mendoza|Argentina|Mendoza|Mendoza|Red
        """
    }
}
