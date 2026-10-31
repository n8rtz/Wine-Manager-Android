package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.gliner.GlinerBundle
import com.yourapp.wine.gliner.GlinerBundleStatus
import com.yourapp.wine.gliner.GlinerLabCorpus
import com.yourapp.wine.gliner.GlinerCustomImages
import com.yourapp.wine.gliner.GlinerLabImageResult
import com.yourapp.wine.gliner.GlinerLabRunService
import com.yourapp.wine.gliner.GlinerLabRunner
import com.yourapp.wine.gliner.GlinerFieldDecisionPolicy
import com.yourapp.wine.gliner.GlinerFieldReview
import com.yourapp.wine.gliner.GlinerReviewCase
import com.yourapp.wine.gliner.summarizeGlinerReviews
import com.yourapp.wine.gliner.GlinerLabSample
import com.yourapp.wine.gliner.GlinerLane
import com.yourapp.wine.gliner.GlinerLaneConfig
import com.yourapp.wine.gliner.GlinerOnnxModel
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import com.yourapp.wine.textlab.TextLabCsv
import com.yourapp.wine.textlab.TextLabWineExtraction
import com.yourapp.wine.textlab.evidence.LabPostOcrModules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class GlinerLabRow(
    val index:Int,
    val sample:GlinerLabSample,
    val status:String="Waiting",
    val result:GlinerLabImageResult?=null,
    /** Human review is never passed to OCR, inference or field decisions. */
    val reviews:Map<String,GlinerFieldReview> = emptyMap(),
) {
    val displayName:String get() = sample.displayName
}

/** Same scoring semantics as the Lab v7 scoreboard, counted per field for this screen only. */
internal data class GlinerAggregate(
    val attempted:Int=0,
    val eligible:Int=0,
    val complete:Int=0,
    val oneError:Int=0,
    val multiError:Int=0,
    val failed:Int=0,
    val fieldCorrect:Map<String,Int> = emptyMap(),
    val fieldTotal:Map<String,Int> = emptyMap(),
    val stopped:Boolean=false,
) {
    fun text():String =
        "Fully correct $complete/$eligible · one error $oneError · 2+ errors $multiError · failed $failed · attempted $attempted"
}

internal data class GlinerLabState(
    val bundle:GlinerBundleStatus,
    val samples:List<GlinerLabRow> = emptyList(),
    val customSamples:List<GlinerLabRow> = emptyList(),
    val customLoaded:Boolean=false,
    val customLoadError:String="",
    val importingImages:Boolean=false,
    val reviewPrompt:Boolean=false,
    val runningCustom:Boolean=false,
    val running:Boolean=false,
    val finished:Boolean=false,
    val stopped:Boolean=false,
    val done:Int=0,
    val total:Int=0,
    val current:String="",
    val message:String="",
    val laneConfig:GlinerLaneConfig=GlinerLaneConfig(),
    val tokenizer:String="not loaded",
    val modules:LabPostOcrModules=LabPostOcrModules.none.withIntegrated(),
    val aggregate:GlinerAggregate?=null,
    val exports:List<File> = emptyList(),
    val expanded:Set<String> = emptySet(),
    val modelsReady:Boolean=false,
    /** Time to make the bundle files readable (asset copy or import check), in milliseconds. */
    val bundleMs:Long=0,
    /** Time to parse the tokenizer and create the ONNX session, in milliseconds. */
    val modelMs:Long=0,
    val runStartedMs:Long=0,
    /** Run-button to first result, including model and all cold initialization. */
    val firstResultMs:Long=0,
    val runElapsedMs:Long=0,
) {
    val ran:Boolean get() = samples.any { it.result!=null }
    val customRan:Boolean get() = customSamples.any { it.result!=null }
    fun row(image:String):GlinerLabRow? = (samples+customSamples).firstOrNull { it.sample.image==image }
}

internal fun aggregateGlinerRows(rows:List<GlinerLabRow>,stopped:Boolean):GlinerAggregate {
    val scored=rows.filter { it.sample.included && it.result!=null }
    val results=scored.mapNotNull { it.result }
    val correct=PhoneCorpusMath.fields.associateWith { field ->
        scored.count { row -> row.sample.target(field).status!="unresolved" &&
            row.result?.fieldResults?.firstOrNull { it.field==field }?.let { PhoneCorpusMath.correct(it.outcome) }==true }
    }
    val total=PhoneCorpusMath.fields.associateWith { field -> scored.count { it.sample.target(field).status!="unresolved" } }
    return GlinerAggregate(
        attempted=results.size,
        eligible=results.count { it.eligible },
        complete=results.count { it.complete },
        oneError=results.count { it.eligible && it.errorCount==1 },
        multiError=results.count { it.eligible && it.errorCount>1 },
        failed=results.count { it.status!="complete" },
        fieldCorrect=correct,
        fieldTotal=total,
        stopped=stopped,
    )
}


