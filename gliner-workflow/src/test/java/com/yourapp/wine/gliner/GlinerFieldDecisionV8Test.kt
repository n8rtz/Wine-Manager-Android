package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Test

class GlinerFieldDecisionV8Test {
    private val pd=GlinerEngines.PADDLE
    private val ml=GlinerEngines.MLKIT
    private val base=mapOf("producer" to "Domaine Solaire", "wine_name" to "", "country" to "France",
        "region" to "Burgundy", "subregion" to "", "vintage" to "")
    private fun line(e:String,t:String,top:Float,bottom:Float)=GlinerEvidenceLine(e,t,0.2f,top,0.8f,bottom)
    private fun pair(t:String,top:Float,bottom:Float)=listOf(line(pd,t,top,bottom),line(ml,t,top,bottom))
    private fun decide(fields:Map<String,String>,rows:List<GlinerEvidenceLine>,
        hints:List<GlinerFieldHint> = emptyList(), origins:List<GlinerOrigin> = emptyList(),
        index:Map<String,List<GlinerOrigin>> = emptyMap()) =
        GlinerFieldDecisionPolicy.applyWithEvidence(fields,hints,rows,setOf("Merlot", "Chardonnay"),
            setOf("Moscato d'Asti", "Prosecco", "Porto", "Vin Santo"),emptySet(),origins,index).fields

    @Test fun classifiedAppellationRequiresAFullOrderedCorroboratedStructure() {
        val f=base+mapOf("wine_name" to "Arbois", "subregion" to "Arbois")
        val location=pair("ARBOIS",0.1f,0.18f)
        val cue=pair("APPELLATION ARBOIS PREMIER CRU CONTROLEE",0.19f,0.23f)
        val title=pair("CLOS DES LUMIERES",0.24f,0.30f)
        val classification=pair("PREMIER CRU",0.31f,0.35f)
        assertEquals("ARBOIS CLOS DES LUMIERES PREMIER CRU",decide(f,location+cue+title+classification)["wine_name"])
        assertEquals("Arbois",decide(f,location+cue.take(1)+title+classification)["wine_name"])
        assertEquals("Arbois",decide(f,location+cue+title+classification.take(1))["wine_name"])
        assertEquals("Arbois",decide(f,location+cue+title+pair("CLOS DES LILAS",0.25f,0.30f)+classification)["wine_name"])
        assertEquals("Arbois",decide(f,location+cue+pair("MIS EN BOUTEILLE",0.24f,0.30f)+classification)["wine_name"])
    }

    @Test fun abbreviatedClassificationNeedsQuotedTitleAppellationCueAndModelHint() {
        val f=base+mapOf("wine_name" to "1er Cru \"Les Aubes\"", "subregion" to "Beaune")
        val location=listOf(line(pd,"BEAUNE",0.1f,0.18f))
        val title=pair("1ER CRU \"LES AUBES\"",0.19f,0.23f)
        val cue=listOf(line(ml,"APPELLATION BEAUNE 1ER CRU CONTROLEE",0.22f,0.26f))
        val hint=listOf(GlinerFieldHint(GlinerLane.APPELLATION,"BEAUNE",0.75f))
        assertEquals("BEAUNE LES AUBES Premier Cru",decide(f,location+title+cue,hint)["wine_name"])
        assertEquals(f["wine_name"],decide(f,location+title+cue)["wine_name"])
        assertEquals(f["wine_name"],decide(f,location+title,hint)["wine_name"])
        assertEquals(f["wine_name"],decide(f,location+title.take(1)+cue,hint)["wine_name"])
        assertEquals(f["wine_name"],decide(f,location+title+cue,listOf(GlinerFieldHint(GlinerLane.APPELLATION,"OTHER",0.8f)))["wine_name"])
    }

    @Test fun portTypeIsNotAFreeFormServingInstruction() {
        val f=base+mapOf("country" to "Portugal", "region" to "Douro", "subregion" to "Porto", "wine_name" to "Porto")
        val port=pair("PORTO",0.31f,0.35f)
        val ruby=pair("FINE RUBY",0.26f,0.30f)
        assertEquals("FINE RUBY PORTO",decide(f,ruby+port)["wine_name"])
        assertEquals("Porto",decide(f,pair("SERVE CHILLED",0.26f,0.30f)+port)["wine_name"])
        assertEquals("Porto",decide(f,ruby.take(1)+port)["wine_name"])
        assertEquals("Porto",decide(f,ruby+pair("FINE TAWNY",0.26f,0.30f)+port)["wine_name"])
    }

