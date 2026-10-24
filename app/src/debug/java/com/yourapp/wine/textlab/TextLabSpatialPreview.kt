package com.yourapp.wine.textlab

import com.yourapp.wine.textlab.evidence.EvidenceExtractor
import com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid
import com.yourapp.wine.textlab.evidence.ProducerRepair
import kotlinx.coroutines.CancellationException
import com.yourapp.wine.gazetteer.ProducerResolver

/** Latest Lab only. No second OCR pass and no separate v2.0 contender execution.
 * The raw form summary is an internal anchor required by the existing guarded algorithm.
 * Original observations feed extraction unchanged; spatial alternatives feed only producer lookup.
 */
internal object TextLabSpatialPreview {
    const val VERSION="raw-plus-producer-v1"
    fun append(journal:TextLabJournal,engine:EvidenceExtractor?,hybrid:EvidenceAnchorHybrid?,repair:ProducerRepair?,year:Int,producerIndex:ProducerResolver.Index?=null,
        producerUnavailable:String="primary LWIN export required",checkProducerBudget:()->Unit={}) {
        if(journal.rows.any { it["stage"]=="spatial_evidence_summary" }) return
        try {
            val original=TextLabFormPreview.records(journal)
            val proposals=TextLabSpatialPhrases.propose(original)
            for((index,proposal) in proposals.withIndex()) {
                journal.add("spatial_lab","spatial_phrase",values=mapOf("post_processing_version" to VERSION,
                    "region_id" to index.toString(),"raw_text" to proposal.phrase.mergedText,
                    "source_engine" to proposal.engineId,"source_variant" to proposal.variantId,
                    "source_indices_in_engine_pass" to proposal.phrase.sourceIndices.joinToString(" "),
                    "source_texts" to proposal.sources.joinToString("\n") { it.textRaw },
                    "source_scores" to proposal.sources.joinToString(" ") { it.score.toString() },
                    "source_rectified_boxes" to proposal.sources.joinToString(";") { it.bboxRect.toString() },
                    "source_original_boxes" to proposal.sources.joinToString(";") { it.bboxOrig.toString() }))
            }
            // Preserve originals. Spatial joins are competing producer lookup spans, never replacement observations.
            val readings=original
            val work=TextLabJournal(journal.selected,journal.build,journal.detectLines)
            work.metadata.putAll(journal.metadata)
            // Unchanged raw baseline anchor, NOT a separately evaluated production-v2.0 result.
            work.rows.addAll(journal.rows.filter { it["stage"]=="form_summary" })
            TextLabEvidencePreview.append(work,engine,year,useAnchors=true,hybrid=hybrid,
                useProducerRepair=true,producerRepair=repair,recordsOverride=readings)
            val spans=original.mapIndexed { id,r -> ProducerResolver.Span(r.textRaw,r.engineId,setOf(id),r.scoreIsEstimated) }+
                proposals.filter { it.sources.size>1 }.map { p -> ProducerResolver.Span(p.phrase.mergedText,p.engineId,
                    p.sources.map { source -> original.indexOfFirst { it===source } }.filter { it>=0 }.toSet(),true) }
            for(rawRow in work.rows.filter { it["stage"]!="form_summary" }) {
                val row=rawRow.toMutableMap()
                if(row["stage"]=="evidence_summary" && row["status"]!="failed") {
                    val before=row["producer"].orEmpty()
                    row["producer_before_gazetteer"]=before
                    row["producer_gazetteer_version"]=producerIndex?.version ?: "unavailable"
                    if(producerIndex==null) {
                        row["producer_gazetteer_status"]="unavailable"
                        row["producer_gazetteer_reason"]=producerUnavailable
                    } else {
                        val started=android.os.SystemClock.elapsedRealtime()
                        val decision=try { ProducerResolver(producerIndex).resolve(before,spans,checkProducerBudget) }
                            catch(cancel:CancellationException) { throw cancel }
                            catch(error:Exception) { ProducerResolver.Decision(before,"lookup failed; unchanged: ${error.javaClass.simpleName}",emptyList()) }
                        row["producer_gazetteer_status"]=if(decision.acceptedId!=null) "suggestion" else "unchanged"
                        row["producer_gazetteer_reason"]=decision.reason
                        val producerMs=android.os.SystemClock.elapsedRealtime()-started
                        row["producer_gazetteer_ms"]=producerMs.toString()
                        row["work_ms"]=((row["work_ms"]?.toLongOrNull() ?: 0L)+producerMs).toString()
                        row["producer_gazetteer_entity_id"]=decision.acceptedId.orEmpty()
                        decision.matches.take(128).forEach { m -> journal.add("spatial_lab","producer_gazetteer_candidate",values=mapOf(
                            "raw_text" to m.observed,"source_engine" to m.engine,"source_observation_ids" to m.observationIds.sorted().joinToString(" "),
                            "producer_entity_id" to m.entity.id,"producer_display_name" to m.entity.name,
                            "edit_distance" to m.distance.toString(),"lexical_support_sources" to m.entity.support.sorted().joinToString(" | "),
                            "producer_gazetteer_version" to producerIndex.version)) }
                        if(decision.acceptedId!=null) {
                            row["producer"]=decision.value;row["producer_state"]="YELLOW";row["producer_reason"]=decision.reason
                            row["producer_alternatives"]="" // stale alternatives must not accompany a different accepted name
                            row["field_values_found"]=com.yourapp.wine.fill.FieldId.entries.count { row[it.name.lowercase(java.util.Locale.ROOT)].orEmpty().isNotBlank() }.toString()
                            row["status"]="suggestions"
                        }
                    }
                }
                journal.add("spatial_lab","spatial_"+row.getValue("stage"),row.getValue("status"),
                    row.filterKeys { it !in setOf("candidate","stage","status","sequence","details") }+
                        mapOf("post_processing_version" to "$VERSION+${ProducerRepair.VERSION}+${ProducerResolver.VERSION}",
                            "spatial_source_count" to original.size.toString(),"spatial_reading_count" to readings.size.toString(),
                            "spatial_merged_groups" to proposals.count { it.sources.size>1 }.toString()),row["details"].orEmpty())
            }
            check(journal.rows.any { it["stage"]=="spatial_evidence_summary" }) { "Spatial extractor produced no summary" }
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            journal.add("spatial_lab","spatial_evidence_summary","failed",mapOf("post_processing_version" to VERSION),"${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
