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
    enum class Mode(val title:String,val pipelineId:String,val summaryStage:String?) {
        PRODUCTION_V1("Production v1.0 · 15s shared budget", "production-v1.0-15s", null),
        PRODUCTION_V2("Production v2.0 · guarded producer · 60s per engine", "production-v2.0-guarded", "evidence_summary"),
        LAB("Lab · producer + wine evidence · 60s per engine", "lab-linked-wine-v1", "spatial_evidence_summary")
    }
    fun sha(file:File):String {
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b=ByteArray(65536);while(true) { val n=input.read(b);if(n<0) break;md.update(b,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    suspend fun runImage(context:Context,image:String,hash:String,mode:Mode,work:File):JSONObject {
        require(image.matches(Regex("[0-9a-f]{40}\\.jpg")))
        work.mkdirs()
        val input=File(work,"input.jpg");val preview=File(work,"preview.png");val crop=File(work,"crop.png")
        val output=JSONObject().put("image",image).put("build",BuildConfig.BUILD_COMMIT).put("mode",mode.name)
            .put("pipeline",mode.pipelineId).put("spatial_merging",mode==Mode.LAB)
            .put("device",android.os.Build.MODEL).put("android_sdk",android.os.Build.VERSION.SDK_INT)
            .put("runtime","on-device benchmark; fresh OCR; no saved-text replay")
        val started=SystemClock.elapsedRealtime()
        val recorded=linkedMapOf<String,F.Gate>()
        fun mark(id:String,passed:Boolean,reason:String="") { recorded[id]=F.Gate(if(passed) F.State.PASSED else F.State.BLOCKED,if(passed) "" else reason) }
        var diagnosticJournal:TextLabJournal?=null
        try {
            mark("input.verified",false,"input copy / checksum did not complete")
            context.assets.open("phone-corpus-v1/images/$image").use { stream -> input.outputStream().use { stream.copyTo(it) } }
            check(sha(input)==hash) { "Packaged photo checksum mismatch" }
            mark("input.verified",true)
            val pipelines=JSONArray()
            if(mode==Mode.PRODUCTION_V1) {
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
            } else {
                val batch=TextLabBatch(listOf(TextLabImageKey(image,image)),setOf("candidate_3"),BuildConfig.BUILD_COMMIT,true,
                    compareEvidence=true,anchorHybrid=true,repairProducer=true,
                    spatialPhrases=mode==Mode.LAB,benchmarkOnly=true)
                val journal=batch.journals.single()
                diagnosticJournal=journal
                mark("input.decoded",false,"image decode did not complete")
                val photo=TextLabRunner.preview(input,preview)
                mark("input.decoded",true)
                TextLabRunner.recognize(context,photo,TextCrop(0f,0f,1f,1f),false,crop,journal)
                TextLabWineExtraction.append(context,batch)
                val engineRows=listOf("mlkit_reference" to "summary","candidate_3" to "candidate_summary").map { (candidate,stage) ->
                    journal.rows.lastOrNull { it["candidate"]==candidate && it["stage"]==stage }
                }
                val finished=setOf("complete","no_text","no_regions")
                val engineFailed=engineRows.any { it==null || it["status"] !in finished+setOf("timed_out","truncated") }
                val incomplete=engineRows.any { it==null || it["status"] !in finished }
                for(row in journal.rows.filter { it["stage"]==mode.summaryStage }) {
                    val fields=JSONObject();PhoneCorpusMath.fields.forEach { f -> fields.put(f,if(row["${f}_state"]=="RED") "" else row[f].orEmpty()) }
                    val name=mode.pipelineId
                    pipelines.put(JSONObject().put("pipeline",name).put("status",if(engineFailed || row["status"] !in setOf("suggestions","partial_suggestions","no_fields","complete")) "failed" else "complete").put("fields",fields)
                        .put("error",if(engineFailed) engineRows.joinToString { "${it?.get("candidate")}: ${it?.get("status") ?: "missing"}" } else if(row["status"]=="failed") row["details"].orEmpty() else ""))
                }
                for(name in listOf(mode.pipelineId))
                    if((0 until pipelines.length()).none { pipelines.getJSONObject(it).getString("pipeline")==name })
                        pipelines.put(JSONObject().put("pipeline",name).put("status","failed").put("fields",JSONObject()))
                output.put("engine_incomplete",incomplete)
                // Raw lab trace remains a separate CSV per image; never keep 168 journals in RAM.
                batch.finishImage(0,"complete");batch.save(File(work.parentFile,"traces"))
            }
            output.put("pipelines",pipelines).put("status","complete")
        } catch(cancel:CancellationException) { throw cancel }
        catch(e:Exception) { output.put("status","failed").put("error","${e.javaClass.simpleName}: ${e.message}") }
        catch(e:LinkageError) { output.put("status","failed").put("abort_run",true).put("error","Native linkage failure: ${e.javaClass.simpleName}") }
        catch(e:OutOfMemoryError) { output.remove("trace");output.remove("tokens");output.put("status","failed").put("abort_run",true).put("error","Memory pressure; run stopped") }
        finally {
            input.delete();preview.delete();crop.delete()
            if(output.optString("error")!="Memory pressure; run stopped") {
                try {
                    val trace=diagnosticJournal?.rows.orEmpty()
                    if(diagnosticJournal!=null) output.put("trace",JSONArray(trace.map { JSONObject(it) }))
                    val pipelines=output.optJSONArray("pipelines")
                    val current=pipelines?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("pipeline")==mode.pipelineId } }
                    val fields=current?.optJSONObject("fields")?.let { f -> PhoneCorpusMath.fields.associateWith { f.optString(it) } }
                    val status=if(output.optString("status")!="complete") "failed" else current?.optString("status") ?: "failed"
                    PhoneCorpusFunnelJson.write(output,PhoneCorpusPipelineDiagnostics.capture(image,1,mode.name,trace,recorded,fields,status,fresh=true))
                    Log.d("WineTracker","Pipeline funnel recorded: $image ${mode.pipelineId}")
                } catch(e:Exception) {
                    output.put("pipeline_funnel_error",e.javaClass.simpleName)
                    Log.d("WineTracker","Pipeline funnel unavailable: ${e.javaClass.simpleName}")
                } catch(_:OutOfMemoryError) { output.put("pipeline_funnel_error","memory pressure") }
            } else output.put("pipeline_funnel_error","omitted after memory pressure")
        }
        return output.put("total_elapsed_ms",SystemClock.elapsedRealtime()-started)
    }
}
