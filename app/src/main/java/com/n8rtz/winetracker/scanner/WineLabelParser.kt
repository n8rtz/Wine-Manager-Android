package com.n8rtz.winetracker.scanner

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

    fun parse(text: String): ScannedWine {
        val lines = text.split('\n').map(String::trim).filter(String::isNotBlank)
        val vintage = vintageRegex.find(text)?.value
        val maker = lines.firstOrNull()
        val name = lines
            .filterIndexed { index, line -> index > 0 && line != vintage }
            .joinToString(" ")
            .takeIf(String::isNotBlank)

        return ScannedWine(
            maker = maker,
            name = name,
            vintage = vintage,
            rawText = text.trim().takeIf(String::isNotBlank)
        )
    }
}
