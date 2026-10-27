package com.yourapp.wine.gazetteer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression checks for the fuzzy lookup bound.
 *
 * The resolver used to fetch `MAX_HITS+1` rows and then drop the whole span with
 * `if(hits.size > MAX_HITS) continue`. Every fuzzy FTS window on the packaged index is
 * full, so that rule fired on 100% of fuzzy lookups and the fuzzy branch could only ever
 * contribute exact primary-key hits. These checks pin the replacement: the fetch asks
 * for the full candidate bound and the span survives a full window, while every
 * precision rule downstream is unchanged.
 */
class ProducerResolverFuzzyLookupTest {

    private class FakeIndex(private val rows: List<ProducerResolver.Entity>) : ProducerResolver.Index {
        override val version = "test-fixture"
        override val primaryVerified = true
        var lastLimit = 0
        var calls = 0
        override fun lookup(key: String, limit: Int, checkBudget: () -> Unit): List<ProducerResolver.Entity> {
            lastLimit = limit
            calls++
            checkBudget()
            return rows
        }
    }

    private fun entity(id: String, name: String, key: String) =
        ProducerResolver.Entity(id, name, key, setOf("primary"))

    private fun span(text: String, engine: String, id: Int) =
        ProducerResolver.Span(text, engine, setOf(id))

    @Test
    fun `fuzzy lookup asks for the full candidate bound`() {
        val index=FakeIndex(listOf(entity("p1","Château Margaux","chateau margaux")))
        ProducerResolver(index).resolve("", listOf(span("chateau margaux","mlkit",0)))
        assertEquals(ProducerResolver.MAX_FUZZY_CANDIDATES, index.lastLimit)
        assertEquals(1, index.calls)
    }

    @Test
    fun `a full fuzzy window no longer drops the span`() {
        // 200 rows: one real candidate and a crowd of rivals. The window is full, which
        // under the old `hits.size > MAX_HITS` rule dropped the span outright.
        val rivals=(1..199).map { entity("r$it","Rival $it","rival name number $it") }
        val index=FakeIndex(listOf(entity("p1","Château Margaux","chateau margaux"))+rivals)
        val decision=ProducerResolver(index).resolve("", listOf(
            span("chateau margaux","mlkit",0),
            span("chateau margaux","paddle",1)))
        assertEquals("Chateau Margaux", decision.value)
        assertEquals("p1", decision.acceptedId)
    }

    @Test
    fun `two engines with disjoint observations are still required`() {
        val index=FakeIndex(listOf(entity("p1","Château Margaux","chateau margaux")))
        // Same entity, but both readings come from one engine: no independent support.
        val decision=ProducerResolver(index).resolve("", listOf(
            span("chateau margaux","mlkit",0),
            span("chateau margaux","mlkit",1)))
        assertEquals("", decision.value)
        assertEquals("no independently supported producer; unchanged", decision.reason)
        assertNull(decision.acceptedId)
    }

    @Test
    fun `overlapping observations from two engines are not independent support`() {
        val index=FakeIndex(listOf(entity("p1","Château Margaux","chateau margaux")))
        val decision=ProducerResolver(index).resolve("", listOf(
            span("chateau margaux","mlkit",0),
            span("chateau margaux","paddle",0)))
        assertEquals("no independently supported producer; unchanged", decision.reason)
    }

    @Test
    fun `an unrelated existing producer is still protected`() {
        val index=FakeIndex(listOf(entity("p1","Château Margaux","chateau margaux")))
        val decision=ProducerResolver(index).resolve("Domaine Drouhin", listOf(
            span("chateau margaux","mlkit",0),
            span("chateau margaux","paddle",1)))
        assertEquals("Domaine Drouhin", decision.value)
        assertEquals("different existing producer protected", decision.reason)
    }

    @Test
    fun `two viable producer identities still abstain`() {
        val index=FakeIndex(listOf(
            entity("p1","Château Margaux","chateau margaux"),
            entity("p2","Château Margon","chateau margon")))
        val decision=ProducerResolver(index).resolve("", listOf(
            span("chateau margaux","mlkit",0),
            span("chateau margon","paddle",1)))
        assertEquals("ambiguous producer identities; unchanged", decision.reason)
    }
}
