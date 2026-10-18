package com.yourapp.wine.extract

import com.yourapp.wine.kb.BundledGrapes
import com.yourapp.wine.kb.BundledWineStyles
import com.yourapp.wine.merge.TextNorm

/** No coordinates: display repair retains the original measured TokenRecords as evidence. */
data class WineTextRepair(val text: String, val changed: Boolean)

/** Exact known-letter matching across OCR word breaks, never edit-distance spelling guesses. */
class WineTermSpacing(forms: List<Pair<String, String>>) {
    private val indexed = forms.groupBy { compact(it.first) }.mapNotNull { (key, values) ->
        val replacements = values.map { it.second }.distinctBy(TextNorm::normalize)
        if (key.length < 3 || replacements.size != 1) null else key to replacements.single()
    }.toMap()
    private val maxLength = indexed.keys.maxOfOrNull { it.length } ?: 0

    /** No coordinates: known forms may repair split words; unrelated/unknown names are left unchanged. */
    fun repair(text: String): WineTextRepair {
        if (indexed.isEmpty()) return WineTextRepair(text, false)
        val words = word.findAll(text).toList()
        val result = StringBuilder()
        var index = 0
        var copiedTo = 0
        while (index < words.size) {
            var key = ""
            var replacement: String? = null
            var last = index - 1
            for (end in index until minOf(words.size, index + 12)) {
                if (end > index) {
                    val gap = text.substring(words[end-1].range.last + 1, words[end].range.first)
                    if (gap.any { !it.isWhitespace() && it !in "'-’" }) break
                }
                key += compact(words[end].value)
                if (key.length > maxLength) break
                indexed[key]?.let { replacement = it; last = end }
            }
            if (last >= index && replacement != null) {
                result.append(text, copiedTo, words[index].range.first).append(replacement)
                copiedTo = words[last].range.last + 1
                index = last + 1
            } else index++
        }
        result.append(text, copiedTo, text.length)
        val repaired = result.toString()
        return WineTextRepair(repaired, TextNorm.normalize(repaired) != TextNorm.normalize(text))
    }

    companion object {
        private val word = Regex("[\\p{L}\\p{M}\\p{N}]+")
        private fun compact(value: String) = TextNorm.normalize(value).filter(Char::isLetterOrDigit)
        val wineWords: WineTermSpacing by lazy {
            // Keep the label's legitimate grape alias (e.g. Petite Sirah), not a different synonym.
            WineTermSpacing((BundledGrapes.terms + BundledWineStyles.terms).flatMap { term ->
                (listOf(term.name) + term.aliases).map { it to it }
            } + WineDesignations.names.map { it to it })
        }
    }
}
