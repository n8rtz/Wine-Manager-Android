package com.yourapp.wine.textlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourapp.wine.gazetteer.ProducerResolver
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.textlab.evidence.EvidenceExtractor
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Catalog role evidence makes a grape a strong non-producer and an origin a softer penalty. */
@RunWith(AndroidJUnit4::class)
class ProducerRolePenaltyDeviceTest {
    @Test fun grapeVarietiesArePenalizedMoreThanRegionalTerms() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("producerRolePenalty")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            val extractor=EvidenceExtractor(kb)
            val grape=extractor.producerCandidatePenalty("Cabernet Sauvignon")
            val mixed=extractor.producerCandidatePenalty("Cabernet Sauvignon Pays d'Oc")
            val region=extractor.producerCandidatePenalty("Pays d'Oc")
            assertTrue(grape>region)
            assertEquals(grape,mixed)
            assertTrue(region>0)

            val index=object:ProducerResolver.Index {
                override val version="synthetic-role-penalty"
                override val primaryVerified=true
                override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(
                    ProducerResolver.Entity("cabernet","Cabernet Sauvignon","cabernet sauvignon",setOf("lwin")),
                )
            }
            val policy=ProducerResolver.Policy.singleReading.withProducerPenalty(extractor::producerCandidatePenalty)
            val decision=ProducerResolver(index).resolve("",listOf(
                ProducerResolver.Span("Cabernet Sauvignon","mlkit",setOf(0)),
            ),policy=policy)
            assertNull("A grape variety must not become a producer even on an exact synthetic catalog hit",decision.acceptedId)

            val cambras=ProducerResolver.Entity("cambras","Les Ormes de Cambras","les ormes de cambras",setOf("xwines"))
            val expanded=object:ProducerResolver.Index {
                override val version="synthetic-expanded-role-penalty"
                override val primaryVerified=false
                override val labSupplemental=true
                override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(cambras)
            }
            val corrected=ProducerResolver(expanded).resolve("Cabernet Sauvignon Pays d'Oc",listOf(
                ProducerResolver.Span("Les Ormes Cambras","mlkit",setOf(1)),
            ),policy=ProducerResolver.Policy.singleReading.withProducerPenalty(extractor::producerCandidatePenalty))
            assertEquals("cambras",corrected.acceptedId)
            assertEquals("Les Ormes de Cambras",corrected.value)
            assertTrue(corrected.reason.contains("strong grape-variety cue"))

            val regionOnly=ProducerResolver(expanded).resolve("Pays d'Oc",listOf(
                ProducerResolver.Span("Les Ormes Cambras","mlkit",setOf(1)),
            ),policy=ProducerResolver.Policy.singleReading.withProducerPenalty(extractor::producerCandidatePenalty))
            assertNull("A weaker geography cue must not bypass existing-producer protection",regionOnly.acceptedId)
            assertEquals("Pays d'Oc",regionOnly.value)
        } finally { kb.close() }
    }
}
