package com.yourapp.wine.textlab

import com.yourapp.wine.textlab.evidence.*
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.fill.*
import kotlinx.coroutines.CancellationException

internal object TextLabEvidencePreview {
    fun engine(kb:WineKnowledgeBase,useAnchors:Boolean)=EvidenceExtractor(kb,ranking=EvidenceRankingConfig(cuveeRole=useAnchors))
    fun append(journal:TextLabJournal,engine:EvidenceExtractor?,year:Int,useAnchors:Boolean=false,hybrid:EvidenceAnchorHybrid?=null) {
        if(journal.rows.any { it["stage"]=="evidence_summary" }) return
        val values=mutableMapOf("post_processing_version" to if(useAnchors) EvidenceAnchorHybrid.VERSION else EvidenceExtractor.VERSION,"field_year" to year.toString(),
            "preview_policy" to if(useAnchors) "guarded hybrid: saved baseline producer/vintage, rules-001 wine/origin, heading-only producer abstention; no production form writes" else "experimental independent evidence graph; saved readings; no production form writes")
        try {
            check(engine!=null) { "Experimental extractor unavailable or skipped after memory pressure" }
            check(journal.detectLines) { "Experimental layout requires Detect text lines; single-line coordinates are insufficient" }
            val baseline=if(useAnchors) journal.rows.singleOrNull { it["stage"]=="form_summary" && it["status"] in setOf("suggestions","partial_suggestions","no_fields") } else null
            if(useAnchors) {
                check(hybrid!=null && baseline!=null) { "Guarded hybrid requires an existing successful baseline summary; it will not rerun baseline or silently switch architecture" }
                check(FieldId.entries.all { baseline.containsKey(it.name.lowercase(java.util.Locale.ROOT)) }) { "Incomplete baseline summary" }
            }
            val records=TextLabFormPreview.records(journal)
            val result=engine.extract(records,year)
            val fill=result.fill
            for(field in FieldId.entries) {
                val key=field.name.lowercase(java.util.Locale.ROOT);val candidate=fill.fields.getValue(field)
                values[key]=candidate.value;values["${key}_state"]=candidate.state.name;values["${key}_reason"]=candidate.reason
                values["${key}_alternatives"]=fill.alternatives[field].orEmpty().joinToString(" | ") { it.value+": "+it.reason }
            }
            var hybridMs=0L
            if(useAnchors) {
                val start=android.os.SystemClock.elapsedRealtime()
                val original=FieldId.entries.associateWith { baseline!!.getValue(it.name.lowercase(java.util.Locale.ROOT)) }
                val combined=hybrid!!.combine(original,fill.fields.mapValues { it.value.value },records,EvidenceAnchorHybrid.Config(true))
                hybridMs=android.os.SystemClock.elapsedRealtime()-start
                combined.forEach { (field,value) ->
                    val key=field.name.lowercase(java.util.Locale.ROOT)
                    values[key]=value;values["${key}_state"]=if(value.isBlank()) "RED" else "YELLOW"
                    if(field in setOf(FieldId.PRODUCER,FieldId.VINTAGE)) {
                        values["${key}_alternatives"]=baseline!!.get("${key}_alternatives").orEmpty()
                        values["${key}_reason"]=if(value.isBlank() && original.getValue(field).isNotBlank()) "Incomplete producer heading suppressed; confirm manually" else "Saved baseline anchor; confirm manually"
                    }
                }
            }
            values["field_input_lines"]=result.graph.observations.size.toString()
            values["field_values_found"]=FieldId.entries.count { values[it.name.lowercase(java.util.Locale.ROOT)].orEmpty().isNotBlank() }.toString()
            values["work_ms"]=(result.elapsedMs+hybridMs).toString()
            values["role_diagnostics"]=fill.postProcessing.joinToString("\n")
            // Separate bounded diagnostic rows preserve the graph without replacing original recognition rows.
            result.graph.observations.forEach { o -> journal.add("experimental_evidence","evidence_observation",values=mapOf(
                "region_id" to o.id.toString(),"raw_text" to o.record.textRaw,
                "evidence_details" to "engine=${o.record.engineId}; score=${o.record.score}; estimated=${o.record.scoreIsEstimated}",
                "base_box_normalized" to listOf(o.record.bboxRect.left,o.record.bboxRect.top,o.record.bboxRect.right,o.record.bboxRect.bottom).joinToString(" "))) }
            result.graph.phrases.forEach { p -> journal.add("experimental_evidence","evidence_phrase",values=mapOf(
                "region_id" to p.id.toString(),"raw_text" to p.text,"evidence_details" to "${p.construction}; observations=${p.observations}")) }
            result.hypotheses.forEachIndexed { index,h -> journal.add("experimental_evidence","evidence_hypothesis",values=mapOf(
                "region_id" to index.toString(),"evidence_details" to "phrase=${h.phrase.id}; observations=${h.phrase.observations}; rank=${h.rank}; ${h.reason}; values=${h.values}")) }
            result.trace.rows.forEach { event -> journal.add("experimental_evidence","evidence_decision",values=mapOf("evidence_details" to event)) }
            values["trace_counts"]=result.trace.counts.toString()
            journal.add("experimental_evidence","evidence_summary",if(values["field_values_found"]=="0") "no_fields" else "suggestions",values,
                "Experimental, not ground truth. Same saved OCR as production baseline; shared lexical KB and cellar display only.")
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) { journal.add("experimental_evidence","evidence_summary","failed",values,details="${error.javaClass.simpleName}: ${error.message}") }
    }
}
