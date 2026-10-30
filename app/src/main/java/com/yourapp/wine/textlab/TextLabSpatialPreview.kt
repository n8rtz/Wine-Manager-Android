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
 * wine lookup. The contenders differ ONLY in the producer index handed in: Prod v4.0 gets the
 * verified LWIN-only index (candidate "spatial_lwin_lab", stage prefix "spatial_lwin_"), Lab v5
 * gets the Lab expanded index (defaults here). Each writes only its own candidate/stage rows.
 */
internal object TextLabSpatialPreview {
    const val VERSION="raw-plus-linked-wine-v4"

    /**
     * Re-query the producer value selected by the existing field extractor only when one same-engine
     * spatial proposal contains every word in that value. The synthetic span keeps the IDs of the
     * original source observations that supplied those words, so it cannot create a second OCR vote.
     * Word order is not required: field extraction may assemble a producer differently from the
     * spatial proposal's visual reading order. This recovers a clean producer phrase from a group
     * that also contains a year/appellation, without sending that long mixed group to the matcher.
     */
    internal fun producerAnchorSpans(
        producer:String,
        original:List<com.yourapp.wine.ocr.TokenRecord>,
        proposals:List<TextLabSpatialPhrases.Proposal>,
    ):List<ProducerResolver.Span> {
        val words=ProducerResolver.normalize(producer).split(' ').filter(String::isNotBlank)
        if(words.isEmpty()) return emptyList()
        return proposals.mapNotNull { proposal ->
            val remaining=words.toMutableList()
            val covering=mutableListOf<com.yourapp.wine.ocr.TokenRecord>()
            if(proposal.engineId.isBlank() || proposal.sources.isEmpty()) return@mapNotNull null
            for(source in proposal.sources) {
                if(source.engineId!=proposal.engineId) return@mapNotNull null
                var contributes=false
                for(word in ProducerResolver.normalize(source.textRaw).split(' ').filter(String::isNotBlank)) {
                    val index=remaining.indexOf(word)
                    if(index>=0) { remaining.removeAt(index);contributes=true }
                }
                if(contributes) covering+=source
                if(remaining.isEmpty()) break
            }
            if(remaining.isNotEmpty() || covering.isEmpty()) return@mapNotNull null
            val ids=covering.map { source -> original.indexOfFirst { it===source } }
            if(ids.any { it<0 } || ids.toSet().size!=ids.size) return@mapNotNull null
            ProducerResolver.Span(producer,proposal.engineId,ids.toSet(),covering.any { it.scoreIsEstimated })
        }.distinctBy { listOf(it.engine,it.observationIds.sorted().joinToString(",")) }
    }

    /**
     * Feed only evidence-graph phrases that the extractor itself considered as producer hypotheses
     * into the catalog resolver. The resolver then compares the OCR-supported reading against its
     * producer index (including harmless omitted particles such as `de`). Require one original
     * engine/pass so graph composition never manufactures independent OCR support.
     */
    internal fun producerEvidenceSpans(
        result:com.yourapp.wine.textlab.evidence.EvidenceResult,
        engine:EvidenceExtractor,
    ):List<ProducerResolver.Span> {
        val producerPhraseIds=result.hypotheses.filter { com.yourapp.wine.fill.FieldId.PRODUCER in it.values }
            .map { it.phrase.id }.toSet()
        val observations=result.graph.observations.associateBy { it.id }
        val selector=com.yourapp.wine.extract.ProducerLineSelector()
        return result.graph.phrases.asSequence().filter { phrase ->
            phrase.id in producerPhraseIds && phrase.observations.size>=2 && phrase.text.length<=96 &&
                phrase.text.none(Char::isDigit) &&
                phrase.text.split(Regex("\\s+")).count { it.isNotBlank() } in 2..8
        }.mapNotNull { phrase ->
            val sources=phrase.observations.mapNotNull(observations::get)
            if(sources.size!=phrase.observations.size) return@mapNotNull null
            val engineIds=sources.map { it.record.engineId }.distinct()
            val variants=sources.map { it.record.variantId }.distinct()
            if(engineIds.size!=1 || variants.size!=1) return@mapNotNull null
            val reading=phrase.text
            if(engine.isNonProducerCatalogTerm(reading) || selector.isWineDescription(reading) ||
                com.yourapp.wine.extract.MetadataRules.isBoilerplate(reading) ||
                com.yourapp.wine.extract.MetadataRules.isPackaging(reading)) return@mapNotNull null
            val engineId=engineIds.single()
            if(sources.none { it.record.score >= if(engineId.startsWith("paddle")) .75f else .60f }) return@mapNotNull null
            ProducerResolver.Span(reading,engineId,phrase.observations,sources.any { it.record.scoreIsEstimated })
        }.distinctBy { listOf(it.engine,it.observationIds.sorted().joinToString(","),ProducerResolver.normalize(it.text)) }
            .take(64).toList()
    }

