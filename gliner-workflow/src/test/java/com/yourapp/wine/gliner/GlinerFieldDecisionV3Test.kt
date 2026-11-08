package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Test

class GlinerFieldDecisionV3Test {
    private val base=mapOf("producer" to "Maison Exemple", "wine_name" to "Bordeaux", "country" to "France",
        "region" to "Bordeaux", "subregion" to "", "vintage" to "2020")
    private fun line(engine:String,text:String,top:Float,bottom:Float,left:Float=0.2f,right:Float=0.8f) =
        GlinerEvidenceLine(engine,text,left,top,right,bottom)
    private fun pair(text:String,top:Float,bottom:Float,left:Float=0.2f,right:Float=0.8f) = listOf(
        line(GlinerEngines.PADDLE,text,top,bottom,left,right),line(GlinerEngines.MLKIT,text,top,bottom,left,right))
    private fun decide(fields:Map<String,String>, lines:List<GlinerEvidenceLine>, hints:List<GlinerFieldHint> = emptyList(),
        styles:Set<String> = emptySet()) = GlinerFieldDecisionPolicy.applyWithEvidence(fields,hints,lines,emptySet(),styles)

    @Test fun quotedTitlesRequireSeparateEnginesAndGeographicFallback() {
        val title=listOf(line(GlinerEngines.PADDLE,"«LES RIVIERES»",0.4f,0.45f),
            line(GlinerEngines.MLKIT,"LES RIVIERFS",0.4f,0.45f))
        assertEquals("LES RIVIERES",decide(base,title).fields["wine_name"])
        assertEquals("Bordeaux",decide(base,title.take(1)+title.take(1)).fields["wine_name"])
        assertEquals("Bordeaux",decide(base,listOf(title[0],title[1].copy(top=0.6f,bottom=0.65f))).fields["wine_name"])
        assertEquals("Existing Cuvee",decide(base+mapOf("wine_name" to "Existing Cuvee"),title).fields["wine_name"])
        val competing=title+pair("«AUTRE CUVEE»",0.6f,0.65f)
        assertEquals("Bordeaux",decide(base,competing).fields["wine_name"])
    }

    @Test fun prominentTitleNeedsModelConfirmedProducerAndProximity() {
        val title=listOf(line(GlinerEngines.PADDLE,"GRANDE RESERVE",0.2f,0.28f),
            line(GlinerEngines.MLKIT,"GRANDE RESERVF",0.2f,0.28f))
        val producer=pair("MAISON EXEMPLE",0.29f,0.32f)
        val hint=GlinerFieldHint(GlinerLane.PRODUCER,"Maison Exemple",0.7f)
        assertEquals("GRANDE RESERVE",decide(base,title+producer,listOf(hint)).fields["wine_name"])
        assertEquals("Bordeaux",decide(base,title+producer).fields["wine_name"])
        assertEquals("Bordeaux",decide(base,title+producer.map { it.copy(top=0.7f,bottom=0.73f) },listOf(hint)).fields["wine_name"])
        assertEquals("Bordeaux",decide(base,title+pair("MAISON EXEMPLE",0.22f,0.25f),listOf(hint)).fields["wine_name"])
    }

    @Test fun adjacentTitleMustLinkToSelectedEstateAndNotBeFarApart() {
        val fields=base+mapOf("producer" to "Chateau la Vieille Cure", "wine_name" to "Fronsac", "subregion" to "Fronsac")
        val lines=pair("LA SACRISTIE",0.2f,0.23f)+pair("DE LA VIEILLE CURE",0.24f,0.27f)
        assertEquals("LA SACRISTIE DE LA VIEILLE CURE",decide(fields,lines).fields["wine_name"])
        assertEquals("Fronsac",decide(fields+mapOf("producer" to "Chateau Autre"),lines).fields["wine_name"])
        assertEquals("Fronsac",decide(fields,pair("LA SACRISTIE",0.2f,0.23f)+pair("DE LA VIEILLE CURE",0.7f,0.73f)).fields["wine_name"])
        assertEquals("Fronsac",decide(fields,lines.filter { it.engine==GlinerEngines.PADDLE }).fields["wine_name"])
    }

    @Test fun unsupportedFuzzySubregionIsClearedOnlyWithIndependentProducerEvidence() {
        val fields=base+mapOf("producer" to "Chateau du Moulin", "subregion" to "Moulis")
        val estate=pair("CHATEAU DU MOULIN",0.2f,0.25f)
        assertEquals("",decide(fields,estate).fields["subregion"])
        assertEquals("Moulis",decide(fields,estate+pair("APPELLATION MOULIS CONTROLEE",0.7f,0.73f)).fields["subregion"])
        assertEquals("Moulis",decide(fields,estate.take(1)).fields["subregion"])
        assertEquals("Moulis",decide(fields,listOf(estate[0],estate[1].copy(top=0.7f,bottom=0.75f))).fields["subregion"])
        assertEquals("Moulis",decide(fields+mapOf("producer" to "Chateau Autre"),estate).fields["subregion"])
    }

