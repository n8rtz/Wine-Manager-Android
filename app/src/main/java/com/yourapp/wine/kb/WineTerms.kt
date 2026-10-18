package com.yourapp.wine.kb

/** No coordinates: a curated display term plus verified aliases, not an inferred wine identity. */
data class WineTerm(val name: String, val aliases: List<String> = emptyList())

/** No coordinates: the old parser's authored geographic row; no producer or vintage is implied. */
data class WineOriginTerm(val name: String, val country: String, val region: String, val aliases: List<String> = emptyList())

/** No coordinates: parses the existing source-authored name/alias table without downloading or inventing records. */
internal fun parseWineTerms(source: String): List<WineTerm> = source.trimIndent().lineSequence()
    .map(String::trim).filter(String::isNotEmpty).map { row ->
        val parts = row.split('|', limit = 2)
        WineTerm(parts.first().trim(), parts.getOrNull(1)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty())
    }.toList()
