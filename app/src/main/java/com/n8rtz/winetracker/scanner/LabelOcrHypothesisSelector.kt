package com.n8rtz.winetracker.scanner

import java.util.Locale

internal data class LabelOcrHypothesis(val text: String)

/** Prefers a legible, wine-relevant OCR result without letting cellar history dominate the choice. */
internal object LabelOcrHypothesisSelector {
    private val wordRegex = Regex("[\\p{L}\\p{N}]+")
    private const val ENHANCED_RESULT_MARGIN = 0.0

    fun choose(
        fullFrame: LabelOcrHypothesis,
        enhancedCrop: LabelOcrHypothesis,
        parser: WineLabelParser,
        dictionary: WineReferenceDictionary
    ): LabelOcrHypothesis {
        if (fullFrame.text.isBlank()) return enhancedCrop
        if (enhancedCrop.text.isBlank()) return fullFrame

        val fullFrameScore = evidenceScore(fullFrame, parser, dictionary)
        val enhancedScore = evidenceScore(enhancedCrop, parser, dictionary)
        return if (enhancedScore > fullFrameScore + ENHANCED_RESULT_MARGIN) enhancedCrop else fullFrame
    }

    /** How much wine evidence one OCR reading carries; also ranks passes for the consensus merge. */
    internal fun evidenceScore(
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
        // Text volume is deliberately weak evidence. It breaks ties between two readable passes,
        // but it must not let a long, cluttered group photo outrank one sharp single-bottle crop.
        val wordCount = wordRegex.findAll(candidate.text).count()
        val lineCount = candidate.text.lineSequence().count(String::isNotBlank)
        val textVolumeBonus = minOf(wordCount, MAX_COUNTED_WORDS) * 0.02 +
            minOf(lineCount, MAX_COUNTED_LINES) * 0.01
        val bodegaCueBonus = if (
            candidate.text.lineSequence().any { line ->
                line.trimStart().startsWith("Bodega", ignoreCase = true)
            }
        ) 0.30 else 0.0
        return lexicalEvidence +
            metadataEvidence +
            textVolumeBonus +
            bodegaCueBonus
    }

    private const val CELLAR_ENTRY_SOURCE_WEIGHT = 20
    private const val MAX_COUNTED_WORDS = 12
    private const val MAX_COUNTED_LINES = 6
}
