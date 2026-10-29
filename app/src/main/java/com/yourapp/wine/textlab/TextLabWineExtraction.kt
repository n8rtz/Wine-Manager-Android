package com.yourapp.wine.textlab

import android.content.Context
import android.graphics.RectF
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import java.time.Year
import kotlinx.coroutines.CancellationException

/** Post-OCR reporting only, outside engine timings; uses the same local KB/rules for every contender. */
internal object TextLabWineExtraction {
    /**
     * Run-scoped read-only cache of extraction catalogs, tries, and SQLite gazetteer handles across
     * a multi-photo benchmark pass. Callers close this session in a `finally` block when the pass
     * finishes, pauses, or stops; single-photo callers omit it and close resources per call.
     */
    internal class WarmSession(context:Context):AutoCloseable {
        private val appContext=context.applicationContext
        private var kb:SqliteWineKnowledgeBase?=null
        private var production:com.yourapp.wine.fill.FillEngine?=null
        private val evidenceEngines=mutableMapOf<Triple<Boolean,Boolean,String>,com.yourapp.wine.textlab.evidence.EvidenceExtractor>()
        private var hybrid:com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid?=null
        private var repair:com.yourapp.wine.textlab.evidence.ProducerRepair?=null
        internal data class WarmGazetteer(
            val gazetteer:com.yourapp.wine.gazetteer.ProducerGazetteer,
            val setupFields:Map<String,String>,
        )
        private var primaryGazetteer:WarmGazetteer?=null
        private var expandedGazetteer:WarmGazetteer?=null

        fun knowledgeBase():SqliteWineKnowledgeBase {
            kb?.let { return it }
            return SqliteWineKnowledgeBase.open(appContext,0).also { kb=it }
        }

        fun fillEngine(base:SqliteWineKnowledgeBase):com.yourapp.wine.fill.FillEngine {
            if(base===kb) production?.let { return it }
            return com.yourapp.wine.fill.FillEngine(base).also { if(base===kb) production=it }
        }

        fun evidenceExtractor(
            base:SqliteWineKnowledgeBase,
            anchorHybrid:Boolean,
            repairPlaces:Boolean,
            labModules:com.yourapp.wine.textlab.evidence.LabPostOcrModules=com.yourapp.wine.textlab.evidence.LabPostOcrModules.unitTestDefault,
        ):com.yourapp.wine.textlab.evidence.EvidenceExtractor {
            val key=Triple(anchorHybrid,repairPlaces,labModules.signatureKey())
            if(base===kb) evidenceEngines[key]?.let { return it }
            return TextLabEvidencePreview.engine(base,anchorHybrid,repairPlaces,labModules).also { engine ->
                engine.isNonProducerCatalogTerm("")
                if(base===kb) evidenceEngines[key]=engine
            }
        }

        fun anchorHybrid(base:SqliteWineKnowledgeBase):com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid {
            if(base===kb) hybrid?.let { return it }
            return com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid(base).also { if(base===kb) hybrid=it }
        }

        fun producerRepair(base:SqliteWineKnowledgeBase):com.yourapp.wine.textlab.evidence.ProducerRepair {
            if(base===kb) repair?.let { return it }
            return com.yourapp.wine.textlab.evidence.ProducerRepair(base).also { if(base===kb) repair=it }
        }

        fun cachedGazetteer(expanded:Boolean):WarmGazetteer?=
            if(expanded) expandedGazetteer else primaryGazetteer

        fun cacheGazetteer(expanded:Boolean,gazetteer:com.yourapp.wine.gazetteer.ProducerGazetteer,setupFields:Map<String,String>) {
            val entry=WarmGazetteer(gazetteer,setupFields)
            if(expanded) expandedGazetteer=entry else primaryGazetteer=entry
        }

        override fun close() {
            runCatching { expandedGazetteer?.gazetteer?.close() }
            expandedGazetteer=null
            runCatching { primaryGazetteer?.gazetteer?.close() }
            primaryGazetteer=null
            evidenceEngines.clear()
            hybrid=null
            repair=null
            production=null
            runCatching { kb?.close() }
            kb=null
        }
    }