/**
 * Process-scoped holder so a GLiNER lab run survives leaving [com.yourapp.wine.gliner.GlinerLabActivity]:
 * another screen in WineTracker, another app, or the screen switched off. The foreground service
 * keeps the process alive and holds a wake lock; the work itself lives on [scope], never on the
 * activity's ViewModel.
 */
private class GlinerLabSession {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile var mutable:MutableStateFlow<GlinerLabState>?=null
    @Volatile var job:Job?=null

    companion object {
        @Volatile private var instance:GlinerLabSession?=null
        fun get():GlinerLabSession = instance ?: synchronized(this) {
            instance ?: GlinerLabSession().also { instance=it }
        }
    }
}

/**
 * Owns one GLiNER lab session: bundle status, the packaged Lab v7 dataset, one model session, one
 * warm post-processing session and the run loop. Nothing is cached across photos except the model,
 * the knowledge base and the producer gazetteer, so a run always reads fresh pixels.
 */
internal class GlinerLabViewModel(application:Application):AndroidViewModel(application) {
    // AndroidViewModel.app is generic over its bound, so keep the concrete reference.
    private val app:Application=application
    private val session=GlinerLabSession.get()
    private val mutable:MutableStateFlow<GlinerLabState> = session.mutable
        ?: MutableStateFlow(GlinerLabState(bundle=GlinerBundle.status(application))).also { session.mutable=it }
    val state=mutable.asStateFlow()
    private val workFolder=File(application.cacheDir,"gliner-lab-run")
    private var job:Job?
        get()=session.job
        set(value) { session.job=value }

    init {
        mutable.update { it.copy(modelsReady=GlinerOcrModels.present(application)) }
        refresh()
        if(!mutable.value.customLoaded) session.scope.launch {
            val loaded=runCatching { GlinerCustomImages.load(app) }
            mutable.update { state ->
                if(state.customLoaded) state else state.copy(customLoaded=true,
                    customSamples=loaded.getOrDefault(emptyList()).mapIndexed { index,sample -> GlinerLabRow(index,sample) },
                    customLoadError=loaded.exceptionOrNull()?.let { "Cannot load imported images: ${it.message}" }.orEmpty())
            }
        }
    }

    fun refresh() {
        val bundle=GlinerBundle.status(app)
        mutable.update { it.copy(bundle=bundle) }
        session.scope.launch {
            val samples=runCatching { GlinerLabCorpus.load(app) }
                .getOrElse {
                    mutable.update { state -> state.copy(message="Cannot read the packaged Lab v7 dataset: ${it.message}") }
                    return@launch
                }
            mutable.update { state ->
                val known=state.samples.associateBy { it.sample.image }
                state.copy(samples=samples.mapIndexed { index,sample ->
                    known[sample.image]?.copy(index=index) ?: GlinerLabRow(index,sample)
                },total=if(state.running) state.total else samples.size)
            }
        }
    }

    fun importBundle(uris:List<Uri>) {
        session.scope.launch {
            val outcome=GlinerBundle.import(app,uris)
            mutable.update { it.copy(bundle=GlinerBundle.status(app),message=outcome.message) }
        }
    }

    fun importImages(uris:List<Uri>) {
        if(uris.isEmpty() || mutable.value.running || mutable.value.importingImages || !mutable.value.customLoaded || mutable.value.customLoadError.isNotBlank()) return
        mutable.update { it.copy(importingImages=true,message="Copying selected images privately…") }
        session.scope.launch {
            val existing=mutable.value.customSamples.map { it.sample }
            val result=runCatching { GlinerCustomImages.import(app,uris,existing) }
            mutable.update { state ->
                result.fold(onSuccess={ imported ->
                    val known=state.customSamples.associateBy { it.sample.image }
                    val warning=if(imported.errors.isEmpty()) "" else " · ${imported.errors.size} failed: ${imported.errors.take(3).joinToString("; ")}"
                    state.copy(importingImages=false,customSamples=imported.samples.mapIndexed { index,sample ->
                        known[sample.image]?.copy(index=index,sample=sample) ?: GlinerLabRow(index,sample)
                    },message="Added ${imported.added} images · ${imported.duplicates} duplicates skipped$warning")
                },onFailure={ error -> state.copy(importingImages=false,message="Image import failed: ${error.message}") })
            }
        }
    }

