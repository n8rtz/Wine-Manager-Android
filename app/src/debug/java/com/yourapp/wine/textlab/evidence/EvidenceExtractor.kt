package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.extract.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord

internal data class Hypothesis(val values:Map<FieldId,String>,val phrase:Phrase,val rank:Double,val reason:String,val place:Place?=null)
internal data class EvidenceResult(val fill:FillResult,val graph:EvidenceGraph,val hypotheses:List<Hypothesis>,val elapsedMs:Long,val trace:EvidenceTrace)

/** Independent lab-only solver. Shared lexical catalogs, not production line/merge/extract/validate rules. */
internal class EvidenceExtractor(base:WineKnowledgeBase,private val typedIdentity:Boolean=true,private val search:EvidenceSearchConfig=EvidenceSearchConfig()) {
    companion object { const val VERSION="evidence-lab-v3-heading-roles";const val DEFAULT_BUDGET_MS=60_000L;const val MAX_HYPOTHESES=384 }
    private val kb=SupplementedWineKnowledgeBase(base)
    private val gazetteer=PlaceGazetteer(kb)
    private val hierarchy=PlaceHierarchy(kb).snapshot()
    private val roles=ProducerLineSelector()
    fun extract(records:List<TokenRecord>,year:Int,budgetMs:Long=DEFAULT_BUDGET_MS):EvidenceResult {
        val started=System.nanoTime()
        fun checkBudget() {
            if(Thread.currentThread().isInterrupted) throw InterruptedException("Evidence extraction interrupted")
            check((System.nanoTime()-started)/1_000_000<budgetMs) { "Evidence extraction budget exceeded" }
        }
        val trace=EvidenceTrace()
        val graph=EvidenceLexicon.expand(EvidenceGraphBuilder.build(records,::checkBudget,typedIdentity),::checkBudget)
        trace.add("graph", "observations=${graph.observations.size}; phrases=${graph.phrases.size}; truncated=${graph.truncated}")
        val hypotheses=mutableListOf<Hypothesis>()
        val roleNotes=mutableListOf<String>()
        for(p in graph.phrases.sortedByDescending { it.construction.contains("alternative") }) {
            checkBudget()
            if(hypotheses.size>=MAX_HYPOTHESES) { trace.add("hypothesis_limit", "remaining phrases not searched");break }
            val text=WineTermSpacing.wineWords.repair(p.text).text;val norm=TextNorm.normalize(text)
            val endsConnector=Regex("\\b(de|du|di|des)$").containsMatchIn(norm)
            val beginsConnector=Regex("^(de|du|di|des)\\b").containsMatchIn(norm)
            val lexicalBonus=6.0*p.construction.split("alternative").lastIndex
            val sources=graph.observations.filter { it.id in p.observations }
            val supported=sources.any { it.record.score >= if(it.record.engineId.startsWith("paddle")) .75f else .60f }
            if(!supported) { trace.add("unsupported", "phrase=${p.id}");continue } // original reading retained
            val administrative=MetadataRules.isBoilerplate(text) || MetadataRules.isPackaging(text) || '%' in text
            val paragraph=p.box.width()<.25f && graph.phrases.count { other -> other.id!=p.id &&
                kotlin.math.abs(other.box.left-p.box.left)<.04f && kotlin.math.abs(other.box.centerY()-p.box.centerY())<.12f }>=3
            val headingOnly=typedIdentity && EvidenceIdentityRoles.headingOnly(text)
            val namedEstate=typedIdentity && EvidenceIdentityRoles.namedEstate(text)
            val places=if(administrative || headingOnly || namedEstate) emptyList() else gazetteer.matches(norm)
            roleNotes+="phrase=${p.id}; administrative=$administrative; paragraph=$paragraph; places=${places.joinToString { it.name }}"
            if(administrative || headingOnly) { trace.add(if(administrative) "administrative" else "incomplete_producer_heading", "phrase=${p.id}; text=$text");continue }
            fun offer(values:Map<FieldId,String>,rank:Double,why:String,place:Place?=null) {
                val incomplete=sources.count { source ->
                    val key=TextNorm.normalize(source.record.textRaw).filter(Char::isLetter)
                    key.length>=3 && graph.observations.any { other ->
                        val full=TextNorm.normalize(other.record.textRaw).filter(Char::isLetter)
                        other.record.engineId!=source.record.engineId && other.record.score>=.75f &&
                            full.length>=key.length+3 && full.startsWith(key) &&
                            com.yourapp.wine.merge.BoxMath.iou(other.record.bboxRect,source.record.bboxRect)>.08f
                    }
                }
                val weakContent=sources.count { TextNorm.normalize(it.record.textRaw) !in setOf("de","du","di","des") && it.record.score < if(it.record.engineId.startsWith("paddle")) .75f else .60f }
                if(hypotheses.size<MAX_HYPOTHESES) hypotheses+=Hypothesis(values,p,rank-weakContent*10-incomplete*6,why+if(incomplete>0) "; overlapping engine supplies a fuller reading" else "",place)
            }
            if(Regex("(?:19|20)\\d{2}").matches(norm) && norm.toInt() in 1900..year)
                offer(mapOf(FieldId.VINTAGE to norm),55.0,"complete observed vintage")
            for(place in places.take(4)) {
                val path=hierarchy.ancestors(place)
                val values=mutableMapOf<FieldId,String>()
                path.lastOrNull { it.type=="country" }?.let { values[FieldId.COUNTRY]=it.name }
                path.firstOrNull { it.type=="region" }?.let { values[FieldId.REGION]=it.name }
                val core=place.nameNorm.filter(Char::isLetterOrDigit)
                val compact=norm.filter(Char::isLetterOrDigit)
                val suffix=if(compact.startsWith(core)) compact.removePrefix(core) else ""
                val unresolvedSuffix=place.type=="appellation" && suffix.length in 1..8 && suffix.all(Char::isLetter) &&
                    suffix !in setOf("aoc","aop","doc","docg","dop","igp") && gazetteer.matches(suffix).none { it.type=="country" } &&
                    places.none { it.nameNorm.filter(Char::isLetterOrDigit)==compact }
                if(place.type !in setOf("country","region") && !unresolvedSuffix) values[FieldId.SUBREGION]=place.name
                offer(values,50.0+path.size*5+lexicalBonus,"observed geography and actual ancestors; no producer-origin inference"+(if(unresolvedSuffix) "; unresolved appellation suffix: finer locality withheld" else ""),place)
            }
            if(places.any { TextNorm.normalize(it.name)==norm } && WineDesignations.isAppellationName(text))
                offer(mapOf(FieldId.WINE_NAME to text),65.0,"exact printed appellation may also name the wine")
            if(!MetadataRules.isIdentity(text) || paragraph || places.isNotEmpty() || endsConnector || beginsConnector) { trace.add("identity_filtered", "phrase=${p.id}; paragraph=$paragraph; places=${places.size}; connector=${endsConnector || beginsConnector}");continue }
            BundledProducerNames.exactName(text)?.let { offer(mapOf(FieldId.PRODUCER to it),105.0,"exact authored producer alias; no origin inference") }
            val known=kb.findProducer(text).filter { it.editDistance==0 }.take(3)
            known.forEach { offer(mapOf(FieldId.PRODUCER to it.producer.name),110.0,"exact local producer; catalog location is not wine origin") }
            if(p.construction.contains("designation-linked") && !WineDesignations.isDesignation(text)) {
                offer(mapOf(FieldId.WINE_NAME to WineDesignations.orderName(text)),90.0+lexicalBonus,"complete name with adjacent designation")
                continue
            }
            if(roles.isWineDescription(text) || WineDesignations.isDesignation(text)) {
                offer(mapOf(FieldId.WINE_NAME to (WineDesignations.display(text) ?: text)),75.0,"observed varietal or designation")
                continue
            }
            if(norm.count(Char::isLetter)<4 || norm.any(Char::isDigit)) continue
            val joined=p.construction.contains("connector")
            val titleCue=Regex("\\b(reserve|reserva|riserva)\\b").containsMatchIn(norm)
            if(!titleCue) offer(mapOf(FieldId.PRODUCER to text),(if(roles.hasProducerCue(text)) 85.0 else if(joined) 82.0 else 32.0)+lexicalBonus,"producer/brand interpretation; ${p.construction}")
            if(!namedEstate) offer(mapOf(FieldId.WINE_NAME to text),(if(joined) 88.0 else 43.0)+lexicalBonus,"title interpretation; ${p.construction}")
        }
        val solution=EvidenceSolver.solve(hypotheses,hierarchy,graph,::checkBudget,trace,search)
        val fields=FieldId.entries.associateWith { field ->
            val values=solution.map { assignment -> assignment.firstOrNull { field in it.values }?.values?.get(field).orEmpty() }.distinctBy(TextNorm::normalize)
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
        return EvidenceResult(fill,graph,hypotheses,(System.nanoTime()-started)/1_000_000,trace)
    }
}
