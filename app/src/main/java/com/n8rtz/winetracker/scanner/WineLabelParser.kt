package com.n8rtz.winetracker.scanner

import java.util.Locale

/** OCR output is only a set of editable suggestions, never a confirmed wine identity. */
data class ScannedWine(
    val maker: String? = null,
    val name: String? = null,
    val vintage: String? = null,
    val rawText: String? = null,
    val correctedText: String? = null,
    val country: String? = null,
    val region: String? = null,
    val subregion: String? = null,
    val type: String? = null,
    /** Barcode read from the photo, whether or not a registry lookup resolved it. */
    val barcode: String? = null,
    /** Share of the winning OCR pass's lines a second pass also read; null when only one pass ran. */
    val agreement: Double? = null,
    /** True when the passes disagreed, nothing was recognised, or a barcode could not be resolved. */
    val needsReview: Boolean = true,
    /**
     * What was wrong with the photo itself, when anything was: "blurry", "too_dark" or "glare".
     * Null when the photo was clean or could not be measured, so the UI can offer a retake.
     */
    val captureQuality: String? = null
) {
    val hasSuggestions: Boolean
        get() = !maker.isNullOrBlank() || !name.isNullOrBlank() || !vintage.isNullOrBlank() ||
            !country.isNullOrBlank() || !region.isNullOrBlank() || !subregion.isNullOrBlank() ||
            !type.isNullOrBlank() || !barcode.isNullOrBlank()
}

/** Pure parser so label-text behavior can be tested without CameraX or ML Kit. */
internal class WineLabelParser {
    private data class ExplicitProducerCue(val maker: String, val lineIndexes: Set<Int>)
    private data class VisibleTextLine(val lineIndex: Int, val text: String)