    fun removeCustomImages(ids:Set<String>) {
        val reset=mutable.value.customLoadError.isNotBlank()
        if((ids.isEmpty() && !reset) || mutable.value.running || mutable.value.importingImages) return
        mutable.update { it.copy(importingImages=true) }
        session.scope.launch {
            val samples=mutable.value.customSamples.map { it.sample }
            val result=runCatching { if(reset) GlinerCustomImages.reset(app) else GlinerCustomImages.remove(app,samples,ids) }
            mutable.update { state ->
                result.fold(onSuccess={ state.copy(importingImages=false,customLoadError="",
                    customSamples=state.customSamples.filterNot { reset || it.sample.image in ids }.mapIndexed { index,row -> row.copy(index=index) },
                    message="Removed private copies; originals were not changed") },
                    onFailure={ error -> state.copy(importingImages=false,message="Could not remove images: ${error.message}") })
            }
        }
    }

    private fun updateReview(image:String,field:String,transform:(GlinerFieldReview)->GlinerFieldReview) {
        if(mutable.value.running || field !in PhoneCorpusMath.fields) return
        mutable.update { state -> state.copy(customSamples=state.customSamples.map { row ->
            if(row.sample.image!=image || row.result?.status!="complete") row else
                row.copy(reviews=row.reviews+(field to transform(row.reviews[field] ?: GlinerFieldReview())))
        }) }
    }

    fun confirmField(image:String,field:String,checked:Boolean) = updateReview(image,field) {
        GlinerFieldReview(confirmed=checked)
    }
    fun correctField(image:String,field:String,value:String) = updateReview(image,field) {
        GlinerFieldReview(corrected=value)
    }
    fun expectBlank(image:String,field:String,checked:Boolean) = updateReview(image,field) {
        GlinerFieldReview(expectedBlank=checked)
    }
    fun confirmAllFields(image:String) {
        if(mutable.value.running) return
        mutable.update { state -> state.copy(customSamples=state.customSamples.map { row ->
            if(row.sample.image!=image || row.result?.status!="complete") row else
                row.copy(reviews=PhoneCorpusMath.fields.associateWith { GlinerFieldReview(confirmed=true) })
        }) }
    }
    fun dismissReviewPrompt() { mutable.update { it.copy(reviewPrompt=false) } }

    fun clearBundle() {
        GlinerBundle.clear(app)
        mutable.update { it.copy(bundle=GlinerBundle.status(app),message="Imported GLiNER files removed") }
    }

    fun setThreshold(delta:Float) {
        mutable.update { state ->
            val value=(state.laneConfig.threshold+delta).coerceIn(0.05f,0.95f)
            state.copy(laneConfig=state.laneConfig.copy(threshold=Math.round(value*100f)/100f))
        }
    }

    fun setMaxWidth(delta:Int) {
        mutable.update { state ->
            state.copy(laneConfig=state.laneConfig.copy(maxWidth=(state.laneConfig.maxWidth+delta).coerceIn(2,24)))
        }
    }

    fun toggleExpanded(image:String) {
        mutable.update { state ->
            state.copy(expanded=if(image in state.expanded) state.expanded-image else state.expanded+image)
        }
    }

    fun stop() {
        job?.cancel(CancellationException("Stopped by user"))
        GlinerLabRunService.stop(app)
    }

    fun runAll() = launchRun(only=null,custom=false)
    fun runSingle(image:String) = launchRun(only=image,custom=false)
    fun runCustomAll() = launchRun(only=null,custom=true)
    fun runCustomSingle(image:String) = launchRun(only=image,custom=true)

