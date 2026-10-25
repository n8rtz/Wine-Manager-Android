package com.yourapp.wine.kb

import com.yourapp.wine.merge.TextNorm
import kotlin.math.max

/** Search scope for the in-app, read-only local knowledge-base diagnostic. */
enum class DatabaseLookupCategory(val label: String) {
    PRODUCER("Producer"), WINE_NAME("Wine name"), APPELLATION("Appellation")
}

data class DatabaseLookupCandidate(
    val identity: String,
    val name: String,
    val context: String,
    /** Aliases are searched but the canonical database name remains the displayed result. */
    val searchTerms: List<String> = listOf(name)
)

data class DatabaseLookupMatch(
    val name: String,
    val context: String,
    val matchedAs: String,
    val editDistance: Int,
    val similarityPercent: Int
)

/** Pure diagnostic ranking. It searches the supplied catalog exhaustively; it does not accept or correct values. */
object DatabaseNameLookup {
    fun rank(query: String, candidates: List<DatabaseLookupCandidate>, limit: Int = 12): List<DatabaseLookupMatch> {
        require(limit > 0)
        val normalizedQuery = TextNorm.normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()
        return candidates.mapNotNull { candidate ->
            val best = candidate.searchTerms.asSequence().filter(String::isNotBlank).mapNotNull { term ->
                val normalizedTerm = TextNorm.normalize(term)
                if (normalizedTerm.isBlank()) null else {
                    val distance = TextNorm.editDistance(normalizedQuery, normalizedTerm,
                        max(normalizedQuery.length, normalizedTerm.length)) ?: return@mapNotNull null
                    val width = max(1, max(normalizedQuery.length, normalizedTerm.length))
                    val editSimilarity = 1.0 - distance.toDouble() / width
                    val score = .7 * editSimilarity + .3 * TextNorm.jaroWinkler(normalizedQuery, normalizedTerm)
                    Triple(term, distance, score)
                }
            }.maxWithOrNull(compareBy<Triple<String, Int, Double>> { it.third }.thenBy { -it.second })
                ?: return@mapNotNull null
            candidate.identity to DatabaseLookupMatch(
                name = candidate.name,
                context = candidate.context,
                matchedAs = best.first,
                editDistance = best.second,
                similarityPercent = (best.third * 100).toInt().coerceIn(0, 100)
            )
        }.groupBy({ it.first }, { it.second }).values.mapNotNull { matches ->
            matches.maxWithOrNull(compareBy<DatabaseLookupMatch> { it.similarityPercent }
                .thenBy { -it.editDistance })
        }.sortedWith(compareByDescending<DatabaseLookupMatch> { it.similarityPercent }
            .thenBy { it.editDistance }.thenBy { it.name.lowercase() })
            .take(limit)
    }
}