    private val vintageRegex = Regex("\\b(19|20)\\d{2}\\b")
    private val wineYearTokenRegex = Regex("(?:19|20)\\d{2}")
    private val packagingNoiseLineRegex = Regex(
        """(?i)\b\d+(?:[.,]\d+)?\s?%|\balc\b|by vol|contains sulfites?|\b\d+\s?(?:ml|cl|oz|l)\b"""
    )
    private val cellarTriviaCueRegex = Regex(
        """(?i)\b(?:established|founded|fondee|fonde|fondée|fondé|since|depuis|vintage)\b"""
    )
    private val whitespaceRegex = Regex("\\s+")
    private val wordRegex = Regex("[\\p{L}\\p{M}]+")
    private val bodegaCueLineRegex = Regex(
        """^\s*(Bodegas?)\b\s*[:–—-]?\s*(.*?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val producerLegalSuffixRegex = Regex(
        """(?i)(?:[\s,]+(?:S\.?\s*A\.?(?:\s*U\.?)?|S\.?\s*L\.?(?:\s*U\.?)?|L\.?\s*L\.?\s*C\.?))+$"""
    )
    private val spanishProducerParticlesRegex = Regex("\\b(De|Del|La|Las|Los|Y|E|Da|Do|Dos)\\b")
    private val producerDescriptorSeparatorRegex = Regex("[^\\p{L}\\p{N}]+")
    private val producerDescriptorLines = setOf("cellar", "cellars", "estate", "vineyard", "vineyards", "wines", "winery")
    private val uppercaseTerms = setOf(
        "AOC", "AOP", "AVA", "DOC", "DOCA", "DOCG", "DOP", "IGP", "IGT",
        "NV", "VDN", "VDP", "VDF", "VDQS", "VS", "VSOP", "XO",
        "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    )

    fun parse(
        text: String,
        dictionary: WineReferenceDictionary = WineReferenceDictionary.builtIn()
    ): ScannedWine {
        val correction = dictionary.correct(text)
        val matches = correction.matches
        val vintage = selectVintage(correction, matches)
        val producerCue = explicitBodegaProducer(text)
        val matchedPlace = bestMatch(
            matches.filter {
                it.entry.kind in setOf(
                    WineTermKind.APPELLATION,
                    WineTermKind.SUBREGION,
                    WineTermKind.REGION,
                    WineTermKind.COUNTRY
                )
            },
            prioritizeSpecificPlaces = true
        )
        val geographicContext = matchedPlace?.entry
        val producerMatches = matches.filter { match ->
            match.entry.kind == WineTermKind.PRODUCER &&
                isStrongCellarMatch(match) &&
                (producerCue == null || dictionary.producerNamesEquivalent(
                    producerCue.maker,
                    match.entry.canonical
                )) &&
                (geographicContext == null || dictionary.isCompatibleWithWineContext(
                    geographicContext,
                    match.entry
                ))
        }
        val producerMatch = bestMatch(producerMatches)
        val cellarWineNameMatches = matches.filter {
            it.entry.kind == WineTermKind.WINE_NAME && isStrongCellarMatch(it)
        }
            .filter { match ->
                geographicContext == null || dictionary.isCompatibleWithWineContext(geographicContext, match.entry)
            }
            .filter { match ->
                when {
                    producerCue != null -> match.entry.maker != null && dictionary.producerNamesEquivalent(
                        match.entry.maker,
                        producerCue.maker
                    )
                    producerMatch != null -> match.entry.maker == null || dictionary.producerNamesEquivalent(
                        match.entry.maker,
                        producerMatch.entry.canonical
                    )
                    else -> true
                }
            }
        val wineNameMatch = bestMatch(cellarWineNameMatches)
        val namedTermMatch = matches
            .filter { match ->
                when (match.entry.kind) {
                    WineTermKind.APPELLATION, WineTermKind.GRAPE, WineTermKind.WINE_STYLE,
                    WineTermKind.SUBREGION -> match.entry.useAsName
                    else -> false
                }
            }
            .filter { match ->
                geographicContext == null || dictionary.isCompatibleWithWineContext(geographicContext, match.entry)
            }
            .maxByOrNull { nameSuggestionScore(it, geographicContext, dictionary) }
        // Every match here already passed the corrector's fuzzy gate, so the name agrees with the
        // corrected text the user is shown (a one-letter slip in "Merlot" is corrected, and named).
        val preferredNameMatch = listOfNotNull(wineNameMatch, namedTermMatch)
            .maxByOrNull { nameSuggestionScore(it, geographicContext, dictionary) }
        // An explicit Spanish winery cue beats unrelated fuzzy cellar matches.
        val nameMatch = preferredNameMatch
        val matchedMaker = WineReferenceDictionary.canonicalProducerName(
            when {
                producerCue != null -> producerMatch?.entry?.canonical ?: producerCue.maker
                else -> producerMatch?.entry?.canonical ?: wineNameMatch?.entry?.maker
            }
        )
        val metadataMatch = preferredNameMatch
        val country = metadataMatch?.entry?.country
            ?: matchedPlace?.entry?.country
            ?: bestMatch(matches.filter { it.entry.kind == WineTermKind.COUNTRY })?.entry?.canonical
        val region = metadataMatch?.entry?.region
            ?: matchedPlace?.entry?.region
            ?: bestMatch(matches.filter { it.entry.kind == WineTermKind.REGION })?.entry?.canonical
        val subregion = metadataMatch?.entry?.subregion
            ?: matchedPlace?.entry?.subregion
            ?: matchedPlace
                ?.takeIf { it.entry.kind == WineTermKind.APPELLATION || it.entry.kind == WineTermKind.SUBREGION }
                ?.entry?.canonical
        val wineType = supportedWineType(metadataMatch?.entry?.wineType)
            ?: supportedWineType(matchedPlace?.entry?.wineType)
            ?: supportedWineType(
                bestMatch(matches.filter {
                    it.entry.kind == WineTermKind.GRAPE || it.entry.kind == WineTermKind.WINE_TYPE ||
                        it.entry.kind == WineTermKind.WINE_STYLE
                })?.entry?.wineType
            )

        val visibleLines = withoutPackagingNoise(
            visibleTextLines(correction, vintage)
                .filterNot { producerCue?.lineIndexes?.contains(it.lineIndex) == true }
                .map { it.text }
        )
        val fallbackMaker = matchedMaker ?: guessMakerFromLeadingLines(visibleLines)
        val fallbackName = nameMatch?.entry?.canonical ?: when {
            matchedMaker != null -> leftoverNameText(visibleLines, matchedMaker)
            visibleLines.size > 1 -> leftoverNameText(visibleLines.drop(1), maker = null)
            else -> null
        }

        return ScannedWine(
            maker = fallbackMaker,
            name = fallbackName,
            vintage = vintage,
            rawText = text.trim().takeIf(String::isNotBlank),
            correctedText = correction.correctedText.trim().takeIf(String::isNotBlank),
            country = country,
            region = region,
            subregion = subregion,
            type = wineType
        )
    }

    /**
     * Picks the vintage of the scanned bottle. A group photo contains several vintages, and words
     * such as "ESTABLISHED 1844" or "FIRST VINTAGE 1996" carry cellar trivia rather than the vintage,
     * so year tokens that a recognised wine term absorbed, or that share a line with a
     * founding/vintage cue, are skipped first.
     */
    private fun selectVintage(
        correction: WineDictionaryCorrection,
        matches: List<WineLexiconMatch>
    ): String? {
        val yearTokens = correction.tokens.withIndex().filter { wineYearTokenRegex.matches(it.value.text) }
        if (yearTokens.isEmpty()) return null

        val notAbsorbedByAWineTerm = yearTokens.filterNot { (index, _) ->
            matches.any { match -> match.startToken <= index && index < match.endTokenExclusive }
        }
        val outsideCellarTrivia = notAbsorbedByAWineTerm.filterNot { (_, token) ->
            val line = correction.correctedText.lineSequence().elementAtOrNull(token.lineIndex)
            line != null && cellarTriviaCueRegex.containsMatchIn(line)
        }
        val preferred = outsideCellarTrivia.ifEmpty { notAbsorbedByAWineTerm.ifEmpty { yearTokens } }
        return preferred.first().value.text
    }

    /**
     * Packaging lines ("ALC 13.5% BY VOL", "750 ml", "contains sulfites") are never the wine name or
     * the producer, and they sit under the real label text, so they are dropped before the fallback
     * name is assembled from whatever is left.
     */
    private fun withoutPackagingNoise(lines: List<String>): List<String> =
        lines.filterNot { packagingNoiseLineRegex.containsMatchIn(it) }

    private fun guessMakerFromLeadingLines(lines: List<String>): String? {
        val firstLine = lines.firstOrNull() ?: return null
        val secondLine = lines.getOrNull(1) ?: return firstLine
        val descriptor = secondLine.lowercase(Locale.ROOT)
            .replace(producerDescriptorSeparatorRegex, " ")
            .trim()
        return if (descriptor in producerDescriptorLines) "$firstLine $secondLine" else firstLine
    }

    private fun explicitBodegaProducer(text: String): ExplicitProducerCue? {
        val lines = text.lineSequence().toList()
        lines.forEachIndexed { lineIndex, line ->
            val cue = bodegaCueLineRegex.matchEntire(line) ?: return@forEachIndexed
            val marker = if (cue.groupValues[1].equals("Bodega", ignoreCase = true)) "Bodega" else "Bodegas"
            var producerName = cue.groupValues[2].trim()
            val producerLineIndexes = mutableSetOf(lineIndex)
            if (producerName.isBlank()) {
                val nextLineIndex = (lineIndex + 1 until lines.size)
                    .firstOrNull { lines[it].isNotBlank() }
                    ?: return@forEachIndexed
                val nextLine = lines[nextLineIndex].trim()
                if (vintageRegex.matches(nextLine)) return@forEachIndexed
                producerName = nextLine
                producerLineIndexes += nextLineIndex
            }
            producerName = producerLegalSuffixRegex.replace(producerName, "")
                .trim()
                .trimEnd(',', '.', ';', ':', '-', '–', '—')
                .trim()
            if (producerName.isBlank()) return@forEachIndexed

            val displayName = normalizeExplicitProducerName("$marker $producerName")
            return ExplicitProducerCue(displayName, producerLineIndexes)
        }
        return null
    }

    private fun normalizeExplicitProducerName(value: String): String {
        val normalized = normalizeDisplayCase(value)
        return spanishProducerParticlesRegex.replace(normalized) { it.value.lowercase(Locale.ROOT) }
    }

    private fun isStrongCellarMatch(match: WineLexiconMatch): Boolean =
        match.entry.sourceWeight >= CURATED_SOURCE_WEIGHT ||
            match.similarity >= WineReferenceDictionary.MIN_CELLAR_TERM_SIMILARITY

    private fun nameSuggestionScore(
        match: WineLexiconMatch,
        geographicContext: WineLexiconEntry?,
        dictionary: WineReferenceDictionary
    ): Double {
        val tokenSpan = match.endTokenExclusive - match.startToken
        val phraseBonus = if (tokenSpan > 1 && match.similarity >= 0.90) {
            0.12 * (tokenSpan - 1).coerceAtMost(2)
        } else {
            0.0
        }
        val wineTermBonus = when (match.entry.kind) {
            WineTermKind.APPELLATION -> 0.18
            WineTermKind.GRAPE -> 0.15
            WineTermKind.WINE_STYLE -> 0.13
            WineTermKind.WINE_NAME -> 0.08
            WineTermKind.SUBREGION -> 0.04
            else -> 0.0
        }
        val sourceBonus = match.entry.sourceWeight.coerceIn(0, 30) / 1_000.0
        val geographyBonus = dictionary.grapeContextBonus(geographicContext, match.entry)
        return match.similarity + phraseBonus + wineTermBonus + sourceBonus + geographyBonus
    }

    private fun bestMatch(
        matches: List<WineLexiconMatch>,
        prioritizeSpecificPlaces: Boolean = false
    ): WineLexiconMatch? = matches.maxWithOrNull(
        compareBy<WineLexiconMatch> { match ->
            if (prioritizeSpecificPlaces) {
                when (match.entry.kind) {
                    WineTermKind.APPELLATION -> 5
                    WineTermKind.SUBREGION -> 4
                    WineTermKind.REGION -> 3
                    WineTermKind.COUNTRY -> 2
                    else -> 0
                }
            } else {
                when (match.entry.kind) {
                    WineTermKind.WINE_NAME -> 5
                    WineTermKind.APPELLATION -> 4
                    WineTermKind.GRAPE -> 3
                    WineTermKind.WINE_STYLE -> 2
                    else -> 0
                }
            }
        }.thenBy { it.similarity }
            .thenBy { it.endTokenExclusive - it.startToken }
            .thenBy { it.entry.sourceWeight }
    )

    private fun visibleTextLines(
        correction: WineDictionaryCorrection,
        vintage: String?
    ): List<VisibleTextLine> {
        val hiddenTokens = mutableSetOf<Int>()
        correction.matches.forEach { match ->
            (match.startToken until match.endTokenExclusive).forEach(hiddenTokens::add)
        }
        correction.tokens.forEachIndexed { index, token ->
            if (vintage != null && wineYearTokenRegex.matches(token.text)) hiddenTokens += index
        }

        return correction.tokens
            .withIndex()
            .filterNot { it.index in hiddenTokens }
            .groupBy { it.value.lineIndex }
            .toSortedMap()
            .map { (lineIndex, lineTokens) ->
                VisibleTextLine(
                    lineIndex = lineIndex,
                    text = normalizeDisplayCase(lineTokens.joinToString(" ") { it.value.text })
                )
            }
            .filter { it.text.isNotBlank() }
    }

    /**
     * Text left over after the recognised terms are removed. It is only a guess at the cuvée, so
     * OCR debris is dropped rather than suggested: fragments under three letters ("Wi", "N" from a
     * split "WINERY") and words that echo the matched producer ("LAOKSTONE" beside "Blackstone").
     * An empty result is better than a name that is visibly wrong.
     */
    private fun leftoverNameText(lines: List<String>, maker: String?): String? {
        val makerWords = maker?.let { wordRegex.findAll(it).map { word -> word.value }.toList() }.orEmpty()
        return lines
            .flatMap { line -> wordRegex.findAll(line).map { it.value }.toList() }
            .filter { it.length >= MIN_NAME_WORD_LETTERS }
            .filterNot { word ->
                makerWords.any { makerWord ->
                    WineReferenceDictionary.termSimilarity(word, makerWord) >= MIN_MAKER_ECHO_SIMILARITY
                }
            }
            .joinToString(" ")
            .takeIf(String::isNotBlank)
    }

    private fun supportedWineType(value: String?): String? = when (
        value?.trim()?.lowercase(Locale.ROOT)?.replace(" ", "")
    ) {
        "red", "redwine" -> "Red"
        "white", "whitewine" -> "White"
        "rose", "rosé", "rosewine" -> "Rose"
        "sparkling", "sparklingwine", "champagne" -> "Sparkling"
        "dessert", "dessertwine", "sweetwine" -> "Dessert"
        else -> null
    }

    private fun normalizeDisplayCase(value: String): String {
        val cleaned = value.replace(whitespaceRegex, " ").trim()
        return wordRegex.replace(cleaned) { match ->
            val uppercase = match.value.uppercase(Locale.ROOT)
            if (uppercase in uppercaseTerms) {
                uppercase
            } else {
                match.value.lowercase(Locale.ROOT).replaceFirstChar { first ->
                    if (first.isLowerCase()) first.titlecase() else first.toString()
                }
            }
        }
    }

    private companion object {
        const val MIN_NAME_WORD_LETTERS = 3
        const val MIN_MAKER_ECHO_SIMILARITY = 0.75
        const val CURATED_SOURCE_WEIGHT = 30
    }
}