    @Test fun styleAndDescriptorNeedBothEnginesVocabularyAndBlankWine() {
        val blank=base+mapOf("wine_name" to "")
        val style=listOf(line(GlinerEngines.PADDLE,"BLANC DE BLANCS",0.679f,0.724f,0.333f,0.725f),
            line(GlinerEngines.MLKIT,"BLANC DE BL",0.688f,0.721f,0.352f,0.608f),
            line(GlinerEngines.MLKIT,"LANCS",0.688f,0.719f,0.591f,0.725f))
        val descriptor=listOf(line(GlinerEngines.PADDLE,"BRUT",0.660f,0.690f,0.465f,0.602f),
            line(GlinerEngines.MLKIT,"BRUT",0.666f,0.686f,0.474f,0.597f))
        val terms=setOf("Blanc de Blancs")
        assertEquals("BRUT BLANC DE BLANCS",decide(blank,style+descriptor,styles=terms).fields["wine_name"])
        assertEquals("",decide(blank,style+descriptor).fields["wine_name"])
        assertEquals("",decide(blank,style.take(1)+descriptor,styles=terms).fields["wine_name"])
        assertEquals("",decide(blank,style+descriptor.take(1),styles=terms).fields["wine_name"])
        assertEquals("Bordeaux",decide(base,style+descriptor,styles=terms).fields["wine_name"])
        val overlapping=pair("BRUT",0.685f,0.715f,0.45f,0.60f)
        assertEquals("",decide(blank,style+overlapping,styles=terms).fields["wine_name"])
    }

    @Test fun fuzzyEstateUsesSeparatelyObservedSpellingAndSpatiallyCorroboratedTitle() {
        val fields=base+mapOf("producer" to "Oker Grand Crucclasse", "subregion" to "Montrose")
        val fragments=listOf(
            line(GlinerEngines.PADDLE,"HATEAU",0.2f,0.28f,0.1f,0.45f),
            line(GlinerEngines.PADDLE,"UMONTROSE",0.2f,0.28f,0.44f,0.85f),
            line(GlinerEngines.MLKIT,"ATEAU MONTROSF",0.2f,0.28f,0.1f,0.85f),
            line(GlinerEngines.PADDLE,"MIS EN BOUTEILLE AU CHATEAU",0.1f,0.13f),
            line(GlinerEngines.PADDLE,"MONTROSE",0.7f,0.73f))
        assertEquals("CHATEAU Montrose",decide(fields,fragments).fields["producer"])
        val noExactPlace=fragments.dropLast(1).map { if(it.text=="UMONTROSE") it.copy(text="UMONTROSF") else it }
        assertEquals("Oker Grand Crucclasse",decide(fields,noExactPlace).fields["producer"])
        assertEquals("Oker Grand Crucclasse",decide(fields,fragments.filterNot { it.text.contains("BOUTEILLE") }).fields["producer"])
    }

    @Test fun estateRepairRequiresClassificationAndMatchingPlaceInBothEngines() {
        val fields=base+mapOf("producer" to "Premier", "subregion" to "Montrose")
        val parts=listOf(line(GlinerEngines.PADDLE,"CHATEAU",0.2f,0.28f,0.1f,0.45f),
            line(GlinerEngines.PADDLE,"MONTROSE",0.2f,0.28f,0.44f,0.85f),
            line(GlinerEngines.MLKIT,"HATEAU MONTROSE",0.2f,0.28f,0.1f,0.85f))
        assertEquals("CHATEAU Montrose",decide(fields,parts).fields["producer"])
        assertEquals("Real Estate",decide(fields+mapOf("producer" to "Real Estate"),parts).fields["producer"])
        assertEquals("Premier",decide(fields,parts.take(2)).fields["producer"])
        assertEquals("Premier",decide(fields+mapOf("subregion" to "Villages"),parts).fields["producer"])
        assertEquals("Premier",decide(fields,parts.map { if(it.engine==GlinerEngines.MLKIT) it.copy(top=0.7f,bottom=0.78f) else it }).fields["producer"])
        assertEquals("Premier",decide(fields,listOf(parts[0],parts[1].copy(top=0.6f,bottom=0.68f),parts[2])).fields["producer"])
    }
}
