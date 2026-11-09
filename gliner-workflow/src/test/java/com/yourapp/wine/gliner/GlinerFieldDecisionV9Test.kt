package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Test

class GlinerFieldDecisionV9Test {
    private val pd=GlinerEngines.PADDLE
    private val ml=GlinerEngines.MLKIT
    private val base=mapOf("producer" to "Laurel", "wine_name" to "", "country" to "France",
        "region" to "Burgundy", "subregion" to "", "vintage" to "")
    private fun line(e:String,t:String,top:Float,bottom:Float)=GlinerEvidenceLine(e,t,0.2f,top,0.8f,bottom)
    private fun pair(t:String,top:Float,bottom:Float)=listOf(line(pd,t,top,bottom),line(ml,t,top,bottom))
    private fun decide(fields:Map<String,String>,rows:List<GlinerEvidenceLine>,
        us:Map<String,List<GlinerOrigin>> = emptyMap()) =
        GlinerFieldDecisionPolicy.applyWithEvidence(fields,emptyList(),rows,
            setOf("Merlot", "Chardonnay", "Zinfandel"),setOf("Porto", "Vin Santo"),emptySet(),
            emptyList(),emptyMap(),us).fields

    @Test fun wineryDesignatorRequiresBothEnginesAndAnUnambiguousAdjacentSuffix() {
        val name=pair("LAUREL",0.1f,0.14f)
        val suffix=listOf(line(pd,"FAMILYVINEYARDS",0.15f,0.19f),line(ml,"FAMILY VINEYARDS",0.15f,0.19f))
        assertEquals("LAUREL Family Vineyards",decide(base,name+suffix)["producer"])
        assertEquals("Laurel",decide(base,name+suffix.take(1))["producer"])
        assertEquals("Laurel",decide(base,name+pair("FAMILY VINEYARDS",0.5f,0.54f))["producer"])
        assertEquals("Laurel",decide(base,name+suffix+pair("WINE CELLARS",0.15f,0.19f))["producer"])
        assertEquals("Laurel",decide(base,name+pair("BEAUTIFUL VINEYARDS",0.15f,0.19f))["producer"])
    }

    @Test fun splitEstateFromTitleOnlyWithAnOrderedWineMethod() {
        val fields=base+mapOf("producer" to "Chateau Belle Rose Esprit du Vent")
        val first=pair("CHATEAU",0.1f,0.14f)
        val maker=pair("BELLE ROSE",0.15f,0.19f)
        val title=pair("ESPRIT DU VENT",0.20f,0.24f)
        val method=pair("METHODE TRADITIONNELLE",0.25f,0.29f)
        val result=decide(fields,first+maker+title+method)
        assertEquals("CHATEAU BELLE ROSE",result["producer"])
        assertEquals("ESPRIT DU VENT",result["wine_name"])
        assertEquals(fields["producer"],decide(fields,first+maker+title)["producer"])
        assertEquals(fields["producer"],decide(fields,first+maker+title+method.take(1))["producer"])
        assertEquals(fields["producer"],decide(fields,first+maker+title+pair("METHODE TRADITIONNELLE",0.6f,0.64f))["producer"])
        assertEquals(fields["producer"],decide(fields,first+maker+pair("ESPRIT DU SOLEIL",0.2f,0.24f)+method)["producer"])
        assertEquals(fields["producer"],decide(fields+mapOf("wine_name" to "Merlot"),first+maker+title+method)["producer"])
    }

    @Test fun usAvaNeedsUsContextAndAUniqueStandalonePlaceBelowSelectedVarietal() {
        val o=GlinerOrigin("United States","California","Mira",listOf("Mira"))
        val index=GlinerGeography.indexOrigins(listOf(o),minKeyLength=4)
        val f=base+mapOf("country" to "United States", "region" to "", "wine_name" to "Zinfandel")
        val grape=pair("ZINFANDEL",0.1f,0.14f)
        val place=listOf(line(pd,"MIR A",0.15f,0.19f),line(ml,"M I R A",0.15f,0.19f))
        val good=decide(f,grape+place,index)
        assertEquals("California",good["country"])
        assertEquals("Mira",good["region"])
        assertEquals("United States",decide(f,grape+place.take(1),index)["country"])
        assertEquals("Italy",decide(f+mapOf("country" to "Italy"),grape+place,index)["country"])
        assertEquals("Other",decide(f+mapOf("region" to "Other"),grape+place,index)["region"])
        assertEquals("United States",decide(f,grape+pair("MIRA",0.4f,0.44f),index)["country"])
        assertEquals("United States",decide(f,grape+pair("BOTTLED IN MIRA",0.15f,0.19f),index)["country"])
        assertEquals("United States",decide(f+mapOf("producer" to "Mira Winery"),grape+place,index)["country"])
        val ambiguous=GlinerGeography.indexOrigins(listOf(o,GlinerOrigin("United States","Oregon","Mira",listOf("Mira"))),4)
        assertEquals("United States",decide(f,grape+place,ambiguous)["country"])
    }