    private fun launchRun(only:String?,custom:Boolean) {
        if(job?.isActive==true || mutable.value.importingImages) return
        val bundle=mutable.value.bundle
        if(!bundle.ready) {
            mutable.update { it.copy(message="Install the GLiNER-Small INT4 bundle first: ${bundle.summary()}") }
            return
        }
        val source=if(custom) mutable.value.customSamples else mutable.value.samples
        if(source.isEmpty()) {
            mutable.update { it.copy(message=if(custom) "Choose images first" else "The packaged dataset is still loading") }
            return
        }
        val laneConfig=mutable.value.laneConfig
        val modules=mutable.value.modules
        val targets=source.filter { it.sample.included && (only==null || it.sample.image==only) }
        if(targets.isEmpty()) {
            mutable.update { it.copy(message="No selected images to run") }
            return
        }
        val targetImages=targets.map { it.sample.image }.toSet()
        mutable.update { state ->
            state.copy(running=true,runningCustom=custom,reviewPrompt=false,finished=false,stopped=false,done=0,total=targets.size,current="",
                aggregate=if(custom) state.aggregate else null,message="",
                runStartedMs=SystemClock.elapsedRealtime(),firstResultMs=0,runElapsedMs=0,
                samples=if(custom) state.samples else state.samples.map { row ->
                    if(row.sample.image in targetImages) row.copy(status="Waiting",result=null) else row
                },
                customSamples=if(!custom) state.customSamples else state.customSamples.map { row ->
                    if(row.sample.image in targetImages) row.copy(status="Waiting",result=null,reviews=emptyMap()) else row
                })
        }
        GlinerLabRunService.start(app,"Preparing ${targets.size} labels…")
        job=session.scope.launch {
            var model:GlinerOnnxModel?=null
            var warm:TextLabWineExtraction.WarmSession?=null
            val results=mutableListOf<GlinerLabImageResult>()
            try {
                workFolder.mkdirs()
                mutable.update { it.copy(message="Loading the GLiNER-Small INT4 graph…") }
                // A single snap pays both of these before the first label, so record them apart:
                // the asset copy is storage, the session is 131 MB of weights plus ORT setup.
                val bundleStarted=SystemClock.elapsedRealtime()
                val status=GlinerBundle.status(app)
                val bundleMs=SystemClock.elapsedRealtime()-bundleStarted
                val modelStarted=SystemClock.elapsedRealtime()
                val opened=GlinerOnnxModel.open(app,status,laneConfig)
                val modelMs=SystemClock.elapsedRealtime()-modelStarted
                model=opened
                if(!opened.int4) mutable.update { it.copy(message="Warning: this model does not declare MatMulNBits, so it is not the INT4 build") }
                mutable.update { it.copy(tokenizer=opened.tokenizerDescription,modelMs=modelMs,bundleMs=bundleMs) }
                val warmSession=TextLabWineExtraction.WarmSession(app)
                warm=warmSession
                targets.forEachIndexed { index,row ->
                    currentCoroutineContext().ensureActive()
                    mutable.update { it.copy(current=row.displayName) }
                    GlinerLabRunService.update(app,"${index}/${targets.size} · ${row.displayName}")
                    setStatus(row.sample.image,"Running")
                    val result=try {
                        GlinerLabRunner.run(app,row.sample,opened,laneConfig,warmSession,workFolder,modules)
                    } catch(cancel:CancellationException) {
                        setStatus(row.sample.image,"Stopped")
                        throw cancel
                    } catch(error:Exception) {
                        GlinerLabRunner.failedFor(row.sample,error,opened)
                    }
                    results += result
                    mutable.update { state ->
                        val samples=if(custom) state.samples else state.samples.map { existing ->
                            if(existing.sample.image==row.sample.image) existing.copy(status=result.status,result=result) else existing
                        }
                        val customSamples=if(!custom) state.customSamples else state.customSamples.map { existing ->
                            if(existing.sample.image==row.sample.image) existing.copy(status=result.status,result=result) else existing
                        }
                        state.copy(done=index+1,samples=samples,customSamples=customSamples,
                            aggregate=if(custom) state.aggregate else aggregateGlinerRows(samples,stopped=false),
                            firstResultMs=if(index==0) (SystemClock.elapsedRealtime()-state.runStartedMs).coerceAtLeast(0) else state.firstResultMs)
                    }
                }
                val aggregate=aggregateGlinerRows(mutable.value.samples,stopped=false)
                mutable.update { state -> state.copy(running=false,finished=true,current="",
                    aggregate=if(custom) state.aggregate else aggregate,
                    reviewPrompt=custom && results.any { it.status=="complete" },
                    runElapsedMs=SystemClock.elapsedRealtime()-state.runStartedMs,message="") }
            } catch(cancel:CancellationException) {
                val aggregate=aggregateGlinerRows(mutable.value.samples,stopped=true)
                mutable.update { state -> state.copy(running=false,finished=true,stopped=true,current="",
                    aggregate=if(custom) state.aggregate else aggregate,runElapsedMs=SystemClock.elapsedRealtime()-state.runStartedMs,
                    message=if(custom) "Stopped after ${results.size} imported photos." else "Stopped after ${results.size} photos. ${aggregate.text()}") }
            } catch(error:Exception) {
                mutable.update { it.copy(running=false,finished=true,current="",runElapsedMs=SystemClock.elapsedRealtime()-it.runStartedMs,
                    aggregate=if(custom) it.aggregate else aggregateGlinerRows(it.samples,stopped=false),
                    message="GLiNER lab could not start: ${error.javaClass.simpleName}: ${error.message}") }
            } finally {
                runCatching { warm?.close() }
                runCatching { model?.close() }
                GlinerLabRunService.stop(app)
            }
        }
    }

