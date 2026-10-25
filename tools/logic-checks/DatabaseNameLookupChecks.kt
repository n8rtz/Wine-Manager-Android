package com.yourapp.wine.kb

object DatabaseNameLookupChecks {
    fun exactAndNearMatchesRankAheadOfUnrelatedEntries() {
        val candidates = listOf(
            DatabaseLookupCandidate("1", "Château d'Agassac", "Bordeaux"),
            DatabaseLookupCandidate("2", "Château Margaux", "Bordeaux"),
            DatabaseLookupCandidate("3", "Barolo", "Piedmont")
        )
        val ranked = DatabaseNameLookup.rank("Chateau d'Agassac", candidates)
        check(ranked.first().name == "Château d'Agassac")
        check(ranked.first().editDistance == 0)
        check(ranked.first().context == "Bordeaux")
        check(ranked[1].similarityPercent > ranked.last().similarityPercent)
    }

    fun aliasesCanMatchWhileCanonicalNameRemainsVisible() {
        val candidate = DatabaseLookupCandidate(
            identity = "producer:6",
            name = "Robert Mondavi",
            context = "California · US",
            searchTerms = listOf("Robert Mondavi", "Robert Mondavi Winery")
        )
        val match = DatabaseNameLookup.rank("Robert Mondavi Winery", listOf(candidate)).single()
        check(match.name == "Robert Mondavi")
        check(match.matchedAs == "Robert Mondavi Winery")
        check(match.editDistance == 0)
    }

    fun resultsAreBoundedAndEmptyQueriesReturnNothing() {
        val candidates = (1..30).map { DatabaseLookupCandidate("$it", "Wine $it", "") }
        check(DatabaseNameLookup.rank("Wine", candidates, limit = 5).size == 5)
        check(DatabaseNameLookup.rank("  ", candidates).isEmpty())
    }
}