    fun append(context:Context,batch:TextLabBatch,skip:Boolean=false,warmSession:WarmSession?=null) {
        var kb:SqliteWineKnowledgeBase?=null
        val ownsKb=warmSession==null
        var setupError=if(skip) "Skipped after memory pressure" else ""
        batch.journals.forEach { it.add("shared","knowledge_setup","running") }
        val engine=try {
            if(skip) null else {
                kb=warmSession?.knowledgeBase() ?: SqliteWineKnowledgeBase.open(context.applicationContext,0)
                if(batch.benchmarkOnly) null else TextLabFieldExtractor(kb!!)
            }
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) { setupError="${error.javaClass.simpleName}: ${error.message}";null }
        batch.journals.forEach { it.add("shared","knowledge_setup",if(kb!=null) "complete" else "unavailable",details=setupError) }
        try {
            if(!batch.benchmarkOnly) for(journal in batch.journals) for((candidate,pass) in TextLabWineTable.groups(journal)) {
                if(journal.rows.any { it["stage"]=="field_summary" && it["candidate"]==candidate && it["pass_id"].orEmpty()==pass }) continue
                val run=journal.rows.lastOrNull { it["candidate"]==candidate && it["pass_id"].orEmpty()==pass &&
                    it["stage"] in setOf("candidate_summary","pass_summary","summary") }
                val values=mutableMapOf("pass_id" to pass,"ocr_status" to (run?.get("status") ?: "not_run"),
                    "field_extractor" to "local lab extractor v2; production field rules without confidence merge gate; isolated candidate/pass",
                    "field_year" to Year.now().value.toString())
                var status="complete";var details="All nonblank fields require confirmation; not ground truth."
                try {
                    check(engine!=null) { setupError }
                    val records=TextLabWineTable.readings(journal,candidate,pass).map { row ->
                        val text=TextNorm.scrub(row.getValue("raw_text"))
                        val baseBox=box(row["base_box_normalized"]) ?: if(!journal.detectLines) RectF(0f,0f,1f,1f)
                            else error("Missing/invalid base coordinates for region ${row["region_id"]}")
                        // Scores are intentionally estimated: the table cannot promote a lab reading to GREEN.
                        TokenRecord(text,TextNorm.normalize(text),.5f,box(row["original_box_normalized"]) ?: baseBox,
                            baseBox,emptyList(),candidate,pass.ifEmpty { "single" },scoreIsEstimated=true)
                    }
                    val result=engine.extract(records,Year.now().value)
                    values["post_processing_version"]=com.yourapp.wine.extract.IdentityAssignment.VERSION
                    values["spelling_suggestions"]=result.spellingSuggestions.joinToString("\n",transform=com.yourapp.wine.extract.BoundedSpelling::reason)
                    values["role_diagnostics"]=result.postProcessing.joinToString("\n")
                    FieldId.entries.forEach { field ->
                        val key=field.name.lowercase(java.util.Locale.ROOT)
                        val value=result.fields[field]
                        values[key]=value?.value.orEmpty()
                        values["${key}_state"]=value?.state?.name ?: "RED"
                        values["${key}_reason"]=value?.reason.orEmpty()
                        value?.spelling?.let { spelling ->
                            values["${key}_observed"]=spelling.observed
                            values["${key}_matched_form"]=spelling.matchedForm
                            values["${key}_edit_distance"]=spelling.editDistance.toString()
                            values["${key}_edit_kind"]=spelling.editKind
                            values["${key}_match_strength"]=spelling.strength
                            values["${key}_spelling_support"]=spelling.support
                        }
                        values["${key}_alternatives"]=result.alternatives[field].orEmpty().joinToString(" | ") { it.value+": "+it.reason }
                    }
                    values["field_input_lines"]=records.size.toString()
                    val count=result.fields.values.count { it.value.isNotBlank() }
                    values["field_values_found"]=count.toString()
                    status=when { records.isEmpty() -> "no_readings";count==0 -> "no_fields";else -> "suggestions" }
                } catch(cancel:CancellationException) { throw cancel }
                catch(error:Exception) { status="failed";details="${error.javaClass.simpleName}: ${error.message}" }
                journal.add(candidate,"field_summary",status,values,details)
            }
            val production=kb?.let { warmSession?.fillEngine(it) ?: com.yourapp.wine.fill.FillEngine(it) }
            batch.journals.forEach { TextLabFormPreview.append(it,production,Year.now().value) }
            if(batch.compareEvidence) {
                val experimental=kb?.let {
                    if(batch.benchmarkAllPipelines) {
                        warmSession?.evidenceExtractor(it,batch.anchorHybrid,batch.repairPlaces,batch.labModules.withIntegrated())
                            ?: TextLabEvidencePreview.engine(it,batch.anchorHybrid,batch.repairPlaces,batch.labModules.withIntegrated())
                    } else {
                        warmSession?.evidenceExtractor(it,batch.anchorHybrid,batch.repairPlaces)
                            ?: TextLabEvidencePreview.engine(it,batch.anchorHybrid,batch.repairPlaces)
                    }
                }
                val prodV5Experimental=if(batch.benchmarkAllPipelines && batch.benchmarkProdV5Spatial) {
                    kb?.let {
                        warmSession?.evidenceExtractor(it,batch.anchorHybrid,batch.repairPlaces,com.yourapp.wine.textlab.evidence.LabPostOcrModules.prodV5Checkpoint)
                            ?: TextLabEvidencePreview.engine(it,batch.anchorHybrid,batch.repairPlaces,com.yourapp.wine.textlab.evidence.LabPostOcrModules.prodV5Checkpoint)
                    }
                } else null
                val prodExperimental=if(batch.benchmarkAllPipelines && (batch.benchmarkEvidence || batch.benchmarkLwinSpatial)) {
                    kb?.let {
                        warmSession?.evidenceExtractor(it,batch.anchorHybrid,false,com.yourapp.wine.textlab.evidence.LabPostOcrModules.none)
                            ?: TextLabEvidencePreview.engine(it,batch.anchorHybrid,false,com.yourapp.wine.textlab.evidence.LabPostOcrModules.none)
                    }
                } else experimental
                val hybrid=if(batch.anchorHybrid) kb?.let { warmSession?.anchorHybrid(it) ?: com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid(it) } else null
                val repair=if(batch.anchorHybrid && batch.repairProducer) kb?.let { warmSession?.producerRepair(it) ?: com.yourapp.wine.textlab.evidence.ProducerRepair(it) } else null
                fun appendSpatialContender(context:Context,journal:TextLabJournal,
                    experimental:com.yourapp.wine.textlab.evidence.EvidenceExtractor?,
                    hybrid:com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid?,
                    repair:com.yourapp.wine.textlab.evidence.ProducerRepair?,skip:Boolean,expanded:Boolean,
                    singleReading:Boolean=false,
                    contenderCandidate:String?=null,contenderStagePrefix:String?=null,
                    modulesOverride:com.yourapp.wine.textlab.evidence.LabPostOcrModules?=null)=appendSpatialContenderWithSession(
                        context,
                        journal,
                        if(!expanded && batch.benchmarkAllPipelines) prodExperimental else experimental,
                        hybrid,
                        repair,
                        skip,
                        expanded,
                        singleReading,
                        production,
                        warmSession,
                        modulesOverride ?: if(expanded) batch.labModules.withIntegrated() else com.yourapp.wine.textlab.evidence.LabPostOcrModules.none,
                        contenderCandidate,
                        contenderStagePrefix,
                    )
                // Benchmark batches select their contenders explicitly and append each selected
                // summary to the one OCR journal: evidence_summary (Prod v2.0),
                // spatial_lwin_evidence_summary (Prod v4.0, LWIN-only producers) and
                // spatial_evidence_summary (Lab v5, expanded producer index). The spatial
                // previews read only OCR rows plus the shared form anchor through their own
                // scratch journals, so one contender's rows neither feed nor alter another's.
                // Every other caller keeps exactly one extractor, as before.
                val runEvidence=if(batch.benchmarkAllPipelines) batch.benchmarkEvidence else !batch.spatialPhrases && !batch.benchmarkLwinSpatial
                val runLwinSpatial=batch.benchmarkLwinSpatial
                val runExpandedSpatial=if(batch.benchmarkAllPipelines) batch.spatialPhrases else batch.spatialPhrases && !batch.benchmarkLwinSpatial
                if(runEvidence) {
                    val evEngine=if(batch.benchmarkAllPipelines) prodExperimental else experimental
                    // The benchmark evidence row belongs to the frozen Prod v2.0 comparator, which keeps
                    // its pre-module hybrid vintage fill. Only a non-benchmark Lab run follows the module.
                    val evVintageFallback=if(batch.benchmarkAllPipelines) true else batch.labModules.withIntegrated().vintageRecovery
                    batch.journals.forEach {
                        TextLabEvidencePreview.append(
                            it,
                            evEngine,
                            Year.now().value,
                            batch.anchorHybrid,
                            hybrid,
                            batch.anchorHybrid && batch.repairProducer,
                            repair,
                            allowHybridVintageFallback=evVintageFallback,
                        )
                    }
                }
                // The two spatial contenders run the identical pipeline; only the producer index
                // differs (Prod v4.0: verified LWIN only; Lab v5: Lab expanded index). Each keeps
                // its own candidate/stage namespace, setup telemetry and setup/lookup budgets.
                if(runLwinSpatial) {
                    batch.journals.forEach { appendSpatialContender(context,it,experimental,hybrid,repair,skip,expanded=false) }
                }
                if(runExpandedSpatial) {
                    batch.journals.forEach { appendSpatialContender(context,it,experimental,hybrid,repair,skip,expanded=true,singleReading=batch.resolverSingleReading) }
                }
                // Prod v5.0 checkpoint contender: the Lab spatial pipeline (expanded producer
                // index, single-reading resolver rule) with the frozen proven module set.
                // It is a known-improvement release, not an experiment: the module set is
                // fixed here and never follows the Lab toggles.
                if(batch.benchmarkProdV5Spatial) {
                    batch.journals.forEach { appendSpatialContender(context,it,prodV5Experimental,hybrid,repair,skip,expanded=true,
                        singleReading=batch.resolverSingleReading,contenderCandidate="spatial_prod_v5",
                        contenderStagePrefix="spatial_prod_v5_",modulesOverride=com.yourapp.wine.textlab.evidence.LabPostOcrModules.prodV5Checkpoint) }
                }
            }
            if(batch.compareGazetteer || batch.residualCandidates) {
                var resolver:com.yourapp.wine.gazetteer.TrigramGazetteer?=null
                var error=setupError
                try {
                    try { if(!skip) resolver=com.yourapp.wine.gazetteer.TrigramGazetteer.open(context.applicationContext) }
                    catch(cancel:CancellationException) { throw cancel }
                    catch(e:Exception) { error="${e.javaClass.simpleName}: ${e.message}" }
                    if(batch.compareGazetteer) batch.journals.forEach { TextLabGazetteerPreview.append(it,resolver,production,Year.now().value,error) }
                    if(batch.residualCandidates) batch.journals.forEach { TextLabResidualPreview.append(it,resolver,Year.now().value,error) }
                } finally { resolver?.close() }
            }
        } finally { if(ownsKb) kb?.close() }
    }
    /** One spatial contender: gazetteer setup under its own budget, then the spatial preview with
     * a separate lookup budget. The producer-data gate fails closed per contender; one contender's
     * unavailable index never alters another's. */
    private fun appendSpatialContenderWithSession(context:Context,journal:TextLabJournal,
        experimental:com.yourapp.wine.textlab.evidence.EvidenceExtractor?,
        hybrid:com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid?,
        repair:com.yourapp.wine.textlab.evidence.ProducerRepair?,skip:Boolean,expanded:Boolean,
        singleReading:Boolean=false,
        fillEngine:com.yourapp.wine.fill.FillEngine?=null,
        warmSession:WarmSession?=null,
        labModules:com.yourapp.wine.textlab.evidence.LabPostOcrModules=com.yourapp.wine.textlab.evidence.LabPostOcrModules.unitTestDefault,
        contenderCandidate:String?=null,contenderStagePrefix:String?=null) {
        // Lab v5 only, default off: the Prod v4.0 contender keeps the Prod two-engine rule so the
        // comparison stays "Prod policy vs Lab policy", not "old build vs new build".
        val policy=if(expanded && singleReading) com.yourapp.wine.gazetteer.ProducerResolver.Policy.singleReading
            else com.yourapp.wine.gazetteer.ProducerResolver.Policy.prod
        val candidate=contenderCandidate ?: if(expanded) "spatial_lab" else "spatial_lwin_lab"
        // Setup and lookups get separate budgets on purpose. Asset verification reads 34-50 MB and
        // used to spend the lookups' time: the resolvers were handed the leftovers of one 500 ms
        // slice, so a slow open made them throw BudgetExceeded before the first query and the funnel
        // recorded "resolver completed within budget" as blocked (owner report, 2026-10-27).
        val setupDeadline=android.os.SystemClock.elapsedRealtime()+TextLabBudgets.GAZETTEER_SETUP_MS
        val checkBudget={ if(android.os.SystemClock.elapsedRealtime()>=setupDeadline) throw com.yourapp.wine.gazetteer.ProducerResolver.BudgetExceeded() }
        val cachedGazetteer=if(!skip) warmSession?.cachedGazetteer(expanded) else null
        var ownsProducers=cachedGazetteer==null
        var producers:com.yourapp.wine.gazetteer.ProducerGazetteer?=cachedGazetteer?.gazetteer
        var unavailable=if(expanded) "Lab expanded producer index required" else "primary LWIN export required"
        val setup=com.yourapp.wine.diagnostics.PipelineFunnel.Recorder()
        var setupStep="asset.manifest"
        try {
            if(cachedGazetteer==null) {
                try {
                    if(!skip) producers=if(expanded)
                        com.yourapp.wine.gazetteer.ProducerGazetteer.openExpanded(
                            context.applicationContext,checkBudget) { step,passed ->
                            setupStep=step;setup.visit(step,passed,"asset validation did not complete")
                        }
                        else com.yourapp.wine.gazetteer.ProducerGazetteer.open(
                            context.applicationContext,checkBudget) { step,passed ->
                            setupStep=step;setup.visit(step,passed,"asset validation did not complete")
                        }
                    if(producers!=null && warmSession!=null) {
                        warmSession.cacheGazetteer(expanded,producers,setup.fields())
                        ownsProducers=false
                    }
                }
                catch(cancel:CancellationException) { throw cancel }
                catch(e:Exception) {
                    unavailable=(if(expanded) "Lab expanded producer gazetteer unavailable: " else "Primary producer gazetteer unavailable: ")+
                        "${e.javaClass.simpleName}; ${e.message}"
                    setup.visit(setupStep,false,unavailable)
                }
            }
            val setupFields=cachedGazetteer?.setupFields ?: setup.fields()
            journal.add(candidate,"gazetteer_setup",if(producers!=null) "complete" else "unavailable",setupFields,unavailable.takeIf { producers==null }.orEmpty())
            var lookupDeadline=0L
            val lookupBudget={
                if(lookupDeadline==0L) lookupDeadline=android.os.SystemClock.elapsedRealtime()+TextLabBudgets.GAZETTEER_LOOKUP_MS
                if(android.os.SystemClock.elapsedRealtime()>=lookupDeadline)
                    throw com.yourapp.wine.gazetteer.ProducerResolver.BudgetExceeded()
            }
            TextLabSpatialPreview.append(journal,experimental,hybrid,repair,Year.now().value,producers,unavailable,lookupBudget,
                candidate=candidate,stagePrefix=contenderStagePrefix ?: if(expanded) "spatial_" else "spatial_lwin_",fillEngine=fillEngine,labModules=labModules,policy=policy)
        } finally { if(ownsProducers) producers?.close() }
    }
    private fun box(value:String?):RectF? {
        val v=value?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() } ?: return null
        return if(v.size==4 && v.all { it.isFinite() && it in 0f..1f } && v[2]>v[0] && v[3]>v[1])
            RectF(v[0],v[1],v[2],v[3]) else null
    }
}
