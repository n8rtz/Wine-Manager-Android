package com.yourapp.wine

import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase

/** Synthetic layout checks: contextual semantics, never physical OCR accuracy. */
object AppellationContextChecks {
    fun designationsFollowNamesWithoutBecomingProducers() {
        val engine=FillEngine(FixtureKnowledgeBase())
        for ((base,suffix,country) in listOf(Triple("Chablis","Grand Cru","France"),
            Triple("Chablis","1er Cru","France"),Triple("Côtes du Rhône","Villages","France"),
            Triple("Barolo","Riserva","Italy"),Triple("Rioja","Gran Reserva","Spain"))) {
            for (above in listOf(false,true)) {
                val report=engine.fill(listOf(LogicChecks.token(base,y=.4f,h=.06f),
                    LogicChecks.token(suffix,y=if(above) .31f else .49f,h=.06f)),2026)
                check(report.fields.getValue(FieldId.PRODUCER).state==UiState.RED) { report.fields }
                check(report.fields.getValue(FieldId.WINE_NAME).value=="$base $suffix") { report.fields }
                check(report.fields.getValue(FieldId.COUNTRY).value==country) { report.fields }
            }
            val combined=engine.fill(listOf(LogicChecks.token("$base $suffix")),2026)
            check(combined.fields.getValue(FieldId.WINE_NAME).value=="$base $suffix") { combined.fields }
        }
        val generic=engine.fill(listOf(LogicChecks.token("Domaine Example",y=.1f),
            LogicChecks.token("Reserve Nightfall",y=.5f)),2026)
        check(generic.fields.getValue(FieldId.WINE_NAME).value=="Nightfall Reserve") { generic.fields }
        val splitLetters=engine.fill(listOf(LogicChecks.token("CHABLIS GRAND CR U")),2026)
        check(splitLetters.fields.getValue(FieldId.WINE_NAME).value=="Chablis Grand Cru") { splitLetters.fields }
        val duplicate=engine.fill(listOf(LogicChecks.token("Chablis Grand Cru",y=.4f,h=.06f),
            LogicChecks.token("Grand Cru",y=.49f,h=.06f)),2026)
        check(duplicate.fields.getValue(FieldId.WINE_NAME).value=="Chablis Grand Cru")
        val incompatible=engine.fill(listOf(LogicChecks.token("Chablis Grand Cru",y=.4f,h=.06f),
            LogicChecks.token("Premier Cru",y=.49f,h=.06f)),2026)
        check(incompatible.fields.getValue(FieldId.WINE_NAME).value=="Chablis Grand Cru")
        check(incompatible.fields.getValue(FieldId.WINE_NAME).reason.contains("conflicting"))
        val distant=engine.fill(listOf(LogicChecks.token("Chablis",y=.1f,h=.05f),LogicChecks.token("Grand Cru",y=.8f,h=.05f)),2026)
        check(distant.fields.getValue(FieldId.WINE_NAME).value=="Chablis")
        val reversed=engine.fill(listOf(LogicChecks.token("Grand Cru Chablis")),2026)
        check(reversed.fields.getValue(FieldId.WINE_NAME).value=="Chablis Grand Cru") { reversed.fields }
    }

    fun namedOriginsAreStrongButGenericDesignationsAreNotCountries() {
        val engine=FillEngine(FixtureKnowledgeBase())
        for ((name,country) in listOf("Chablis" to "France","Chateauneuf du Pape" to "France",
            "Barolo" to "Italy","Rioja" to "Spain")) {
            val fill=engine.fill(listOf(LogicChecks.token(name)),2026)
            check(fill.fields.getValue(FieldId.COUNTRY).value==country) { fill.fields }
            check(fill.fields.getValue(FieldId.PRODUCER).state==UiState.RED)
        }
        for (designation in listOf("Grand Cru","Premier Cru","1er Cru","Villages","Reserva","Riserva")) {
            val fill=engine.fill(listOf(LogicChecks.token(designation)),2026)
            check(fill.fields.getValue(FieldId.COUNTRY).state==UiState.RED) { fill.fields }
            check(fill.fields.getValue(FieldId.PRODUCER).state==UiState.RED)
        }
        val conflict=engine.fill(listOf(LogicChecks.token("Barolo",y=.2f),LogicChecks.token("France",y=.6f)),2026)
        check(conflict.fields.getValue(FieldId.COUNTRY).state==UiState.YELLOW) { conflict.fields }
        check(conflict.fields.getValue(FieldId.COUNTRY).reason.contains("conflict"))
    }

    fun spellingNeedsIndependentCompatibleEvidenceAndStaysYellow() {
        val engine=FillEngine(FixtureKnowledgeBase())
        val evidence=listOf(LogicChecks.token("Chabl1s",.8f,y=.4f,h=.06f),
            LogicChecks.token("Grand Cru",.85f,y=.49f,h=.06f),LogicChecks.token("France",.9f,y=.8f))
        val corrected=engine.fill(evidence,2026)
        val name=corrected.fields.getValue(FieldId.WINE_NAME)
        check(name.value=="Chablis Grand Cru" && name.state==UiState.YELLOW) { corrected.fields }
        check(name.sources.any { it.canonical=="Chabl1s" } && name.reason.contains("contextual"))
        check(corrected.fields.getValue(FieldId.REGION).state==UiState.YELLOW)
        val noContext=engine.fill(evidence.dropLast(1),2026)
        check(noContext.fields.getValue(FieldId.COUNTRY).state==UiState.RED)
        check(noContext.fields.getValue(FieldId.WINE_NAME).value!="Chablis Grand Cru")
        val wrongContext=engine.fill(evidence.dropLast(1)+LogicChecks.token("Italy",y=.8f),2026)
        check(wrongContext.fields.getValue(FieldId.WINE_NAME).value!="Chablis Grand Cru")
        val conflictingContext=engine.fill(evidence+LogicChecks.token("Italy",y=.9f),2026)
        check(conflictingContext.fields.getValue(FieldId.WINE_NAME).value!="Chablis Grand Cru")
        val regionalContext=engine.fill(evidence.dropLast(1)+LogicChecks.token("Burgundy",y=.8f),2026)
        check(regionalContext.fields.getValue(FieldId.WINE_NAME).value=="Chablis Grand Cru")
        val importer=engine.fill(evidence.dropLast(1)+LogicChecks.token("Imported by Someone France",y=.8f),2026)
        check(importer.fields.getValue(FieldId.WINE_NAME).value!="Chablis Grand Cru")
        val tooWeak=engine.fill(evidence.map { if(it.textRaw=="Chabl1s") it.copy(score=.4f) else it },2026)
        check(!tooWeak.fields.getValue(FieldId.WINE_NAME).value.contains("Chablis"))
        val producer=engine.fill(listOf(LogicChecks.token("Domaine Chabl1s",y=.3f),LogicChecks.token("France",y=.8f)),2026)
        check(producer.fields.getValue(FieldId.PRODUCER).value=="Domaine Chabl1s") { producer.fields }
    }
}
