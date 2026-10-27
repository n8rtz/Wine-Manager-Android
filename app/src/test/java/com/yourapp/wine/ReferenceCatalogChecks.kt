package com.yourapp.wine

import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.*

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
