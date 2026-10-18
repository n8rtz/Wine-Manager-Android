package com.yourapp.wine.extract

import com.yourapp.wine.kb.BundledOrigins
import com.yourapp.wine.merge.TextNorm

/** Designations qualify a name; none of these words by itself establishes a country or producer. */
object WineDesignations {
    val names = listOf("Gran Reserva", "Gran Riserva", "Grand Reserve", "Reserva", "Riserva", "Reserve",
        "Crianza", "Premier Grand Cru Classé", "Grand Cru Classé", "Grand Cru", "Premier Cru", "1er Cru", "Villages")
    private fun key(text: String) = TextNorm.normalize(text).filter(Char::isLetterOrDigit)
    private val standalone = names.associateBy(::key)
    fun display(text: String): String? = standalone[key(text)]
    fun isDesignation(text: String): Boolean = display(text) != null

    private val patterns = names.sortedByDescending { it.length }.map { name ->
        val pattern = name.split(' ').joinToString("\\s+") { Regex.escape(it) }
        Triple(name, Regex("(?i)\\s+($pattern)$"), Regex("(?i)^($pattern)\\s+"))
    }
    data class Parts(val base: String, val suffix: String?)
    /** Only actual words are reordered. No fuzzy spelling or missing designation is supplied. */
    fun split(text: String): Parts {
        val clean = text.trim()
        for ((name, endPattern, startPattern) in patterns) {
            val end = endPattern.find(clean)
            if (end != null) return Parts(clean.substring(0, end.range.first).trim(), name)
            val start = startPattern.find(clean)
            if (start != null) return Parts(clean.substring(start.range.last + 1).trim(), name)
        }
        return Parts(clean, null)
    }

    // Only authored appellation/style terms, not generic countries/states or an arbitrary fuzzy place name.
    val appellations by lazy { BundledOrigins.terms.filter { it.country in setOf("France", "Italy", "Spain", "Portugal") } }
    private val appellationKeys by lazy { appellations.flatMap { listOf(it.name) + it.aliases }.map(::key).toSet() }
    fun isAppellationName(text: String): Boolean = key(text) in appellationKeys ||
        key(split(text).base) in appellationKeys || LabelTextRoles.isRhoneAppellation(text)

    fun hasNameAndDesignation(text: String): Boolean {
        val parts = split(text)
        return parts.suffix != null && parts.base.count(Char::isLetter) >= 3 &&
            !ProducerLineSelector().hasProducerCue(text)
    }

    fun orderName(text: String): String {
        val parts = split(text)
        return if (parts.suffix != null && (key(parts.base) in appellationKeys || hasNameAndDesignation(text))) "${parts.base} ${parts.suffix}" else text
    }
}
