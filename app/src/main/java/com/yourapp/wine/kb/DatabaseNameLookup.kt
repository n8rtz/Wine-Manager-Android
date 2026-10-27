package com.yourapp.wine.kb

import com.yourapp.wine.merge.TextNorm
import kotlin.math.max

/** Search scope for the in-app, read-only local knowledge-base diagnostic.
 * PRODUCER is one lookup across every packaged producer database. Owner rule
 * (2026-10-26, .agents/instructions.md #17): every future packaged database
 * addition/change must join the matching category's search — and appear in that
 * category's on-page database listing with its record count — in the same change. */
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
    val similarityPercent: Int,
    /** Winning candidate's identity, so callers can enrich a result afterwards (e.g. wine -> producers). */
    val identity: String = ""
)

/** Pure diagnostic ranking. It searches the supplied catalog exhaustively; it does not accept or correct values. */
object DatabaseNameLookup {
    private val wordSeparator = Regex("[^\\p{L}\\p{N}]+")

    fun rank(query: String, candidates: List<DatabaseLookupCandidate>, limit: Int = 12): List<DatabaseLookupMatch> {
        require(limit > 0)
        val normalizedQuery = TextNorm.normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()
        val queryWords = words(normalizedQuery)
        val joinedQuery = queryWords.joinToString(" ")
        return candidates.mapNotNull { candidate ->
            val best = candidate.searchTerms.asSequence().filter(String::isNotBlank).mapNotNull { term ->
                val normalizedTerm = TextNorm.normalize(term)
                if (normalizedTerm.isBlank()) null else {
                    val fullDistance = TextNorm.editDistance(normalizedQuery, normalizedTerm,
                        max(normalizedQuery.length, normalizedTerm.length)) ?: return@mapNotNull null
                    // An exact word (or contiguous word run) inside a longer name is a
                    // distance-0 match: "Koonunga" must not pay for "Hill Shiraz".
                    val distance = if (fullDistance == 0) 0 else
                        wordDistance(queryWords, joinedQuery, normalizedTerm, fullDistance - 1) ?: fullDistance
                    val width = max(1, max(normalizedQuery.length, normalizedTerm.length))
                    val editSimilarity = 1.0 - distance.toDouble() / width
                    val score = .7 * editSimilarity + .3 * TextNorm.jaroWinkler(normalizedQuery, normalizedTerm)
                    Triple(term, distance, score)
                }
            }.maxWithOrNull(compareBy<Triple<String, Int, Double>> { it.third }.thenBy { -it.second })
                ?: return@mapNotNull null
            candidate.identity to DatabaseLookupMatch(
                name = TextNorm.scrub(candidate.name),
                context = TextNorm.scrub(candidate.context),
                matchedAs = TextNorm.scrub(best.first),
                editDistance = best.second,
                similarityPercent = (best.third * 100).toInt().coerceIn(0, 100),
                identity = candidate.identity
            )
        }.groupBy({ it.first }, { it.second }).values.mapNotNull { matches ->
            matches.maxWithOrNull(compareBy<DatabaseLookupMatch> { it.similarityPercent }
                .thenBy { -it.editDistance })
        }.sortedWith(compareByDescending<DatabaseLookupMatch> { it.similarityPercent }
            .thenBy { it.editDistance }.thenBy { it.name.lowercase() })
            .take(limit)
    }

    /**
     * Best edit distance between the query's words and any contiguous run of the same
     * number of words in [normalizedTerm], or null when no run beats [bound]. The
     * tightening bound keeps this cheap inside the exhaustive catalog scans: a window
     * only counts when it is strictly closer than everything found so far.
     */
    private fun wordDistance(
        queryWords: List<String>, joinedQuery: String, normalizedTerm: String, bound: Int
    ): Int? {
        val termWords = words(normalizedTerm)
        if (queryWords.isEmpty() || queryWords.size > termWords.size) return null
        var best: Int? = null
        var limit = bound
        for (start in 0..termWords.size - queryWords.size) {
            val window = termWords.subList(start, start + queryWords.size).joinToString(" ")
            val distance = TextNorm.editDistance(joinedQuery, window, limit) ?: continue
            best = distance
            if (distance == 0) break
            limit = distance - 1
        }
        return best
    }

    /** Letter/digit words of an already normalized string (same tokenization as the catalog keys). */
    private fun words(normalized: String): List<String> =
        normalized.split(wordSeparator).filter { it.isNotEmpty() }

    /** Appellation candidates with their parent chain as context, from any place view
     * (raw scanner-KB gazetteer or the supplemented origins view the scanner uses). */
    fun appellationCandidates(places: List<Place>): List<DatabaseLookupCandidate> {
        val byId = places.associateBy { it.id }
        return places.asSequence().filter { it.type == "appellation" }.map { place ->
            val parents = mutableListOf<String>()
            var parent = place.parentId?.let(byId::get)
            while (parent != null) {
                parents += parent.name
                parent = parent.parentId?.let(byId::get)
            }
            DatabaseLookupCandidate(
                identity = "appellation:${place.id}", name = place.name,
                context = parents.asReversed().joinToString(" · ")
            )
        }.toList()
    }
}
