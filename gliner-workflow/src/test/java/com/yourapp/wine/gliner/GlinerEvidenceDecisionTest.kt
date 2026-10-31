package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlinerEvidenceDecisionTest {
    private val grapes=setOf("Cabernet Sauvignon", "Chardonnay", "Pinot Noir", "Merlot")
    private fun line(engine:String,text:String,top:Float,bottom:Float,left:Float=0.2f,right:Float=0.8f) =
        GlinerEvidenceLine(engine,text,left,top,right,bottom)
    private fun pair(text:String,top:Float,bottom:Float,left:Float=0.2f,right:Float=0.8f) = listOf(
        line("mlkit",text,top,bottom,left,right),line("paddle-v6-medium",text,top,bottom,left,right))
    private val base=mapOf("producer" to "Example Estate", "wine_name" to "Cabernet Sauvignon",
        "country" to "France", "region" to "Bordeaux", "subregion" to "", "vintage" to "2020")

    @Test fun aWineLaneCanDisplaceAGeographicFallbackWhenItIsAnExactOcrLine() {
        val fields=base+mapOf("producer" to "Ciabot", "wine_name" to "Barbaresco", "subregion" to "Barbaresco")
        val hint=GlinerFieldHint(GlinerLane.WINE_NAME,"Baronetto",0.52f)
        val decision=GlinerFieldDecisionPolicy.apply(fields,listOf(hint),listOf("Baronetto"))
        assertEquals("Baronetto",decision.fields["wine_name"])
        assertEquals("Barbaresco",decision.fields["subregion"])
        assertEquals(1,decision.changes.size)
        assertEquals("Barbaresco",GlinerFieldDecisionPolicy.apply(fields,listOf(hint),listOf("Baronet")).fields["wine_name"])
    }

    @Test fun exactGrapeReadByBothEnginesCanFillBlankOrRemoveProducerPrefix() {
        val lines=pair("PINOT NOIR",0.4f,0.45f)
        val blank=base+mapOf("wine_name" to "")
        assertEquals("PINOT NOIR",GlinerFieldDecisionPolicy.applyWithEvidence(blank,emptyList(),lines,grapes).fields["wine_name"])
        val prefixed=base+mapOf("producer" to "Decoy", "wine_name" to "Decoy Pinot Noir")
        assertEquals("PINOT NOIR",GlinerFieldDecisionPolicy.applyWithEvidence(prefixed,emptyList(),lines,grapes).fields["wine_name"])
        val longer=base+mapOf("wine_name" to "Special Pinot Noir")
        assertEquals("Special Pinot Noir",GlinerFieldDecisionPolicy.applyWithEvidence(longer,emptyList(),lines,grapes).fields["wine_name"])
    }

    @Test fun repeatedReadingsFromOneEngineOrSeparateLocationsCannotManufactureConsensus() {
        val blank=base+mapOf("wine_name" to "")
        val sameEngine=listOf(line("mlkit","MERLOT",0.2f,0.3f),line("mlkit","MERLOT",0.2f,0.3f))
        assertEquals("",GlinerFieldDecisionPolicy.applyWithEvidence(blank,emptyList(),sameEngine,grapes).fields["wine_name"])
        val differentLocations=listOf(line("mlkit","MERLOT",0.1f,0.2f),line("paddle-v6-medium","MERLOT",0.6f,0.7f))
        assertEquals("",GlinerFieldDecisionPolicy.applyWithEvidence(blank,emptyList(),differentLocations,grapes).fields["wine_name"])
        val competing=pair("MERLOT",0.2f,0.25f)+pair("PINOT NOIR",0.4f,0.45f)
        assertEquals("",GlinerFieldDecisionPolicy.applyWithEvidence(blank,emptyList(),competing,grapes).fields["wine_name"])
    }

    @Test fun joinsOneConsensusTitlePrefixToTheWholeExistingGrapeName() {
        val lines=pair("SIGNATURE SERIES",0.2f,0.22f)+pair("CABERNET",0.27f,0.30f)+pair("SAUVIGNON",0.29f,0.32f)
        val result=GlinerFieldDecisionPolicy.applyWithEvidence(base,emptyList(),lines,grapes)
        assertEquals("SIGNATURE SERIES CABERNET SAUVIGNON",result.fields["wine_name"])
        assertEquals("Example Estate",result.fields["producer"])
        assertEquals(1,result.changes.size)
    }

    @Test fun refusesLegalProducerOrStronglyOverlappingPrefixForWineCompletion() {
        val legal=pair("FAMILY ESTATE GROWN",0.2f,0.22f)+pair("CABERNET SAUVIGNON",0.23f,0.26f)
        assertEquals("Cabernet Sauvignon",GlinerFieldDecisionPolicy.applyWithEvidence(base,emptyList(),legal,grapes).fields["wine_name"])
        val producer=pair("EXAMPLE ESTATE",0.2f,0.22f)+pair("CABERNET SAUVIGNON",0.23f,0.26f)
        assertEquals("Cabernet Sauvignon",GlinerFieldDecisionPolicy.applyWithEvidence(base,emptyList(),producer,grapes).fields["wine_name"])
        val overlapping=pair("CAMBRAS",0.20f,0.34f)+pair("CABERNET SAUVIGNON",0.25f,0.40f)
        assertEquals("Cabernet Sauvignon",GlinerFieldDecisionPolicy.applyWithEvidence(base,emptyList(),overlapping,grapes).fields["wine_name"])
    }

    @Test fun repairsOnlyBlankOrLegalContaminatedProducerWithConsensusEstateName() {
        val lines=pair("CHATEAU DU",0.2f,0.23f)+pair("BOUSQUET",0.23f,0.27f)
        val legal=base+mapOf("producer" to "Mis en Bouteille au Chateau Cotes de Bourg")
        val result=GlinerFieldDecisionPolicy.applyWithEvidence(legal,emptyList(),lines,grapes)
        assertEquals("CHATEAU DU BOUSQUET",result.fields["producer"])
        assertEquals("Cabernet Sauvignon",result.fields["wine_name"])
        assertTrue(result.changes.single().startsWith("producer:"))
        assertEquals("Example Estate",GlinerFieldDecisionPolicy.applyWithEvidence(base,emptyList(),lines,grapes).fields["producer"])
        assertEquals("CHATEAU DU BOUSQUET",GlinerFieldDecisionPolicy.applyWithEvidence(base+mapOf("producer" to ""),emptyList(),lines,grapes).fields["producer"])
    }

    @Test fun stripsACompanyPrefixOnlyFromCorroboratedEstateEvidence() {
        val fields=base+mapOf("producer" to "Proprietaire a Saillans S.N.C. Chateau la Vieille Cure")
        val lines=pair("S.N.C. CHATEAU LA VIEILLE CURE",0.3f,0.35f)
        val result=GlinerFieldDecisionPolicy.applyWithEvidence(fields,emptyList(),lines,grapes)
        assertEquals("CHATEAU LA VIEILLE CURE",result.fields["producer"])
        assertEquals("Proprietaire a Saillans S.N.C. Chateau la Vieille Cure",
            GlinerFieldDecisionPolicy.applyWithEvidence(fields,emptyList(),lines.take(1),grapes).fields["producer"])
    }

    @Test fun invalidGeometryDoesNotContributeToConsensus() {
        val fields=base+mapOf("wine_name" to "")
        val invalid=listOf(line("mlkit","MERLOT",0.3f,0.4f),line("paddle-v6-medium","MERLOT",Float.NaN,0.4f))
        assertEquals("",GlinerFieldDecisionPolicy.applyWithEvidence(fields,emptyList(),invalid,grapes).fields["wine_name"])
    }

    @Test fun ambiguousEstateCandidatesAreNotSelected() {
        val fields=base+mapOf("producer" to "")
        val lines=pair("CHATEAU DU",0.1f,0.12f)+pair("BOUSQUET",0.12f,0.15f)+
            pair("DOMAINE DE",0.4f,0.42f)+pair("CASSAN",0.42f,0.45f)
        assertEquals("",GlinerFieldDecisionPolicy.applyWithEvidence(fields,emptyList(),lines,grapes).fields["producer"])
    }
}
