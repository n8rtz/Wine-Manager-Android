package com.yourapp.wine.extract

import com.yourapp.wine.merge.TextNorm

object MetadataRules {
    private val packaging = Regex("""(?i)\b\d+(?:[.,]\d+)?\s*%|\b\d+(?:[.,]\d+)?\s*(?:cl|ml|l|litres?|liters?|oz)\b|\b(?:alc|alcohol|abv)\b|\b\d{8,14}\b""")
    private val boilerplate = Regex("""\b(?:appellation|controlee|protegee|denominazione|origine|contains|sulfites|bottled|mis en bouteille|product of|produced in|imported|barcode|gtin|ean|upc|volume|by vol|founded|established|est|since|grand vin)\b""")

    /** No coordinates: packaging, alcohol and barcodes are never identity/name candidates. */
    fun isPackaging(text: String): Boolean = packaging.containsMatchIn(text)

    /** No coordinates: administrative label text is not a distinctive producer or cuvée. */
    fun isBoilerplate(text: String): Boolean = boilerplate.containsMatchIn(TextNorm.normalize(text))

    /** No coordinates: remove year/packaging tokens and explicit producer cues, retaining the original evidence. */
    fun identityText(text: String): String = packaging.replace(text, " ")
        .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
        .replace(Regex("""(?i)^\s*(?:produced by|bottled by|mis en bouteille par)\s*[:,-]?\s*"""), "")
        .replace(Regex("\\s+"), " ").trim()

    /** No coordinates: a meaningful identity needs at least three letters, not just three digits. */
    fun isIdentity(text: String): Boolean = text.count(Char::isLetter) >= 3 &&
        !isPackaging(text) && !isBoilerplate(text)
}
