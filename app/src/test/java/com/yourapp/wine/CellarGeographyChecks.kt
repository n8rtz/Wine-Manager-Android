package com.yourapp.wine

import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase

object CellarGeographyChecks {
    fun run() {
        val engine=FillEngine(FixtureKnowledgeBase())
        fun scan(vararg text:String)=CellarFieldPresentation.apply(engine.fill(text.mapIndexed { i,t -> LogicChecks.token(t,y=.05f+i*.12f) },2026))
        val brunello=scan("KIRKLAND","BRUNELLO DI MONTALCINO","2021")
        check(brunello.fields.getValue(FieldId.WINE_NAME).value.equals("Brunello di Montalcino",true))
        check(brunello.fields.getValue(FieldId.REGION).value=="Tuscany")
        check(brunello.fields.getValue(FieldId.SUBREGION).value=="Montalcino") { brunello.fields }
        val rosso=scan("KIRKLAND","Rosso di Montalcino")
        check(rosso.fields.getValue(FieldId.WINE_NAME).value=="Rosso di Montalcino")
        check(rosso.fields.getValue(FieldId.SUBREGION).value=="Montalcino")
        val townOnly=scan("KIRKLAND","Montalcino")
        check(townOnly.fields.getValue(FieldId.WINE_NAME).value.isEmpty()) { townOnly.fields }
        val partial=scan("KIRKLAND","BRUNELO","Montalcino")
        check(partial.fields.getValue(FieldId.WINE_NAME).value=="Brunello di Montalcino") { partial.fields }
        check(partial.fields.getValue(FieldId.WINE_NAME).state==UiState.YELLOW)
        val alexander=scan("Alexander Valley","SONOMA COUNTY","CALIFORNIA")
        check(alexander.fields.getValue(FieldId.COUNTRY).value=="California")
        check(alexander.fields.getValue(FieldId.REGION).value=="Alexander Valley") { alexander.fields }
        check(alexander.fields.getValue(FieldId.SUBREGION).value.isEmpty())
        val oakville=scan("Oakville","Napa Valley","California")
        check(oakville.fields.getValue(FieldId.REGION).value=="Napa Valley") { oakville.fields }
        check(oakville.fields.getValue(FieldId.SUBREGION).value=="Oakville")
        for(result in listOf(brunello,rosso,townOnly,partial,alexander,oakville,scan("Rockpile"),scan("Walla Walla Valley"))) {
            check(result.fields.getValue(FieldId.SUBREGION).value.isBlank() || result.fields.getValue(FieldId.REGION).value.isNotBlank())
            check(CellarFieldPresentation.apply(result).fields==result.fields) // Idempotent formatting.
        }
        check(com.yourapp.wine.extract.LabelTextRoles.isLegalStatement("aorigne controllata"))
        println("PASS: cellar Montalcino locality, supported style inference, Rosso negative control, valley hierarchy and no orphan subregion")
    }
}
