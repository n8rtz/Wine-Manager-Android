package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Test

class GlinerFieldDecisionV7Test {
    private val pd=GlinerEngines.PADDLE
    private val ml=GlinerEngines.MLKIT
    private val grapes=setOf("Merlot", "Riesling", "Cabernet Sauvignon", "Shiraz", "Lemberger")
    private val base=mapOf("producer" to "Estate Example", "wine_name" to "", "country" to "",
        "region" to "", "subregion" to "", "vintage" to "")
    private fun l(e:String,t:String,top:Float,bottom:Float,left:Float=0.2f,right:Float=0.8f)=
        GlinerEvidenceLine(e,t,left,top,right,bottom)
    private fun pair(t:String,top:Float,bottom:Float)=listOf(l(pd,t,top,bottom),l(ml,t,top,bottom))
    private fun decide(f:Map<String,String>,r:List<GlinerEvidenceLine>,h:List<GlinerFieldHint> = emptyList()) =
        GlinerFieldDecisionPolicy.applyWithEvidence(f,h,r,grapes,setOf("Vinho Verde", "Rosé"),emptySet(),GlinerGeography.origins).fields

    @Test fun wineryPrefixAndExplicitConnectorNeedBothEnginesAndCorrectLayout() {
        val name=pair("Oaklight",0.2f,0.27f)
        val link=pair("& ROSES",0.28f,0.32f)
        assertEquals("Oaklight & ROSES",decide(base+mapOf("producer" to "Oaklight"),name+link)["producer"])
        assertEquals("Oaklight",decide(base+mapOf("producer" to "Oaklight"),name+link.take(1))["producer"])
        assertEquals("Oaklight",decide(base+mapOf("producer" to "Oaklight"),name+pair("& ROSES",0.8f,0.84f))["producer"])
        val descriptor=pair("WEINGUT",0.10f,0.14f)+pair("ALDINGER",0.15f,0.2f)
        assertEquals("WEINGUT ALDINGER",decide(base+mapOf("producer" to "Aldinger"),descriptor)["producer"])
    }

    @Test fun legalParagraphRecoveryRequiresTwoPartNameAndSeparateCompanyRole() {
        val f=base+mapOf("producer" to "Non Disperdere 12 Cascina")
        val first=listOf(l(pd,"Cascina",0.2f,0.28f,0.1f,0.4f),l(ml,"Cascina",0.2f,0.28f,0.1f,0.4f))
        val second=listOf(l(pd,"Bellaria",0.2f,0.28f,0.5f,0.8f),l(ml,"Bellaria",0.2f,0.28f,0.5f,0.8f))
        val role=listOf(l(ml,"Azienda Agricola Cascina Bellaria",0.5f,0.55f))
        assertEquals("Cascina Bellaria",decide(f,first+second+role)["producer"])
        assertEquals(f["producer"],decide(f,first+second)["producer"])
        assertEquals(f["producer"],decide(f,first+second.take(1)+role)["producer"])
        assertEquals("Other Winery",decide(f+mapOf("producer" to "Other Winery"),first+second+role)["producer"])
    }

    @Test fun producerPrefixRemovalNeedsAnIndependentlyReadMakerAndPrintedGrape() {
        val f=base+mapOf("producer" to "North Estate", "wine_name" to "North Estate Merlot")
        val maker=pair("North Estate",0.2f,0.25f)
        val grape=listOf(l(pd,"Merlot",0.35f,0.39f))
        assertEquals("Merlot",decide(f,maker+grape)["wine_name"])
        assertEquals("North Estate Merlot",decide(f,maker.take(1)+grape)["wine_name"])
        assertEquals("North Estate Merlot",decide(f,maker+listOf(l(pd,"Merlot",0.8f,0.84f)))["wine_name"])
        assertEquals("North Estate Merlot",decide(f,maker+grape+pair("Shiraz",0.4f,0.44f))["wine_name"])
    }

    @Test fun blendRepairNeedsTwoMakerLinesTwoGrapesAndModelEvidence() {
        val f=base+mapOf("producer" to "Oxford Landing", "wine_name" to "Landing Cabernet Sauvignon")
        val rows=pair("OXFORD",0.1f,0.18f)+pair("LANDING",0.19f,0.27f)+
            pair("CABERNET SAUVIGNON",0.30f,0.34f)+pair("SHIRAZ",0.35f,0.39f)
        val hint=listOf(GlinerFieldHint(GlinerLane.WINE_NAME,"SHIRAZ",0.6f))
        assertEquals("CABERNET SAUVIGNON SHIRAZ",decide(f,rows,hint)["wine_name"])
        assertEquals("Landing Cabernet Sauvignon",decide(f,rows)["wine_name"])
        assertEquals("Landing Cabernet Sauvignon",decide(f,rows.filterNot { it.text=="SHIRAZ" && it.engine==ml },hint)["wine_name"])
    }

    @Test fun datedVarietalRequiresCorroborationAndSameSelectedVintage() {
        val f=base+mapOf("wine_name" to "Private Reserve", "vintage" to "2019")
        val title=pair("PRIVATE RESERVE",0.2f,0.24f)
        val grape=pair("RIESLING 2019",0.27f,0.31f)
        assertEquals("PRIVATE RESERVE RIESLING",decide(f,title+grape)["wine_name"])
        assertEquals("Private Reserve",decide(f,title+grape.take(1))["wine_name"])
        assertEquals("Private Reserve",decide(f+mapOf("vintage" to "2020"),title+grape)["wine_name"])
        assertEquals("Private Reserve",decide(f,title+pair("UNKNOWN 2019",0.27f,0.31f))["wine_name"])
    }

