package com.yourapp.wine.extract

import com.yourapp.wine.kb.BundledOrigins
import com.yourapp.wine.merge.TextNorm

/** Designations qualify a name; none of these words by itself establishes a country or producer. */
object WineDesignations {
    val names = com.yourapp.wine.kb.ReferenceWineCatalog.names("suffix")
    private fun key(text: String) = TextNorm.normalize(text).filter(Char::isLetterOrDigit)
    private val standalone = names.associateBy(::key)
    fun display(text: String): String? = standalone[key(text)]
    fun isDesignation(text: String): Boolean = display(text) != null

    private val patterns = names.sortedByDescending { it.length }.map { name ->
        val pattern = name.split(' ').joinToString("\\s+") { Regex.escape(it) }
        Triple(name, Regex("(?i)\\s+($pattern)$"), Regex("(?i)^($pattern)\\s+"))
    }
    data class Parts(val base: String, val suffix: String?)
    /** Only actual words are reordered. Accents are scrubbed; no fuzzy spelling or missing designation is supplied. */
    fun split(text: String): Parts {
        val clean = TextNorm.scrub(text).trim()
        for ((name, endPattern, startPattern) in patterns) {
            val end = endPattern.find(clean)
            if (end != null) return Parts(clean.substring(0, end.range.first).trim(), name)
            val start = startPattern.find(clean)
            if (start != null) return Parts(clean.substring(start.range.last + 1).trim(), name)
        }
        return Parts(clean, null)
    }

    // Only authored appellation/style terms, not generic countries/states or an arbitrary fuzzy place name.
    val appellations by lazy { BundledOrigins.terms.filter { term ->
        val reference = com.yourapp.wine.kb.ReferenceWineCatalog.named(term.name, BundledOrigins.countryCode(term.country).orEmpty())
        if (reference != null) reference.nameRole == "appellation"
        else term.country in setOf("France", "Italy", "Spain", "Portugal")
    } }
    private val appellationKeys by lazy { appellations.flatMap { listOf(it.name) + it.aliases }.map(::key).toSet() }
    fun isAppellationName(text: String): Boolean = key(text) in appellationKeys ||
        key(split(text).base) in appellationKeys || LabelTextRoles.isRhoneAppellation(text)

    /**
     * Classification suffixes that qualify an existing appellation instead of forming a distinct one:
     * `Saint-Emilion Grand Cru`, `Bordeaux Superieur`, `Medoc Superieur`, `Graves Superieures` all name
     * the same place as their base. Zone-forming suffixes are deliberately absent, because
     * `Chianti Classico`, `Soave Superiore`, `Beaujolais Villages` and `Barolo Riserva` style names are
     * separate appellations whose base is a different place (owner benchmark: Chianti Classico must
     * stay Chianti Classico, Saint-Emilion Grand Cru must resolve to Saint-Emilion).
     */
    private val classificationSuffixes = setOf("grandcru","grandcruclasse","premiergrandcruclasse",
        "superieur","superieure","superieures")

    /** The place this classification-qualified name belongs to, or null when the name forms its own zone. */
    fun classificationBase(text: String): String? {
        val parts = split(text)
        val suffix = parts.suffix ?: return null
        if (key(suffix) !in classificationSuffixes) return null
        return parts.base.takeIf { it.count(Char::isLetter) >= 3 }
    }

    private val bareFramingWords = setOf(
        "d'origine", "d origine", "origine", "origen", "origin",
        "controlee", "protegee", "controllata", "garantita",
        "calificada", "qualificada", "protegida", "protetta", "tipica",
        "appellation", "denominazione", "denominacion", "indicazione", "geografica",
        "vin", "vino", "wine", "de", "di", "du", "des",
    )

    private val framingPatterns = listOf(
        Regex("(?i)^appellation\\s+d[' ]origine\\s+(?:controlee|protegee)\\s+(.+)$"),
        Regex("(?i)^(.+?)\\s+appellation\\s+(?:d[' ]origine\\s+)?(?:controlee|protegee)$"),
        Regex("(?i)^appellation\\s+(.+?)\\s+(?:d[' ]origine\\s+)?(?:controlee|protegee)$"),
        Regex("(?i)^(.+?)\\s+denominazione\\s+di\\s+origine\\s+controllata(?:\\s+e\\s+garantita)?$"),
        Regex("(?i)^denominazione\\s+di\\s+origine\\s+controllata(?:\\s+e\\s+garantita)?\\s+(.+)$"),
        Regex("(?i)^(.+?)\\s+indicazione\\s+geografica\\s+(?:tipica|protetta)$"),
        Regex("(?i)^indicazione\\s+geografica\\s+(?:tipica|protetta)\\s+(.+)$"),
        Regex("(?i)^(.+?)\\s+denominacion\\s+de\\s+origen(?:\\s+(?:calificada|qualificada|protegida))?$"),
        Regex("(?i)^denominacion\\s+de\\s+origen(?:\\s+(?:calificada|qualificada|protegida))?\\s+(.+)$"),
    )

    /**
     * Extracts the inner appellation phrase `<X>` from a legal framing line such as
     * `APPELLATION SAINT-JULIEN CONTROLEE` or `BAROLO DENOMINAZIONE DI ORIGINE CONTROLLATA E GARANTITA`,
     * or null when the line is bare boilerplate (`APPELLATION D'ORIGINE CONTROLEE`) or unframed.
     */
    fun framedAppellation(text: String): String? {
        val clean = TextNorm.scrub(text).trim().trim('-', ',', ';', ':', '.', '/')
        if (clean.isEmpty() || '%' in clean) return null
        for (pattern in framingPatterns) {
            val match = pattern.matchEntire(clean) ?: continue
            val inner = match.groupValues[1].trim().trim('-', ',', ';', ':', '.', '/')
            val norm = TextNorm.normalize(inner)
            if (inner.count(Char::isLetter) < 3 || norm in bareFramingWords) continue
            if (norm.split(' ').size > 6) continue
            val without1er = norm.replace(Regex("\\b1er\\b"), "")
            if (without1er.any(Char::isDigit)) continue
            return inner
        }
        return null
    }

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
