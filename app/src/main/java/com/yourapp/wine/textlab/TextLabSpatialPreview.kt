package com.yourapp.wine.textlab

import com.yourapp.wine.textlab.evidence.EvidenceExtractor
import com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid
import com.yourapp.wine.textlab.evidence.ProducerRepair
import kotlinx.coroutines.CancellationException
import com.yourapp.wine.gazetteer.ProducerResolver
import com.yourapp.wine.gazetteer.LinkedWineResolver
import com.yourapp.wine.diagnostics.PipelineFunnel
import com.yourapp.wine.merge.TextNorm

/** Current spatial pipeline, shared by the two gazetteer benchmark contenders. No second OCR pass.
 * The raw form summary is an internal anchor required by the existing guarded algorithm.
 * Original observations feed extraction unchanged; spatial alternatives feed producer and linked
 * wine lookup. The contenders differ ONLY in the producer index handed in: Prod v3.0 gets the
 * verified LWIN-only index (candidate "spatial_lwin_lab", stage prefix "spatial_lwin_"), Lab v4
 * gets the Lab expanded index (defaults here). Each writes only its own candidate/stage rows.
 */
internal object TextLabSpatialPreview {
    const val VERSION="raw-plus-linked-wine-v2"
    fun append(journal:TextLabJournal,engine:EvidenceExtractor?,hybrid:EvidenceAnchorHybrid?,repair:ProducerRepair?,year:Int,producerIndex:ProducerResolver.Index?=null,
        producerUnavailable:String="primary LWIN export required",checkProducerBudget:()->Unit={},
        candidate:String="spatial_lab",stagePrefix:String="spatial_",
        policy:ProducerResolver.Policy=ProducerResolver.Policy.prod) {
        val summaryStage=stagePrefix+"evidence_summary"
        if(journal.rows.any { it["stage"]==summaryStage }) return
        try {
            val rawOriginal=TextLabFormPreview.records(journal)
            val filterOutcome=if(policy.minimumIndependentEngines<2) TextLabBottleFilter.filterTokens(rawOriginal) else null
            val original=filterOutcome?.kept ?: rawOriginal
            filterOutcome?.dropped?.forEachIndexed { index,item ->
                val token=item.entry.payload
                journal.add(candidate,stagePrefix+"filtered",values=mapOf("post_processing_version" to VERSION,
                    "region_id" to index.toString(),"raw_text" to token.textRaw,
                    "source_engine" to token.engineId,"filter_reason" to item.reason,
                    "base_box_normalized" to "${item.entry.rectBox.left} ${item.entry.rectBox.top} ${item.entry.rectBox.right} ${item.entry.rectBox.bottom}"))
            }
            val proposals=TextLabSpatialPhrases.propose(original)
            for((index,proposal) in proposals.withIndex()) {
                val mergedText=TextNorm.scrub(proposal.phrase.mergedText)
                journal.add(candidate,stagePrefix+"phrase",values=mapOf("post_processing_version" to VERSION,
                    "region_id" to index.toString(),"raw_text" to mergedText,
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
            // Unchanged raw baseline anchor for Prod v3.0 and unfiltered images; when Lab v4 drops
            // stray side-bottle or medal-sticker tokens, recompute the anchor on filtered readings so
            // dropped noise cannot leak back through EvidenceAnchorHybrid.
            if(filterOutcome!=null && filterOutcome.dropped.isNotEmpty() && engine!=null) {
                work.rows.addAll(journal.rows.filter { it["stage"] in setOf("summary","candidate_summary") })
                TextLabFormPreview.append(work,com.yourapp.wine.fill.FillEngine(engine.baseKb),year,recordsOverride=readings)
            } else {
                work.rows.addAll(journal.rows.filter { it["stage"]=="form_summary" })
            }
            TextLabEvidencePreview.append(work,engine,year,useAnchors=true,hybrid=hybrid,
                useProducerRepair=true,producerRepair=repair,recordsOverride=readings)
            val spans=original.mapIndexed { id,r -> ProducerResolver.Span(r.textRaw,r.engineId,setOf(id),r.scoreIsEstimated) }+
                proposals.filter { it.sources.size>1 }.map { p -> ProducerResolver.Span(TextNorm.scrub(p.phrase.mergedText),p.engineId,
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
                        row["wine_gazetteer_status"]="unavailable";row["wine_gazetteer_reason"]=producerUnavailable
                    } else {
                        val isLabMode=policy.minimumIndependentEngines<2
                        val started=android.os.SystemClock.elapsedRealtime()
                        var producerFailureTelemetry=emptyMap<String,String>()
                        fun runProducerLookup(policy:ProducerResolver.Policy)=
                            ProducerResolver(producerIndex,onFailureTelemetry={ producerFailureTelemetry=it }).resolve(before,spans,checkProducerBudget,policy)
                        val activePolicy=if(isLabMode) {
                            policy.withCountryContext(row["country"].orEmpty())
                                .withExactMatchFastPath { term -> engine?.isNonProducerCatalogTerm(term)==true }
                        } else policy
                        val decision=try { runProducerLookup(activePolicy) }
                            catch(cancel:CancellationException) { throw cancel }
                            catch(error:Exception) { ProducerResolver.Decision(before,"lookup failed; unchanged: ${error.javaClass.simpleName}",emptyList(),telemetry=
                                producerFailureTelemetry.ifEmpty { PipelineFunnel.Recorder("producer.").apply { visit("invoked",true);visit("execution",false,error.javaClass.simpleName) }.fields() }) }
                        row.putAll(decision.telemetry)
                        row["producer_gazetteer_status"]=if(decision.acceptedId!=null) "suggestion" else "unchanged"
                        row["producer_gazetteer_reason"]=decision.reason
                        val producerMs=android.os.SystemClock.elapsedRealtime()-started
                        row["producer_gazetteer_ms"]=producerMs.toString()
                        row["work_ms"]=((row["work_ms"]?.toLongOrNull() ?: 0L)+producerMs).toString()
                        row["producer_gazetteer_entity_id"]=decision.acceptedId.orEmpty()
                        decision.matches.take(128).forEach { m -> journal.add(candidate,"producer_gazetteer_candidate",values=mapOf(
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
                        val linkedIndex=producerIndex as? LinkedWineResolver.Index
                        row["wine_before_gazetteer"]=row["wine_name"].orEmpty()
                        var linkedDecision:LinkedWineResolver.Decision?=null
                        if(linkedIndex!=null) {
                            val wineStart=android.os.SystemClock.elapsedRealtime()
                            val wineBefore=row["wine_name"].orEmpty()
                            var wineFailureTelemetry=emptyMap<String,String>()
                            fun runLinkedWineLookup(policy:ProducerResolver.Policy)=
                                LinkedWineResolver(linkedIndex,onFailureTelemetry={ wineFailureTelemetry=it }).resolve(before,wineBefore,decision,spans,checkProducerBudget,policy)
                            val linked=try { runLinkedWineLookup(activePolicy) }
                                catch(cancel:CancellationException) { throw cancel }
                                catch(error:Exception) { LinkedWineResolver.Decision(decision.value,wineBefore,"linked lookup failed; unchanged: ${error.javaClass.simpleName}",telemetry=
                                    wineFailureTelemetry.ifEmpty { PipelineFunnel.Recorder("wine.").apply { visit("invoked",true);visit("execution",false,error.javaClass.simpleName) }.fields() }) }
                            linkedDecision=linked
                            row.putAll(linked.telemetry)
                            val wineMs=android.os.SystemClock.elapsedRealtime()-wineStart
                            row["wine_gazetteer_ms"]=wineMs.toString()
                            row["work_ms"]=((row["work_ms"]?.toLongOrNull() ?: 0L)+wineMs).toString()
                            row["wine_gazetteer_status"]=if(linked.wineId!=null) "supported_pair" else "unchanged"
                            row["wine_gazetteer_reason"]=linked.reason
                            row["wine_gazetteer_id"]=linked.wineId.orEmpty()
                            row["wine_gazetteer_producer_id"]=linked.producerId.orEmpty()
                            row["wine_gazetteer_lwin7"]=linked.lwin7
                            linked.matches.take(128).forEach { m -> journal.add(candidate,"linked_wine_candidate",values=mapOf(
                                "raw_text" to m.span.text,"source_engine" to m.span.engine,
                                "source_observation_ids" to m.span.observationIds.sorted().joinToString(" "),
                                "wine_name_id" to m.wine.id,"wine_display_name" to m.wine.name,"producer_entity_id" to m.wine.producerId,
                                "lwin7" to m.wine.lwin7,"edit_distance" to m.distance.toString(),"producer_gazetteer_version" to linkedIndex.version)) }
                            if(linked.wineId!=null) {
                                if(row["producer"]!=linked.producer) {
                                    row["producer"]=linked.producer;row["producer_state"]="YELLOW"
                                    row["producer_reason"]=linked.reason;row["producer_alternatives"]=""
                                    row["producer_gazetteer_status"]="linked_pair_suggestion"
                                    row["producer_gazetteer_reason"]=linked.reason
                                    row["producer_gazetteer_entity_id"]=linked.producerId.orEmpty()
                                }
                                row["wine_name"]=linked.wine;row["wine_name_state"]="YELLOW"
                                row["wine_name_reason"]=linked.reason;row["wine_name_alternatives"]=""
                                row["field_values_found"]=com.yourapp.wine.fill.FieldId.entries.count { row[it.name.lowercase(java.util.Locale.ROOT)].orEmpty().isNotBlank() }.toString()
                                row["status"]="suggestions"
                            }
                        } else {
                            row["wine_gazetteer_status"]="unavailable";row["wine_gazetteer_reason"]="linked wine index unavailable"
                        }
                        if(isLabMode && engine!=null) {
                            var backfilled=false
                            val confirmedWine=linkedDecision
                            val acceptedProducerId=confirmedWine?.producerId ?: decision.acceptedId
                            val acceptedEntity=acceptedProducerId?.let { id -> decision.matches.firstOrNull { it.entity.id==id }?.entity }
                            if(confirmedWine!=null && confirmedWine.wineId!=null) {
                                val inferred=engine.resolveLinkedWineGeography(
                                    confirmedWine.wine,
                                    row["country"].orEmpty(),
                                    row["region"].orEmpty(),
                                    row["subregion"].orEmpty(),
                                    acceptedEntity?.countryGroup.orEmpty(),
                                )
                                for((fieldId,value) in inferred) {
                                    val fieldKey=fieldId.name.lowercase(java.util.Locale.ROOT)
                                    if(row[fieldKey].orEmpty().isBlank()) {
                                        row[fieldKey]=value
                                        row["${fieldKey}_state"]="YELLOW"
                                        row["${fieldKey}_reason"]="inferred from confirmed LWIN producer+wine pair (${confirmedWine.lwin7.ifBlank { confirmedWine.wineId.orEmpty() }}); confirm manually"
                                        row["${fieldKey}_alternatives"]=""
                                        backfilled=true
                                    }
                                }
                            }
                            if(row["country"].orEmpty().isBlank() && acceptedEntity!=null && acceptedEntity.countryGroup.isNotBlank()) {
                                val countryName=engine.canonicalCountryName(acceptedEntity.countryGroup)
                                val sameKeyCountries=decision.matches.filter { it.entity.key==acceptedEntity.key }
                                    .mapNotNull { m -> m.entity.countryGroup.takeIf(String::isNotBlank)?.let(engine::canonicalCountryName) }.toSet()
                                if(countryName!=null && sameKeyCountries.size==1) {
                                    row["country"]=countryName
                                    row["country_state"]="YELLOW"
                                    row["country_reason"]="unambiguous LWIN producer country (${acceptedEntity.name}); confirm manually"
                                    row["country_alternatives"]=""
                                    backfilled=true
                                }
                            }
                            if(backfilled) {
                                row["field_values_found"]=com.yourapp.wine.fill.FieldId.entries.count { row[it.name.lowercase(java.util.Locale.ROOT)].orEmpty().isNotBlank() }.toString()
                                row["status"]="suggestions"
                            }
                        }
                    }
                }
                journal.add(candidate,stagePrefix+row.getValue("stage"),row.getValue("status"),
                    row.filterKeys { it !in setOf("candidate","stage","status","sequence","details") }+
                        mapOf("post_processing_version" to "$VERSION+${ProducerRepair.VERSION}+${ProducerResolver.VERSION}+${LinkedWineResolver.VERSION}",
                            "spatial_source_count" to original.size.toString(),"spatial_reading_count" to readings.size.toString(),
                            "spatial_filter_dropped" to (filterOutcome?.dropped?.size ?: 0).toString(),
                            "spatial_merged_groups" to proposals.count { it.sources.size>1 }.toString()),row["details"].orEmpty())
            }
            check(journal.rows.any { it["stage"]==summaryStage }) { "Spatial extractor produced no summary" }
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            journal.add(candidate,summaryStage,"failed",mapOf("post_processing_version" to VERSION),"${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
