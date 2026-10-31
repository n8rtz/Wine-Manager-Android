package com.yourapp.wine.gliner

import android.content.Context
import android.os.SystemClock
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.gazetteer.ProducerGazetteer
import com.yourapp.wine.gazetteer.ProducerResolver
import com.yourapp.wine.kb.BundledGrapes
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import com.yourapp.wine.textlab.TextLabBudgets
import com.yourapp.wine.textlab.TextLabFormPreview
import com.yourapp.wine.textlab.TextLabJournal
import com.yourapp.wine.textlab.TextLabSpatialPreview
import com.yourapp.wine.textlab.TextLabWineExtraction
import com.yourapp.wine.textlab.evidence.LabPostOcrModules
import com.yourapp.wine.textlab.evidence.ProducerRepair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Year

internal data class GlinerFieldResult(
    val field:String,
    val label:String,
    val expected:String,
    val observed:String,
    val state:String,
    val outcome:String,
)

internal data class GlinerLabImageResult(
    val image:String,
    val reviewNumber:Int,
    val status:String,
    val message:String,
    val ocrLines:List<String>,
    val observations:List<GlinerLaneObservation>,
    val laneTexts:Map<String,String>,
    val residualTexts:List<String>,
    val fields:Map<String,String>,
    val fieldStates:Map<String,String>,
    val fieldResults:List<GlinerFieldResult>,
    val eligible:Boolean,
    val errorCount:Int,
    val wordCount:Int,
    val spanCount:Int,
    val layout:String,
    val outputShape:String,
    val modelSignature:String,
    val int4:Boolean,
    val ocrMs:Long,
    val glinerMs:Long,
    val postMs:Long,
    val totalMs:Long,
    val notes:String,
    /** Best span per lane before thresholding; empty when the model never scored a span. */
    val probes:List<String> = emptyList(),
    /** Every accepted lane hint and its model score, retained for reproducible replay. */
    val laneHints:List<GlinerFieldHint> = emptyList(),
    /** Exact lines and geometry received by the decision policy, before fusion or scrubbing. */
    val decisionReadings:List<GlinerEvidenceLine> = emptyList(),
    /** Per-engine base-extractor records, including scores and estimated-score flags. */
    val rawReadings:List<com.yourapp.wine.ocr.TokenRecord> = emptyList(),
    /** Unmodified base extraction and GLiNER-only changes, for side-by-side evaluation. */
    val baseFields:Map<String,String> = emptyMap(),
    val baseOutcomes:Map<String,String> = emptyMap(),
    val fieldDecisions:List<String> = emptyList(),
) {
    val complete:Boolean get() = status=="complete" && eligible && errorCount==0
}

/**
 * ML Kit and PP-OCRv6 run in parallel; fusion is used only for GLiNER-Small INT4 inference.
 * The base `TextLabSpatialPreview` contender receives the original independent readings, not
 * grouped lane fragments. The GLiNER-specific policy then evaluates typed hints and corroborated
 * OCR candidates without seeing scoring targets. Lab v7 and production do not call this policy.
 */
internal object GlinerLabRunner {
    const val OCR_BUDGET_MS=60_000L
    const val GLINER_BUDGET_MS=30_000L
    const val POST_BUDGET_MS=60_000L
    private val grapeTerms:Set<String> by lazy {
        BundledGrapes.terms.flatMap { listOf(it.name)+it.aliases }.toSet()
    }
    val fieldLabels=linkedMapOf(
        "producer" to "Producer",
        "wine_name" to "Wine name",
        "country" to "Country",
        "region" to "Region",
        "subregion" to "Subregion",
        "vintage" to "Vintage",
    )

