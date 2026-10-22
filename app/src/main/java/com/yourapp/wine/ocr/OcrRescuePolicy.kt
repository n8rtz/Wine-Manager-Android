package com.yourapp.wine.ocr

import com.yourapp.wine.extract.MetadataRules
import com.yourapp.wine.extract.ProducerLineSelector
import com.yourapp.wine.merge.TextNorm

/** Scheduling only: this does not alter token acceptance, independent-engine votes or field validation. */
object OcrRescuePolicy {
    private val producerLines = ProducerLineSelector()
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    /** Completed RECTIFIED/fallback pixel readings in; request the last photometric pass when usable text is sparse. */
    fun shouldRun(readings: List<RawToken>): Boolean {
        val valid = readings.filter { it.score.isFinite() && it.score in 0f..1f && it.text.isNotBlank() }
        val usable = valid.filter { !it.scoreIsEstimated && it.score >= threshold(it.engineId) &&
            it.text.count(Char::isLetter) >= 3 && !MetadataRules.isPackaging(it.text) &&
            !MetadataRules.isBoilerplate(it.text) && !producerLines.isMarker(it.text) && !producerLines.isAdministrative(it.text) }
            .map { separators.replace(TextNorm.normalize(it.text), " ").trim() }.distinct()
        val weakIdentity = valid.any {
            it.score < threshold(it.engineId) && !MetadataRules.isPackaging(it.text) &&
                !producerLines.isMarker(it.text) && !producerLines.isAdministrative(it.text) &&
                MetadataRules.isIdentity(MetadataRules.identityText(it.text))
        }
        return usable.size < 3 || weakIdentity
    }

    /** BASE rectified-label polygons in; choose aspect rescue for weak, abnormally tall/condensed identity glyphs. */
    fun needsAspectRescue(readings: List<RawToken>): Boolean = readings.any { token ->
        if (!token.score.isFinite() || token.score !in 0f..1f || token.score >= threshold(token.engineId) ||
            token.polygon.size < 3 || token.polygon.any { !it.x.isFinite() || !it.y.isFinite() } ||
            producerLines.isMarker(token.text) || producerLines.isAdministrative(token.text) ||
            MetadataRules.isPackaging(token.text) || !MetadataRules.isIdentity(MetadataRules.identityText(token.text))) false
        else {
            val width = token.polygon.maxOf { it.x } - token.polygon.minOf { it.x }
            val height = token.polygon.maxOf { it.y } - token.polygon.minOf { it.y }
            val letters = token.text.count(Char::isLetter)
            width > 0 && height > 0 && letters >= 3 && height * letters / width >= 1.8f
        }
    }

    private fun threshold(engine: String): Float = if (engine.startsWith("paddle")) .75f else .60f
}