    @Test fun internalStyleNeedsExactOuterNameAndTwoEngineEvidence() {
        val f=base+mapOf("wine_name" to "Moonrise del Chianti", "producer" to "Domaine Solaire")
        val first=pair("MOONRISE",0.1f,0.14f)
        val style=pair("VIN SANTO",0.15f,0.19f)
        val last=pair("DEL CHIANTI",0.2f,0.24f)
        fun run(rows:List<GlinerEvidenceLine>)=GlinerFieldDecisionPolicy.applyWithEvidence(f,emptyList(),rows,
            emptySet(),setOf("Vin Santo"),emptySet(),emptyList()).fields["wine_name"]
        assertEquals("MOONRISE VIN SANTO DEL CHIANTI",run(first+style+last))
        assertEquals("Moonrise del Chianti",run(first+style.take(1)+last))
        assertEquals("Moonrise del Chianti",run(first+pair("ORGANIC WINE",0.15f,0.19f)+last))
        assertEquals("Moonrise del Chianti",run(first+style+pair("DEL OTHER",0.2f,0.24f)))
        assertEquals("Moonrise del Chianti",run(first+pair("VIN SANTO",0.5f,0.54f)+last))
    }

    @Test fun compoundAppellationMustBePrintedAndNotShortened() {
        val f=base+mapOf("country" to "Italy", "region" to "Veneto", "subregion" to "Asolo Prosecco Superiore")
        val name=pair("ASOLO",0.20f,0.25f)
        val style=pair("PROSECCO",0.26f,0.30f)
        val quality=pair("SUPERIORE D.O.C.G.",0.31f,0.35f)
        val result=decide(f,name+style+quality)
        assertEquals("ASOLO PROSECCO SUPERIORE",result["wine_name"])
        assertEquals("Asolo Prosecco Superiore",result["subregion"])
        assertEquals("",decide(f,name+style+quality.take(1))["wine_name"])
        assertEquals("",decide(f+mapOf("subregion" to "Other Prosecco Superiore"),name+style+quality)["wine_name"])
    }

    @Test fun makerTitleSwapNeedsTheHintIndependentNameAndBottlingRole() {
        val f=base+mapOf("producer" to "Solaris", "wine_name" to "Moscato d'Asti", "country" to "Italy",
            "region" to "Piedmont", "subregion" to "Moscato d'Asti")
        val maker=pair("MATTEO",0.1f,0.14f)+pair("SERENO",0.15f,0.2f)
        val title=pair("SOLARIS",0.3f,0.38f)
        val style=pair("MOSCATO D'ASTI",0.4f,0.44f)
        val cue=pair("PRODOTTO E IMBOIGLIATO",0.6f,0.64f)
        val company=pair("MATTEO SERENO ITALIA",0.65f,0.69f)
        val all=maker+title+style+cue+company
        val hint=listOf(GlinerFieldHint(GlinerLane.PRODUCER,"MATTEO SERENO",0.72f))
        val result=decide(f,all,hint)
        assertEquals("MATTEO SERENO",result["producer"])
        assertEquals("SOLARIS MOSCATO D'ASTI",result["wine_name"])
        assertEquals("Solaris",decide(f,all)["producer"])
        assertEquals("Solaris",decide(f,maker+title+style+company,hint)["producer"])
        assertEquals("Solaris",decide(f,all.filterNot { it.text=="SERENO" && it.engine==ml },hint)["producer"])
        assertEquals("Solaris",decide(f,all,listOf(GlinerFieldHint(GlinerLane.PRODUCER,"MATTEO SERENO",0.6f)))["producer"])
    }

