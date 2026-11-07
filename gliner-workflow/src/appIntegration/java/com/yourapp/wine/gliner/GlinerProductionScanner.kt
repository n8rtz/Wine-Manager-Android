package com.yourapp.wine.gliner

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.scanner.LabelScanProgressStep
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.diagnostics.ScanDiagnostics
import com.yourapp.wine.diagnostics.ScanEngineStatus
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.diagnostics.ScanReadSample
import com.yourapp.wine.diagnostics.ScanStage
import com.yourapp.wine.diagnostics.ScanStageTiming
import com.yourapp.wine.textlab.TextLabWineExtraction
import com.yourapp.wine.textlab.evidence.LabPostOcrModules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** One production photo, through exactly the same OCR and v10 post-processing as the GLiNER lab. */
internal object GlinerProductionScanner {
    sealed interface Attempt {
        data class Success(val report:ScanReport):Attempt
        data class Unavailable(val reason:String):Attempt
    }

    private const val TAG = "WineTracker"

    /** One logcat line per failed production scan; the success line is logged with its fields. */
    private fun unavailable(reason:String,started:Long,source:ScanInputSource):Attempt {
        Log.i(TAG,"WineScan FAIL build=${BuildConfig.BUILD_COMMIT} branch=${BuildConfig.BUILD_BRANCH} " +
            "source=$source elapsedMs=${SystemClock.elapsedRealtime()-started} reason=$reason")
        return Attempt.Unavailable(reason)
    }

    private fun oneLine(value:String):String =
        value.replace('\n',' ').replace('\r',' ').replace('\t',' ').take(48)

    suspend fun scan(context:Context,photo:File,started:Long,source:ScanInputSource,
        deadlineAtMs:Long,budgetMs:Long,onStage:(LabelScanProgressStep)->Unit={}):Attempt = withContext(Dispatchers.Default) {
        var model:GlinerOnnxModel?=null
        var warm:TextLabWineExtraction.WarmSession?=null
        try {
            onStage(LabelScanProgressStep.PREPARE_READER)
            val scanContext=currentCoroutineContext()
            val status=GlinerBundle.status(context) { scanContext.ensureActive() }
            if(!status.ready || !status.int4 || status.config==null || status.files.any { !it.pinned || !it.matchesPin }) {
                val why=when {
                    !status.ready -> "model bundle missing or invalid"
                    !status.int4 -> "model is not INT4"
                    status.config==null -> "model config missing"
                    else -> "model bundle is not pinned"
                }
                return@withContext unavailable(why,started,source)
            }
            if(SystemClock.elapsedRealtime()>=deadlineAtMs) return@withContext unavailable("model setup exceeded scan budget",started,source)
            onStage(LabelScanProgressStep.LOAD_READER)
            val laneConfig=GlinerLaneConfig()
            val opened=GlinerOnnxModel.open(context,status,laneConfig)
            model=opened
            val readyMs=SystemClock.elapsedRealtime()
            val session=TextLabWineExtraction.WarmSession(context)
            warm=session
            val result=GlinerLabRunner.runProduction(context,photo,opened,session,deadlineAtMs,onStage)
            if(result.status!="complete") return@withContext unavailable("GLiNER workflow: ${result.status} ${oneLine(result.message)}",started,source)
            if(result.rawReadings.isEmpty()) return@withContext unavailable("no OCR readings",started,source)
            if(SystemClock.elapsedRealtime()>=deadlineAtMs) return@withContext unavailable("scan budget reached",started,source)
            val elapsed=SystemClock.elapsedRealtime()-started
            val tokens=result.rawReadings
            val diagnostics=ScanDiagnostics(source=source,pipeline="gliner",budgetMs=budgetMs,inputBytes=photo.length(),
                workW=result.workingWidth,workH=result.workingHeight,kbImplementation="Lab v7 expanded + ${GlinerFieldDecisionPolicy.VERSION}",
                quadSource="full_photo_gliner",lastStage=ScanStage.FILL,
                stages=listOf(ScanStageTiming(ScanStage.INITIALIZE,(readyMs-started).coerceAtLeast(0)),
                    ScanStageTiming(ScanStage.OCR,result.ocrMs),ScanStageTiming(ScanStage.GLINER,result.glinerMs),
                    ScanStageTiming(ScanStage.FILL,result.postMs)),
                engines=result.engineReadings.map { (id,count) -> ScanEngineStatus(id,count>0,
                    if(count>0) "$count readings" else "No readings; engine may have failed or found no text") } +
                    ScanEngineStatus("gliner-small-int4",true,opened.signature),
                reads=tokens.take(80).map { ScanReadSample(it.textRaw,it.score,it.scoreIsEstimated,it.engineId,it.variantId,"FULL_PHOTO",true) },
                totalReadings=tokens.size,mappingReceived=tokens.size)
            val fill=GlinerProductionAdapter.fill(result.fields,result.fieldStates,result.fieldDecisions).let { base ->
                // A missing OCR reading is not evidence of agreement. Keep uncertain values
                // reviewable, without exposing the implementation as a separate scan experience.
                if(result.engineReadings.values.any { it==0 }) base.copy(fields=base.fields.mapValues { (_,candidate) ->
                    if(candidate.state==com.yourapp.wine.fill.UiState.GREEN)
                        candidate.copy(state=com.yourapp.wine.fill.UiState.YELLOW,reason="Check against the label")
                    else candidate
                }) else base
            }
            val modules=LabPostOcrModules.none.withIntegrated()
            val engines=result.engineReadings.entries.joinToString(",") { "${it.key}=${it.value}" }
            val lanes=result.laneHints.joinToString(";") { "${it.lane.key}=${oneLine(it.text)}@${it.confidence}" }
            val probes=result.probes.joinToString(";") { oneLine(it) }
            val decided=listOf("producer","wine_name","country","region","subregion","vintage").joinToString(";") { key ->
                "$key=${oneLine(result.fields[key].orEmpty())}[${result.fieldStates[key] ?: "?"}]"
            }
            Log.i(TAG,"WineScan OK build=${BuildConfig.BUILD_COMMIT} branch=${BuildConfig.BUILD_BRANCH} " +
                "policy=${GlinerFieldDecisionPolicy.VERSION} lane=${laneConfig.signatureKey()} modules=${modules.signatureKey()} " +
                "source=$source inputBytes=${photo.length()} engines={$engines} " +
                "ocrMs=${result.ocrMs} glinerMs=${result.glinerMs} postMs=${result.postMs} elapsedMs=$elapsed " +
                "lanes={$lanes} probes={$probes} fields={$decided}")
            Attempt.Success(ScanReport(fill,
                tokens,result.originalWidth,result.originalHeight,result.workingWidth,result.workingHeight,null,null,
                emptyList(),elapsed,false,result.peakWorkingBitmaps,0,diagnostics))
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            Log.w(TAG,"WineScan exception",error)
            unavailable("GLiNER initialization or scan failed: ${error.javaClass.simpleName}",started,source)
        } catch(error:LinkageError) {
            Log.w(TAG,"WineScan exception",error)
            unavailable("native model unavailable: ${error.javaClass.simpleName}",started,source)
        } finally {
            runCatching { warm?.close() }
            runCatching { model?.close() }
        }
    }
}
