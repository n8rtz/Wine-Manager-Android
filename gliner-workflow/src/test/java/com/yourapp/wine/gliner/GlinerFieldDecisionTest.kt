package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlinerFieldDecisionTest {
    private fun hint(text:String,score:Float=0.52f) = GlinerFieldHint(GlinerLane.WINE_NAME,text,score)
    private val baseline = mapOf("producer" to "Ciabot", "wine_name" to "Ciabot", "country" to "Italy")

    @Test fun correctWineHintCanRepairAnIdentityCollision() {
        val result=GlinerFieldDecisionPolicy.apply(baseline,listOf(hint("Baronetto")),listOf("CIABOT","Baronetto"))
        assertEquals("Baronetto",result.fields["wine_name"])
        assertEquals("Ciabot",result.fields["producer"])
        assertEquals("Italy",result.fields["country"])
        assertEquals(1,result.changes.size)
    }

    @Test fun doesNotOverrideUnrelatedNonblankWineOrInventOcr() {
        val result=GlinerFieldDecisionPolicy.apply(baseline+mapOf("wine_name" to "Merlot"),listOf(hint("Baronetto")),listOf("Baronetto"))
        assertEquals("Merlot",result.fields["wine_name"])
        assertTrue(result.changes.isEmpty())
        val missing=GlinerFieldDecisionPolicy.apply(baseline,listOf(hint("Baronetto")),listOf("Baronet"))
        assertEquals("Ciabot",missing.fields["wine_name"])
    }

    @Test fun abstainsOnCompetingHintsAndNeedsHigherConfidenceForBlank() {
        val conflict=GlinerFieldDecisionPolicy.apply(baseline,listOf(hint("Baronetto"),hint("Altro")),listOf("Baronetto","Altro"))
        assertEquals("Ciabot",conflict.fields["wine_name"])
        val blank=baseline+mapOf("wine_name" to "")
        assertEquals("",GlinerFieldDecisionPolicy.apply(blank,listOf(hint("Baronetto")),listOf("Baronetto")).fields["wine_name"])
        assertEquals("Baronetto",GlinerFieldDecisionPolicy.apply(blank,listOf(hint("Baronetto",0.66f)),listOf("Baronetto")).fields["wine_name"])
    }

    @Test fun wrongLaneNonFiniteScoreAndProducerMatchAreNotCandidates() {
        val other=GlinerFieldHint(GlinerLane.APPELLATION,"Baronetto",0.9f)
        assertEquals("Ciabot",GlinerFieldDecisionPolicy.apply(baseline,listOf(other),listOf("Baronetto")).fields["wine_name"])
        assertEquals("Ciabot",GlinerFieldDecisionPolicy.apply(baseline,listOf(hint("Baronetto",Float.NaN)),listOf("Baronetto")).fields["wine_name"])
        assertEquals("Ciabot",GlinerFieldDecisionPolicy.apply(baseline,listOf(hint("CIABOT",0.99f)),listOf("CIABOT")).fields["wine_name"])
    }
}
