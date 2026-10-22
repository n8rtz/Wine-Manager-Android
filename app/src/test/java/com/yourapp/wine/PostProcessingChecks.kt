package com.yourapp.wine

import com.yourapp.wine.extract.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.merge.*

object PostProcessingChecks {
    fun rolesRankingAndSafety() {
        val fill=FillEngine(FixtureKnowledgeBase())
        fun scan(vararg text:String)=fill.fill(text.mapIndexed { i,t -> LogicChecks.token(t,y=.04f+i*.12f,h=.05f) },2026)
        for(pack in listOf("13.58 BY TOL","13.55 BY FOL","ALC 13.5% BY VOL","750 ml","2019 ml",".Bby Vol.","NET CONT'30M")) {
            check(MetadataRules.isPackaging(pack))
            val result=scan(pack,"CALIFORNIA")
            check(result.fields.getValue(FieldId.WINE_NAME).value.isEmpty()) { result.fields }
            check(result.fields.getValue(FieldId.PRODUCER).value.isEmpty()) { result.fields }
            check(result.fields.getValue(FieldId.VINTAGE).value.isEmpty())
        }
        for(name in listOf("Winery 7","19 Crimes","Domaine Volnay","Cuvee 12.5")) check(!MetadataRules.isPackaging(name))
        val kirkland=scan("KIRKLAND","Stags Leap District","CABERNET SAUVIGNON","2024")
        check(kirkland.fields.getValue(FieldId.PRODUCER).value=="Kirkland Signature") { kirkland.fields }
        check(kirkland.fields.getValue(FieldId.WINE_NAME).value.equals("CABERNET SAUVIGNON",ignoreCase=true)) { kirkland.fields }
        val verdejo=scan("MARQUÉS DE RISCAL","Verdejo","Rueda-España","2023")
        check(verdejo.fields.getValue(FieldId.WINE_NAME).value=="Verdejo") { verdejo.fields }
        check(verdejo.postProcessing.any { "ORIGIN" in it })
        check(LabelTextRoles.isLegalStatement("dorgine conitrollata"))
        check(LabelTextRoles.isLegalStatement("denominczione"))
        check(LabelTextRoles.isLegalStatement("DENOMINACIÓN DE OR"))
        check(!LabelTextRoles.isLegalStatement("Domaine Control"))
        val district=scan("KIRKLAND","Stags Leap Distrid","CABERNET SAUVIGNON","2024")
        check(district.fields.getValue(FieldId.WINE_NAME).value.equals("Cabernet Sauvignon",ignoreCase=true))
        check(district.fields.getValue(FieldId.SUBREGION).value.isEmpty()) // Role suppression must not invent a corrected place.
        val unknown=scan("Domaine Petit Nuage","Pinot Noir")
        check(unknown.fields.getValue(FieldId.PRODUCER).value=="Domaine Petit Nuage")
        check(unknown.fields.getValue(FieldId.WINE_NAME).value=="Pinot Noir")
        val split=fill.fill(listOf(LogicChecks.token("KIRKLAND",y=.1f),LogicChecks.token("BRUNELLO",y=.3f),
            LogicChecks.token("DIMONTALCINO",y=.38f),LogicChecks.token("2021",y=.7f)),2026)
        check(split.fields.getValue(FieldId.PRODUCER).value=="Kirkland Signature")
        check(TextNorm.normalize(split.fields.getValue(FieldId.WINE_NAME).value).replace(" ","")=="brunellodimontalcino") { split.fields }
        val distant=LineBuilder().build(TokenMerger().merge(listOf(LogicChecks.token("FIRST",x=.02f,w=.12f),LogicChecks.token("SECOND",x=.72f,w=.14f))))
        check(distant.size==2)
        val conflict=scan("2019","2020")
        check(conflict.fields.getValue(FieldId.VINTAGE).state==UiState.YELLOW)
        check(conflict.fields.getValue(FieldId.VINTAGE).reason.contains("conflict"))
        val empty=scan()
        check(empty.fields.values.all { it.value.isEmpty() && it.state==UiState.RED })
    }
    fun boundedAssignmentKeepsAlternativesAndAbstains() {
        val sources=TokenMerger().merge(listOf(LogicChecks.token("Sample")))
        fun option(field:FieldId,value:String,rank:Double)=IdentityOption(FieldCandidate(field,value,UiState.YELLOW,"test evidence",sources),rank)
        val choice=IdentityAssignment.select(listOf(option(FieldId.PRODUCER,"Maker A",95.0),option(FieldId.PRODUCER,"Maker B",95.0)),emptyList())
        check(choice.fields.getValue(FieldId.PRODUCER).single().value.isEmpty())
        check(choice.alternatives.getValue(FieldId.PRODUCER).size==2)
        val same=IdentityAssignment.select(listOf(option(FieldId.PRODUCER,"A",65.0)),listOf(option(FieldId.WINE_NAME,"B",65.0)))
        check(same.fields.values.all { it.single().value.isEmpty() }) // Equally plausible competing roles.
        val lots=IdentityAssignment.select((1..50).map { option(FieldId.PRODUCER,"Maker $it",it.toDouble()) },emptyList())
        check(lots.alternatives.getValue(FieldId.PRODUCER).size==4)
    }
}
