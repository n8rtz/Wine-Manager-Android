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
    val type: String? = null
) {
    val hasSuggestions: Boolean
        get() = !maker.isNullOrBlank() || !name.isNullOrBlank() || !vintage.isNullOrBlank() ||
            !country.isNullOrBlank() || !region.isNullOrBlank() || !subregion.isNullOrBlank() || !type.isNullOrBlank()
}

/** Pure parser so label-text behavior can be tested without CameraX or ML Kit. */
internal class WineLabelParser {
    private val vintageRegex = Regex("\\b(19|20)\\d{2}\\b")
    private val wineYearTokenRegex = Regex("(?:19|20)\\d{2}")
    private val whitespaceRegex = Regex("\\s+")
    private val wordRegex = Regex("[\\p{L}\\p{M}]+")
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
        val vintage = vintageRegex.find(text)?.value
        val producerMatch = bestMatch(matches.filter { it.entry.kind == WineTermKind.PRODUCER })
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
        val cellarWineNameMatches = matches.filter { it.entry.kind == WineTermKind.WINE_NAME }
            .filter { match ->
                geographicContext == null || dictionary.isCompatibleWithWineContext(geographicContext, match.entry)
            }
        val wineNameMatch = if (producerMatch == null) {
            bestMatch(cellarWineNameMatches)
        } else {
            bestMatch(
                cellarWineNameMatches.filter { match ->
                    match.entry.maker == null || dictionary.producerNamesEquivalent(
                        match.entry.maker,
                        producerMatch.entry.canonical
                    )
                }
            )
        }
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
        val preferredNameMatch = listOfNotNull(wineNameMatch, namedTermMatch)
            .filter { it.similarity >= MIN_SUGGESTED_NAME_SIMILARITY }
            .maxByOrNull { nameSuggestionScore(it, geographicContext, dictionary) }
        // Reject cellar wines from a different maker and wine terms that contradict a defining appellation.
        val nameMatch = preferredNameMatch
        val matchedMaker = WineReferenceDictionary.canonicalProducerName(
            producerMatch?.entry?.canonical ?: wineNameMatch?.entry?.maker
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

        val visibleLines = visibleTextLines(correction, vintage)
        val fallbackMaker = matchedMaker ?: visibleLines.firstOrNull()
        val fallbackName = nameMatch?.entry?.canonical ?: when {
            matchedMaker != null -> visibleLines.joinToString(" ").takeIf(String::isNotBlank)
            visibleLines.size > 1 -> visibleLines.drop(1).joinToString(" ").takeIf(String::isNotBlank)
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
    ): List<String> {
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
            .values
            .map { lineTokens ->
                normalizeDisplayCase(lineTokens.joinToString(" ") { it.value.text })
            }
            .filter(String::isNotBlank)
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
        const val MIN_SUGGESTED_NAME_SIMILARITY = 0.84
    }
}
