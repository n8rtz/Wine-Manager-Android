package com.n8rtz.winetracker.scanner

import com.yourapp.wine.kb.BundledGrapes
import com.yourapp.wine.kb.BundledWineStyles
import com.yourapp.wine.kb.BundledProducerNames
import com.yourapp.wine.kb.BundledOrigins

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
                .map(::producerIdentityKey)
                .filter(String::isNotEmpty)
                .toSet()
        }

    fun producerNamesEquivalent(first: String, second: String): Boolean {
        val firstKey = producerIdentityKey(first)
        val secondKey = producerIdentityKey(second)
        return firstKey == secondKey || producerFormSets.any { forms ->
            firstKey in forms && secondKey in forms
        }
    }

    private fun producerIdentityKey(value: String): String {
        val key = normalizedKey(canonicalProducerName(value) ?: value)
        return when {
            key.startsWith("bodegas") && key.length > "bodegas".length -> key.removePrefix("bodegas")
            key.startsWith("bodega") && key.length > "bodega".length -> key.removePrefix("bodega")
            else -> key
        }
    }

    /**
     * A recognized origin should rule out incompatible cellar-history guesses. Defining grape
     * rules remain a separate, stricter check for appellations such as Brunello and Chablis.
     */
    fun isCompatibleWithWineContext(context: WineLexiconEntry, wineTerm: WineLexiconEntry): Boolean {
        val contextCountry = context.country?.let(::normalizedKey)
        val termCountry = wineTerm.country?.let(::normalizedKey)
        if (contextCountry != null && termCountry != null && contextCountry != termCountry) return false

        if (
            wineTerm.kind in setOf(WineTermKind.WINE_NAME, WineTermKind.PRODUCER) &&
            context.kind in setOf(
                WineTermKind.APPELLATION,
                WineTermKind.SUBREGION,
                WineTermKind.REGION
            )
        ) {
            val contextRegion = context.region?.let(::normalizedKey)
            val termRegion = wineTerm.region?.let(::normalizedKey)
            if (contextRegion != null && termRegion != null && contextRegion != termRegion) return false
        }

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
                    if (!acceptsFuzzyMatch(distance, similarity, candidate.key.length)) continue
                    // Personal cellar entries are useful only for near-exact matches; otherwise a
                    // familiar producer or cuvée can override unrelated label text.
                    val isCellarIdentity = candidate.entry.sourceWeight < CURATED_SOURCE_WEIGHT &&
                        candidate.entry.kind in setOf(WineTermKind.PRODUCER, WineTermKind.WINE_NAME)
                    if (isCellarIdentity && similarity < MIN_CELLAR_TERM_SIMILARITY) continue

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
        internal const val MIN_CELLAR_TERM_SIMILARITY = 0.90
        private const val CURATED_SOURCE_WEIGHT = 30
        private const val MIN_TERM_LENGTH = 3
        private const val MAX_PHRASE_TOKENS = 7
        private const val MIN_FUZZY_SIMILARITY = 0.82
        /**
         * Two OCR slips in a long word ("LAOKSTONE" for "BLACKSTONE", similarity 0.80) are a normal
         * misread, and the edit cap already allows two edits up to 12 letters, so a term of this length
         * may take two edits even though its similarity falls just below the general bar.
         */
        private const val LONG_TERM_LENGTH_FOR_TWO_EDITS = 10
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
            val producerGeography = definitions
                .mapNotNull { definition ->
                    val producerKey = canonicalProducerName(definition.maker)
                        ?.let(::normalizedKey)
                        ?: return@mapNotNull null
                    producerKey to (
                        canonicalWineCountry(definition.country)
                            to definition.region?.trim()?.takeIf(String::isNotEmpty)
                        )
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, locations) ->
                    val countries = locations.mapNotNull { it.first }.distinctBy(::normalizedKey)
                    val regions = locations.mapNotNull { it.second }.distinctBy(::normalizedKey)
                    countries.singleOrNull() to regions.singleOrNull()
                }
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
                val (country, region) = producerGeography[normalizedKey(maker)] ?: (null to null)
                entries += WineLexiconEntry(
                    canonical = maker,
                    kind = WineTermKind.PRODUCER,
                    aliases = makerAliases,
                    country = country,
                    region = region,
                    sourceWeight = 20
                )
            }

            definitions.forEach { definition ->
                val definitionCountry = canonicalWineCountry(definition.country)
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
                    // A personal spelling such as "Tuscana" must stay a recognised alias instead of
                    // becoming a second region that can outrank the canonical location-data entry.
                    val canonicalRegionName = canonicalRegion(region, definitionCountry, locationData) ?: region
                    entries += WineLexiconEntry(
                        canonical = canonicalRegionName,
                        kind = WineTermKind.REGION,
                        aliases = cellarRegionAliases(region, canonicalRegionName),
                        country = definitionCountry,
                        region = canonicalRegionName,
                        sourceWeight = 20,
                        associatedGrapes = grapesForGeography(definitionCountry, canonicalRegionName, null)
                    )
                }
                definition.subregion?.trim()?.takeIf(String::isNotEmpty)?.let { subregion ->
                    val canonicalRegionName = canonicalRegion(definition.region, definitionCountry, locationData)
                    entries += WineLexiconEntry(
                        canonical = subregion,
                        kind = WineTermKind.SUBREGION,
                        country = definitionCountry,
                        region = canonicalRegionName,
                        subregion = subregion,
                        sourceWeight = 20,
                        useAsName = normalizedKey(subregion) in knownAppellationKeys,
                        associatedGrapes = grapesForGeography(definitionCountry, canonicalRegionName, subregion)
                    )
                }
            }

            return WineReferenceDictionary(mergeDuplicateEntries(entries))
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

        /** Native-language spellings that appear on labels next to the English region name. */
        private fun locationAliases(location: String): List<String> = when (normalizedKey(location)) {
            "tuscany" -> listOf("Toscana", "Tuscana")
            "piedmont" -> listOf("Piemonte")
            "sicily" -> listOf("Sicilia")
            "burgundy" -> listOf("Bourgogne")
            else -> emptyList()
        }

        /** Keeps the spelling a cellar used as an alias whenever it differs from the canonical region. */
        private fun cellarRegionAliases(spelling: String, canonical: String?): List<String> = listOfNotNull(
            spelling.trim().takeIf(String::isNotEmpty)?.takeIf {
                canonical == null || normalizedKey(it) != normalizedKey(canonical)
            }
        )

        /**
         * Location data is the source of truth for region spelling. Compare countries through their
         * canonical form so that a definition stored as "USA" still resolves "Toscana" to "Tuscany".
         */
        private fun canonicalRegion(
            value: String?,
            country: String?,
            locationData: Map<String, Map<String, List<String>>>
        ): String? {
            val region = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val regionKey = normalizedKey(region)
            val canonicalCountry = country?.let(::canonicalWineCountry)?.takeIf(String::isNotBlank)
            val regions = locationData
                .filterKeys { canonicalCountry == null || canonicalWineCountry(it).equals(canonicalCountry, ignoreCase = true) }
                .values
                .flatMap { it.keys }
            return regions.firstOrNull { candidate ->
                normalizedKey(candidate) == regionKey || locationAliases(candidate).any { normalizedKey(it) == regionKey }
            } ?: region
        }

        private fun entryIdentityKey(entry: WineLexiconEntry): String = listOf(
            entry.kind.name,
            normalizedKey(entry.canonical),
            entry.country.orEmpty(),
            entry.region.orEmpty(),
            entry.subregion.orEmpty(),
            entry.maker.orEmpty()
        ).joinToString("|")

        /**
         * Curated terminology, location data, and personal cellar history often describe the same
         * wine term. Merging them into one entry keeps the strongest source weight, every alias, and
         * the regional grape context, so a lower-weight duplicate can no longer hide curated data.
         */
        private fun mergeDuplicateEntries(entries: List<WineLexiconEntry>): List<WineLexiconEntry> {
            val merged = LinkedHashMap<String, WineLexiconEntry>()
            entries.forEach { entry ->
                val identity = entryIdentityKey(entry)
                val existing = merged[identity]
                if (existing == null) {
                    merged[identity] = entry
                    return@forEach
                }
                val winner = if (existing.sourceWeight >= entry.sourceWeight) existing else entry
                val loser = if (winner === existing) entry else existing
                merged[identity] = WineLexiconEntry(
                    canonical = winner.canonical,
                    kind = winner.kind,
                    aliases = (winner.aliases + loser.aliases + listOf(winner.canonical, loser.canonical))
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .distinctBy(::normalizedKey)
                        .filterNot { normalizedKey(it) == normalizedKey(winner.canonical) },
                    country = winner.country ?: loser.country,
                    region = winner.region ?: loser.region,
                    subregion = winner.subregion ?: loser.subregion,
                    wineType = winner.wineType ?: loser.wineType,
                    maker = winner.maker ?: loser.maker,
                    sourceWeight = maxOf(existing.sourceWeight, entry.sourceWeight),
                    useAsName = existing.useAsName || entry.useAsName,
                    associatedGrapes = winner.associatedGrapes.ifEmpty { loser.associatedGrapes },
                    grapesAreDefining = existing.grapesAreDefining || entry.grapesAreDefining
                )
            }
            return merged.values.toList()
        }

        private fun styleType(style: String): String? = when (normalizedKey(style)) {
            "champagne", "prosecco", "cava", "cremant", "franciacorta", "sekt", "sparkling" -> "Sparkling"
            "shirazcabernet" -> "Red"
            "rose", "rosewine" -> "Rose"
            "sauternes", "icewine", "tokajiaszu", "port", "sherry", "madeira", "marsala", "vinsanto" -> "Dessert"
            else -> null
        }

        /** The single fuzzy-match gate: the corrector and the name suggestion both use it. */
        internal fun acceptsFuzzyMatch(distance: Int, similarity: Double, candidateLength: Int): Boolean =
            distance == 0 || similarity >= MIN_FUZZY_SIMILARITY ||
                (distance == 2 && candidateLength >= LONG_TERM_LENGTH_FOR_TWO_EDITS)

        /** Similarity of two words after the same accent/case/punctuation folding used for matching. */
        internal fun termSimilarity(left: String, right: String): Double {
            val leftKey = normalizedKey(left)
            val rightKey = normalizedKey(right)
            val longest = maxOf(leftKey.length, rightKey.length)
            if (longest == 0) return 0.0
            return 1.0 - levenshteinDistance(leftKey, rightKey, longest).toDouble() / longest
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

        private val RED_GRAPES = BundledGrapes.redSource

        private val WHITE_GRAPES = BundledGrapes.whiteSource

        private val OTHER_GRAPES = BundledGrapes.otherSource

        private val WINE_STYLES = BundledWineStyles.source

        private val PRODUCERS = BundledProducerNames.source

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
            Meses en Barrica|Meses de Barrica
            Protected Geographical Indication|Indicación Geográfica Protegida;Indication Géographique Protégée;IGP;PGI
            American Viticultural Area|AVA
            Vin de Qualité Supérieure|VDQS
            Vino de Pago
            Vin de Pays
            Vino da Tavola
            Vin de France
            Vin de Table
        """

        private val APPELLATIONS = BundledOrigins.source
    }
}
