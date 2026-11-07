package com.yourapp.wine.gliner

import android.content.Context
import android.os.SystemClock
import com.n8rtz.winetracker.scanner.LabelScanProgressStep
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.diagnostics.ScanDiagnostics
import com.yourapp.wine.diagnostics.ScanEngineStatus
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.diagnostics.ScanReadSample
import com.yourapp.wine.diagnostics.ScanStage
import com.yourapp.wine.diagnostics.ScanStageTiming
import com.yourapp.wine.textlab.TextLabWineExtraction
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
                return@withContext Attempt.Unavailable(why)
            }
            if(SystemClock.elapsedRealtime()>=deadlineAtMs) return@withContext Attempt.Unavailable("model setup exceeded scan budget")
            onStage(LabelScanProgressStep.LOAD_READER)
            val opened=GlinerOnnxModel.open(context,status,GlinerLaneConfig())
            model=opened
            val readyMs=SystemClock.elapsedRealtime()
            val session=TextLabWineExtraction.WarmSession(context)
            warm=session
            val result=GlinerLabRunner.runProduction(context,photo,opened,session,deadlineAtMs,onStage)
            if(result.status!="complete") return@withContext Attempt.Unavailable("GLiNER workflow: ${result.status}")
            if(result.rawReadings.isEmpty()) return@withContext Attempt.Unavailable("no OCR readings")
            if(SystemClock.elapsedRealtime()>=deadlineAtMs) return@withContext Attempt.Unavailable("scan budget reached")
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
            Attempt.Success(ScanReport(fill,
                tokens,result.originalWidth,result.originalHeight,result.workingWidth,result.workingHeight,null,null,
                emptyList(),elapsed,false,result.peakWorkingBitmaps,0,diagnostics))
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            Attempt.Unavailable("GLiNER initialization or scan failed: ${error.javaClass.simpleName}")
        } catch(error:LinkageError) {
            Attempt.Unavailable("native model unavailable: ${error.javaClass.simpleName}")
        } finally {
            runCatching { warm?.close() }
            runCatching { model?.close() }
        }
    }
}
