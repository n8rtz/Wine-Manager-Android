package com.n8rtz.winetracker.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WineSearchTest {

    private val kirklandChablis = WineSearchDocument.fromFields(
        "Kirkland Signature", "Chablis", "2021", "White", "France", "Burgundy", "Chablis"
    )

    @Test
    fun fragmentsAcrossFieldsMatch() {
        // The in-store case: words split across maker and name.
        assertNotNull(scoreWineSearch("Kirkland Chablis", kirklandChablis))
        assertTrue(matchesWineSearch("Kirkland Chablis", "Kirkland Signature", "Chablis"))
    }

    @Test
    fun wordOrderDoesNotMatter() {
        assertNotNull(scoreWineSearch("Chablis Kirkland", kirklandChablis))
    }

    @Test
    fun abbreviationsMatchByPrefix() {
        assertNotNull(scoreWineSearch("kirk sig", kirklandChablis))
        assertNotNull(scoreWineSearch("chab", kirklandChablis))
    }

    @Test
    fun accentsAreFolded() {
        val chateau = WineSearchDocument.fromFields("Château Margaux", "Grand Vin", "2015")
        assertNotNull(scoreWineSearch("chateau margaux", chateau))
    }

    @Test
    fun typosMatchFuzzily() {
        assertNotNull(scoreWineSearch("chabls", kirklandChablis))
        // Short tokens never fuzzy-match, to avoid surprising hits.
        assertNull(scoreWineSearch("kirk", WineSearchDocument.fromFields("Kork", "Red")))
    }

    @Test
    fun vintageAndPlaceTokensCount() {
        assertNotNull(scoreWineSearch("2021 burgundy", kirklandChablis))
        assertNotNull(scoreWineSearch("france white", kirklandChablis))
    }

    @Test
    fun unmatchedTokenRejectsTheWine() {
        assertNull(scoreWineSearch("Kirkland Barolo", kirklandChablis))
        assertEquals(0, scoreWineSearch("", kirklandChablis))
        assertEquals(0, scoreWineSearch("   ", kirklandChablis))
    }

    @Test
    fun exactMatchesOutrankFuzzyOnes() {
        val exact = scoreWineSearch("kirkland chablis", kirklandChablis)!!
        val fuzzy = scoreWineSearch("kirkland chabls", kirklandChablis)!!
        assertTrue(exact < fuzzy)
    }

    @Test
    fun editDistanceHelper() {
        assertTrue(editDistanceAtMostOne("chablis", "chabls"))
        assertTrue(editDistanceAtMostOne("chablis", "chabliz"))
        assertTrue(editDistanceAtMostOne("chablis", "chabliis"))
        assertTrue(!editDistanceAtMostOne("chablis", "chabl"))
        assertTrue(!editDistanceAtMostOne("chablis", "barolo"))
    }
}
