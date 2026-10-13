package com.n8rtz.winetracker.scanner

import java.util.Locale

internal data class LabelOcrHypothesis(
    val text: String,
    val isFullFrame: Boolean
)

/** Prefers a legible, wine-relevant OCR result without letting cellar history dominate the choice. */
internal object LabelOcrHypothesisSelector {
    private val wordRegex = Regex("[\\p{L}\\p{N}]+")
    private const val FULL_FRAME_BONUS = 0.35
    private const val ENHANCED_RESULT_MARGIN = 0.20

    fun choose(
        fullFrame: LabelOcrHypothesis,
        enhancedCrop: LabelOcrHypothesis,
        parser: WineLabelParser,
        dictionary: WineReferenceDictionary
    ): LabelOcrHypothesis {
        if (fullFrame.text.isBlank()) return enhancedCrop
        if (enhancedCrop.text.isBlank()) return fullFrame

        val fullFrameScore = score(fullFrame, parser, dictionary)
        val enhancedScore = score(enhancedCrop, parser, dictionary)
        return if (enhancedScore > fullFrameScore + ENHANCED_RESULT_MARGIN) enhancedCrop else fullFrame
    }

    private fun score(
        candidate: LabelOcrHypothesis,
        parser: WineLabelParser,
        dictionary: WineReferenceDictionary
    ): Double {
        val correction = dictionary.correct(candidate.text)
        val distinctCuratedMatches = correction.matches
            .filter { it.entry.sourceWeight != CELLAR_ENTRY_SOURCE_WEIGHT }
            .distinctBy { it.entry.kind to it.entry.canonical.lowercase(Locale.ROOT) }
        val lexicalEvidence = distinctCuratedMatches.sumOf { match ->
            val kindWeight = when (match.entry.kind) {
                WineTermKind.PRODUCER, WineTermKind.APPELLATION -> 0.90
                WineTermKind.WINE_NAME -> 0.80
                WineTermKind.GRAPE, WineTermKind.SUBREGION, WineTermKind.REGION -> 0.60
                WineTermKind.COUNTRY -> 0.45
                WineTermKind.WINE_STYLE, WineTermKind.WINE_TYPE -> 0.35
                WineTermKind.LABEL_MARKER -> 0.10
            }
            kindWeight * match.similarity
        }
        val parsed = parser.parse(candidate.text, dictionary)
        val metadataEvidence = listOf(
            parsed.vintage,
            parsed.country,
            parsed.region,
            parsed.subregion,
            parsed.type
        ).count { !it.isNullOrBlank() } * 0.18
        val wordCount = wordRegex.findAll(candidate.text).count().coerceAtMost(30)
        val lineCount = candidate.text.lineSequence().count(String::isNotBlank).coerceAtMost(10)
        val bodegaCueBonus = if (
            candidate.text.lineSequence().any { line ->
                line.trimStart().startsWith("Bodega", ignoreCase = true)
            }
        ) 0.30 else 0.0
        val frameBonus = if (candidate.isFullFrame) FULL_FRAME_BONUS else 0.0

        return lexicalEvidence +
            metadataEvidence +
            wordCount * 0.035 +
            lineCount * 0.035 +
            bodegaCueBonus +
            frameBonus
    }

    private const val CELLAR_ENTRY_SOURCE_WEIGHT = 20
}
