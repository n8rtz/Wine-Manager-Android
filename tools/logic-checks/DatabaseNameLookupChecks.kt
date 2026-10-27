package com.yourapp.wine.kb

object DatabaseNameLookupChecks {
    fun exactAndNearMatchesRankAheadOfUnrelatedEntries() {
        val candidates = listOf(
            DatabaseLookupCandidate("1", "Château d'Agassac", "Bordeaux"),
            DatabaseLookupCandidate("2", "Château Margaux", "Bordeaux"),
            DatabaseLookupCandidate("3", "Barolo", "Piedmont")
        )
        val ranked = DatabaseNameLookup.rank("Chateau d'Agassac", candidates)
        check(ranked.first().name == "Chateau d'Agassac")
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

    fun exactWordMatchesRankFirstWithZeroDistance() {
        // Owner report (2026-10-26): searching "Koonunga" buried the Koonunga Hill
        // wines under short unrelated names, because only the full name was scored.
        val candidates = listOf(
            DatabaseLookupCandidate("1", "Codalunga", ""),
            DatabaseLookupCandidate("2", "Koonunga Hill Chardonnay", "Producer: Penfolds"),
            DatabaseLookupCandidate("3", "Koonunga Hill Shiraz", "Producer: Penfolds"),
            DatabaseLookupCandidate("4", "Schulz Koonunga Hill Shiraz", ""),
            DatabaseLookupCandidate("5", "Donna", "")
        )
        val ranked = DatabaseNameLookup.rank("Koonunga", candidates)
        check(ranked.take(2).map { it.name }.toSet() ==
            setOf("Koonunga Hill Chardonnay", "Koonunga Hill Shiraz"))
        check(ranked.take(3).all { it.editDistance == 0 })
        check(ranked[2].name == "Schulz Koonunga Hill Shiraz")
        // A near word match also beats the full-name distance (13 -> 1 here).
        val near = DatabaseNameLookup.rank("Koonunga",
            listOf(DatabaseLookupCandidate("6", "Koonuga Hill Shiraz", ""))).single()
        check(near.editDistance == 1)
        // Multi-word queries match a contiguous run of words exactly.
        val windowed = DatabaseNameLookup.rank("Koonunga Hill", candidates)
        check(windowed.first().editDistance == 0)
        check(windowed.first().name.startsWith("Koonunga Hill"))
    }

    fun resultsAreBoundedAndEmptyQueriesReturnNothing() {
        val candidates = (1..30).map { DatabaseLookupCandidate("$it", "Wine $it", "") }
        check(DatabaseNameLookup.rank("Wine", candidates, limit = 5).size == 5)
        check(DatabaseNameLookup.rank("  ", candidates).isEmpty())
    }
}
