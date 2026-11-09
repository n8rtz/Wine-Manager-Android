package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Test

class GlinerFieldDecisionV10Test {
    private val pd=GlinerEngines.PADDLE
    private val ml=GlinerEngines.MLKIT
    private fun line(e:String,t:String,l:Float,top:Float,r:Float,bottom:Float)=
        GlinerEvidenceLine(e,t,l,top,r,bottom)
    private fun pair(t:String,top:Float,bottom:Float)=listOf(
        line(pd,t,0.2f,top,0.8f,bottom),line(ml,t,0.2f,top,0.8f,bottom))
    private fun decide(fields:Map<String,String>,rows:List<GlinerEvidenceLine>) =
        GlinerFieldDecisionPolicy.applyWithEvidence(fields,emptyList(),rows,
            setOf("Merlot","Syrah","Grenache"),setOf("Porto"),emptySet(),
            emptyList(),emptyMap(),emptyMap()).fields
    private val base=mapOf("producer" to "MAISON LUMIERE", "wine_name" to "Grenache - Syrah",
        "country" to "France", "region" to "Languedoc", "subregion" to "", "vintage" to "")

    @Test fun adjacentFragmentsNeedIndependentReconstructionAndOrderedRoles() {
        val maker=pair("MAISON LUMIERE",0.10f,0.12f)
        val first=line(ml,"DOMAINE.",0.22f,0.14f,0.43f,0.18f)
        val second=line(ml,"des etoiles",0.44f,0.14f,0.76f,0.18f)
        val other=listOf(line(pd,"D",0.22f,0.14f,0.25f,0.18f),
            line(pd,"OMAINE des etoiles",0.26f,0.14f,0.76f,0.18f))
        val blend=pair("GRENACHE - SYRAH",0.20f,0.22f)
        val good=maker+listOf(first,second)+other+blend
        assertEquals("DOMAINE des etoiles",decide(base,good)["wine_name"])
        assertEquals(base["wine_name"],decide(base,maker+listOf(first,second)+blend)["wine_name"])
        assertEquals(base["wine_name"],decide(base,maker+listOf(first,second)+other.take(1)+blend)["wine_name"])
        assertEquals(base["wine_name"],decide(base,good.filterNot { it.engine==ml && it.text=="MAISON LUMIERE" })["wine_name"])
        assertEquals(base["wine_name"],decide(base,good.filterNot { it.engine==ml && it.text=="GRENACHE - SYRAH" })["wine_name"])
        assertEquals(base["wine_name"],decide(base,maker+listOf(first,second.copy(left=0.64f))+other+blend)["wine_name"])
        assertEquals("Merlot",decide(base+mapOf("wine_name" to "Merlot"),good)["wine_name"])
        assertEquals(base["wine_name"],decide(base,maker+listOf(first,second)+
            listOf(line(pd,"DIFFERENT TITLE",0.22f,0.14f,0.76f,0.18f))+blend)["wine_name"])
    }

    @Test fun articleLedTitleNeedsLegalAndBottlingRolesAndNoOverlappingSecondReading() {
        val f=base+mapOf("producer" to "DOMAINE AUREL", "wine_name" to "Sancerre", "subregion" to "Sancerre")
        val appellation=pair("SANCERRE",0.10f,0.13f)
        val cue=pair("APPELLATION SANCERRE CONTROLEE",0.14f,0.16f)
        val title=line(pd,"LES ETOILES",0.2f,0.17f,0.8f,0.21f)
        val bottling=pair("MIS EN BOUTEILLE",0.23f,0.25f)
        val maker=pair("DOMAINE AUREL",0.26f,0.29f)
        val evidence=appellation+cue+listOf(title)+bottling+maker
        assertEquals("LES ETOILES",decide(f,evidence)["wine_name"])
        assertEquals("Sancerre",decide(f,evidence+listOf(line(ml,"LES ROCHES",0.2f,0.17f,0.8f,0.21f)))["wine_name"])
        assertEquals("Sancerre",decide(f,evidence.filterNot { it.engine==ml && it.text=="MIS EN BOUTEILLE" })["wine_name"])
        assertEquals("Sancerre",decide(f,evidence.filterNot { it.engine==ml && it.text=="APPELLATION SANCERRE CONTROLEE" })["wine_name"])
        assertEquals("Sancerre",decide(f,evidence.map { if(it===title) title.copy(text="LES RESERVE") else it })["wine_name"])
        assertEquals("Sancerre",decide(f+mapOf("country" to "Spain"),evidence)["wine_name"])
    }

    @Test fun aNamedSiteRequiresExactReadLegalContextMakerAndNoCompetingKnownSite() {
        val f=base+mapOf("producer" to "TENUTA ALBA", "wine_name" to "Barolo", "country" to "Italy",
            "region" to "Piedmont", "subregion" to "Barolo")
        val app=pair("BAROLO",0.10f,0.14f)
        val legal1=pair("DENOMINAZIONE DI ORIGINE",0.15f,0.19f)
        val legal2=pair("CONTROLLATA E GARANTITA",0.20f,0.24f)
        val site=line(pd,"CEREQUIO",0.2f,0.25f,0.8f,0.29f)
        val maker=pair("TENUTA ALBA",0.32f,0.36f)
        val evidence=app+legal1+legal2+listOf(site)+maker
        assertEquals("CEREQUIO",decide(f,evidence)["wine_name"])
        assertEquals("CEREQUIO",decide(f,evidence+listOf(line(ml,"XZYPUI",0.2f,0.25f,0.8f,0.29f)))["wine_name"])
        assertEquals("Barolo",decide(f,evidence+listOf(line(ml,"BRUNATE",0.2f,0.25f,0.8f,0.29f)))["wine_name"])
        assertEquals("Barolo",decide(f,evidence+listOf(line(ml,"BRUNATF",0.2f,0.25f,0.8f,0.29f)))["wine_name"])
        assertEquals("Barolo",decide(f,evidence+listOf(line(ml,"LA ROSE DORÉE",0.2f,0.25f,0.8f,0.29f)))["wine_name"])
        assertEquals("Barolo",decide(f,evidence.filterNot { it.engine==ml && it.text=="CONTROLLATA E GARANTITA" })["wine_name"])
        assertEquals("Barolo",decide(f,evidence.filterNot { it.engine==ml && it.text=="TENUTA ALBA" })["wine_name"])
        assertEquals("Barolo",decide(f,evidence.map { if(it===site) site.copy(text="CEREQU1O") else it })["wine_name"])
        assertEquals("Barolo",decide(f+mapOf("country" to "France"),evidence)["wine_name"])
        assertEquals("Barolo",decide(f+mapOf("subregion" to "Other"),evidence)["wine_name"])
    }
}