    private fun setStatus(image:String,status:String) {
        mutable.update { state ->
            state.copy(samples=state.samples.map { if(it.sample.image==image) it.copy(status=status) else it },
                customSamples=state.customSamples.map { if(it.sample.image==image) it.copy(status=status) else it })
        }
    }

    /** Plain text of the whole run, for the "Copy all results" button. */
    fun reportText():String {
        val snapshot=mutable.value
        return buildString {
            appendLine("GLiNER lane lab · ${BuildConfig.BUILD_COMMIT} · ${snapshot.laneConfig.signatureKey()}")
            appendLine("bundle: ${snapshot.bundle.summary()}")
            if(snapshot.modelMs>0) appendLine("model load: bundle ${snapshot.bundleMs} ms + tokenizer/session ${snapshot.modelMs} ms")
            if(snapshot.firstResultMs>0) appendLine("first result wall time: ${snapshot.firstResultMs} ms (includes initialization)")
            val ran=snapshot.samples.mapNotNull { it.result }
            appendLine("status: "+ran.groupingBy { it.status }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" })
            snapshot.aggregate?.let { appendLine(it.text()) }
            val paired=ran.filter { it.eligible && it.baseOutcomes.size==PhoneCorpusMath.fields.size }
            if(paired.isNotEmpty()) {
                val baseComplete=paired.count { result -> PhoneCorpusMath.fields.all { PhoneCorpusMath.correct(result.baseOutcomes[it].orEmpty()) } }
                val finalComplete=paired.count { it.complete }
                appendLine("same-OCR base $baseComplete/${paired.size} · GLiNER decisions $finalComplete/${paired.size} · overrides ${paired.count { it.fieldDecisions.isNotEmpty() }}")
            }
            if(snapshot.message.isNotBlank()) appendLine("message: ${snapshot.message}")
            snapshot.samples.filter { it.result!=null }.forEach { row ->
                val result=row.result ?: return@forEach
                appendLine()
                appendLine("${row.index+1}. ${row.displayName} — ${result.status} · ${result.message}")
                result.probes.forEach { appendLine("  probe: $it") }
                GlinerLane.ordered.forEach { lane ->
                    appendLine("  ${lane.shortName}: ${result.laneTexts[lane.key].orEmpty().ifBlank { "(none)" }}")
                }
                result.residualTexts.takeIf { it.isNotEmpty() }?.let { appendLine("  residual: ${it.joinToString(" | ")}") }
                result.fieldResults.forEach { field ->
                    appendLine("  ${field.label}: ${field.observed.ifBlank { "(blank)" }} · ${field.state.ifBlank { "-" }} · ${field.outcome} · expected ${field.expected.ifBlank { "-" }}")
                }
                appendLine("  ${result.layout} · ${result.wordCount} words · ${result.spanCount} spans · ocr ${result.ocrMs} ms · gliner ${result.glinerMs} ms")
                if(result.ocrLines.isNotEmpty()) {
                    appendLine("  OCR (${result.ocrLines.size}):")
                    result.ocrLines.forEach { appendLine("    $it") }
                }
                if(result.notes.isNotBlank()) result.notes.trim().lines().forEach { appendLine("  note: $it") }
            }
            if(snapshot.customRan) {
                appendLine("\nImported images · manual review (not part of curated scores)")
                snapshot.customSamples.filter { it.result!=null }.forEach { row ->
                    val result=row.result ?: return@forEach
                    appendLine("${row.displayName} · ${result.status}${if(row.sample.matchesCorpus) " · exact corpus match" else ""}")
                    result.fieldResults.forEach { field ->
                        val review=row.reviews[field.field] ?: GlinerFieldReview()
                        val expected=if(review.confirmed) field.observed else if(review.expectedBlank) "(blank)" else review.corrected
                        appendLine("  ${field.label}: ${field.observed.ifBlank { "(blank)" }} · ${review.verdict}"+
                            if(review.reviewed) " · expected ${expected.ifBlank { "(blank)" }}" else "")
                    }
                    result.ocrLines.forEach { appendLine("  OCR: $it") }
                }
            }
        }
    }

    /** JSON export of the whole run, for the "Save JSON" button. */
    fun saveJson(uri:Uri) {
        val snapshot=mutable.value
        session.scope.launch {
            val results=snapshot.samples.mapNotNull { it.result }
            val customRows=snapshot.customSamples.filter { it.result!=null }
            if(results.isEmpty() && customRows.isEmpty()) {
                mutable.update { it.copy(message="Run images before exporting") }
                return@launch
            }
            val payload=JSONObject().apply {
                put("pipeline","gliner-lane-v1")
                put("decision_policy",GlinerFieldDecisionPolicy.VERSION)
                put("build",BuildConfig.BUILD_COMMIT)
                put("branch",BuildConfig.BUILD_BRANCH)
                put("signature",snapshot.laneConfig.signatureKey())
                put("threshold",snapshot.laneConfig.threshold.toDouble())
                put("max_width",snapshot.laneConfig.maxWidth)
                put("max_words",snapshot.laneConfig.maxWords)
                put("bundle",snapshot.bundle.summary())
                put("tokenizer",snapshot.tokenizer)
                put("model_load_ms",JSONObject().apply {
                    put("bundle",snapshot.bundleMs)
                    put("session",snapshot.modelMs)
                    put("first_result_wall",snapshot.firstResultMs)
                })
                put("lab_modules",snapshot.modules.signatureKey())
                snapshot.aggregate?.let { aggregate ->
                    put("aggregate",JSONObject().apply {
                        put("attempted",aggregate.attempted)
                        put("eligible",aggregate.eligible)
                        put("complete",aggregate.complete)
                        put("one_error",aggregate.oneError)
                        put("multi_error",aggregate.multiError)
                        put("failed",aggregate.failed)
                        put("field_correct",JSONObject(aggregate.fieldCorrect as Map<*, *>))
                    })
                }
                val paired=results.filter { it.eligible && it.baseOutcomes.size==PhoneCorpusMath.fields.size }
                if(paired.isNotEmpty()) {
                    fun baseComplete(result:GlinerLabImageResult) = PhoneCorpusMath.fields.all { PhoneCorpusMath.correct(result.baseOutcomes[it].orEmpty()) }
                    put("same_ocr_comparison",JSONObject().apply {
                        put("eligible",paired.size)
                        put("base_complete",paired.count { baseComplete(it) })
                        put("final_complete",paired.count { it.complete })
                        put("override_labels",paired.count { it.fieldDecisions.isNotEmpty() })
                        put("gains",paired.count { !baseComplete(it) && it.complete })
                        put("losses",paired.count { baseComplete(it) && !it.complete })
                        put("fields",JSONObject().apply {
                            PhoneCorpusMath.fields.forEach { field ->
                                fun baseCorrect(result:GlinerLabImageResult) = PhoneCorpusMath.correct(result.baseOutcomes[field].orEmpty())
                                fun finalCorrect(result:GlinerLabImageResult) = result.fieldResults.firstOrNull { it.field==field }
                                    ?.let { PhoneCorpusMath.correct(it.outcome) } ?: false
                                put(field,JSONObject().apply {
                                    put("base_correct",paired.count(::baseCorrect))
                                    put("final_correct",paired.count(::finalCorrect))
                                    put("gains",paired.count { !baseCorrect(it) && finalCorrect(it) })
                                    put("losses",paired.count { baseCorrect(it) && !finalCorrect(it) })
                                })
                            }
                        })
                    })
                }
                put("images",JSONArray(results.map { result -> jsonOf(result) }))
                put("custom_images",JSONArray(customRows.map { row -> customJson(row) }))
                val reviewSummary=summarizeGlinerReviews(customRows.map { row ->
                    GlinerReviewCase(row.result?.status=="complete",row.sample.matchesCorpus,row.reviews)
                },PhoneCorpusMath.fields)
                put("custom_review_summary",JSONObject().apply {
                    put("processed",reviewSummary.processed)
                    put("fully_reviewed",reviewSummary.fullyReviewed)
                    put("all_fields_confirmed",reviewSummary.allConfirmed)
                    put("reviewed_fields",reviewSummary.reviewedFields)
                    put("confirmed_fields",reviewSummary.confirmedFields)
                    put("exact_corpus_matches",reviewSummary.corpusMatches)
                })
            }
            val file=File(app.cacheDir,"gliner-lab-${System.currentTimeMillis()}.json")
            runCatching {
                file.writeText(payload.toString(2))
                app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer -> writer.write(payload.toString(2)) }
                    ?: error("Cannot open the selected document")
                mutable.update { it.copy(exports=(it.exports+file).takeLast(4),message="Exported ${results.size} curated and ${customRows.size} imported labels to JSON") }
            }.onFailure { error ->
                mutable.update { it.copy(message="JSON export failed: ${error.javaClass.simpleName}: ${error.message}") }
            }
        }
    }

