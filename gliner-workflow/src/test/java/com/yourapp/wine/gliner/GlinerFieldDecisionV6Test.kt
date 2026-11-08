package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Test

class GlinerFieldDecisionV6Test {
    private val pd=GlinerEngines.PADDLE
    private val ml=GlinerEngines.MLKIT
    private val base=mapOf("producer" to "Estate Example", "wine_name" to "Merlot", "country" to "Chile",
        "region" to "", "subregion" to "", "vintage" to "2019")
    private fun line(e:String,t:String,top:Float=0.2f,bottom:Float=0.25f)=GlinerEvidenceLine(e,t,0.2f,top,0.8f,bottom)
    private fun pair(t:String,top:Float=0.2f,bottom:Float=0.25f)=listOf(line(pd,t,top,bottom),line(ml,t,top,bottom))
    private fun run(fields:Map<String,String>,lines:List<GlinerEvidenceLine>) =
        GlinerFieldDecisionPolicy.applyWithEvidence(fields,emptyList(),lines,setOf("Merlot"),setOf("Reserva"),emptySet(),GlinerGeography.origins).fields

    @Test fun geographyNeedsCountryTwoEnginesAndMatchingLocation() {
        val result=run(base,pair("VALLE DE COLCHAGUA"))
        assertEquals("Central Valley",result["region"])
        assertEquals("Colchagua Valley",result["subregion"])
        assertEquals("",run(base,pair("VALLE DE COLCHAGUA").take(1))["region"])
        assertEquals("",run(base,pair("VALLE DE COLCHAGUA").mapIndexed { i,l -> if(i==1) l.copy(top=0.7f,bottom=0.75f) else l })["region"])
        assertEquals("",run(base+mapOf("country" to "Argentina"),pair("VALLE DE COLCHAGUA"))["region"])
        assertEquals("",run(base,pair("XVALLE DE COLCHAGUA"))["region"])
        assertEquals("",run(base,pair("BOTTLED VALLE DE COLCHAGUA"))["region"])
    }

    @Test fun geographyDoesNotOverrideIncompatibleExistingFieldsOrMultiplePlaces() {
        assertEquals("Other",run(base+mapOf("region" to "Other"),pair("VALLE DE COLCHAGUA"))["region"])
        assertEquals("Other",run(base+mapOf("subregion" to "Other"),pair("VALLE DE COLCHAGUA"))["subregion"])
        assertEquals("",run(base,pair("VALLE DE COLCHAGUA")+pair("VALLE DEL MAULE",0.4f,0.45f))["region"])
    }

    @Test fun datedRolesRequireIndependentMarkerAndYearEvidence() {
        val old=base+mapOf("vintage" to "1970")
        val founded=pair("ESTABLISHED",0.10f,0.14f)+pair("1970",0.15f,0.19f)+pair("2024",0.4f,0.45f)
        assertEquals("2024",run(old,founded)["vintage"])
        assertEquals("1970",run(old,founded.filter { it.engine==pd })["vintage"])
        assertEquals("1970",run(old,pair("ANNO 1970")+pair("2024",0.4f,0.45f))["vintage"])
        val marked=listOf(line(pd,"VIÑA 1970"),line(ml,"VIÑA 1970"))
        assertEquals("",run(old,marked)["vintage"])
        assertEquals("1970",run(old,listOf(line(pd,"VIÑA 1970"),line(ml,"1970")))["vintage"])
    }

    @Test fun dateMarkersDoNotEraseTrueOldVintageOrConfuseDifferentProducers() {
        assertEquals("1931",run(base+mapOf("vintage" to "1931"),pair("1931"))["vintage"])
        assertEquals("1931",run(base+mapOf("vintage" to "1931"),pair("ANNO 1931"))["vintage"])
        assertEquals("Stags' Leap",run(base+mapOf("producer" to "Stags' Leap"),emptyList())["producer"])
        assertEquals("Stag's Leap Wine Cellars",run(base+mapOf("producer" to "Stag's Leap Wine Cellars"),emptyList())["producer"])
    }
}