    @Test fun anchoredFuzzyHierarchyRequiresCountrySelectedChildAndTwoEngines() {
        val origin=GlinerOrigin("New Country","Great Valley","Sparrow Hills",listOf("Sparrow Hills"))
        val f=base+mapOf("country" to "New Country", "region" to "Sparrow Hills", "subregion" to "")
        val rows=listOf(line(pd,"Sparow Hills",0.2f,0.25f),line(ml,"Sparrow Hill",0.2f,0.25f))
        val result=decide(f,rows,origins=listOf(origin))
        assertEquals("Great Valley",result["region"])
        assertEquals("Sparrow Hills",result["subregion"])
        assertEquals("Sparrow Hills",decide(f,rows.take(1),origins=listOf(origin))["region"])
        assertEquals("",decide(f+mapOf("region" to ""),rows,origins=listOf(origin))["subregion"])
        assertEquals("",decide(f+mapOf("country" to "Other"),rows,origins=listOf(origin))["subregion"])
        assertEquals("Other",decide(f+mapOf("subregion" to "Other"),rows,origins=listOf(origin))["subregion"])
        val separate=listOf(rows[0],rows[1].copy(top=0.7f,bottom=0.75f))
        assertEquals("Sparrow Hills",decide(f,separate,origins=listOf(origin))["region"])
    }

    @Test fun exactCataloguedDesignationCanDisambiguateAStyleButNotVisibleConflicts() {
        val origin=GlinerOrigin("Italy","Tuscany","Valtoro",listOf("Valtoro"))
        val index=GlinerGeography.indexOrigins(listOf(origin))
        val f=base+mapOf("country" to "Greece", "region" to "Aegean Islands", "subregion" to "Vin Santo")
        val style=pair("VIN SANTO",0.1f,0.14f)
        val name=pair("DEL VALTORO",0.15f,0.19f)
        val cue=pair("DENOMINAZIONE",0.20f,0.24f)
        val end=pair("DI ORIGINE CONTROLLATA",0.25f,0.29f)
        val input=style+name+cue+end
        val result=decide(f,input,index=index)
        assertEquals("Italy",result["country"])
        assertEquals("Tuscany",result["region"])
        assertEquals("Valtoro",result["subregion"])
        assertEquals("Greece",decide(f,input+pair("PRODUCT OF GREECE",0.6f,0.64f),index=index)["country"])
        assertEquals("Aegean Islands",decide(f,input+pair("AEGEAN ISLANDS",0.6f,0.64f),index=index)["region"])
        assertEquals("Other appellation",decide(f+mapOf("subregion" to "Other appellation"),input,index=index)["subregion"])
        assertEquals("Greece",decide(f,style+name+cue.take(1)+end,index=index)["country"])
        assertEquals("Greece",decide(f,style+name+pair("DENOMINAZIONE",0.5f,0.54f)+end,index=index)["country"])
        assertEquals("Greece",decide(f,style+pair("BOTTLED IN VALTORO",0.15f,0.19f)+cue+end,index=index)["country"])
        val ambiguous=GlinerGeography.indexOrigins(listOf(origin,GlinerOrigin("Italy","Veneto","Valtoro",listOf("Valtoro"))))
        assertEquals("Greece",decide(f,input,index=ambiguous)["country"])
    }

    @Test fun blendTitleRequiresOrderedDistinctRolesAndRejectsClaims() {
        val f=base+mapOf("producer" to "Secret Spot Wines", "wine_name" to "Douro", "country" to "Portugal", "region" to "Douro")
        val title=pair("NIGHTFALL",0.1f,0.20f)
        val place=pair("DOURO",0.30f,0.34f)
        val blend=pair("FIELD BLEND",0.40f,0.44f)
        val maker=pair("SECRET SPOT WINES",0.50f,0.54f)
        assertEquals("NIGHTFALL FIELD BLEND",decide(f,title+place+blend+maker)["wine_name"])
        assertEquals("Douro",decide(f,title+place+blend.take(1)+maker)["wine_name"])
        assertEquals("Douro",decide(f,pair("NATURAL WINE",0.1f,0.20f)+place+blend+maker)["wine_name"])
        assertEquals("Douro",decide(f,title+pair("SUNLIGHT",0.11f,0.20f)+place+blend+maker)["wine_name"])
        assertEquals("Douro",decide(f,title+pair("FIELD BLEND",0.22f,0.26f)+place+maker)["wine_name"])
    }
}