    private fun jsonOf(result:GlinerLabImageResult):JSONObject=JSONObject().apply {
        put("image",result.image)
        put("review_number",result.reviewNumber)
        put("status",result.status)
        put("message",result.message)
        put("eligible",result.eligible)
        put("field_errors",result.errorCount)
        put("layout",result.layout)
        put("output_shape",result.outputShape)
        put("model",result.modelSignature)
        put("int4",result.int4)
        put("words",result.wordCount)
        put("spans",result.spanCount)
        put("probes",JSONArray(result.probes))
        put("lane_hints",JSONArray(result.laneHints.map { hint -> JSONObject().apply {
            put("lane",hint.lane.key)
            put("text",hint.text)
            put("score",hint.confidence.toDouble())
        } }))
        put("ocr_ms",result.ocrMs)
        put("gliner_ms",result.glinerMs)
        put("post_ms",result.postMs)
        put("total_ms",result.totalMs)
        put("lanes",JSONObject(result.laneTexts as Map<*, *>))
        put("residual",JSONArray(result.residualTexts))
        put("fields",JSONObject(result.fields as Map<*, *>))
        put("base_fields",JSONObject(result.baseFields as Map<*, *>))
        put("base_outcomes",JSONObject(result.baseOutcomes as Map<*, *>))
        put("gliner_decisions",JSONArray(result.fieldDecisions))
        put("decision_readings",JSONArray(result.decisionReadings.map { reading -> JSONObject().apply {
            put("text",reading.text)
            put("engine",reading.engine)
            put("box",JSONArray(listOf(reading.left,reading.top,reading.right,reading.bottom)))
        } }))
        put("raw_readings",JSONArray(result.rawReadings.map { reading -> JSONObject().apply {
            put("text",reading.textRaw)
            put("engine",reading.engineId)
            put("variant",reading.variantId)
            put("score",reading.score.toDouble())
            put("score_estimated",reading.scoreIsEstimated)
            put("box",JSONArray(listOf(reading.bboxRect.left,reading.bboxRect.top,reading.bboxRect.right,reading.bboxRect.bottom)))
        } }))
        put("states",JSONObject(result.fieldStates as Map<*, *>))
        put("expected",JSONObject(result.fieldResults.associate { it.field to it.expected } as Map<*, *>))
        put("outcomes",JSONObject(result.fieldResults.associate { it.field to it.outcome } as Map<*, *>))
        put("ocr_lines",JSONArray(result.ocrLines))
        put("notes",result.notes)
    }

