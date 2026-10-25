package com.yourapp.wine.textlab

import android.os.SystemClock
import com.yourapp.wine.extract.WineDesignations
import com.yourapp.wine.gazetteer.TrigramGazetteer
import com.yourapp.wine.kb.BundledGrapes
import com.yourapp.wine.kb.BundledProducerNames
import com.yourapp.wine.textlab.residual.ResidualTagger
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Candidate discovery only. No FillEngine, producer selection, wine selection, form mutation, or OCR. */
internal object TextLabResidualPreview {
    fun append(journal:TextLabJournal,resolver:TrigramGazetteer?,year:Int,setupError:String="") {
        if(journal.rows.any { it["stage"]=="residual_summary" }) return
        try {
            check(resolver!=null) { setupError.ifBlank { "Gazetteer unavailable; no partial subtraction" } }
            val deadline=SystemClock.elapsedRealtime()+60000L
            fun budget() { check(SystemClock.elapsedRealtime()<deadline) { "Residual diagnostic deadline exceeded" } }
            val records=TextLabFormPreview.records(journal)
            require(records.size<=128) { "Residual observation cap exceeded" }
            val tagger=ResidualTagger(BundledGrapes.terms.flatMap { listOf(it.name)+it.aliases },WineDesignations.names,
                BundledProducerNames.terms.flatMap { listOf(it.name)+it.aliases })
            val cache=mutableMapOf<String,ResidualTagger.Geography?>()
            val results=records.map { record ->
                tagger.tag(record.textRaw,year,{ raw ->
                    budget()
                    if(!cache.containsKey(raw)) {
                        check(cache.size<256) { "Residual total lookup cap exceeded" }
                        val r=resolver.resolve(raw,::budget);val h=r.accepted
                        cache[raw]=if(h!=null) ResidualTagger.Geography(true,h.distance==0,
                            TrigramGazetteer.normalize(raw)==r.query,h.entity.name,h.entity.id.toString(),r.reason)
                        else if(r.reason=="ambiguous entities") ResidualTagger.Geography(false,false,true,reason=r.reason) else null
                    }
                    cache[raw]
                },::budget)
            }
            budget()
            // Publish only after the entire stage succeeds, so failure cannot look like a partial candidate set.
            results.forEachIndexed { index,result ->
                val source=records[index]
                val common=mapOf("source_observation" to index.toString(),"raw_ocr_input" to result.raw,
                    "source_engine" to source.engineId,"source_variant" to source.variantId,
                    "original_box" to source.bboxOrig.toString(),"rectified_box" to source.bboxRect.toString(),
                    "box_scope" to "whole source observation; NOT a derived character box", "offset_units" to "UTF-16; end exclusive",
                    "post_processing_version" to ResidualTagger.VERSION)
                result.tags.forEach { tag ->
                    journal.add("residual","residual_tag","tagged",common+mapOf("span_start" to tag.span.start.toString(),"span_end" to tag.span.end.toString(),
                        "span_text" to tag.span.text(result.raw),"tag" to tag.kind.name,"subtracted" to tag.subtract.toString(),
                        "matched_term" to tag.canonical,"entity_id" to tag.entityId),tag.reason)
                }
                result.candidates.forEach { c ->
                    journal.add("residual","residual_candidate","unassigned",common+mapOf("span_start" to c.span.start.toString(),"span_end" to c.span.end.toString(),
                        "candidate_text" to c.text,"identity_role" to "producer_or_cuvee_or_noise", "identity_confidence" to "not established",
                        "same_segment_qualifiers" to JSONArray(c.qualifiers.map { span -> JSONObject().put("start",span.start).put("end",span.end).put("text",span.text(result.raw)) }).toString()),
                        c.warnings.joinToString("; ")+"; qualifier attachment is not established")
                }
            }
            val count=results.sumOf { it.candidates.size }
            journal.add("residual","residual_summary",if(count==0) "no_candidates" else "candidates",mapOf(
                "post_processing_version" to ResidualTagger.VERSION,"source_observations" to records.size.toString(),
                "candidate_count" to count.toString(),"tag_count" to results.sumOf { it.tags.size }.toString(),"lookup_count" to cache.size.toString(),
                "candidate_texts" to results.flatMap { it.candidates }.map { it.text }.distinct().joinToString(" | ")),
                "Candidates only, not producer/wine predictions. Raw readings and all six existing fields are unchanged.")
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            journal.add("residual","residual_summary","failed",mapOf("post_processing_version" to ResidualTagger.VERSION),"${error.javaClass.simpleName}: ${error.message}")
        }
    }
    fun text(journals:List<TextLabJournal>):String=buildString {
        val summaries=journals.flatMap { j -> j.rows.filter { it["stage"]=="residual_summary" }.map { j to it } }
        if(summaries.isNotEmpty()) {
            appendLine("RESIDUAL PRODUCER / CUVÉE CANDIDATES — UNASSIGNED")
            appendLine("Not predictions: remaining text may still be noise. Qualifiers, rejected spans and source boxes are retained in CSV.")
            summaries.forEach { (j,row) ->
                appendLine("${j.metadata["image_name"]}: ${row["status"]}; ${row["candidate_count"].orEmpty()} fragments; ${row["candidate_texts"].orEmpty()}")
                if(row["status"]=="failed") appendLine(row["details"])
            }
        }
    }
}
