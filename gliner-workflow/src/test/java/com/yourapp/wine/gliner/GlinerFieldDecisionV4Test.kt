package com.yourapp.wine.gliner

import org.junit.Assert.assertEquals
import org.junit.Test

class GlinerFieldDecisionV4Test {
    private val grapes=setOf("Merlot", "Cabernet Sauvignon", "Chardonnay")
    private val base=mapOf("producer" to "Domaine de Clair", "wine_name" to "Bordeaux", "country" to "France",
        "region" to "Bordeaux", "subregion" to "", "vintage" to "2015")
    private fun l(e:String,t:String,top:Float,bottom:Float,left:Float=0.2f,right:Float=0.8f)=
        GlinerEvidenceLine(e,t,left,top,right,bottom)
    private fun p(t:String,top:Float,bottom:Float,left:Float=0.2f,right:Float=0.8f)=listOf(
        l(GlinerEngines.PADDLE,t,top,bottom,left,right),l(GlinerEngines.MLKIT,t,top,bottom,left,right))
    private fun run(f:Map<String,String>,r:List<GlinerEvidenceLine>,h:List<GlinerFieldHint> = emptyList()) =
        GlinerFieldDecisionPolicy.applyWithEvidence(f,h,r,grapes)
    private val pd=GlinerEngines.PADDLE
    private val ml=GlinerEngines.MLKIT

    @Test fun legalOrRoleProducerRequiresSpatiallyCorroboratedTitle() {
        val title=listOf(l(pd,"La Jolie",0.2f,0.24f,0.35f,0.60f),l(pd,"CHAPELLE",0.23f,0.30f),
            l(ml,"CHAIPEL LLE",0.23f,0.30f))
        val legal=base+mapOf("producer" to "Vin de Bordeaux Ppellati")
        assertEquals("La Jolie CHAPELLE",run(legal,title).fields["producer"])
        assertEquals("Vin de Bordeaux Ppellati",run(legal,title.take(2)).fields["producer"])
        assertEquals("Domaine de Clair",run(base,title).fields["producer"])
        assertEquals("Vin de Bordeaux Ppellati",run(legal,title.map { if(it.engine==ml) it.copy(top=0.7f,bottom=0.77f) else it }).fields["producer"])
        val role=listOf(l(ml,"VIGNOBL DE EXEMPLE",0.7f,0.75f),l(pd,"VIGNOBLE",0.7f,0.75f,0.2f,0.48f),
            l(pd,"DE EXEMPLE",0.7f,0.75f,0.48f,0.8f))
        assertEquals("La Jolie CHAPELLE",run(base+mapOf("producer" to "Exemple"),title+role).fields["producer"])
        assertEquals("Exemple",run(base+mapOf("producer" to "Exemple"),title+role.take(1)).fields["producer"])
    }

    @Test fun addressAndGrapeLeakageNeedCompleteIndependentProducerComponents() {
        val linked=p("L'HERITAGE",0.2f,0.23f)+p("DE MARTIN",0.24f,0.27f)
        val address=base+mapOf("producer" to "PAR 33900")
        assertEquals("L'HERITAGE DE MARTIN",run(address,linked).fields["producer"])
        assertEquals("PAR 33900",run(address,linked.filter { it.engine==pd }).fields["producer"])
        assertEquals("PAR 33900",run(address,p("L'HERITAGE",0.1f,0.13f)+p("DE MARTIN",0.7f,0.73f)).fields["producer"])
        val overlapping=p("LES ARBRES",0.20f,0.40f,0.2f,0.8f)+p("BELLECROIX",0.26f,0.46f,0.2f,0.8f)+
            p("DE",0.29f,0.32f,0.4f,0.6f)
        val grape=base+mapOf("producer" to "Merlot Pays d'Oc", "wine_name" to "Merlot")
        assertEquals("LES ARBRES DE BELLECROIX",run(grape,overlapping).fields["producer"])
        assertEquals("Merlot Pays d'Oc",run(grape,overlapping.filter { it.engine==pd }).fields["producer"])
        assertEquals("Domaine de Clair",run(grape+mapOf("producer" to "Domaine de Clair"),overlapping).fields["producer"])
        val misplaced=overlapping.map { if(it.text=="DE") it.copy(top=0.6f,bottom=0.63f) else it }
        assertEquals("Merlot Pays d'Oc",run(grape,misplaced).fields["producer"])
    }