    private fun customJson(row:GlinerLabRow):JSONObject {
        val result=requireNotNull(row.result)
        return jsonOf(result).apply {
            put("source","user_import")
            put("source_filename",row.sample.sourceFilename)
            put("sha256",row.sample.sha256)
            put("exact_corpus_match",row.sample.matchesCorpus)
            put("field_errors",JSONObject.NULL) // No curated reference is available.
            put("manual_review",JSONObject().apply {
                PhoneCorpusMath.fields.forEach { field ->
                    val review=row.reviews[field] ?: GlinerFieldReview()
                    val observed=result.fields[field].orEmpty()
                    val expected=if(review.confirmed) observed else if(review.expectedBlank) "" else review.corrected
                    put(field,JSONObject().apply {
                        put("verdict",review.verdict)
                        put("expected_status",if(!review.reviewed) "unresolved" else if(expected.isBlank()) "expected_blank" else "expected_value")
                        if(review.reviewed) put("expected",expected)
                    })
                }
            })
        }
    }

    fun saveCsv(uri:Uri) {
        val snapshot=mutable.value
        session.scope.launch {
            val rows=snapshot.samples.mapNotNull { it.result }.map { result -> csvRow(result,snapshot.laneConfig)+("source" to "curated") } +
                snapshot.customSamples.filter { it.result!=null }.map { row ->
                    val result=requireNotNull(row.result)
                    val review=linkedMapOf("source" to "user_import", "field_errors" to "", "source_filename" to row.sample.sourceFilename,
                        "sha256" to row.sample.sha256,"exact_corpus_match" to row.sample.matchesCorpus.toString())
                    PhoneCorpusMath.fields.forEach { field ->
                        val item=row.reviews[field] ?: GlinerFieldReview()
                        review["${field}_manual_verdict"]=item.verdict
                        review["${field}_manual_expected"]=if(item.confirmed) result.fields[field].orEmpty() else item.corrected
                        review["${field}_manual_expected_blank"]=if(item.reviewed) (if(item.confirmed) result.fields[field].isNullOrBlank() else item.expectedBlank).toString() else ""
                    }
                    csvRow(result,snapshot.laneConfig)+review
                }
            if(rows.isEmpty()) {
                mutable.update { it.copy(message="Run images before exporting") }
                return@launch
            }
            val file=File(app.cacheDir,"gliner-lab-${System.currentTimeMillis()}.csv")
            runCatching {
                app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    TextLabCsv.write(writer,rows.asSequence())
                } ?: error("Cannot open the selected document")
                mutable.update { it.copy(exports=(it.exports+file).takeLast(4),message="Exported ${rows.size} rows to CSV") }
            }.onFailure { error ->
                mutable.update { it.copy(message="Export failed: ${error.javaClass.simpleName}: ${error.message}") }
            }
        }
    }

    private fun csvRow(result:GlinerLabImageResult,config:GlinerLaneConfig):Map<String,String> {
        val base=linkedMapOf(
            "pipeline" to "gliner-lane-v1",
            "build" to BuildConfig.BUILD_COMMIT,
            "image" to result.image,
            "review_number" to result.reviewNumber.toString(),
            "status" to result.status,
            "message" to result.message,
            "eligible" to result.eligible.toString(),
            "field_errors" to result.errorCount.toString(),
            "model" to result.modelSignature,
            "int4_matmul_nbits" to result.int4.toString(),
            "output_shape" to result.outputShape,
            "output_layout" to result.layout,
            "words" to result.wordCount.toString(),
            "spans" to result.spanCount.toString(),
            "threshold" to config.threshold.toString(),
            "max_width" to config.maxWidth.toString(),
            "lane_prompts" to config.prompts.joinToString(" | "),
            "probes" to result.probes.joinToString(" | "),
            "ocr_ms" to result.ocrMs.toString(),
            "gliner_ms" to result.glinerMs.toString(),
            "post_ms" to result.postMs.toString(),
            "total_ms" to result.totalMs.toString(),
            "producer_lane" to result.laneTexts["producer"].orEmpty(),
            "wine_name_lane" to result.laneTexts["wine_name"].orEmpty(),
            "appellation_lane" to result.laneTexts["appellation"].orEmpty(),
            "residual" to result.residualTexts.joinToString(" | "),
            "ocr_lines" to result.ocrLines.joinToString(" | "),
            "notes" to result.notes.replace('\n',' '),
        )
        PhoneCorpusMath.fields.forEach { field ->
            val outcome=result.fieldResults.firstOrNull { it.field==field }
            base["${field}_expected"]=outcome?.expected.orEmpty()
            base["${field}_observed"]=result.fields[field].orEmpty()
            base["${field}_state"]=result.fieldStates[field].orEmpty()
            base["${field}_outcome"]=outcome?.outcome.orEmpty()
        }
        return base
    }

    override fun onCleared() {
        // Deliberately does not cancel the run: GlinerLabSession + GlinerLabRunService keep it
        // alive when the activity goes away, and the screen picks the state back up on return.
        super.onCleared()
    }
}

/** Keeps the GLiNER screen independent of the OCR helper's package layout. */
private object GlinerOcrModels {
    fun present(application:Application):Boolean =
        runCatching { com.yourapp.wine.gliner.GlinerOcr.modelsPresent(application) }.getOrDefault(false)
}