    suspend fun run(
        context:Context,
        sample:GlinerLabSample,
        model:GlinerOnnxModel,
        laneConfig:GlinerLaneConfig,
        warm:TextLabWineExtraction.WarmSession,
        workFolder:File,
        modules:LabPostOcrModules=LabPostOcrModules.none.withIntegrated(),
    ):GlinerLabImageResult = withContext(Dispatchers.Default) {
        val started=SystemClock.elapsedRealtime()
        val photo=File(workFolder,"${sample.image}")
        GlinerLabCorpus.copyTo(context,sample,photo)
        var ocrMs=0L
        var glinerMs=0L
        var postMs=0L
        try {
            val ocr=GlinerOcr.recognize(context,photo,SystemClock.elapsedRealtime()+OCR_BUDGET_MS)
            ocrMs=ocr.elapsedMs
            val document=GlinerDocument.build(ocr.lines)
            if(document.words.isEmpty()) {
                return@withContext failed(sample,"no_text","No OCR words on this label",ocr.lines.map { it.text },
                    ocrMs,0,0,SystemClock.elapsedRealtime()-started,model)
            }
            val labelled=try {
                model.label(document,SystemClock.elapsedRealtime()+GLINER_BUDGET_MS)
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) {
                val promptHint=runCatching { "prompt ids ${model.promptPreview()}" }.getOrDefault("")
                return@withContext failed(sample,"gliner_failed","GLiNER stage failed: ${error.message} · $promptHint",
                    ocr.lines.map { it.text },ocrMs,0,0,SystemClock.elapsedRealtime()-started,model)
            }
            glinerMs=labelled.elapsedMs
            // Fusion is for the model only: retain each original engine's actual reading for the
            // downstream evidence graph, so lane claims cannot delete words or manufacture votes.
            val records=GlinerLaneRecords.originalRecords(ocr.readings)
            if(records.isEmpty()) {
                return@withContext failed(sample,"no_observations","GLiNER produced no lane or residual observations",
                    ocr.lines.map { it.text },ocrMs,glinerMs,0,SystemClock.elapsedRealtime()-started,model)
            }
            val postStarted=SystemClock.elapsedRealtime()
            val post=postProcess(context,records,warm,modules,labelled)
            postMs=SystemClock.elapsedRealtime()-postStarted
            val laneHints=labelled.entities.map { GlinerFieldHint(it.lane,it.text,it.score) }
            val decisionReadings=ocr.readings.map { reading ->
                GlinerEvidenceLine(reading.engineId,reading.text,reading.box.left,reading.box.top,
                    reading.box.right,reading.box.bottom)
            }
            val decision=if(post.status=="complete") GlinerFieldDecisionPolicy.applyWithEvidence(
                post.fields,laneHints,decisionReadings,grapeTerms) else GlinerFieldDecision(post.fields,emptyList())
            val fields=decision.fields
            val fieldStates=post.states.toMutableMap().apply {
                PhoneCorpusMath.fields.filter { fields[it].orEmpty()!=post.fields[it].orEmpty() }
                    .forEach { put(it,"YELLOW") }
            }
            val outcomes=PhoneCorpusMath.fields.associateWith { field ->
                PhoneCorpusMath.outcome(sample.target(field),fields[field].orEmpty(),post.status=="failed")
            }
            val baseOutcomes=PhoneCorpusMath.fields.associateWith { field ->
                PhoneCorpusMath.outcome(sample.target(field),post.fields[field].orEmpty(),post.status=="failed")
            }
            val eligible=PhoneCorpusMath.eligible(PhoneCorpusMath.fields.associateWith { sample.target(it) })
            val errors=outcomes.values.count { !PhoneCorpusMath.correct(it) }
            val results=PhoneCorpusMath.fields.map { field ->
                GlinerFieldResult(field,fieldLabels.getValue(field),sample.target(field).display,
                    fields[field].orEmpty(),fieldStates[field].orEmpty(),outcomes.getValue(field))
            }
            GlinerLabImageResult(
                image=sample.image,
                reviewNumber=sample.reviewNumber,
                status=if(post.status=="failed") "post_processing_failed" else "complete",
                message=post.message,
                ocrLines=ocr.lines.map { it.text },
                observations=GlinerLaneRecords.observations(labelled),
                laneTexts=GlinerLane.ordered.associate { it.key to labelled.lane(it).text },
                residualTexts=labelled.residualLines.map { it.text },
                fields=fields,
                fieldStates=fieldStates,
                fieldResults=results,
                eligible=eligible,
                errorCount=errors,
                wordCount=labelled.wordCount,
                spanCount=labelled.spanCount,
                layout=labelled.layout,
                outputShape=labelled.outputShape,
                modelSignature=labelled.modelSignature,
                int4=labelled.int4,
                ocrMs=ocrMs,
                glinerMs=glinerMs,
                postMs=postMs,
                totalMs=SystemClock.elapsedRealtime()-started,
                probes=labelled.probes,
                laneHints=laneHints,
                decisionReadings=decisionReadings,
                rawReadings=records,
                baseFields=post.fields,
                baseOutcomes=baseOutcomes,
                fieldDecisions=decision.changes,
                notes=buildString {
                    appendLine(ocr.description)
                    appendLine("readings: "+ocr.engineReadings.entries.joinToString(" · ") { "${it.key} ${it.value} lines" })
                    appendLine("GLiNER ${labelled.layout} · ${labelled.wordCount} words · ${labelled.spanCount} spans · ${labelled.entities.size} lane fragments")
                    labelled.probes.forEach { appendLine("probe: $it") }
                    appendLine("prompt ids: "+labelled.promptIds.take(24).joinToString(","))
                    appendLine("unknown tokens: ${labelled.unknownTokens}")
                    appendLine(laneConfig.summaryLabel())
                    appendLine("post input: original independent OCR readings (${records.size})")
                    if(decision.changes.isEmpty()) appendLine("GLiNER field decision: no override")
                    else decision.changes.forEach { appendLine("GLiNER field decision: $it") }
                    post.notes.forEach { appendLine(it) }
                },
            )
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            failed(sample,"error","${error.javaClass.simpleName}: ${error.message}",
                emptyList(),ocrMs,glinerMs,postMs,SystemClock.elapsedRealtime()-started,model)
        } finally {
            photo.delete()
        }
    }

