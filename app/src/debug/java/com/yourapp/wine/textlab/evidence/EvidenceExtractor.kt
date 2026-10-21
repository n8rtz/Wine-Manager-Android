package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.extract.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord

internal data class Hypothesis(val values:Map<FieldId,String>,val phrase:Phrase,val rank:Double,val reason:String,val place:Place?=null)
internal data class EvidenceResult(val fill:FillResult,val graph:EvidenceGraph,val hypotheses:List<Hypothesis>,val elapsedMs:Long)

/** Independent lab-only solver. Shared lexical catalogs, not production line/merge/extract/validate rules. */
internal class EvidenceExtractor(base:WineKnowledgeBase) {
    companion object { const val VERSION="evidence-lab-v1";const val MAX_HYPOTHESES=192;const val BEAM=64 }
    private val kb=SupplementedWineKnowledgeBase(base)
    private val gazetteer=PlaceGazetteer(kb)
    private val hierarchy=PlaceHierarchy(kb).snapshot()
    private val roles=ProducerLineSelector()
    fun extract(records:List<TokenRecord>,year:Int,budgetMs:Long=2000):EvidenceResult {
        val started=System.nanoTime()
        fun checkBudget() {
            if(Thread.currentThread().isInterrupted) throw InterruptedException("Evidence extraction interrupted")
            check((System.nanoTime()-started)/1_000_000<budgetMs) { "Evidence extraction budget exceeded" }
        }
        val graph=EvidenceGraphBuilder.build(records,::checkBudget)
        val hypotheses=mutableListOf<Hypothesis>()
        val roleNotes=mutableListOf<String>()
        for(p in graph.phrases) {
            checkBudget()
            if(hypotheses.size>=MAX_HYPOTHESES) break
            val text=p.text;val norm=TextNorm.normalize(text)
            val sources=graph.observations.filter { it.id in p.observations }
            val supported=sources.any { it.record.score >= if(it.record.engineId.startsWith("paddle")) .75f else .60f }
            if(!supported) continue // retained in graph, not an independent identity assertion
            val administrative=MetadataRules.isBoilerplate(text) || MetadataRules.isPackaging(text)
            val paragraph=p.box.width()<.25f && graph.phrases.count { other -> other.id!=p.id &&
                kotlin.math.abs(other.box.left-p.box.left)<.04f && kotlin.math.abs(other.box.centerY()-p.box.centerY())<.12f }>=3
            val places=if(administrative) emptyList() else gazetteer.matches(norm)
            roleNotes+="phrase=${p.id}; administrative=$administrative; paragraph=$paragraph; places=${places.joinToString { it.name }}"
            if(administrative) continue
            fun offer(values:Map<FieldId,String>,rank:Double,why:String,place:Place?=null) {
                if(hypotheses.size<MAX_HYPOTHESES) hypotheses+=Hypothesis(values,p,rank,why,place)
            }
            if(Regex("(?:19|20)\\d{2}").matches(norm) && norm.toInt() in 1900..year)
                offer(mapOf(FieldId.VINTAGE to norm),55.0,"complete observed vintage")
            for(place in places.take(4)) {
                val path=hierarchy.ancestors(place)
                val values=mutableMapOf<FieldId,String>()
                path.lastOrNull { it.type=="country" }?.let { values[FieldId.COUNTRY]=it.name }
                path.firstOrNull { it.type=="region" }?.let { values[FieldId.REGION]=it.name }
                if(place.type !in setOf("country","region")) values[FieldId.SUBREGION]=place.name
                offer(values,50.0+path.size*5,"observed geography and actual ancestors; no producer-origin inference",place)
            }
            if(places.any { TextNorm.normalize(it.name)==norm } && WineDesignations.isAppellationName(text))
                offer(mapOf(FieldId.WINE_NAME to text),65.0,"exact printed appellation may also name the wine")
            if(!MetadataRules.isIdentity(text) || paragraph || places.isNotEmpty()) continue
            BundledProducerNames.exactName(text)?.let { offer(mapOf(FieldId.PRODUCER to it),105.0,"exact authored producer alias; no origin inference") }
            val known=kb.findProducer(text).filter { it.editDistance==0 }.take(3)
            known.forEach { offer(mapOf(FieldId.PRODUCER to it.producer.name),110.0,"exact local producer; catalog location is not wine origin") }
            if(roles.isWineDescription(text) || WineDesignations.isDesignation(text)) {
                offer(mapOf(FieldId.WINE_NAME to (WineDesignations.display(text) ?: text)),65.0,"observed varietal or designation")
                continue
            }
            if(norm.count(Char::isLetter)<4 || norm.any(Char::isDigit)) continue
            val joined=p.construction.contains("connector")
            offer(mapOf(FieldId.PRODUCER to text),if(roles.hasProducerCue(text)) 80.0 else if(joined) 72.0 else 32.0,"producer/brand interpretation; ${p.construction}")
            offer(mapOf(FieldId.WINE_NAME to text),if(joined) 78.0 else 43.0,"title interpretation; ${p.construction}")
        }
        val solution=EvidenceSolver.solve(hypotheses,hierarchy,::checkBudget)
        val fields=FieldId.entries.associateWith { field ->
            val values=solution.map { assignment -> assignment.firstOrNull { field in it.values }?.values?.get(field).orEmpty() }.distinct()
            val winner=solution.firstOrNull()?.firstOrNull { field in it.values }
            val value=values.singleOrNull().orEmpty()
            FieldCandidate(field,value,if(value.isBlank()) UiState.RED else UiState.YELLOW,
                if(value.isBlank()) "Experimental abstention: missing or competing assignments" else "${winner?.reason}; phrase=${winner?.phrase?.id}; observations=${winner?.phrase?.observations}; experimental rank, not probability; confirm",
                emptyList())
        }
        val path=solution.firstOrNull()?.firstOrNull { it.place!=null }?.place?.let(hierarchy::ancestors).orEmpty()
        val alternatives=FieldId.entries.associateWith { f -> hypotheses.filter { f in it.values }.sortedByDescending { it.rank }.distinctBy { it.values[f] }.take(6).map {
            FieldCandidate(f,it.values.getValue(f),UiState.YELLOW,"${it.reason}; phrase=${it.phrase.id}; observations=${it.phrase.observations}; rank=${it.rank}",emptyList())
        } }
        val fill=CellarFieldPresentation.apply(FillResult(fields,"Experimental saved-reading extraction; not production autofill",alternatives,
            roleNotes.take(384)+"observations=${graph.observations.size}; phrases=${graph.phrases.size}; hypotheses=${hypotheses.size}; truncated=${graph.truncated || hypotheses.size>=MAX_HYPOTHESES}",geographyPath=path))
        return EvidenceResult(fill,graph,hypotheses,(System.nanoTime()-started)/1_000_000)
    }
}
