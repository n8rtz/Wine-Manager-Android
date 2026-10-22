package com.yourapp.wine.textlab

import com.yourapp.wine.textlab.evidence.EvidenceExtractor
import com.yourapp.wine.fill.*
import kotlinx.coroutines.CancellationException

internal object TextLabEvidencePreview {
    fun append(journal:TextLabJournal,engine:EvidenceExtractor?,year:Int) {
        if(journal.rows.any { it["stage"]=="evidence_summary" }) return
        val values=mutableMapOf("post_processing_version" to EvidenceExtractor.VERSION,"field_year" to year.toString(),
            "preview_policy" to "experimental independent evidence graph; saved readings; no production form writes")
        try {
            check(engine!=null) { "Experimental extractor unavailable or skipped after memory pressure" }
            check(journal.detectLines) { "Experimental layout requires Detect text lines; single-line coordinates are insufficient" }
            val result=engine.extract(TextLabFormPreview.records(journal),year)
            val fill=result.fill
            for(field in FieldId.entries) {
                val key=field.name.lowercase(java.util.Locale.ROOT);val candidate=fill.fields.getValue(field)
                values[key]=candidate.value;values["${key}_state"]=candidate.state.name;values["${key}_reason"]=candidate.reason
                values["${key}_alternatives"]=fill.alternatives[field].orEmpty().joinToString(" | ") { it.value+": "+it.reason }
            }
            values["field_input_lines"]=result.graph.observations.size.toString()
            values["field_values_found"]=fill.fields.values.count { it.value.isNotBlank() }.toString()
            values["work_ms"]=result.elapsedMs.toString()
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
            journal.add("experimental_evidence","evidence_summary",if(fill.fields.values.all { it.value.isBlank() }) "no_fields" else "suggestions",values,
                "Experimental, not ground truth. Same saved OCR as production baseline; shared lexical KB and cellar display only.")
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) { journal.add("experimental_evidence","evidence_summary","failed",values,details="${error.javaClass.simpleName}: ${error.message}") }
    }
}
