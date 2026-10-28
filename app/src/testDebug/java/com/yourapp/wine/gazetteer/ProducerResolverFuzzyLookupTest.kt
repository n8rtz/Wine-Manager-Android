package com.yourapp.wine.gazetteer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression checks for the fuzzy lookup bound.
 *
 * The resolver used to fetch `MAX_HITS+1` rows and then drop the whole span with
 * `if(hits.size > MAX_HITS) continue`. Every fuzzy FTS window on the packaged index is
 * full, so that rule fired on 100% of fuzzy lookups and the fuzzy branch could only ever
 * contribute exact primary-key hits. These checks pin the bounded producer shortlist, ensure a
 * candidate beyond the old 200-row cutoff survives, and keep both the span-based precision gates
 * and the deliberately broader final-answer database canonicalization explicit.
 */
class ProducerResolverFuzzyLookupTest {

    private class FakeIndex(
        private val rows: List<ProducerResolver.Entity>,
        override val primaryVerified: Boolean = true,
        override val labSupplemental: Boolean = false,
    ) : ProducerResolver.Index {
        override val version = "test-fixture"
        var lastLimit = 0
        var calls = 0
        override fun lookup(key: String, limit: Int, checkBudget: () -> Unit): List<ProducerResolver.Entity> {
            lastLimit = limit
            calls++
            checkBudget()
            return rows.take(limit)
        }
    }

    private fun entity(id: String, name: String, key: String, support:Set<String> = setOf("lwin")) =
        ProducerResolver.Entity(id, name, key, support)

    private fun span(text: String, engine: String, id: Int) =
        ProducerResolver.Span(text, engine, setOf(id))

    @Test
    fun `fuzzy lookup asks for the full candidate bound`() {
        val index=FakeIndex(listOf(entity("p1","Château Margaux","chateau margaux")))
        ProducerResolver(index).resolve("", listOf(span("chateau margaux","mlkit",0)))
        assertEquals(ProducerResolver.MAX_PRODUCER_FUZZY_CANDIDATES, index.lastLimit)
        assertEquals(1, index.calls)
    }

    @Test
    fun `a result set larger than the former hit cutoff still reaches precision checks`() {
        // 200 rows: one real candidate and a crowd of rivals. The old `MAX_HITS` rule
        // dropped a saturated result set before applying the precision gates.
        val rivals=(1..199).map { entity("r$it","Rival $it","rival name number $it") }
        val index=FakeIndex(listOf(entity("p1","Château Margaux","chateau margaux"))+rivals)
        val decision=ProducerResolver(index).resolve("", listOf(
            span("chateau margaux","mlkit",0),
            span("chateau margaux","paddle",1)))
        assertEquals("Chateau Margaux", decision.value)
        assertEquals("p1", decision.acceptedId)
    }

    @Test
    fun `final answer canonicalizes to the unique closest database spelling`() {
        val index=FakeIndex(listOf(
            entity("francette","La Francette","la francette",setOf("xwines")),
            entity("grangette","Grangette","grangette",setOf("lwin")),
        ),primaryVerified=false,labSupplemental=true)
        val near=ProducerResolver(index).finalNearMatch("Trancette")
        assertEquals("francette",near?.entity?.id)
        assertEquals("La Francette",near?.entity?.name)
        assertEquals(1,near?.distance ?: -1)
    }

    @Test
    fun `final near match does not rewrite exact or distant database values`() {
        val exactIndex=FakeIndex(listOf(entity("exact","Chateau Margaux","chateau margaux")))
        assertNull(ProducerResolver(exactIndex).finalNearMatch("CHATEAU MARGAUX"))
        val distantIndex=FakeIndex(listOf(entity("amelisse","Chateau Amelisse","chateau amelisse")))
        assertNull(ProducerResolver(distantIndex).finalNearMatch("Chateau Ameli"))
        val genericIndex=FakeIndex(listOf(entity("chateau","Chateau","chateau")))
        assertNull(ProducerResolver(genericIndex).finalNearMatch("HATEAU"))
    }

    @Test
    fun `final near match uses FTS order to break equal-distance ties`() {
        val index=FakeIndex(listOf(
            entity("margaux","Chateau Margaux","chateau margaux"),
            entity("margat","Chateau Margat","chateau margat"),
        ))
        assertEquals("margaux",ProducerResolver(index).finalNearMatch("Chateau Margax")?.entity?.id)
    }

    @Test
    fun `one-word Trancette miss remains abstention despite retrieving La Francette`() {
        val index=FakeIndex(listOf(entity("la-francette","La Francette","la francette")))
        val decision=ProducerResolver(index).resolve("",listOf(span("Trancette","paddle-v6-medium",0)),
            policy=ProducerResolver.Policy.singleReading.withExactMatchFastPath())
        assertEquals(1,index.calls)
        assertEquals(4,ProducerResolver.distance("trancette","la francette"))
        assertNull(decision.acceptedId)
        assertEquals("no independently supported producer; unchanged",decision.reason)
    }

    @Test
    fun `producer hit ranked 225th survives the expanded bounded shortlist`() {
        val target=entity("tour-de-by","Chateau La Tour de By","chateau la tour de by")
        val rows=(1..224).map { entity("r$it","Rival $it","unrelated producer candidate $it") }+target
        val index=FakeIndex(rows)
        val decision=ProducerResolver(index).resolve("", listOf(
            span("CHATEAU La Cour de By","paddle-v6-medium",0)),
            policy=ProducerResolver.Policy.singleReading.withExactMatchFastPath())
        assertEquals(ProducerResolver.MAX_PRODUCER_FUZZY_CANDIDATES,index.lastLimit)
        assertEquals("tour-de-by",decision.acceptedId)
        assertEquals("Chateau La Tour de By",decision.value)
    }

    @Test
    fun `single-reading fuzzy supplemental candidate cannot overrule the observed name`() {
        val candidate=entity("osm-neighbor","Chateau des Rochers","chateau des rochers",setOf("osm"))
        val index=FakeIndex(listOf(candidate),primaryVerified=false,labSupplemental=true)
        val existing="CHATEAU DES ROCHES"
        val decision=ProducerResolver(index).resolve(existing,listOf(span(existing,"paddle-v6-medium",0)),
            policy=ProducerResolver.Policy.singleReading.withExactMatchFastPath())
        assertEquals(existing,decision.value)
        assertNull(decision.acceptedId)
        assertEquals("single-engine fuzzy supplemental candidate needs LWIN or independent engines; unchanged",decision.reason)
    }

    @Test
    fun `two engines can still support a fuzzy supplemental producer`() {
        val candidate=entity("osm-neighbor","Chateau des Rochers","chateau des rochers",setOf("osm"))
        val index=FakeIndex(listOf(candidate),primaryVerified=false,labSupplemental=true)
        val decision=ProducerResolver(index).resolve("",listOf(
            span("CHATEAU DES ROCHES","paddle-v6-medium",0),
            span("CHATEAU DES ROCHES","mlkit",1)),
            policy=ProducerResolver.Policy.singleReading.withExactMatchFastPath())
        assertEquals("osm-neighbor",decision.acceptedId)
        assertEquals("Chateau des Rochers",decision.value)
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