    private data class PostOutcome(
        val fields:Map<String,String>,
        val states:Map<String,String>,
        val status:String,
        val message:String,
        val notes:List<String>,
    )

    /**
     * Stage 3. Everything below this line is the existing Lab v7 post-processing: the production
     * fill engine produces the anchor summary, then the spatial contender runs the evidence
     * extractor, producer repair, producer gazetteer and linked-wine resolver over the original
     * independent OCR observations.
     */
    private fun postProcess(
        context:Context,
        records:List<com.yourapp.wine.ocr.TokenRecord>,
        warm:TextLabWineExtraction.WarmSession,
        modules:LabPostOcrModules,
        labelled:GlinerLabelResult,
    ):PostOutcome {
        val year=Year.now().value
        val journal=TextLabJournal(setOf("candidate_3"),BuildConfig.BUILD_COMMIT,true)
        journal.metadata.putAll(mapOf(
            "pipeline" to "gliner-lane-v1",
            "gliner_layout" to labelled.layout,
            "gliner_words" to labelled.wordCount.toString(),
            "gliner_spans" to labelled.spanCount.toString(),
            "gliner_threshold" to labelled.config.threshold.toString(),
            "gliner_model" to labelled.modelSignature,
            "gliner_int4" to labelled.int4.toString(),
            "lab_modules" to modules.signatureKey(),
        ))
        val notes=mutableListOf<String>()
        val kb=warm.knowledgeBase()
        val fill=warm.fillEngine(kb)
        TextLabFormPreview.append(journal,fill,year,recordsOverride=records)
        val engine=warm.evidenceExtractor(kb,true,true,modules)
        val hybrid=warm.anchorHybrid(kb)
        val repair=warm.producerRepair(kb)
        var producers=warm.cachedGazetteer(true)?.gazetteer
        var ownsProducers=producers==null
        var unavailable="Lab expanded producer index required"
        if(producers==null) {
            try {
                val opened=ProducerGazetteer.openExpanded(context.applicationContext)
                producers=opened
                warm.cacheGazetteer(true,opened,emptyMap())
                ownsProducers=false
            } catch(error:Exception) {
                unavailable="Lab expanded producer gazetteer unavailable: ${error.javaClass.simpleName}; ${error.message}"
                notes += unavailable
            }
        }
        try {
            var lookupDeadline=0L
            val lookupBudget={
                if(lookupDeadline==0L) lookupDeadline=SystemClock.elapsedRealtime()+TextLabBudgets.GAZETTEER_LOOKUP_MS
                if(SystemClock.elapsedRealtime()>=lookupDeadline) throw ProducerResolver.BudgetExceeded()
            }
            TextLabSpatialPreview.append(journal,engine,hybrid,repair,year,producers,unavailable,lookupBudget,
                candidate="gliner_lane",stagePrefix="gliner_",fillEngine=fill,labModules=modules,
                policy=ProducerResolver.Policy.singleReading,recordsOverride=records)
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            notes += "spatial contender error: ${error.javaClass.simpleName}: ${error.message}"
        } finally { if(ownsProducers) producers?.close() }
        val summary=journal.rows.lastOrNull { it["stage"]=="gliner_evidence_summary" }
            ?: journal.rows.lastOrNull { it["stage"]=="form_summary" }
        val fields=PhoneCorpusMath.fields.associateWith { field ->
            if(summary?.get("${field}_state")=="RED") "" else summary?.get(field).orEmpty()
        }
        val states=PhoneCorpusMath.fields.associateWith { field -> summary?.get("${field}_state").orEmpty() }
        val failed=summary==null || summary["status"]=="failed"
        if(summary==null) notes += "No post-processing summary row was produced"
        if(summary!=null && summary["status"]=="failed") notes += "post-processing detail: ${summary["details"].orEmpty()}"
        val valuesFound=PhoneCorpusMath.fields.count { fields[it].orEmpty().isNotBlank() }
        return PostOutcome(fields,states,if(failed) "failed" else "complete",
            if(failed) "Post-processing failed" else "$valuesFound/6 fields suggested before GLiNER field decisions",
            notes)
    }

