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
    fun append(context:Context,batch:TextLabBatch,skip:Boolean=false) {
        var kb:SqliteWineKnowledgeBase?=null
        var setupError=if(skip) "Skipped after memory pressure" else ""
        batch.journals.forEach { it.add("shared","knowledge_setup","running") }
        val engine=try {
            if(skip) null else {
                kb=SqliteWineKnowledgeBase.open(context.applicationContext,0)
                TextLabFieldExtractor(kb!!)
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
                        val text=row.getValue("raw_text")
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
            val production=kb?.let { com.yourapp.wine.fill.FillEngine(it) }
            batch.journals.forEach { TextLabFormPreview.append(it,production,Year.now().value) }
            if(batch.compareEvidence) {
                val experimental=kb?.let { TextLabEvidencePreview.engine(it,batch.anchorHybrid,batch.repairPlaces) }
                val hybrid=if(batch.anchorHybrid) kb?.let { com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid(it) } else null
                val repair=if(batch.anchorHybrid && batch.repairProducer) kb?.let { com.yourapp.wine.textlab.evidence.ProducerRepair(it) } else null
                // Benchmark batches select their contenders explicitly and append each selected
                // summary to the one OCR journal: evidence_summary (Prod v2.0),
                // spatial_lwin_evidence_summary (Prod v3.0, LWIN-only producers) and
                // spatial_evidence_summary (Lab v4, expanded producer index). The spatial
                // previews read only OCR rows plus the shared form anchor through their own
                // scratch journals, so one contender's rows neither feed nor alter another's.
                // Every other caller keeps exactly one extractor, as before.
                val runEvidence=if(batch.benchmarkAllPipelines) batch.benchmarkEvidence else !batch.spatialPhrases && !batch.benchmarkLwinSpatial
                val runLwinSpatial=batch.benchmarkLwinSpatial
                val runExpandedSpatial=if(batch.benchmarkAllPipelines) batch.spatialPhrases else batch.spatialPhrases && !batch.benchmarkLwinSpatial
                if(runEvidence) {
                    batch.journals.forEach { TextLabEvidencePreview.append(it,experimental,Year.now().value,batch.anchorHybrid,hybrid,batch.anchorHybrid && batch.repairProducer,repair) }
                }
                // The two spatial contenders run the identical pipeline; only the producer index
                // differs (Prod v3.0: verified LWIN only; Lab v4: Lab expanded index). Each keeps
                // its own candidate/stage namespace, setup telemetry and 500 ms stage budget.
                if(runLwinSpatial) {
                    batch.journals.forEach { appendSpatialContender(context,it,experimental,hybrid,repair,skip,expanded=false) }
                }
                if(runExpandedSpatial) {
                    batch.journals.forEach { appendSpatialContender(context,it,experimental,hybrid,repair,skip,expanded=true) }
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
        } finally { kb?.close() }
    }
    /** One spatial contender: gazetteer setup under its own 500 ms budget, then the shared
     * spatial preview in the contender's candidate/stage namespace. The producer-data gate
     * fails closed per contender; one contender's unavailable index never alters another's. */
    private fun appendSpatialContender(context:Context,journal:TextLabJournal,
        experimental:com.yourapp.wine.textlab.evidence.EvidenceExtractor?,
        hybrid:com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid?,
        repair:com.yourapp.wine.textlab.evidence.ProducerRepair?,skip:Boolean,expanded:Boolean) {
        val candidate=if(expanded) "spatial_lab" else "spatial_lwin_lab"
        val deadline=android.os.SystemClock.elapsedRealtime()+500L
        val checkBudget={ if(android.os.SystemClock.elapsedRealtime()>=deadline) throw com.yourapp.wine.gazetteer.ProducerResolver.BudgetExceeded() }
        var producers:com.yourapp.wine.gazetteer.ProducerGazetteer?=null
        var unavailable=if(expanded) "Lab expanded producer index required" else "primary LWIN export required"
        val setup=com.yourapp.wine.diagnostics.PipelineFunnel.Recorder()
        var setupStep="asset.manifest"
        try {
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
            }
            catch(cancel:CancellationException) { throw cancel }
            catch(e:Exception) {
                unavailable=(if(expanded) "Lab expanded producer gazetteer unavailable: " else "Primary producer gazetteer unavailable: ")+
                    "${e.javaClass.simpleName}; ${e.message}"
                setup.visit(setupStep,false,unavailable)
            }
            journal.add(candidate,"gazetteer_setup",if(producers!=null) "complete" else "unavailable",setup.fields(),unavailable.takeIf { producers==null }.orEmpty())
            val remaining=(deadline-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            var lookupDeadline:Long?=null
            val lookupBudget={
                val now=android.os.SystemClock.elapsedRealtime()
                if(lookupDeadline==null) lookupDeadline=now+remaining
                if(now>=lookupDeadline!!) throw com.yourapp.wine.gazetteer.ProducerResolver.BudgetExceeded()
            }
            TextLabSpatialPreview.append(journal,experimental,hybrid,repair,Year.now().value,producers,unavailable,lookupBudget,
                candidate=candidate,stagePrefix=if(expanded) "spatial_" else "spatial_lwin_")
        } finally { producers?.close() }
    }
    private fun box(value:String?):RectF? {
        val v=value?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() } ?: return null
        return if(v.size==4 && v.all { it.isFinite() && it in 0f..1f } && v[2]>v[0] && v[3]>v[1])
            RectF(v[0],v[1],v[2],v[3]) else null
    }
}
