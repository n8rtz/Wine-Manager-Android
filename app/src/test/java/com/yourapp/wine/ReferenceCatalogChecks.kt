package com.yourapp.wine

import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.*
import com.yourapp.wine.textlab.evidence.PlaceRepair

/** Synthetic semantic tests, not pixel-OCR or legal-registry certification. */
object ReferenceCatalogChecks {
    private fun fill(vararg lines:String):FillResult = FillEngine(FixtureKnowledgeBase()).fill(
        lines.mapIndexed { i,text -> LogicChecks.token(text,y=.1f+i*.18f,h=.06f) },2026)
    fun catalogAndProvenance() {
        check(ReferenceWineCatalog.origins.size >= 2000)
        check(ReferenceWineCatalog.countryCodes.size >= 45)
        check(ReferenceWineCatalog.origins.all { it.country.isNotBlank() && it.source.isNotBlank() })
        val imported=FixtureKnowledgeBase()
        val supplemented=SupplementedWineKnowledgeBase(imported)
        check(imported.places().all { original -> supplemented.places().first { it.id==original.id }==original })
        val rockpile=fill("Rockpile")
        check(rockpile.fields.getValue(FieldId.REGION).value=="California") { rockpile.fields }
        check(rockpile.fields.getValue(FieldId.COUNTRY).state==UiState.YELLOW) { rockpile.fields }
        check(CellarFieldPresentation.apply(rockpile).fields.getValue(FieldId.COUNTRY).value=="California")
        val altus=CellarFieldPresentation.apply(fill("Altus"))
        check(altus.fields.getValue(FieldId.COUNTRY).value=="Arkansas") { altus.fields }
        val weak=FillEngine(FixtureKnowledgeBase()).fill(listOf(LogicChecks.token("Rockpile",.3f)),2026)
        check(weak.fields.getValue(FieldId.COUNTRY).state==UiState.RED) { weak.fields }
        val foreign=fill("Cairanne")
        check(foreign.fields.getValue(FieldId.COUNTRY).value=="France") { foreign.fields }
        check(foreign.fields.getValue(FieldId.COUNTRY).state==UiState.YELLOW)
        check(foreign.fields.getValue(FieldId.PRODUCER).state==UiState.RED)
        val conflict=fill("Cairanne","Italy")
        check(conflict.fields.getValue(FieldId.COUNTRY).state==UiState.YELLOW)
        check(conflict.fields.getValue(FieldId.COUNTRY).reason.contains("conflict"))
    }
    fun ambiguousAndMultiregionOrigins() {
        val gazetteer=PlaceGazetteer(SupplementedWineKnowledgeBase(FixtureKnowledgeBase()))
        fun matches(text:String) = gazetteer.matches(com.yourapp.wine.merge.TextNorm.normalize(text))
        check(matches("Napa").any { it.name=="Napa Valley" }) // Keep pre-existing shorthand aliases.
        check(matches("Orange").none { it.name=="Orange" })
        check(matches("Orange Australia").any { it.name=="Orange" })
        check(matches("Orange Wine Australia").none { it.name=="Orange" })
        check(matches("Orange Blossom Australia").none { it.name=="Orange" })
        check(matches("Cadillac Côtes de Bordeaux").map { it.name }==listOf("Cadillac Côtes de Bordeaux"))
        val multi=fill("Walla Walla Valley")
        check(multi.fields.getValue(FieldId.REGION).state==UiState.RED) { multi.fields }
        check(CellarFieldPresentation.apply(multi).fields.getValue(FieldId.COUNTRY).state==UiState.RED)
        val oregon=CellarFieldPresentation.apply(fill("Walla Walla Valley","Oregon"))
        check(oregon.fields.getValue(FieldId.COUNTRY).value=="Oregon") { oregon.fields }
        check(!oregon.fields.getValue(FieldId.COUNTRY).reason.contains("conflict")) { oregon.fields }
        val wrong=fill("Walla Walla Valley","California")
        check(wrong.fields.getValue(FieldId.REGION).reason.contains("conflict")) { wrong.fields }
        val prosecco=fill("Prosecco")
        check(prosecco.fields.getValue(FieldId.COUNTRY).value=="Italy")
        check(prosecco.fields.getValue(FieldId.REGION).state==UiState.RED) { prosecco.fields }
        val cava=fill("Cava")
        check(cava.fields.getValue(FieldId.REGION).state==UiState.RED) { cava.fields }
    }
    /** Lab-only bounded appellation repair: off by default, anchored, widened to one engine, never an override. */
    fun placeRepairIsBoundedAnchoredAndReadingDriven() {
        val kb=SupplementedWineKnowledgeBase(FixtureKnowledgeBase())
        val hierarchy=PlaceHierarchy(kb)
        val repairs=PlaceRepair(kb,hierarchy)
        val view=hierarchy.snapshot()
        val bordeaux=view.byName.getValue("bordeaux").first()
        val california=view.byName.getValue("california").first()
        val on=PlaceRepair.Config.experimental
        val mlkit=listOf(PlaceRepair.Reading("mlkit","Pauilac"))
        val paddle=listOf(PlaceRepair.Reading("paddle","Pauilac"))
        val both=mlkit+paddle
        val repaired=repairs.resolve("Pauilac",both,listOf(bordeaux),on)
        check(repaired?.place?.name=="Pauillac") { repaired.toString() }
        check(repaired?.editDistance==1) { repaired.toString() }
        check(repaired?.engines==listOf("mlkit","paddle")) { repaired.toString() }
        // One engine is enough: the engines widen the net, they are not a quorum, so a form only
        // ML Kit or only Paddle read is still repaired.
        check(repairs.resolve("Pauilac",mlkit,listOf(bordeaux),on)?.place?.name=="Pauillac")
        check(repairs.resolve("Pauilac",mlkit,listOf(bordeaux),on)?.engines==listOf("mlkit"))
        check(repairs.resolve("Pauilac",paddle,listOf(bordeaux),on)?.place?.name=="Pauillac")
        check(repairs.resolve("Pauilac",paddle,listOf(bordeaux),on)?.engines==listOf("paddle"))
        // One clean engine reading carries a phrase the merged text garbled; the clean reading is
        // already exact for the trie, so the form still arrives through the other spelling.
        check(repairs.resolve("Pauilac",listOf(PlaceRepair.Reading("paddle","Pauillac")),listOf(bordeaux),on)?.place?.name=="Pauillac")
        // Off by default, and each remaining gate alone is enough to abstain.
        check(repairs.resolve("Pauilac",both,listOf(bordeaux),PlaceRepair.Config.off)==null)
        check(repairs.resolve("Pauilac",both,emptyList(),on)==null)
        check(repairs.resolve("Pauilac",both,listOf(bordeaux,california),on)==null)
        // A repair may never contradict the printed anchor, and never re-derives an exact reading.
        check(repairs.resolve("Pauilac",both,listOf(california),on)==null)
        check(repairs.resolve("Pauillac",both,listOf(bordeaux),on)==null)
        check(repairs.resolve("Chateau Mouton",both,listOf(bordeaux),on)==null)
        // Short words are outside the production spelling budget, so they are never repaired.
        check(repairs.resolve("Pom",both,listOf(bordeaux),on)==null)
    }
    fun labelTermsNeverInventFacts() {
        for(term in listOf("DOCG","DAC","VQA","AVA","AOC","Brut","Kabinett","Blanc de Blancs")) {
            val result=fill(term)
            check(result.fields.getValue(FieldId.COUNTRY).state==UiState.RED) { "$term: ${result.fields}" }
            check(result.fields.getValue(FieldId.PRODUCER).state==UiState.RED) { "$term: ${result.fields}" }
        }
        for(text in listOf("Contiene solfiti","Contém sulfitos","Enthält Sulfite","Importato da Example Italy")) {
            val result=fill(text)
            check(result.fields.getValue(FieldId.PRODUCER).state==UiState.RED) { result.fields }
            check(result.fields.getValue(FieldId.WINE_NAME).state==UiState.RED) { result.fields }
            check(result.fields.getValue(FieldId.COUNTRY).state==UiState.RED) { result.fields }
        }
        val chablis=fill("Chablis")
        check(chablis.fields.values.none { it.value.contains("Chardonnay") })
        val cellar=fill("Celler Example","Montsant")
        check(cellar.fields.getValue(FieldId.PRODUCER).value=="Celler Example") { cellar.fields }
    }
}
