package com.yourapp.wine

import com.yourapp.wine.extract.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.TextNorm

object FuzzySpellingChecks {
    fun run() {
        val kb=FixtureKnowledgeBase()
        val fill=FillEngine(kb)
        fun scan(vararg words:String)=CellarFieldPresentation.apply(fill.fill(words.mapIndexed { i,w -> LogicChecks.token(w,y=.05f+i*.16f) },2026))
        val blackstone=scan("LACKSTONE","WINERY","Merlot","CALIFORNIA")
        val producer=blackstone.fields.getValue(FieldId.PRODUCER)
        check(producer.value=="Blackstone" && producer.state==UiState.YELLOW) { blackstone.fields }
        check(producer.spelling?.editDistance==1 && producer.spelling?.editKind=="missing_characters")
        check(producer.sources.flatMap { it.members }.any { it.textRaw=="LACKSTONE" && it.score==.9f })
        check(blackstone.spellingSuggestions.any { it.observed.contains("LACKSTONE") && it.domain=="producer" })
        val corroborated=fill.fill(listOf(LogicChecks.token("LACKSTONE",score=.98f),
            LogicChecks.token("Blackstone",score=.9f,engine="paddle-v6-medium")),2026)
        check(corroborated.fields.getValue(FieldId.PRODUCER).spelling?.support?.contains("separate engine")==true) { corroborated.fields }
        val repeated=fill.fill(listOf(LogicChecks.token("LACKSTONE",score=.98f),
            LogicChecks.token("Blackstone",score=.9f,variant="V1")),2026)
        check(repeated.fields.getValue(FieldId.PRODUCER).spelling?.support?.contains("separate engine")!=true)
        val merlot=scan("BLACKSTONE","Merlo")
        check(merlot.fields.getValue(FieldId.WINE_NAME).value=="Merlot") { merlot.fields }
        check(merlot.fields.getValue(FieldId.WINE_NAME).state==UiState.YELLOW)
        check(merlot.fields.getValue(FieldId.WINE_NAME).spelling?.editDistance==1)
        val cuvee=scan("Château Margaux","Pavillon Roug")
        check(cuvee.fields.getValue(FieldId.WINE_NAME).value=="Pavillon Rouge") { cuvee.fields }
        check(cuvee.fields.getValue(FieldId.WINE_NAME).state==UiState.YELLOW)
        val fuzzyMaker=scan("Chateau Margauy","Pavillon Roug")
        check(fuzzyMaker.fields.getValue(FieldId.WINE_NAME).value!="Pavillon Rouge")
        val unsupported=scan("Domaine Unknown","Pavillon Roug")
        check(unsupported.fields.getValue(FieldId.WINE_NAME).value!="Pavillon Rouge")
        val geography=scan("CALIFORNA","United States","2019")
        check(geography.fields.getValue(FieldId.COUNTRY).value=="California") { geography.fields }
        check(geography.fields.getValue(FieldId.COUNTRY).state==UiState.YELLOW)
        val conflict=scan("CALIFORNA","France")
        check(conflict.fields.values.none { it.value=="California" })
        val unanchored=scan("CALIFORNA")
        check(unanchored.fields.values.none { it.value=="California" })
        for(noise in listOf("ALC 13.55 BY FOL","2019","KJ","Mer","750 ML")) {
            check(BoundedSpelling.matches(noise,listOf(SpellingTerm("1","Merlot",emptyList())),"style").isEmpty())
        }
        check(BoundedSpelling.matches("Merlot",listOf(SpellingTerm("a","Merlot",emptyList()),SpellingTerm("b","Merlor",emptyList())),"style").isEmpty())
        check(BoundedSpelling.distance("Blackstone Extra Words","Blackstone")==null)
        check(KbMatching.fuzzyPrefixes("LACKSTONE").contains("blac"))
        check(KbMatching.fuzzyPrefixes("SLACKSTONE").contains("blac"))
        check(KbMatching.fuzzyPrefixes("a very long multicomponent producer name missing letters").size<=120)
        val unknown=scan("Domaine Xylophora","Syrah")
        check(unknown.fields.getValue(FieldId.PRODUCER).value=="Domaine Xylophora")
        val snapshot=FixtureKnowledgeBase.snapshot
        val tiedKb=FixtureKnowledgeBase(snapshot.copy(producers=snapshot.producers+listOf(
            Producer(101,"Lackstona","US",19),Producer(102,"Lackstonb","US",19))))
        val tied=FillEngine(tiedKb).fill(listOf(LogicChecks.token("Lackstonc")),2026)
        check(tied.fields.getValue(FieldId.PRODUCER).value.isEmpty()) { tied.fields }
        check(tied.alternatives.getValue(FieldId.PRODUCER).size>=2)
        val localMaker=FixtureKnowledgeBase(snapshot.copy(producers=snapshot.producers+Producer(109,"Californa","US",19)))
        val protected=FillEngine(localMaker).fill(listOf(LogicChecks.token("Californa"),LogicChecks.token("United States",y=.4f)),2026)
        check(protected.fields.getValue(FieldId.PRODUCER).value=="Californa")
        check(protected.spellingSuggestions.none { it.domain=="geography" })
        check(scan("Merlot","Italy").fields.getValue(FieldId.WINE_NAME).value=="Merlot")
        val geoKb=FixtureKnowledgeBase(snapshot.copy(places=snapshot.places+listOf(
            Place(910,"Califorba","region",18,"US"),Place(911,"Califorbb","region",18,"US"))))
        val geoTied=FillEngine(geoKb).fill(listOf(LogicChecks.token("Califorbc"),LogicChecks.token("United States",y=.4f)),2026)
        check(geoTied.spellingSuggestions.count { it.domain=="geography" && it.support.contains("not applied") }>=2)
        check(geoTied.fields.values.none { it.value in setOf("Califorba","Califorbb") })
        val twoCuvees=FixtureKnowledgeBase(snapshot.copy(cuvees=snapshot.cuvees+listOf(Cuvee(1,"Pavillon Ronga"),Cuvee(1,"Pavillon Rongb"))))
        val ties=FillEngine(twoCuvees).fill(listOf(LogicChecks.token("Château Margaux"),LogicChecks.token("Pavillon Rongc",y=.3f)),2026)
        check(ties.fields.getValue(FieldId.WINE_NAME).value.isEmpty()) { ties.fields }
        println("PASS: field-aware fuzzy producer/style/cuvee/geography; missing first letter; ties abstain; raw scores unchanged; unknowns and negatives preserved")
    }
}
