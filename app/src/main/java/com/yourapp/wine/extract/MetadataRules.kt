package com.yourapp.wine.extract

import com.yourapp.wine.merge.TextNorm

object MetadataRules {
    private val packaging = Regex("""(?i)\b\d+(?:[.,]\d+)?\s*%|\b\d+(?:[.,]\d+)?\s*(?:cl|ml|l|litres?|liters?|oz)\b|\b(?:alc|alcohol|abv)\b|\b\d{8,14}\b""")
    private val boilerplate = Regex("""\b(?:appellation|controlee|protegee|denominazione|denominacion|calificada|origine|contains|sulfites|bottled|mis en bouteille|product of|produced in|imported|barcode|gtin|ean|upc|volume|by vol|founded|established|est|since|grand vin)\b""")

    /** No coordinates: packaging, alcohol and barcodes are never identity/name candidates. */
    fun isPackaging(text: String): Boolean = packaging.containsMatchIn(text) || damagedAlcohol.containsMatchIn(text) || truncatedAlcohol.matches(text.trim()) ||
        damagedUnit.matches(TextNorm.normalize(text).filter(Char::isLetterOrDigit))

    // Closed unit vocabulary plus numeric/BY syntax, not a blacklist of digit-bearing brands.
    private val truncatedAlcohol=Regex("""(?i)^(?:[\d.,%]+|[a-z][.,])?\s*b?by\s+vol\.?$""")
    private val damagedUnit=Regex("(?:[0-9b]{0,4}byvol|netcont(?:ents?)?[0-9]+[ml1]{1,2})")
    private val damagedAlcohol = Regex("""(?i)\b(?:[5-9]|[12][0-9]|30)[.,][0-9]{1,2}\s*%?\s+by\s+[vft1][o0][l1i]\b""")

    /** No coordinates: administrative label text is not a distinctive producer or cuvée. */
    fun isBoilerplate(text: String): Boolean = boilerplate.containsMatchIn(TextNorm.normalize(text)) ||
        LabelTextRoles.isFoundingStatement(text) || LabelTextRoles.isLegalStatement(text) ||
        Regex("\\bwith\\s+(?:organic\\s+|organically grown\\s+)?grapes\\b").containsMatchIn(TextNorm.normalize(text)) ||
        com.yourapp.wine.kb.ReferenceWineCatalog.isAdministrative(text)

    /** No coordinates: remove year/packaging tokens and explicit producer cues, retaining the original evidence. */
    fun identityText(text: String): String = packaging.replace(text, " ")
        .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
        .replace(Regex("""(?i)^\s*(?:produced by|bottled by|mis en bouteille par)\s*[:,-]?\s*"""), "")
        .replace(Regex("\\s+"), " ").trim()

    /** No coordinates: a meaningful identity needs at least three letters, not just three digits. */
    fun isIdentity(text: String): Boolean = text.count(Char::isLetter) >= 3 &&
        Regex("[\\p{L}\\p{M}]{3,}").containsMatchIn(text) &&
        !isPackaging(text) && !isBoilerplate(text)
}