    @Test fun estateTitleAndRegionalIndicationRequireSeparateRolesAndNoPrintedConflict() {
        val f=base+mapOf("producer" to "Solare", "country" to "Spain", "region" to "Valencia", "subregion" to "Castello")
        val region=pair("CHARDONNAY DI TOSCANA",0.1f,0.14f)
        val igt=pair("INDICAZIONE GEOGRAFICA TIPICA",0.15f,0.18f)
        val title=pair("SOLARE",0.30f,0.34f)
        val maker=pair("CASTELLO DEL SOLE",0.45f,0.49f)
        val cue=pair("PRODOTTO E IMBOTTIGLIATO DA",0.51f,0.54f)
        val company=pair("CASTELLO DEL SOLE SOCIETA AGRICOLA",0.55f,0.59f)
        val all=region+igt+title+maker+cue+company
        val good=decide(f,all)
        assertEquals("CASTELLO DEL SOLE",good["producer"])
        assertEquals("SOLARE",good["wine_name"])
        assertEquals("Italy",good["country"])
        assertEquals("Tuscany",good["region"])
        assertEquals("",good["subregion"])
        assertEquals("Solare",decide(f,region+igt+title+maker+company)["producer"])
        assertEquals("Solare",decide(f,region+igt+title+maker+cue+company.take(1))["producer"])
        assertEquals("Spain",decide(f,all+pair("PRODUCT OF SPAIN",0.7f,0.74f))["country"])
        assertEquals("Spain",decide(f,all+pair("VINO ESPAÑOL",0.7f,0.74f))["country"])
        assertEquals("United States",decide(f+mapOf("country" to "United States"),
            all+pair("PRODUCT OF U.S.A.",0.7f,0.74f))["country"])
        assertEquals("Valencia",decide(f,all+pair("VALENCIA",0.7f,0.74f))["region"])
        assertEquals("Castello",decide(f,all+pair("CASTELLO",0.7f,0.74f))["subregion"])
        assertEquals("Spain",decide(f,region+title+maker+cue+company)["country"])
        assertEquals("Spain",decide(f,region.take(1)+igt+title+maker+cue+company)["country"])
        assertEquals("Other",decide(f+mapOf("subregion" to "Other"),all)["subregion"])
    }

    @Test fun docPlaceAndVarietalNeedTheSelectedSubregionAndIndependentLegalCue() {
        val f=base+mapOf("country" to "Italy", "region" to "Piedmont", "subregion" to "Colline", "wine_name" to "Merlot")
        val place=pair("COLLINE",0.1f,0.14f)
        val cue=pair("DENOMINAZIONE DI ORIGINE CONTROLLATA",0.15f,0.19f)
        val grape=pair("MERLOT",0.20f,0.24f)
        assertEquals("COLLINE MERLOT",decide(f,place+cue+grape)["wine_name"])
        assertEquals("Merlot",decide(f,place+cue.take(1)+grape)["wine_name"])
        assertEquals("Merlot",decide(f,place+cue+grape.take(1))["wine_name"])
        assertEquals("Merlot",decide(f+mapOf("subregion" to "Other"),place+cue+grape)["wine_name"])
        assertEquals("Merlot",decide(f+mapOf("country" to "France"),place+cue+grape)["wine_name"])
        assertEquals("Merlot",decide(f,place+cue+grape+pair("CHARDONNAY",0.25f,0.29f))["wine_name"])
        assertEquals("Merlot",decide(f,place+pair("INDICAZIONE GEOGRAFICA",0.15f,0.19f)+grape)["wine_name"])
    }

    @Test fun normalTitlesAreNotMisreadAsStyleAndGrapeOriginIsNotAPrefix() {
        val grapeOrigin=pair("CHARDONNAY DI TOSCANA",0.1f,0.14f)
        val ordinary=pair("SOLARE",0.15f,0.19f)
        val style=pair("RESERVA TAWNY",0.15f,0.19f)
        assertEquals("Solare",decide(base+mapOf("wine_name" to "Solare"),grapeOrigin+ordinary)["wine_name"])
        assertEquals("Reserva Tawny",decide(base+mapOf("wine_name" to "Reserva Tawny"),grapeOrigin+style)["wine_name"])
        assertEquals("DONNA NOTTE RESERVA TAWNY",decide(base+mapOf("wine_name" to "Reserva Tawny"),
            pair("DONNA NOTTE",0.1f,0.14f)+style)["wine_name"])
    }
}
