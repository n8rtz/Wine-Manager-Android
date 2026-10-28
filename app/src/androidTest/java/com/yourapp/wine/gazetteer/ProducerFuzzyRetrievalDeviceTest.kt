package com.yourapp.wine.gazetteer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Integration regression for the candidate that ranked 225th in the captured expanded-index query. */
@RunWith(AndroidJUnit4::class)
class ProducerFuzzyRetrievalDeviceTest {
    @Test fun expandedFtsShortlistRetainsTourDeByNearMiss() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("producerFuzzyRetrieval")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val gazetteer=ProducerGazetteer.openExpanded(context)
        try {
            assertEquals(256,ProducerResolver.MAX_PRODUCER_FUZZY_CANDIDATES)
            val missingParticle="Les Ormes Cambras"
            val cambrasId="a86fa916e58801673863a652"
            val cambrasHits=gazetteer.lookup(ProducerResolver.normalize(missingParticle),
                ProducerResolver.MAX_PRODUCER_FUZZY_CANDIDATES) {}
            val cambras=cambrasHits.firstOrNull { it.id==cambrasId }
            assertNotNull("Omitting French producer particle `de` must still retrieve Les Ormes de Cambras",cambras)
            assertEquals(0,ProducerResolver.producerDistance(ProducerResolver.normalize(missingParticle),cambras!!.key))
            assertEquals(3,ProducerResolver.distance(ProducerResolver.normalize(missingParticle),cambras!!.key)) // linked-wine distance stays particle-sensitive
            val cambrasDecision=ProducerResolver(gazetteer).resolve("Cabernet Sauvignon Pays d'Oc",listOf(
                ProducerResolver.Span(missingParticle,"mlkit",setOf(0)),
            ),policy=ProducerResolver.Policy.singleReading.withExactMatchFastPath())
            assertEquals(cambrasId,cambrasDecision.acceptedId)
            assertEquals("Les Ormes de Cambras",cambrasDecision.value)
            val cambrasFinal=ProducerResolver(gazetteer).finalNearMatch(missingParticle)
            assertEquals(cambrasId,cambrasFinal?.entity?.id)
            assertEquals(0,cambrasFinal?.distance ?: -1)
            val id="2dfc220744b222c6705c181f"
            val observed="CHATEAU La Cour de By"
            val hits=gazetteer.lookup(ProducerResolver.normalize(observed),
                ProducerResolver.MAX_PRODUCER_FUZZY_CANDIDATES) {}
            assertTrue("The previously truncated primary candidate must survive the bounded FTS shortlist",
                hits.any { it.id==id })
            val finalNear=ProducerResolver(gazetteer).finalNearMatch(observed)
            assertEquals(id,finalNear?.entity?.id)
            assertEquals(1,finalNear?.distance ?: -1)
            val decision=ProducerResolver(gazetteer).resolve(observed,listOf(
                ProducerResolver.Span(observed,"paddle-v6-medium",setOf(0))),
                policy=ProducerResolver.Policy.singleReading)
            assertEquals(id,decision.acceptedId)
            assertEquals("Chateau La Tour de By",decision.value)

            // The shortlist can find La Francette for this one-word OCR reading, but the resolver
            // must not turn a 4-edit, article-plus-initial mismatch into an identity assignment.
            val francetteId="28bf5a0505bb79ea62358b88"
            val trancetteHits=gazetteer.lookup("trancette",ProducerResolver.MAX_PRODUCER_FUZZY_CANDIDATES) {}
            assertTrue(trancetteHits.any { it.id==francetteId })
            val abstention=ProducerResolver(gazetteer).resolve("",listOf(
                ProducerResolver.Span("Trancette","paddle-v6-medium",setOf(1))),
                policy=ProducerResolver.Policy.singleReading.withExactMatchFastPath())
            assertNull(abstention.acceptedId)
            val corrected=ProducerResolver(gazetteer).finalNearMatch("Trancette")
            assertEquals(francetteId,corrected?.entity?.id)
            assertEquals(1,corrected?.distance ?: -1)
        } finally {
            gazetteer.close()
        }
    }
}
