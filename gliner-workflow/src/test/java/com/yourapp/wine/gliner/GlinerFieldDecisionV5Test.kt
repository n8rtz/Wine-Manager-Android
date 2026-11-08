package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Test

class GlinerFieldDecisionV5Test {
    private val pd=GlinerEngines.PADDLE
    private val ml=GlinerEngines.MLKIT
    private val grapes=setOf("Monastrell", "Cabernet Sauvignon", "Chardonnay", "Merlot")
    private val styles=setOf("Crianza", "Gran Reserva", "Shiraz Cabernet")
    private val base=mapOf("producer" to "Alturas de", "wine_name" to "Example Valley", "country" to "Spain",
        "region" to "Example Valley", "subregion" to "", "vintage" to "2019")
    private fun line(e:String,t:String,top:Float,bottom:Float,left:Float=0.25f,right:Float=0.75f)=
        GlinerEvidenceLine(e,t,left,top,right,bottom)
    private fun pair(t:String,top:Float,bottom:Float)=listOf(line(pd,t,top,bottom),line(ml,t,top,bottom))
    private fun run(fields:Map<String,String>,readings:List<GlinerEvidenceLine>,places:Set<String> = emptySet()) =
        GlinerFieldDecisionPolicy.applyWithEvidence(fields,emptyList(),readings,grapes,styles,
            places.map(GlinerDocument::normalize).toSet())

    @Test fun partialProducerAndStyleRequireIndependentOrderedEvidence() {
        val anchor=listOf(line(pd,"ALTURASde",0.20f,0.27f))
        val suffix=listOf(line(pd,"MIRAVAL",0.25f,0.35f),line(ml,"MIRAVA1",0.25f,0.35f))
        val style=pair("CRIANZA",0.40f,0.45f)
        val place=pair("EXAMPLE VALLEY",0.52f,0.57f)
        val input=anchor+suffix+style+place
        val result=run(base,input)
        assertEquals("Alturas de MIRAVAL",result.fields["producer"])
        assertEquals("CRIANZA",result.fields["wine_name"])
        assertEquals("Alturas de",run(base,input.filter { it.engine==pd }).fields["producer"])
        assertEquals("Alturas de",run(base,input,setOf("Miraval")).fields["producer"])
        assertEquals("Alturas de",run(base,input-anchor).fields["producer"])
        assertEquals("Alturas de",run(base,input.map { if(it.text=="MIRAVA1") it.copy(top=0.7f,bottom=0.8f) else it }).fields["producer"])
        assertEquals("Alturas de",run(base,anchor+pair("CRIANZA",0.25f,0.35f)+place).fields["producer"])
        assertEquals("Alturas de",run(base,anchor+suffix+pair("ALBORADA",0.29f,0.37f)+style+place).fields["producer"])
        assertEquals("Example Valley",run(base,anchor+suffix+style.filter { it.engine==pd }+place).fields["wine_name"])
        assertEquals("Existing Title",run(base+mapOf("wine_name" to "Existing Title"),input).fields["wine_name"])
        assertEquals("Example Valley",run(base,anchor+suffix+style.map { it.copy(top=0.62f,bottom=0.67f) }+place).fields["wine_name"])
    }

    @Test fun blankNameCanUseAnUnambiguousTwoEngineAdjacentGrapeBlend() {
        val fields=base+mapOf("producer" to "Domaine Example", "wine_name" to "")
        val blend=pair("MONASTRELL",0.40f,0.43f)+pair("CABERNET SAUVIGNON",0.44f,0.47f)
        assertEquals("MONASTRELL CABERNET SAUVIGNON",run(fields,blend).fields["wine_name"])
        assertEquals("",run(fields,blend.filter { it.engine==pd }).fields["wine_name"])
        assertEquals("",run(fields,blend+pair("MERLOT",0.48f,0.51f)).fields["wine_name"])
        assertEquals("",run(fields,pair("MONASTRELL",0.1f,0.13f)+pair("CABERNET SAUVIGNON",0.8f,0.83f)).fields["wine_name"])
        assertEquals("Existing Title",run(fields+mapOf("wine_name" to "Existing Title"),blend).fields["wine_name"])
    }

    @Test fun nearMatchingTitleAndVarietalRequireBothEnginesAndOneCandidate() {
        val fields=base+mapOf("producer" to "Producer Example", "wine_name" to "Chardonnay")
        val prefix=listOf(line(pd,"SILVER HILLS",0.20f,0.27f),line(ml,"SILVER HIL",0.20f,0.27f))
        val suffix=pair("CHARDONNAY",0.29f,0.32f)
        assertEquals("SILVER HILLS CHARDONNAY",run(fields,prefix+suffix).fields["wine_name"])
        assertEquals("Chardonnay",run(fields,prefix.filter { it.engine==pd }+suffix).fields["wine_name"])
        assertEquals("Chardonnay",run(fields,prefix+suffix.filter { it.engine==pd }).fields["wine_name"])
        assertEquals("Chardonnay",run(fields,prefix+suffix.map { it.copy(top=0.8f,bottom=0.83f) }).fields["wine_name"])
        assertEquals("Chardonnay",run(fields,pair("ESTATE GROWN",0.20f,0.27f)+suffix).fields["wine_name"])
        assertEquals("Existing Title",run(fields+mapOf("wine_name" to "Existing Title"),prefix+suffix).fields["wine_name"])
        val alternate=pair("GOLDEN HILLS",0.21f,0.27f)
        assertEquals("Chardonnay",run(fields,prefix+alternate+suffix).fields["wine_name"])
        // The observed line is a catalogued place, even if the base region is broader.
        val location=pair("FOOTHILL COAST",0.20f,0.27f)
        assertEquals("Chardonnay",run(fields,location+suffix,setOf("Foothill Coast")).fields["wine_name"])
        val damaged=listOf(line(pd,"FOOTHILL COAST",0.20f,0.27f),line(ml,"FOOTHILL COAS",0.20f,0.27f))
        assertEquals("Chardonnay",run(fields,damaged+suffix,setOf("Foothill Coast")).fields["wine_name"])
    }
}