    @Test fun reordersOnlyAnObservedParticleNestedBetweenObservedProducerWords() {
        val fields=base+mapOf("producer" to "L'Heritage Maison de")
        val parts=p("L'HERITAGE",0.20f,0.34f)+p("MAISON",0.28f,0.42f)+p("DE",0.29f,0.32f,0.4f,0.6f)
        assertEquals("L'HERITAGE DE MAISON",run(fields,parts).fields["producer"])
        assertEquals("L'Heritage Maison de",run(fields,parts.filter { it.engine==pd }).fields["producer"])
        assertEquals("L'Heritage Maison de",run(fields,parts.map { if(it.text=="DE") it.copy(top=0.6f,bottom=0.63f) else it }).fields["producer"])
        assertEquals("L'Heritage de Maison",run(fields+mapOf("producer" to "L'Heritage de Maison"),parts).fields["producer"])
    }

    @Test fun titleAndProducerCollisionRequiresModelGrapesMakerAndRole() {
        val f=base+mapOf("producer" to "Baron du Verger", "wine_name" to "Baron du Verger Merlot-Cabernet Sauvignon")
        val title=l(pd,"Baron du Verger",0.10f,0.22f)
        val blend=p("MERLOT-CABERNET SAUVIGNON",0.4f,0.45f)
        val maker=p("DU VERGER",0.72f,0.78f)
        val role=l(ml,"Eleve par la maison",0.66f,0.73f)
        val hint=listOf(GlinerFieldHint(GlinerLane.PRODUCER,"Baron du Verger",0.7f))
        val r=listOf(title)+blend+maker+role
        val actual=run(f,r,hint)
        assertEquals("DU VERGER",actual.fields["producer"])
        assertEquals("Baron du Verger",actual.fields["wine_name"])
        assertEquals(f["wine_name"],run(f,r).fields["wine_name"])
        assertEquals(f["producer"],run(f,r-role,hint).fields["producer"])
        assertEquals(f["producer"],run(f,r.filterNot { it.engine==ml && it.text=="DU VERGER" },hint).fields["producer"])
        assertEquals("Unrelated Title",run(f+mapOf("wine_name" to "Unrelated Title"),r,hint).fields["wine_name"])
    }

    @Test fun companyAndHarvestRecoveryAreBoundedByExistingFields() {
        val f=base+mapOf("producer" to "Moulinn-Rouge", "wine_name" to "Rioja", "region" to "Rioja", "vintage" to "2015")
        val title=l(pd,"MOULINN-ROUGE",0.2f,0.25f)
        val company=l(pd,"BODEGAS MOULIN ROUGE, S.A",0.7f,0.74f)
        val harvest=l(pd,"COSECHA 2015 AZUL",0.3f,0.34f)
        val r=listOf(title,company,harvest)
        val result=run(f,r)
        assertEquals("MOULIN ROUGE",result.fields["producer"])
        assertEquals("AZUL",result.fields["wine_name"])
        assertEquals("Moulinn-Rouge",run(f,listOf(title,company.copy(text="BODEGAS MOULIN ROUGE"),harvest)).fields["producer"])
        assertEquals("Moulinn-Rouge",run(f,listOf(title,company.copy(text="BARRELS MOULIN ROUGE, S.A"),harvest)).fields["producer"])
        assertEquals("Moulinn-Rouge",run(f,r+company.copy(text="BODEGAS MOULIN RAUGE, S.A")).fields["producer"])
        assertEquals("Moulinn-Rouge",run(f,listOf(title,company.copy(engine=ml),harvest)).fields["producer"])
        assertEquals("Rioja",run(f+mapOf("vintage" to "2014"),r).fields["wine_name"])
        assertEquals("Existing Wine",run(f+mapOf("wine_name" to "Existing Wine"),r).fields["wine_name"])
        assertEquals("Rioja",run(f,r+harvest.copy(text="COSECHA 2015 ORO")).fields["wine_name"])
    }