    @Test fun articlesMustBeObservedAndAmbiguityMustAbstain() {
        val f=base+mapOf("producer" to "Estate Example", "wine_name" to "Merlot")
        val input=pair("LOS",0.1f,0.13f)+pair("CARDOS",0.14f,0.20f)+
            pair("Estate Example",0.21f,0.25f)+pair("MERLOT",0.28f,0.32f)
        assertEquals("LOS CARDOS MERLOT",decide(f,input)["wine_name"])
        assertEquals("CARDOS MERLOT",decide(f,input.filterNot { it.text=="LOS" && it.engine==ml })["wine_name"])
    }

    @Test fun orderedDescriptorsRequireAllPiecesAndCorrectBaseTerms() {
        val f=base+mapOf("wine_name" to "Trocken Reserve")
        val input=pair("MERLOT",0.2f,0.24f)+pair("RESERVE",0.25f,0.29f)+pair("TROCKEN",0.30f,0.34f)
        assertEquals("MERLOT RESERVE TROCKEN",decide(f,input)["wine_name"])
        assertEquals("Trocken Reserve",decide(f,input.filterNot { it.text=="RESERVE" && it.engine==ml })["wine_name"])
        assertEquals("Special Reserve",decide(f+mapOf("wine_name" to "Special Reserve"),input)["wine_name"])
    }

    @Test fun blankNameRequiresDistinctiveTitleAndKnownGrapeAndStyle() {
        val rows=pair("FELIX",0.2f,0.25f)+pair("LEMBERGER TROCKEN",0.24f,0.30f)
        assertEquals("FELIX LEMBERGER TROCKEN",decide(base,rows)["wine_name"])
        assertEquals("",decide(base,rows.filterNot { it.text=="FELIX" && it.engine==ml })["wine_name"])
        assertEquals("",decide(base,pair("FELIX",0.2f,0.25f)+pair("UNKNOWN TROCKEN",0.26f,0.30f))["wine_name"])
    }

    @Test fun explicitOriginNeedsTwoEnginesAndDoesNotUseAddress() {
        assertEquals("Italy",decide(base,pair("PRODUCT OF ITALY",0.2f,0.24f))["country"])
        assertEquals("",decide(base,listOf(l(pd,"PRODUCT OF ITALY",0.2f,0.24f)))["country"])
        assertEquals("",decide(base,pair("WINERY SIENA ITALY",0.2f,0.24f))["country"])
        assertEquals("Spain",decide(base+mapOf("country" to "Spain"),pair("PRODUCT OF ITALY",0.2f,0.24f))["country"])
        assertEquals("",decide(base+mapOf("region" to "Bordeaux"),pair("PRODUCT OF ITALY",0.2f,0.24f))["country"])
        assertEquals("",decide(base,pair("PRODUCT OF ITALY",0.2f,0.24f)+pair("PRODUCT OF FRANCE",0.4f,0.44f))["country"])
    }

    @Test fun regionalIndicationNeedsMatchingRegionAndDesignationWithoutConflicts() {
        val cue=pair("INDICAZIONE GEOGRAFICA PROTETTA",0.2f,0.24f)
        val region=pair("PUGLIA",0.25f,0.29f)
        assertEquals("Italy",decide(base,cue+region)["country"])
        assertEquals("Puglia",decide(base,cue+region)["region"])
        assertEquals("",decide(base,region)["country"])
        assertEquals("",decide(base,cue+region.take(1))["country"])
        assertEquals("Spain",decide(base+mapOf("country" to "Spain"),cue+region)["country"])
        assertEquals("",decide(base+mapOf("subregion" to "Other"),cue+region)["country"])
        assertEquals("",decide(base,cue+region+pair("VENETO",0.26f,0.30f))["region"])
        assertEquals("",decide(base,cue+pair("BOTTLED PUGLIA",0.25f,0.29f))["region"])
    }

    @Test fun specificAustrianRegionOnlyReplacesCountryNameInRegionSlot() {
        val f=base+mapOf("country" to "Austria", "region" to "Osterreich", "wine_name" to "Merlot")
        val rows=pair("MERLOT",0.2f,0.24f)+pair("NIEDEROSTERREICH",0.25f,0.29f)
        assertEquals("Niederösterreich",decide(f,rows)["region"])
        assertEquals("Osterreich",decide(f,rows.filterNot { it.text=="NIEDEROSTERREICH" && it.engine==ml })["region"])
        assertEquals("Other",decide(f+mapOf("region" to "Other"),rows)["region"])
    }

    @Test fun producerSubstringIsNotAnAdjacentTitlePrefix() {
        val f=base+mapOf("producer" to "Domaine Faiveley", "wine_name" to "Special Reserve")
        val rows=pair("FAIVELEY",0.2f,0.24f)+pair("SPECIAL RESERVE",0.25f,0.29f)
        assertEquals("Special Reserve",decide(f,rows)["wine_name"])
    }
}