    fun append(journal:TextLabJournal,engine:EvidenceExtractor?,hybrid:EvidenceAnchorHybrid?,repair:ProducerRepair?,year:Int,producerIndex:ProducerResolver.Index?=null,
        producerUnavailable:String="primary LWIN export required",checkProducerBudget:()->Unit={},
        candidate:String="spatial_lab",stagePrefix:String="spatial_",
        policy:ProducerResolver.Policy=ProducerResolver.Policy.prod,
        fillEngine:com.yourapp.wine.fill.FillEngine?=null,
        labModules:com.yourapp.wine.textlab.evidence.LabPostOcrModules=com.yourapp.wine.textlab.evidence.LabPostOcrModules.unitTestDefault) {
        val summaryStage=stagePrefix+"evidence_summary"
        if(journal.rows.any { it["stage"]==summaryStage }) return
        try {
            val rawOriginal=TextLabFormPreview.records(journal)
            val filterOutcome=if(policy.minimumIndependentEngines<2) TextLabBottleFilter.filterTokens(rawOriginal) else null
            val isLabSpatial=policy.minimumIndependentEngines<2
            val original=filterOutcome?.kept ?: rawOriginal
            filterOutcome?.dropped?.forEachIndexed { index,item ->
                val token=item.entry.payload
                journal.add(candidate,stagePrefix+"filtered",values=mapOf("post_processing_version" to VERSION,
                    "region_id" to index.toString(),"raw_text" to token.textRaw,
                    "source_engine" to token.engineId,"filter_reason" to item.reason,
                    "base_box_normalized" to "${item.entry.rectBox.left} ${item.entry.rectBox.top} ${item.entry.rectBox.right} ${item.entry.rectBox.bottom}"))
            }
            val proposals=if(!isLabSpatial || labModules.spatialSemanticGuards) {
                TextLabSpatialPhrases.propose(
                    original,
                    allowHorizontalJoin={ left,right -> engine?.allowsSpatialProducerJoin(left,right)==true },
                    allowVerticalJoin={ upper,lower -> engine?.allowsSpatialProducerJoin(upper,lower)==true },
                )
            } else {
                TextLabSpatialPhrases.proposeUnconstrained(original)
            }
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
            // Unchanged raw baseline anchor for Prod v4.0 and unfiltered images; when Lab v5 drops
            // stray side-bottle or medal-sticker tokens, recompute the anchor on filtered readings so
            // dropped noise cannot leak back through EvidenceAnchorHybrid.
            if(filterOutcome!=null && filterOutcome.dropped.isNotEmpty() && engine!=null) {
                work.rows.addAll(journal.rows.filter { it["stage"] in setOf("summary","candidate_summary") })
                if(fillEngine!=null) TextLabFormPreview.append(work,fillEngine,year,recordsOverride=readings)
                else TextLabFormPreview.append(work,com.yourapp.wine.fill.FillEngine(engine.baseKb),year,recordsOverride=readings)
            } else {
                work.rows.addAll(journal.rows.filter { it["stage"]=="form_summary" })
            }
            // Lab honors the vintage module; the frozen Prod v4.0 contender keeps its own pre-module
            // behavior (the hybrid vintage fill was always available there) so its row is unchanged.
            val allowHybridVintage=if(isLabSpatial) labModules.vintageRecovery else true
            val evidenceResult=if(isLabSpatial && producerIndex!=null && engine!=null) {
                engine.withDeferredBlankSubregionFallback {
                    TextLabEvidencePreview.append(work,engine,year,useAnchors=true,hybrid=hybrid,
                        useProducerRepair=true,producerRepair=repair,recordsOverride=readings,
                        exactGeographyFastPath=true,allowHybridVintageFallback=allowHybridVintage,
                        appellationFallbackEngines=if(labModules.appellationWineFallback) 1 else 0,
                        deepestAppellationFallback=labModules.deepestAppellationFallback,styleAppellationWine=labModules.styleAppellationWine,producerAnchorFallback=labModules.producerAnchorFallback,geographyAppellationWine=labModules.geographyAppellationWine)
                }
            } else {
                TextLabEvidencePreview.append(work,engine,year,useAnchors=true,hybrid=hybrid,
                    useProducerRepair=true,producerRepair=repair,recordsOverride=readings,
                    exactGeographyFastPath=policy.minimumIndependentEngines<2,
                    allowHybridVintageFallback=allowHybridVintage,
                    appellationFallbackEngines=if(isLabSpatial && labModules.appellationWineFallback) 1 else 0,
                    deepestAppellationFallback=isLabSpatial && labModules.deepestAppellationFallback,styleAppellationWine=isLabSpatial && labModules.styleAppellationWine,producerAnchorFallback=isLabSpatial && labModules.producerAnchorFallback,geographyAppellationWine=isLabSpatial && labModules.geographyAppellationWine)
            }
            val evidenceSpans=if(engine!=null && evidenceResult!=null && (!isLabSpatial || labModules.spatialSemanticGuards)) {
                producerEvidenceSpans(evidenceResult,engine)
            } else emptyList()
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
                        row["producer_final_near_match_status"]="unavailable"
                        row["wine_gazetteer_status"]="unavailable";row["wine_gazetteer_reason"]=producerUnavailable
                    } else {
                        val isLabMode=policy.minimumIndependentEngines<2
                        val supplementalSpans=if(!isLabMode || labModules.spatialSemanticGuards) {
                            (producerAnchorSpans(before,original,proposals)+evidenceSpans)
                                .distinctBy { listOf(it.engine,it.observationIds.sorted().joinToString(","),ProducerResolver.normalize(it.text)) }
                                .filterNot { extra -> spans.any { existing -> existing.engine==extra.engine &&
                                    existing.observationIds==extra.observationIds &&
                                    ProducerResolver.normalize(existing.text)==ProducerResolver.normalize(extra.text) } }
                                .take((ProducerResolver.MAX_SPANS-spans.size).coerceAtLeast(0))
                        } else emptyList()
                        val lookupSpans=spans+supplementalSpans
                        val started=android.os.SystemClock.elapsedRealtime()
                        var producerFailureTelemetry=emptyMap<String,String>()
                        val producerResolver=ProducerResolver(producerIndex,onFailureTelemetry={ producerFailureTelemetry=it })
                        fun runProducerLookup(policy:ProducerResolver.Policy)=
                            producerResolver.resolve(before,lookupSpans,checkProducerBudget,policy)
                        val activePolicy=if(isLabMode) {
                            val basePolicy=policy.withCountryContext(row["country"].orEmpty())
                                .withExactMatchFastPath { term -> engine?.isNonProducerCatalogTerm(term)==true }
                                .withParticleAndRoleRules(labModules.producerRoleAndParticle)
                            if(labModules.producerRoleAndParticle) {
                                basePolicy.withProducerPenalty { term -> engine?.producerCandidatePenalty(term) ?: 0 }
                            } else basePolicy
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
                                LinkedWineResolver(linkedIndex,onFailureTelemetry={ wineFailureTelemetry=it }).resolve(before,wineBefore,decision,spans,checkProducerBudget,policy,includeSubEstates=isLabMode && labModules.subEstateWineLinking)
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
                        val finalProducer=row["producer"].orEmpty()
                        val runFinalNearMatch=if(isLabMode) labModules.finalProducerNearMatch else false
                        if(!runFinalNearMatch) {
                            row["producer_final_near_match_status"]="disabled"
                        } else if(finalProducer.isBlank()) {
                            row["producer_final_near_match_status"]="blank"
                        } else {
                            row["producer_final_near_match_policy"]="primary_or_exact_or_independent; identity_ties_abstain"
                            val nearStarted=android.os.SystemClock.elapsedRealtime()
                            var nearMatchError=""
                            val nearMatch=try {
                                producerResolver.finalNearMatch(finalProducer,checkProducerBudget,
                                    protectExact=labModules.exactProducerProtection,
                                    observedExact=if(labModules.exactProducerProtection) readings.filter {
                                        it.score >= if(it.engineId.startsWith("paddle")) .75f else .60f
                                    }.map { it.textRaw } else emptyList(),
                                    supportingMatches=decision.matches,
                                    onRejected={ reason -> nearMatchError=reason }) { term ->
                                    if(labModules.producerRoleAndParticle) (engine?.producerCandidatePenalty(term) ?: 0) else 0
                                }
                            } catch(cancel:CancellationException) { throw cancel }
                              catch(_:ProducerResolver.BudgetExceeded) { nearMatchError="budget_exceeded";null }
                              catch(error:Exception) { nearMatchError=error.javaClass.simpleName;null }
                            val nearMs=android.os.SystemClock.elapsedRealtime()-nearStarted
                            row["producer_final_near_match_ms"]=nearMs.toString()
                            row["producer_gazetteer_ms"]=((row["producer_gazetteer_ms"]?.toLongOrNull() ?: 0L)+nearMs).toString()
                            row["work_ms"]=((row["work_ms"]?.toLongOrNull() ?: 0L)+nearMs).toString()
                            // Lab v7 (producer_near_match_unprotected): when only the
                            // exact-observation protection vetoed canonicalization
                            // (the OCR misreading is itself the observed text, e.g.
                            // Trancette for La Francette), retry once without it.
                            var effectiveNearMatch=nearMatch
                            if(effectiveNearMatch==null && isLabMode && labModules.producerNearMatchUnprotected &&
                                nearMatchError=="observed_producer_preserved") {
                                effectiveNearMatch=try {
                                    producerResolver.finalNearMatch(finalProducer,checkProducerBudget,
                                        protectExact=false,
                                        observedExact=emptyList(),
                                        supportingMatches=decision.matches,
                                        onRejected={ }) { term ->
                                        if(labModules.producerRoleAndParticle) (engine?.producerCandidatePenalty(term) ?: 0) else 0
                                    }
                                } catch(cancel:CancellationException) { throw cancel }
                                  catch(_:ProducerResolver.BudgetExceeded) { null }
                                  catch(_:Exception) { null }
                                if(effectiveNearMatch!=null) row["producer_final_near_match_status"]="suggestion_unprotected_retry"
                            }
                            if(effectiveNearMatch==null) {
                                if(row["producer_final_near_match_status"]!="suggestion_unprotected_retry")
                                    row["producer_final_near_match_status"]=if(nearMatchError.isBlank()) "no_unique_near_match" else nearMatchError
                            } else {
                                val cleanName=TextNorm.scrub(effectiveNearMatch.entity.name)
                                val sources=effectiveNearMatch.entity.support.sorted().joinToString(" | ")
                                val reason="final answer canonicalized to database value at penalty-adjusted distance ${effectiveNearMatch.distance} (${sources.ifBlank { "primary producer index" }}); confirm manually"
                                row["producer_before_final_near_match"]=finalProducer
                                row["producer"]=cleanName;row["producer_state"]="YELLOW";row["producer_reason"]=reason
                                row["producer_alternatives"]=""
                                row["producer_gazetteer_status"]="final_near_match"
                                row["producer_gazetteer_reason"]=reason
                                row["producer_gazetteer_entity_id"]=effectiveNearMatch.entity.id
                                row["producer_final_near_match_status"]="suggestion"
                                row["producer_final_near_match_entity_id"]=effectiveNearMatch.entity.id
                                row["producer_final_near_match_distance"]=effectiveNearMatch.distance.toString()
                                row["producer_final_near_match_sources"]=sources
                                row["field_values_found"]=com.yourapp.wine.fill.FieldId.entries.count { row[it.name.lowercase(java.util.Locale.ROOT)].orEmpty().isNotBlank() }.toString()
                                row["status"]="suggestions"
                                journal.add(candidate,stagePrefix+"producer_final_near_match","suggestion",mapOf(
                                    "post_processing_version" to VERSION,"raw_text" to finalProducer,
                                    "producer_entity_id" to effectiveNearMatch.entity.id,"producer_display_name" to cleanName,
                                    "edit_distance" to effectiveNearMatch.distance.toString(),"lexical_support_sources" to sources,
                                    "producer_gazetteer_version" to producerIndex.version))
                            }
                        }
                        // Gap D (Lab producer_completion_match): a truncated final
                        // producer completed by the unique longer observed producer
                        // span that contains its tokens in order (Les Chemins ->
                        // Les Chemins d'Onairac). Observed spans only; no catalog
                        // invention, and competing completions abstain.
                        if(isLabMode && labModules.producerCompletionMatch && row["producer"].orEmpty().isNotBlank()) {
                            val finalTokens=TextNorm.normalize(row["producer"].orEmpty())
                                .split(Regex("\\s+")).filter(String::isNotBlank)
                            if(finalTokens.size>=2 || (finalTokens.size==1 && finalTokens.single().length>=5)) {
                                val spanCandidates=(evidenceResult?.hypotheses.orEmpty()
                                    .filter { it.values.keys==setOf(com.yourapp.wine.fill.FieldId.PRODUCER) }
                                    .map { it.values.getValue(com.yourapp.wine.fill.FieldId.PRODUCER) })
                                    .map { TextNorm.scrub(it) }.filter(String::isNotBlank).distinct()
                                val completionBoilerplate=Regex("\\b(mis|bouteille\\w*|produit|vin de|coteaux|vignobles|cuvee)\\b")
                                val completions=spanCandidates.filter { candidate ->
                                    val candidateTokens=TextNorm.normalize(candidate)
                                        .split(Regex("\\s+")).filter(String::isNotBlank)
                                    candidateTokens.size>finalTokens.size &&
                                        candidateTokens.size<=finalTokens.size+3 &&
                                        (finalTokens.size>=2 || (candidateTokens.size>=3 &&
                                            candidateTokens.last()==finalTokens.single() &&
                                            candidateTokens.first() in setOf("baron","chateau","domaine","maison","villa","tenuta","chateaux"))) &&
                                        run {
                                            // Tokens of the candidate not consumed by the
                                            // in-order match must look like name words.
                                            var i=0
                                            val extras=mutableListOf<String>()
                                            for(token in candidateTokens) {
                                                if(i<finalTokens.size && token==finalTokens[i]) i++ else extras+=token
                                            }
                                            i==finalTokens.size && extras.all { extra ->
                                                extra in setOf("de","du","di","des","d","la","le","les") ||
                                                    (extra.length>=5 && extra !in finalTokens)
                                            }
                                        } &&
                                        !completionBoilerplate.containsMatchIn(TextNorm.normalize(candidate)) &&
                                        !com.yourapp.wine.extract.WineDesignations.isAppellationName(candidate) &&
                                        engine?.let { e -> com.yourapp.wine.kb.PlaceGazetteer(
                                            com.yourapp.wine.kb.SupplementedWineKnowledgeBase(e.baseKb))
                                            .matches(TextNorm.normalize(candidate)).isEmpty() }==true &&
                                        true
                                }.distinctBy { TextNorm.normalize(it) }
                                if(completions.size==1) {
                                    row["producer_before_completion"]=row["producer"].orEmpty()
                                    row["producer"]=completions.single()
                                    row["producer_state"]="YELLOW"
                                    row["producer_reason"]="truncated producer completed from the unique longer observed producer span; confirm manually"
                                    row["producer_completion_status"]="suggestion"
                                } else {
                                    row["producer_completion_status"]="no_unique_completion"
                                }
                            }
                        }
                        // Lab v7 experiment (Conde case study): Gap D completion
                        // refuses one-token producers unless the token ends an
                        // estate-headed span. Here the final token is the FIRST
                        // token of the unique observed span/joined phrase
                        // (Conde -> Conde de Basalu). Same protection family as
                        // Gap D: unique candidate, name-like extras only, no
                        // boilerplate/appellation/geography candidates.
                        if(isLabMode && labModules.producerPrefixCompletion && engine!=null && row["producer"].orEmpty().isNotBlank()) {
                            val finalToken=TextNorm.normalize(row["producer"].orEmpty())
                            if(!finalToken.contains(' ') && finalToken.length>=5) {
                                val phraseCandidates=(evidenceResult?.hypotheses.orEmpty()
                                    .filter { it.values.keys==setOf(com.yourapp.wine.fill.FieldId.PRODUCER) }
                                    .map { it.values.getValue(com.yourapp.wine.fill.FieldId.PRODUCER) })+
                                    proposals.filter { it.sources.size>1 }.map { TextNorm.scrub(it.phrase.mergedText) }
                                val prefixBoilerplate=Regex("\\b(mis|bouteille\\w*|produit|vin de|coteaux|vignobles|cuvee)\\b")
                                // v2 (Conde journal trace, 2026-10-30): extras must
                                // include a minor name-connector (de/di/la/...),
                                // which descriptive continuations lack (this
                                // refuses A.Kolb -> A.Kolb Methode Traditionnelle),
                                // and among surviving candidates the unique
                                // shortest one that prefixes all others wins
                                // (Conde de Basalu over Conde de Basalu manza,
                                // the garble-extended three-line span).
                                val minorConnectors=setOf("de","du","di","des","d","la","le","les","d'","l'")
                                val completions=phraseCandidates.map { TextNorm.scrub(it) }
                                    .filter(String::isNotBlank).distinctBy { TextNorm.normalize(it) }
                                    .filter { candidate ->
                                        val candidateTokens=TextNorm.normalize(candidate)
                                            .split(Regex("\\s+")).filter(String::isNotBlank)
                                        candidateTokens.size in 2..4 && candidateTokens.first()==finalToken &&
                                            candidateTokens.drop(1).any { it in minorConnectors || it.startsWith("d'") || it.startsWith("l'") } &&
                                            candidateTokens.drop(1).all { extra ->
                                                extra in minorConnectors || extra.startsWith("d'") || extra.startsWith("l'") ||
                                                    (extra.length>=5 && extra!=finalToken)
                                            } &&
                                            !prefixBoilerplate.containsMatchIn(TextNorm.normalize(candidate)) &&
                                            !com.yourapp.wine.extract.WineDesignations.isAppellationName(candidate) &&
                                            com.yourapp.wine.kb.PlaceGazetteer(
                                                com.yourapp.wine.kb.SupplementedWineKnowledgeBase(engine.baseKb))
                                                .matches(TextNorm.normalize(candidate)).isEmpty()
                                    }
                                fun tokensOf(value:String)=TextNorm.normalize(value)
                                    .split(Regex("\\s+")).filter(String::isNotBlank)
                                val bySize=completions.sortedBy { tokensOf(it).size }
                                val best=bySize.firstOrNull()
                                val uniqueShortest=best!=null && bySize.drop(1).all { other ->
                                    val b=tokensOf(best); val o=tokensOf(other)
                                    o.size>b.size && o.subList(0,b.size)==b
                                }
                                if(best!=null && uniqueShortest) {
                                    row["producer_before_prefix_completion"]=row["producer"].orEmpty()
                                    row["producer"]=best
                                    row["producer_state"]="YELLOW"
                                    row["producer_reason"]="one-token producer completed from the unique observed span it prefixes; confirm manually"
                                    row["producer_prefix_completion_status"]="suggestion"
                                } else {
                                    row["producer_prefix_completion_status"]="no_unique_completion"
                                }
                            }
                        }
                        if(isLabMode && engine!=null && row["subregion"].orEmpty().isBlank()) {
                            if(labModules.blankSubregionFuzzy) {
                                val prodMatchLines=decision.matches.filter { it.distance <= 1 }.map { it.observed }.toSet()
                                val fallback=try {
                                    engine.fallbackBlankSubregionFromRawOcr(
                                        records=original,
                                        existingCountry=row["country"].orEmpty(),
                                        existingRegion=row["region"].orEmpty(),
                                        existingProducer=row["producer"].orEmpty(),
                                        existingWine=row["wine_name"].orEmpty(),
                                        knownProducerLineObservations=prodMatchLines,
                                        checkBudget=checkProducerBudget,
                                    )
                                } catch(cancel:CancellationException) { throw cancel }
                                  catch(_:ProducerResolver.BudgetExceeded) { null }
                                  catch(_:Exception) { null }
                                if(fallback!=null) {
                                    val reason="raw OCR appellation fallback: '${fallback.observedSpan}' -> '${fallback.matchedForm}' (edits=${fallback.editDistance}, engine=${fallback.engineId}); confirm manually"
                                    row["subregion"]=fallback.subregion
                                    row["subregion_state"]="YELLOW"
                                    row["subregion_reason"]=reason
                                    row["subregion_alternatives"]=""
                                    if(row["region"].orEmpty().isBlank() && fallback.region.isNotBlank()) {
                                        row["region"]=fallback.region
                                        row["region_state"]="YELLOW"
                                        row["region_reason"]=reason
                                        row["region_alternatives"]=""
                                    }
                                    if(row["country"].orEmpty().isBlank() && fallback.country.isNotBlank()) {
                                        row["country"]=fallback.country
                                        row["country_state"]="YELLOW"
                                        row["country_reason"]=reason
                                        row["country_alternatives"]=""
                                    }
                                    row["field_values_found"]=com.yourapp.wine.fill.FieldId.entries.count { row[it.name.lowercase(java.util.Locale.ROOT)].orEmpty().isNotBlank() }.toString()
                                    row["status"]="suggestions"
                                    journal.add(candidate,stagePrefix+"raw_ocr_subregion_fallback","suggestion",mapOf(
                                        "post_processing_version" to VERSION,
                                        "raw_text" to fallback.observedSpan,
                                        "matched_form" to fallback.matchedForm,
                                        "subregion" to fallback.subregion,
                                        "region" to fallback.region,
                                        "country" to fallback.country,
                                        "edit_distance" to fallback.editDistance.toString(),
                                        "source_engine" to fallback.engineId,
                                    ))
                                }
                            }
                        }
                    }
                }
                // Lab v7 case standardization (owner rule): after every other
                // final-field mutation (near-match canonicalization, producer
                // completion, geography fallbacks), force the final text
                // fields to title case. Formatting only; scoring case-folds.
                if(isLabSpatial && labModules.caseStandardization) {
                    for(field in listOf("producer","wine_name","country","region","subregion")) {
                        val value=row[field].orEmpty()
                        if(value.isNotBlank()) row[field]=TextNorm.displayCase(value)
                    }
                    row["case_standardization"]="display_case_v1"
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