    @Test fun qualifiedPlaceAndDescriptorNeedIndependentEvidenceAndContext() {
        val geo=base+mapOf("subregion" to "Listrac")
        val qualifier=listOf(l(pd,"LISTRAC SUPERIEUR",0.4f,0.5f),l(ml,"TRAC SUPERIEUE",0.4f,0.5f))
        assertEquals("LISTRAC SUPERIEUR",run(geo,qualifier).fields["wine_name"])
        assertEquals("Bordeaux",run(geo,qualifier.take(1)).fields["wine_name"])
        assertEquals("Bordeaux",run(geo+mapOf("subregion" to "Margaux"),qualifier).fields["wine_name"])
        assertEquals("Existing",run(geo+mapOf("wine_name" to "Existing"),qualifier).fields["wine_name"])
        val f=base+mapOf("wine_name" to "Bourgogne", "subregion" to "Bourgogne")
        val estate=p("DOMAINE DE",0.1f,0.15f)
        val descriptor=listOf(l(pd,"Tradition",0.35f,0.43f),l(ml,"Tradeti",0.35f,0.43f))
        val location=listOf(l(pd,"BOURGOGNE",0.5f,0.55f))
        assertEquals("Tradition",run(f,estate+descriptor+location).fields["wine_name"])
        assertEquals("Bourgogne",run(f,estate+descriptor.take(1)+location).fields["wine_name"])
        assertEquals("Bourgogne",run(f,descriptor+location).fields["wine_name"])
        assertEquals("Bourgogne",run(f,estate+descriptor).fields["wine_name"])
    }

    @Test fun appellationAndClassificationRepairDoNotReplaceUnrelatedWine() {
        val f=base+mapOf("producer" to "Chateau Luciole", "subregion" to "Saint-Emilion",
            "wine_name" to "Luciole Aint-Emilion O Grand Cru")
        val name=p("LUCIOLE",0.2f,0.3f)
        val appellation=l(ml,"AINT-EMILION O GRAND CRU",0.28f,0.38f)
        assertEquals("Saint-Emilion GRAND CRU",run(f,name+appellation).fields["wine_name"])
        assertEquals(f["wine_name"],run(f,name.filter { it.engine==ml }+appellation).fields["wine_name"])
        assertEquals(f["wine_name"],run(f+mapOf("subregion" to "Pomerol"),name+appellation).fields["wine_name"])
        assertEquals("Another Wine",run(f+mapOf("wine_name" to "Another Wine"),name+appellation).fields["wine_name"])
        val classification=listOf(l(pd,"RONIER GRAND CRU LASSE",0.4f,0.5f),l(ml,"RMIER GRAND CRU CLASS",0.4f,0.5f))
        val damaged=base+mapOf("wine_name" to "Ronier Grand Cru Lasse")
        assertEquals("Premier Grand Cru Classé",run(damaged,classification).fields["wine_name"])
        assertEquals(damaged["wine_name"],run(damaged,classification.take(1)).fields["wine_name"])
        assertEquals("Another Wine",run(base+mapOf("wine_name" to "Another Wine"),classification).fields["wine_name"])
        assertEquals(damaged["wine_name"],run(damaged,listOf(classification[0],classification[1].copy(top=0.7f,bottom=0.8f))).fields["wine_name"])
    }
}
