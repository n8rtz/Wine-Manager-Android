package com.yourapp.wine.textlab

import com.yourapp.wine.textlab.evidence.*
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.fill.*
import kotlinx.coroutines.CancellationException

internal object TextLabEvidencePreview {
    // equivalentNames: two spellings of one name differing only by spacing/punctuation (`CHATEAU
    // ABELYCE` / `CHATEAU ABELY CE`) are one answer, not an ambiguity that blanks the field.
    fun engine(kb:WineKnowledgeBase,useAnchors:Boolean,repairPlaces:Boolean=false)=EvidenceExtractor(kb,ranking=EvidenceRankingConfig(equivalentNames=true,cuveeRole=useAnchors),
        placeRepair=if(repairPlaces) PlaceRepair.Config.experimental else PlaceRepair.Config.off)
    fun append(journal:TextLabJournal,engine:EvidenceExtractor?,year:Int,useAnchors:Boolean=false,hybrid:EvidenceAnchorHybrid?=null,useProducerRepair:Boolean=false,producerRepair:ProducerRepair?=null,recordsOverride:List<com.yourapp.wine.ocr.TokenRecord>?=null,exactGeographyFastPath:Boolean=false):EvidenceResult? {
        if(journal.rows.any { it["stage"]=="evidence_summary" }) return null
        val values=mutableMapOf("post_processing_version" to if(useProducerRepair) ProducerRepair.VERSION else if(useAnchors) EvidenceAnchorHybrid.VERSION else EvidenceExtractor.VERSION,"field_year" to year.toString(),
            "preview_policy" to if(useAnchors) "guarded hybrid: saved baseline producer; populated vintage preserved, otherwise validated experimental vintage may fill; rules-001 wine/origin; heading-only producer abstention; no production form writes" else "experimental independent evidence graph; saved readings; no production form writes")
        return try {
            check(engine!=null) { "Experimental extractor unavailable or skipped after memory pressure" }
            check(journal.detectLines) { "Experimental layout requires Detect text lines; single-line coordinates are insufficient" }
            if(useProducerRepair) check(useAnchors && producerRepair!=null) { "Producer repair requires guarded hybrid and its repair engine" }
            val baseline=if(useAnchors) journal.rows.singleOrNull { it["stage"]=="form_summary" && it["status"] in setOf("suggestions","partial_suggestions","no_fields") } else null
            if(useAnchors) {
                check(hybrid!=null && baseline!=null) { "Guarded hybrid requires an existing successful baseline summary; it will not rerun baseline or silently switch architecture" }
                check(FieldId.entries.all { baseline.containsKey(it.name.lowercase(java.util.Locale.ROOT)) }) { "Incomplete baseline summary" }
            }
            val records=recordsOverride ?: TextLabFormPreview.records(journal)
            val result=engine.extract(records,year,exactGeographyFastPath=exactGeographyFastPath)
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
                val combined=hybrid!!.combine(original,fill.fields.mapValues { it.value.value },records,EvidenceAnchorHybrid.Config(true),year)
                hybridMs=android.os.SystemClock.elapsedRealtime()-start
                combined.forEach { (field,value) ->
                    val key=field.name.lowercase(java.util.Locale.ROOT)
                    values[key]=value;values["${key}_state"]=if(value.isBlank()) "RED" else "YELLOW"
                    if(field in setOf(FieldId.PRODUCER,FieldId.VINTAGE)) {
                        values["${key}_alternatives"]=baseline!!.get("${key}_alternatives").orEmpty()
                        values["${key}_reason"]=when(field) {
                            FieldId.PRODUCER -> if(value.isBlank() && original.getValue(field).isNotBlank())
                                "Incomplete producer heading suppressed; confirm manually"
                            else "Saved baseline producer anchor; confirm manually"
                            FieldId.VINTAGE -> when {
                                original.getValue(field).isNotBlank() -> "Saved baseline vintage anchor preserved; confirm manually"
                                value.isNotBlank() -> "Validated experimental vintage filled a blank baseline anchor; confirm manually"
                                else -> "No validated vintage evidence; baseline anchor was blank"
                            }
                            else -> "Saved baseline anchor; confirm manually"
                        }
                    }
                }
            }
            if(useProducerRepair) {
                val started=android.os.SystemClock.elapsedRealtime()
                val original=values.getValue("producer")
                // Historical alternatives are audit-only: actual repairs require current observed phrase support.
                val decision=producerRepair!!.repair(original,records,emptyList(),ProducerRepair.recommendedConfig)
                hybridMs+=android.os.SystemClock.elapsedRealtime()-started
                values["producer"]=decision.value
                values["producer_state"]=if(decision.value.isBlank()) "RED" else "YELLOW"
                values["producer_reason"]=decision.reason+"; before="+original+"; confirm manually"
                values["preview_policy"]=values.getValue("preview_policy")+"; conservative two-engine producer repair"
                decision.candidates.forEach { c -> journal.add("experimental_evidence","evidence_decision",values=mapOf("evidence_details" to "producer-repair candidate: $c")) }
                journal.add("experimental_evidence","evidence_decision",values=mapOf("evidence_details" to "producer-repair result: before=$original; after=${decision.value}; ${decision.reason}"))
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
            result
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            journal.add("experimental_evidence","evidence_summary","failed",values,details="${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }
}
