package com.yourapp.wine.textlab

import com.yourapp.wine.textlab.evidence.EvidenceExtractor
import com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid
import com.yourapp.wine.textlab.evidence.ProducerRepair
import kotlinx.coroutines.CancellationException

/** Latest Lab only. No second OCR pass and no separate v2.0 contender execution.
 * The raw form summary is an internal anchor required by the existing guarded algorithm.
 * Original recognition rows are retained unchanged; spatial proposals feed the actual extractor.
 */
internal object TextLabSpatialPreview {
    const val VERSION="spatial-phrases-v1"
    fun append(journal:TextLabJournal,engine:EvidenceExtractor?,hybrid:EvidenceAnchorHybrid?,repair:ProducerRepair?,year:Int) {
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
            val readings=TextLabSpatialPhrases.readings(proposals)
            val work=TextLabJournal(journal.selected,journal.build,journal.detectLines)
            work.metadata.putAll(journal.metadata)
            // Unchanged raw baseline anchor, NOT a separately evaluated production-v2.0 result.
            work.rows.addAll(journal.rows.filter { it["stage"]=="form_summary" })
            TextLabEvidencePreview.append(work,engine,year,useAnchors=true,hybrid=hybrid,
                useProducerRepair=true,producerRepair=repair,recordsOverride=readings)
            for(row in work.rows.filter { it["stage"]!="form_summary" }) {
                journal.add("spatial_lab","spatial_"+row.getValue("stage"),row.getValue("status"),
                    row.filterKeys { it !in setOf("candidate","stage","status","sequence","details") }+
                        mapOf("post_processing_version" to "$VERSION+${ProducerRepair.VERSION}",
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
