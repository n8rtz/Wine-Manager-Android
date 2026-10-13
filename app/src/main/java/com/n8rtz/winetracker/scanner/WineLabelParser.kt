package com.n8rtz.winetracker.scanner

import java.util.Locale

/** OCR output is only a set of editable suggestions, never a confirmed wine identity. */
data class ScannedWine(
    val maker: String? = null,
    val name: String? = null,
    val vintage: String? = null,
    val rawText: String? = null
) {
    val hasSuggestions: Boolean
        get() = !maker.isNullOrBlank() || !name.isNullOrBlank() || !vintage.isNullOrBlank()
}

/** Pure parser so label-text behavior can be tested without CameraX or ML Kit. */
internal class WineLabelParser {
    private val vintageRegex = Regex("\\b(19|20)\\d{2}\\b")
    private val whitespaceRegex = Regex("\\s+")
    private val wordRegex = Regex("[\\p{L}\\p{M}]+")

    // Keep familiar wine, appellation, and classification abbreviations in their standard form.
    private val uppercaseTerms = setOf(
        "AOC", "AOP", "AVA", "DOC", "DOCA", "DOCG", "DOP", "IGP", "IGT",
        "NV", "VDN", "VDP", "VDF", "VDQS", "VS", "VSOP", "XO",
        "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    )

    fun parse(text: String): ScannedWine {
        val lines = text.split('\n').map(String::trim).filter(String::isNotBlank)
        val vintage = vintageRegex.find(text)?.value
        val maker = lines.firstOrNull()?.let(::normalizeDisplayCase)
        val name = lines
            .filterIndexed { index, line -> index > 0 && line != vintage }
            .joinToString(" ") { normalizeDisplayCase(it) }
            .takeIf(String::isNotBlank)

        return ScannedWine(
            maker = maker,
            name = name,
            vintage = vintage,
            rawText = text.trim().takeIf(String::isNotBlank)
        )
    }

    private fun normalizeDisplayCase(value: String): String {
        val cleaned = value.replace(whitespaceRegex, " ").trim()
        return wordRegex.replace(cleaned) { match ->
            val original = match.value
            val uppercase = original.uppercase(Locale.ROOT)
            if (uppercase in uppercaseTerms) {
                uppercase
            } else {
                original.lowercase(Locale.ROOT).replaceFirstChar { first ->
                    if (first.isLowerCase()) first.titlecase() else first.toString()
                }
            }
        }
    }
}
