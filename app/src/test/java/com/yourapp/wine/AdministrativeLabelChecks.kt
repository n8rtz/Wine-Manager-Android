package com.yourapp.wine

import com.yourapp.wine.extract.MetadataRules
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase

/** CSV-derived text/scores; box arrangements are synthetic because CSV does not contain per-line geometry. */
object AdministrativeLabelChecks {
    fun rhoneAppellationIsNotAMakerAndLegalDebrisIsNotAName() {
        val engine = FillEngine(FixtureKnowledgeBase())
        val result = engine.fill(listOf(
            LogicChecks.token("FONDÉE EN EN 1317", .786f, y=.1f),
            LogicChecks.token("2022", .944f, y=.25f),
            LogicChecks.token("CỚTES DU RHÔNE VILLAC", .609f, y=.4f),
            LogicChecks.token("APPELILATION", .669f, y=.6f),
            LogicChecks.token("APPELLA TIO N", .621f, y=.7f),
            LogicChecks.token("OiGNERONS DE L'ENCLAUE", .430f, y=.85f)
        ),2026)
        check(result.fields.getValue(FieldId.PRODUCER).state == UiState.RED) { result.fields }
        val name = result.fields.getValue(FieldId.WINE_NAME)
        check(name.value == "Cotes du Rhone Villages" && name.state == UiState.YELLOW) { result.fields }
        check(name.reason.contains("bounded role/appellation repair")) // Anchored suffix suggestion, never a silent exact reading.
        check(result.fields.getValue(FieldId.COUNTRY).value == "France")
        check(result.fields.getValue(FieldId.VINTAGE).value == "2022")
        val exact = engine.fill(listOf(LogicChecks.token("Côtes du Rhône Villages",.9f)),2026)
        check(exact.fields.getValue(FieldId.PRODUCER).state == UiState.RED)
        check(exact.fields.getValue(FieldId.WINE_NAME).value == "Cotes du Rhone Villages") { exact.fields }
    }

    fun narrowAdministrativeRulesPreserveRealIdentities() {
        listOf("FONDÉE", "FONDDEE EN", "FONDÉE EN 1317", "FONDÉE EN EN 1317", "ONDEE EN134",
            "APPELILATION", "APPELLA TIO N", "NPPELLNTION", "APPELLATIOND'ORIGINEP PROTÉCEE",
            "NDORIGI NE PROTÉGEE").forEach { check(!MetadataRules.isIdentity(it)) { it } }
        listOf("Domaine Fondee", "Appelilation Hills", "La Reserve De L'Enclave", "Vignerons de l'Enclave",
            "Domaine du Rhône").forEach { check(MetadataRules.isIdentity(it)) { it } }
        val result=FillEngine(FixtureKnowledgeBase()).fill(listOf(
            LogicChecks.token("Domaine du Rhône",.9f,y=.2f),
            LogicChecks.token("Côtes du Rhône Villages",.85f,y=.5f)),2026)
        check(result.fields.getValue(FieldId.PRODUCER).value == "Domaine du Rhone") { result.fields }
    }

    fun reserveHeadingIsNotAnUnsupportedProducer() {
        val engine=FillEngine(FixtureKnowledgeBase())
        val result=engine.fill(listOf(
            LogicChecks.token("RECIENTE",.6298828f,y=.4f,h=.07f),
            LogicChecks.token("GRAN RESERVA",.78808594f,y=.5f,h=.05f),
            LogicChecks.token("UODEGAS 0LARRA",.49330357f,y=.65f),
            LogicChecks.token("RIOJA",.73125f,y=.8f)),2026)
        check(result.fields.getValue(FieldId.PRODUCER).state==UiState.RED) { result.fields }
        check(result.fields.getValue(FieldId.WINE_NAME).value=="RECIENTE Gran Reserva") { result.fields }
        // Explicit producer cues still support boutique makers next to a reserve designation.
        val realMaker=engine.fill(listOf(LogicChecks.token("Domaine Example",.9f,y=.4f,h=.07f),
            LogicChecks.token("Reserve",.8f,y=.5f,h=.05f)),2026)
        check(realMaker.fields.getValue(FieldId.PRODUCER).value=="Domaine Example") { realMaker.fields }
    }
}
