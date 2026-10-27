package com.yourapp.wine.phonecorpus

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.yourapp.wine.diagnostics.PipelineFunnel as F
import com.yourapp.wine.diagnostics.ScanStage
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.textlab.*
import com.yourapp.wine.ui.ScanChoicePolicy
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Year

/** Fresh pixels only. Receives input identity/hash, NEVER target fields or saved predictions. */
internal object PhoneCorpusRunner {
    /**
     * Lab-only measurement switch for bounded appellation repair. Off by default so the frozen Prod
     * comparators and every stored Lab v4 checkpoint keep their current meaning. To measure the
     * difference, set this to true, rebuild, and tap **New Lab run** so no photo mixes both settings
     * (the flag is written to each CSV's `place_repair_enabled` metadata column).
     */
    internal const val REPAIR_PLACES_IN_BENCHMARK=false
    enum class Mode(val title:String,val pipelineId:String,val summaryStage:String?) {
        LAB("Lab v4", "lab-v4-expanded-producers", "spatial_evidence_summary"),
        PRODUCTION_V3("Prod v3.0", "production-v3.0-lwin-only", "spatial_lwin_evidence_summary"),
        PRODUCTION_V2("Prod v2.0", "production-v2.0-guarded", "evidence_summary"),
        PRODUCTION_V1("Prod v1.0", "production-v1.0-15s", null);
        /** Both spatial contenders run the producer/linked-wine resolvers; only the producer
         * index differs (Prod v3.0 verified LWIN only, Lab v4 the Lab expanded index). */
        val usesSpatialEvidence:Boolean get()=this==PRODUCTION_V3 || this==LAB
        companion object {
            /** Pipeline IDs written by earlier builds, mapped to the mode that owned them.
             * Saved scored CSVs keep the producing build's ID forever; without these aliases
             * the expanded-producers rename made every saved Lab v4 table unreadable, which
             * hid the restored results and their export options (owner report, 2026-10-26).
             * Never reuse a retired ID for a different pipeline; append future renames here. */
            val legacyPipelineIds:Map<String,Mode> = mapOf(
                "lab-v4-lwin-only" to Mode.LAB,
                "production-v3.0-spatial-merged" to Mode.PRODUCTION_V3,
            )
            /** Mode that owns a pipeline ID exactly as written in a saved CSV (current or legacy). */
            fun fromPipelineId(id:String):Mode? =
                Mode.entries.firstOrNull { it.pipelineId==id } ?: legacyPipelineIds[id]
        }
    }
    fun sha(file:File):String {
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b=ByteArray(65536);while(true) { val n=input.read(b);if(n<0) break;md.update(b,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    /**
     * Runs every requested version against one verified copy of the photo, so the versions'
     * Attempted counts advance together instead of depending on separate per-version runs.
     * Prod v1.0 keeps its own production scan and runs first (its shared 15-second budget is
     * anchored at photo start). Prod v2.0, Prod v3.0 and Lab v4 share one fresh Text Lab OCR
     * pass and differ only in post-OCR extraction, exactly as their standalone runs did:
     * v2.0 guarded evidence only; v3.0 and Lab v4 run the same current spatial pipeline
     * (original readings plus producer/linked-wine resolution over spatial spans) and differ
     * only in producer data — v3.0 verified LWIN only, Lab v4 the Lab expanded producer index.
     * Every requested version returns a result; one that could not run is failed, never missing.
     */
    suspend fun runImageAllModes(context:Context,image:String,hash:String,modes:Set<Mode>,work:File,traceParents:List<File>,assetRoot:String="wine-app-labels-v1"):Map<Mode,JSONObject> {
        require(image.matches(Regex("[0-9a-f]{40}\\.jpg")))
        require(modes.isNotEmpty()) { "At least one pipeline version is required" }
        work.mkdirs()
        val input=File(work,"input.jpg");val preview=File(work,"preview.png");val crop=File(work,"crop.png")
        val outputs=linkedMapOf<Mode,JSONObject>()
        val recordedByMode=linkedMapOf<Mode,MutableMap<String,F.Gate>>()
        for(mode in Mode.entries) if(mode in modes) {
            outputs[mode]=JSONObject().put("image",image).put("build",BuildConfig.BUILD_COMMIT).put("mode",mode.name)
                .put("pipeline",mode.pipelineId).put("spatial_merging",mode.usesSpatialEvidence)
                .put("device",android.os.Build.MODEL).put("android_sdk",android.os.Build.VERSION.SDK_INT)
                .put("runtime","on-device benchmark; fresh OCR; no saved-text replay")
            recordedByMode[mode]=linkedMapOf()
        }
        fun mark(mode:Mode,id:String,passed:Boolean,reason:String="") {
            recordedByMode.getValue(mode)[id]=F.Gate(if(passed) F.State.PASSED else F.State.BLOCKED,if(passed) "" else reason)
        }
        fun markAll(id:String,passed:Boolean,reason:String="") { recordedByMode.keys.forEach { mark(it,id,passed,reason) } }
        fun fail(mode:Mode,error:String,abort:Boolean=false) {
            val output=outputs.getValue(mode)
            output.put("status","failed").put("error",error)
            if(abort) output.put("abort_run",true)
        }
        fun failMemory(mode:Mode) {
            outputs.getValue(mode).remove("trace");outputs.getValue(mode).remove("tokens")
            fail(mode,"Memory pressure; run stopped",abort=true)
        }
        val started=SystemClock.elapsedRealtime()
        var diagnosticJournal:TextLabJournal?=null
        var memoryStopped=false
        try {
            markAll("input.verified",false,"input copy / checksum did not complete")
            context.assets.open("$assetRoot/images/$image").use { stream -> input.outputStream().use { stream.copyTo(it) } }
            check(sha(input)==hash) { "Packaged photo checksum mismatch" }
            markAll("input.verified",true)
            var aborted=false
            if(Mode.PRODUCTION_V1 in modes) {
                val mode=Mode.PRODUCTION_V1
                val output=outputs.getValue(mode)
                val recorded=recordedByMode.getValue(mode)
                fun mark(id:String,passed:Boolean,reason:String="") { mark(mode,id,passed,reason) }
                try {
                    val pipelines=JSONArray()
                    // Same FILE entry point and 15-second shared production budget; no experimental override.
                    val scanner=WineScannerServices.get(context)
                    mark("input.services",true)
                    val report=scanner.scan(input,started)
                    report.diagnostics.nativeReady?.let { mark("input.native",it,"native initialization failed") }
                    if(report.diagnostics.kbImplementation!=null) mark("extract.kb",true)
                    for((id,stage) in listOf("input.decoded" to ScanStage.DECODE,"input.quality" to ScanStage.QUALITY,
                        "input.localized" to ScanStage.LOCALIZE,"input.prepared" to ScanStage.WARP,"extract.form" to ScanStage.FILL)) {
                        if(report.diagnostics.stages.any { it.stage==stage }) {
                            val qualityBlocked=stage==ScanStage.QUALITY && report.diagnostics.quality?.state=="RED"
                            mark(id,!qualityBlocked && report.diagnostics.failures.none { it.stage==stage } && report.diagnostics.deadlineStage!=stage,
                                if(qualityBlocked) "RED image quality requires retake" else "$stage failed / deadline")
                        }
                    }
                    for((key,id) in listOf("mlkit" to "mlkit","paddle" to "paddle-v6-medium")) {
                        val timings=report.timings.filter { it.engineId==id }
                        if(timings.isNotEmpty()) {
                            mark("$key.started",true)
                            mark("$key.completed",timings.none { it.deadlineCancelled || it.detail?.contains("failed:")==true },"engine failure / deadline")
                            val tokens=report.tokens.filter { it.engineId==id }
                            if(tokens.any { it.textRaw.isNotBlank() }) mark("$key.text",true)
                            else if(timings.all { it.tokenCount==0 }) mark("$key.text",false,"no OCR text returned")
                            mark("$key.mapped",tokens.any { it.textRaw.isNotBlank() && it.score.isFinite() && it.score in 0f..1f &&
                                it.bboxRect.width()>0 && it.bboxRect.height()>0 },"no usable mapped OCR observations")
                        } else recorded["$key.started"]=F.Gate(F.State.NOT_REACHED,"no engine pass attempted")
                    }
                    mark("ocr.both_completed",listOf("mlkit","paddle").all { recorded["$it.completed"]?.state==F.State.PASSED },"one or both engines failed / not reached")
                    mark("ocr.both_text",listOf("mlkit","paddle").all { recorded["$it.mapped"]?.state==F.State.PASSED },"one or both engines supplied no usable observation")
                    check(report.peakWorkingBitmaps<=2 && report.workingBitmapsAfter==0) { "Bitmap lifetime violation" }
                    val values=report.fill.fields.mapValues { if(it.value.state==UiState.RED) "" else it.value.value }
                    val fields=JSONObject();val choices=JSONObject()
                    FieldId.entries.forEach { f ->
                        fields.put(f.name.lowercase(),values.getValue(f))
                        choices.put(f.name.lowercase(),JSONArray(ScanChoicePolicy.choices(f,report.fill,values,Year.now().value).map { it.value }))
                    }
                    val engineFailed=report.timings.any { it.detail?.contains("failed:")==true } || report.diagnostics.engines.any { !it.available }
                    val fatal=report.diagnostics.nativeReady==false || engineFailed || report.diagnostics.failures.isNotEmpty()
                    output.put("engine_incomplete",fatal || report.deadlineCancelled)
                    output.put("engine_timings",JSONArray(report.timings.map { JSONObject().put("engine",it.engineId).put("elapsed_ms",it.ms).put("deadline_reached",it.deadlineCancelled).put("detail",it.detail).put("token_count",it.tokenCount).put("variant",it.variantId) }))
                    pipelines.put(JSONObject().put("pipeline",mode.pipelineId).put("status",if(fatal) "failed" else "complete").put("fields",fields).put("choices",choices)
                        .put("error",if(fatal) report.diagnostics.failures.toString()+report.timings.filter { it.detail?.contains("failed:")==true }.toString() else "")
                        .put("deadline_reached",report.deadlineCancelled).put("elapsed_ms",report.elapsedMs))
                    output.put("tokens",JSONArray(report.tokens.map { r -> JSONObject().put("raw",r.textRaw).put("engine",r.engineId).put("variant",r.variantId)
                        .put("score",r.score).put("original_box",r.bboxOrig.toString()).put("rectified_box",r.bboxRect.toString()) }))
                    output.put("diagnostics",report.diagnostics.toString())
                    output.put("pipelines",pipelines).put("status","complete")
                } catch(cancel:CancellationException) { throw cancel }
                catch(e:LinkageError) { fail(mode,"Native linkage failure: ${e.javaClass.simpleName}",abort=true);aborted=true }
                catch(e:OutOfMemoryError) { memoryStopped=true;failMemory(mode);aborted=true }
                catch(e:Exception) { fail(mode,"${e.javaClass.simpleName}: ${e.message}") }
            }
            val labModes=Mode.entries.filter { it!=Mode.PRODUCTION_V1 && it in modes }
            if(labModes.isNotEmpty()) {
                if(aborted) labModes.forEach { fail(it,"Not run: an earlier version aborted this photo",abort=true) }
                else try {
                    val needEvidence=Mode.PRODUCTION_V2 in labModes
                    val needLwin=Mode.PRODUCTION_V3 in labModes
                    val needSpatial=Mode.LAB in labModes
                    val batch=TextLabBatch(listOf(TextLabImageKey(image,image)),setOf("candidate_3"),BuildConfig.BUILD_COMMIT,true,
                        compareEvidence=true,anchorHybrid=true,repairProducer=true,
                        spatialPhrases=needSpatial,benchmarkOnly=true,benchmarkAllPipelines=true,
                        benchmarkEvidence=needEvidence,benchmarkLwinSpatial=needLwin,repairPlaces=REPAIR_PLACES_IN_BENCHMARK)
                    val journal=batch.journals.single()
                    diagnosticJournal=journal
                    labModes.forEach { mark(it,"input.decoded",false,"image decode did not complete") }
                    val photo=TextLabRunner.preview(input,preview)
                    labModes.forEach { mark(it,"input.decoded",true) }
                    TextLabRunner.recognize(context,photo,TextCrop(0f,0f,1f,1f),false,crop,journal)
                    TextLabWineExtraction.append(context,batch)
                    val engineRows=listOf("mlkit_reference" to "summary","candidate_3" to "candidate_summary").map { (candidate,stage) ->
                        journal.rows.lastOrNull { it["candidate"]==candidate && it["stage"]==stage }
                    }
                    val finished=setOf("complete","no_text","no_regions")
                    val engineFailed=engineRows.any { it==null || it["status"] !in finished+setOf("timed_out","truncated") }
                    val incomplete=engineRows.any { it==null || it["status"] !in finished }
                    val pipelinesByMode=labModes.associateWith { mode ->
                        val pipelines=JSONArray()
                        for(row in journal.rows.filter { it["stage"]==mode.summaryStage }) {
                            val fields=JSONObject();PhoneCorpusMath.fields.forEach { f -> fields.put(f,if(row["${f}_state"]=="RED") "" else row[f].orEmpty()) }
                            val name=mode.pipelineId
                            pipelines.put(JSONObject().put("pipeline",name).put("status",if(engineFailed || row["status"] !in setOf("suggestions","partial_suggestions","no_fields","complete")) "failed" else "complete").put("fields",fields)
                                .put("error",if(engineFailed) engineRows.joinToString { "${it?.get("candidate")}: ${it?.get("status") ?: "missing"}" } else if(row["status"]=="failed") row["details"].orEmpty() else ""))
                        }
                        if((0 until pipelines.length()).none { pipelines.getJSONObject(it).getString("pipeline")==mode.pipelineId })
                            pipelines.put(JSONObject().put("pipeline",mode.pipelineId).put("status","failed").put("fields",JSONObject()))
                        pipelines
                    }
                    labModes.forEach { outputs.getValue(it).put("engine_incomplete",incomplete) }
                    // Raw lab trace remains a separate CSV per image; never keep 168 journals in RAM.
                    batch.finishImage(0,"complete")
                    traceParents.forEach { batch.save(File(it,"traces")) }
                    labModes.forEach { mode -> outputs.getValue(mode).put("pipelines",pipelinesByMode.getValue(mode)).put("status","complete") }
                } catch(cancel:CancellationException) { throw cancel }
                catch(e:LinkageError) { labModes.forEach { fail(it,"Native linkage failure: ${e.javaClass.simpleName}",abort=true) } }
                catch(e:OutOfMemoryError) { memoryStopped=true;labModes.forEach { failMemory(it) } }
                catch(e:Exception) { labModes.forEach { fail(it,"${e.javaClass.simpleName}: ${e.message}") } }
            }
        } catch(cancel:CancellationException) { throw cancel }
        catch(e:LinkageError) { modes.forEach { if(outputs.getValue(it).optString("status")!="complete") fail(it,"Native linkage failure: ${e.javaClass.simpleName}",abort=true) } }
        catch(e:OutOfMemoryError) { memoryStopped=true;modes.forEach { if(outputs.getValue(it).optString("status")!="complete") failMemory(it) } }
        catch(e:Exception) { modes.forEach { if(outputs.getValue(it).optString("status")!="complete") fail(it,"${e.javaClass.simpleName}: ${e.message}") } }
        finally {
            input.delete();preview.delete();crop.delete()
            for((mode,output) in outputs) {
                if(memoryStopped) output.put("pipeline_funnel_error","omitted after memory pressure")
                else try {
                    // Each version keeps the trace its standalone run would have produced: the shared
                    // OCR rows plus its own extraction rows, never another contender's. Prod v1.0
                    // has no Text Lab journal, as before.
                    val trace=if(mode==Mode.PRODUCTION_V1) emptyList() else diagnosticJournal?.let { journal ->
                        val ownCandidate=when(mode) {
                            Mode.LAB -> "spatial_lab"
                            Mode.PRODUCTION_V3 -> "spatial_lwin_lab"
                            Mode.PRODUCTION_V2,Mode.PRODUCTION_V1 -> "experimental_evidence"
                        }
                        val contenderCandidates=setOf("experimental_evidence","spatial_lwin_lab","spatial_lab")
                        journal.rows.filter { it["candidate"] !in contenderCandidates || it["candidate"]==ownCandidate }
                    }.orEmpty()
                    if(diagnosticJournal!=null && mode!=Mode.PRODUCTION_V1) output.put("trace",JSONArray(trace.map { JSONObject(it) }))
                    val pipelines=output.optJSONArray("pipelines")
                    val current=pipelines?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("pipeline")==mode.pipelineId } }
                    val fields=current?.optJSONObject("fields")?.let { f -> PhoneCorpusMath.fields.associateWith { f.optString(it) } }
                    val status=if(output.optString("status")!="complete") "failed" else current?.optString("status") ?: "failed"
                    PhoneCorpusFunnelJson.write(output,PhoneCorpusPipelineDiagnostics.capture(image,1,mode.name,trace,recordedByMode.getValue(mode),fields,status,fresh=true))
                    Log.d("WineTracker","Pipeline funnel recorded: $image ${mode.pipelineId}")
                } catch(e:Exception) {
                    output.put("pipeline_funnel_error",e.javaClass.simpleName)
                    Log.d("WineTracker","Pipeline funnel unavailable: ${e.javaClass.simpleName}")
                } catch(_:OutOfMemoryError) { output.put("pipeline_funnel_error","memory pressure") }
                output.put("total_elapsed_ms",SystemClock.elapsedRealtime()-started)
            }
        }
        return outputs
    }
}
