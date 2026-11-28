package com.n8rtz.winetracker.data.local

import java.text.Normalizer

/**
 * Global fragment search (W-067). One shared matcher for Cellar, Notes,
 * Rankings and the note editor's wine picker, replacing the old
 * single-phrase substring checks ("Kirkland Chablis" matched nothing
 * because the words live in different fields: maker "Kirkland
 * Signature", name "Chablis").
 *
 * The query is split into tokens and EVERY token must be found
 * somewhere in the wine's search document (maker, name, vintage, type,
 * country, region, subregion), in any order and across field
 * boundaries. Tokens match in tiers, best first: exact word, word
 * prefix ("chab" -> "Chablis"), substring, then fuzzy (edit distance
 * <= 1, longer tokens only, for typos like "chabls"). Lower scores are
 * better matches; null means no match.
 */

/** Lowercase, strip accents ("Château" -> "chateau"), punctuation -> spaces. */
fun normalizeSearchText(value: String): String {
    val folded = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
    return folded.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

/** The normalised query words, in the order typed. */
fun searchQueryTokens(query: String): List<String> =
    normalizeSearchText(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * A wine's searchable text, precomputed once per list so filtering
 * doesn't re-normalise on every keystroke. Fields are kept in priority
 * order (maker first, subregion last) because earlier fields weigh
 * slightly better in scoring.
 */
class WineSearchDocument private constructor(
    val words: List<WordEntry>,
    val normalizedText: String
) {
    data class WordEntry(val word: String, val fieldIndex: Int)

    companion object {
        fun fromFields(vararg fields: String?): WineSearchDocument {
            val entries = mutableListOf<WordEntry>()
            fields.forEachIndexed { fieldIndex, field ->
                if (field.isNullOrBlank()) return@forEachIndexed
                normalizeSearchText(field)
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .forEach { entries += WordEntry(it, fieldIndex) }
            }
            return WineSearchDocument(
                words = entries,
                normalizedText = entries.joinToString(" ") { it.word }
            )
        }
    }
}

/** Convenience: build a document from a definition plus its vintage. */
fun WineDefinition.searchDocument(vintage: String?): WineSearchDocument =
    WineSearchDocument.fromFields(
        maker, name, vintage, type, country, region, subregion
    )

/**
 * Score [query] against [document]; lower is a better match, null is
 * no match. An empty query scores 0 (matches everything).
 */
fun scoreWineSearch(query: String, document: WineSearchDocument): Int? {
    val tokens = searchQueryTokens(query)
    if (tokens.isEmpty()) return 0
    var total = 0
    for (token in tokens) {
        var best: Int? = null
        for (entry in document.words) {
            val tier = tokenMatchTier(token, entry.word) ?: continue
            val weighted = tier * 10 + entry.fieldIndex
            if (best == null || weighted < best) best = weighted
            if (best == 0) break
        }
        total += best ?: return null
    }
    // Typing the words in the wine's own order, adjacently, is the
    // strongest signal (covers the old exact-phrase behaviour).
    if (tokens.size > 1 && document.normalizedText.contains(tokens.joinToString(" "))) {
        total -= 5
    }
    return total
}

/** True when every fragment of [query] is found in [fields]. */
fun matchesWineSearch(query: String, vararg fields: String?): Boolean =
    scoreWineSearch(query, WineSearchDocument.fromFields(*fields)) != null

private fun tokenMatchTier(token: String, word: String): Int? = when {
    word == token -> 0
    token.length >= 2 && word.startsWith(token) -> 1
    token.length >= 2 && word.contains(token) -> 2
    token.length >= 5 && editDistanceAtMostOne(token, word) -> 3
    else -> null
}

/** True when [a] can become [b] with at most one insert/delete/substitute. */
internal fun editDistanceAtMostOne(a: String, b: String): Boolean {
    val lengthDiff = a.length - b.length
    if (lengthDiff < -1 || lengthDiff > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++
            j++
        } else {
            edits++
            if (edits > 1) return false
            when {
                lengthDiff == 0 -> { i++; j++ } // substitution
                lengthDiff > 0 -> i++           // extra char in a
                else -> j++                     // extra char in b
            }
        }
    }
    if (i < a.length || j < b.length) edits++
    return edits <= 1
}