    /** Failure row for an unexpected exception outside the stage handlers, so the table stays complete. */
    fun failedFor(
        sample:GlinerLabSample,
        error:Exception,
        model:GlinerOnnxModel,
    ):GlinerLabImageResult = failed(sample,"error","${error.javaClass.simpleName}: ${error.message}",
        emptyList(),0,0,0,0,model)

    private fun failed(
        sample:GlinerLabSample,
        status:String,
        message:String,
        ocrLines:List<String>,
        ocrMs:Long,
        glinerMs:Long,
        postMs:Long,
        totalMs:Long,
        model:GlinerOnnxModel,
    ):GlinerLabImageResult = GlinerLabImageResult(
        image=sample.image,
        reviewNumber=sample.reviewNumber,
        status=status,
        message=message,
        ocrLines=ocrLines,
        observations=emptyList(),
        laneTexts=GlinerLane.ordered.associate { it.key to "" },
        residualTexts=emptyList(),
        fields=emptyMap(),
        fieldStates=emptyMap(),
        fieldResults=PhoneCorpusMath.fields.map { field ->
            GlinerFieldResult(field,fieldLabels.getValue(field),sample.target(field).display,"","","execution_failed")
        },
        eligible=PhoneCorpusMath.eligible(PhoneCorpusMath.fields.associateWith { sample.target(it) }),
        errorCount=PhoneCorpusMath.fields.size,
        wordCount=0,
        spanCount=0,
        layout="",
        outputShape="",
        modelSignature=model.signature,
        int4=model.int4,
        ocrMs=ocrMs,
        glinerMs=glinerMs,
        postMs=postMs,
        totalMs=totalMs,
        notes=message,
    )
}
