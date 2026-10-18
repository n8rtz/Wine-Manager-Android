package com.yourapp.wine.benchmark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.yourapp.wine.capture.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.geom.*
import com.yourapp.wine.ocr.*
import com.yourapp.wine.preprocess.Preprocess
import kotlinx.coroutines.*
import java.io.File
import java.time.Year
import kotlin.random.Random

data class BenchmarkCaseResult(val recipe:Treatment,val report:ScanReport,val status:String,
    val preparationMs:Long,val previews:List<File>,val score:BenchmarkScoring.Score?=null)

/** No ground truth accepted here: fixed crop, raw OCR and production field extraction only. */
class BenchmarkRunner(private val scanner:OfflineWineScanner) {
    suspend fun run(photo:File,directory:File,seed:Int,onBase:suspend (ScanReport,File)->Unit,
        onProgress:(String)->Unit,onResult:suspend (BenchmarkCaseResult)->Unit,previewBudgetBytes:Long=64L*1024*1024) = withContext(Dispatchers.Default) {
        val baseFile=File(directory,"base.png")
        onProgress("Locating label and warming engines (not a scored case)…")
        val setup=scanner.captureBenchmarkBase(photo,baseFile)
        require(baseFile.isFile && setup.homography!=null) {
            "Could not prepare a mapped image. Choose a clearer label photo."
        }
        if(baseFile.length()>previewBudgetBytes) {
            baseFile.delete()
            error("Base image exceeds per-image preview budget; try a smaller batch")
        }
        // A declined crop is still a valid mapped, bounded full-frame base, not a setup failure.
        val inputKind = if (setup.quadOrig != null) OcrInputKind.RECTIFIED_LABEL else OcrInputKind.FULL_FRAME_FALLBACK
        onBase(setup,baseFile)
        var previewBytes=baseFile.length()
        val ordered=listOf(BenchmarkPlan.cases.first())+BenchmarkPlan.cases.drop(1).shuffled(Random(seed))
        scanner.withBenchmarkEngines { engines,fills ->
            ordered.forEachIndexed { index,recipe ->
                currentCoroutineContext().ensureActive()
                onProgress("${index+1}/30: ${recipe.id}")
                val start=SystemClock.elapsedRealtime()
                val deadline=ScanDeadline(start+ScanDeadline.BUDGET_MS-300)
                val workspace=BitmapWorkspace()
                val trace=com.yourapp.wine.diagnostics.ScanTraceRecorder(SystemClock::elapsedRealtime)
                trace.enter(com.yourapp.wine.diagnostics.ScanStage.OCR)
                val records=mutableListOf<TokenRecord>();val timings=mutableListOf<EngineTiming>()
                val previews=mutableListOf<File>()
                var prepMs=0L;var status="complete"
                val folder=File(directory,recipe.id).apply { mkdirs() }
                val mapper=CoordMapper(Matrix3(requireNotNull(setup.homography)),setup.origW,setup.origH,setup.rectW,setup.rectH)
                try {
                    val finished=deadline.run {
                        val prepareStart=SystemClock.elapsedRealtime()
                        val base=workspace.own(requireNotNull(BitmapFactory.decodeFile(baseFile.absolutePath)))
                        checkpoint(deadline)
                        val variants=if(recipe.id=="baseline") Preprocess(enableV2=true).variants(base,workspace) else {
                            val prepared=if(recipe.id=="raw") PreparedTreatment(base,Matrix3.scale(1.0,1.0))
                                else BenchmarkPreprocess.prepare(base,recipe,workspace,deadline)
                            listOf(OcrVariant(recipe.id,{ prepared.bitmap },{},
                                toLabel={ token,_,_ -> token.copy(polygon=token.polygon.map(prepared.variantToBase::map)) },
                                description={ _,_ -> recipe.description }))
                        }
                        prepMs+=SystemClock.elapsedRealtime()-prepareStart
                        val withPreviews=variants.map { variant -> variant.copy(prepare={
                            if(deadline.expired) throw BenchmarkBudgetExceeded()
                            val before=SystemClock.elapsedRealtime()
                            val bitmap=variant.prepare()
                            val image=File(folder,"${variant.id}.png")
                            if(previewBytes < previewBudgetBytes) {
                                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                                if(previewBytes+image.length() <= previewBudgetBytes) { previews+=image;previewBytes+=image.length() }
                                else image.delete()
                            }
                            prepMs+=SystemClock.elapsedRealtime()-before
                            bitmap
                        }) }
                        val batch=OcrEnsemble(engines,onTiming={timings+=it}).run(withPreviews,deadline,onTokens={ raw ->
                            raw.forEach { token ->
                                val mapped=mapper.toOriginal(token)
                                trace.reading(token,inputKind,mapped!=null)
                                if(mapped!=null) records+=mapped
                            }
                        },inputKind=inputKind)
                        if(batch.deadlineCancelled) status="deadline"
                        Unit
                    }
                    if(finished==null || deadline.expired) status="deadline"
                    if(timings.isEmpty() && status=="complete") status="no engine attempt"
                    if(timings.any { it.detail?.contains("failed:")==true }) status="engine failure"
                } catch(cancel:CancellationException) { throw cancel }
                catch(_:BenchmarkBudgetExceeded) { status="deadline" }
                catch(error:Exception) { trace.failed(error);status="failure: ${error.javaClass.simpleName}" }
                finally { workspace.close() }
                val fill=CellarFieldPresentation.apply(fills.fill(records,Year.now().value,
                    ConfidenceContext(labelFound=setup.quadOrig!=null,deadlineReached=status=="deadline",weakQuality=status!="complete")))
                val elapsed=SystemClock.elapsedRealtime()-start
                if(elapsed>=ScanDeadline.BUDGET_MS) status="deadline"
                val safeFill=if(status=="complete") fill else fill.copy(fields=fill.fields.mapValues { (_,field) ->
                    if(field.state==UiState.GREEN) field.copy(state=UiState.YELLOW,reason=field.reason+"; incomplete benchmark case") else field
                })
                val report=setup.copy(fill=safeFill,tokens=records.toList(),timings=timings.toList(),elapsedMs=elapsed,
                    deadlineCancelled=status=="deadline",peakWorkingBitmaps=workspace.peakCount,workingBitmapsAfter=workspace.aliveCount,
                    diagnostics=trace.finish(setup.diagnostics.copy(recoveredProbeReadings=0,
                        deadlineStage=if(status=="deadline") com.yourapp.wine.diagnostics.ScanStage.OCR else null)))
                onResult(BenchmarkCaseResult(recipe,report,status,prepMs,previews.toList()))
                // Yield between cases. No overlapping native OCR, regardless of timeout/late Task completion.
                delay(250)
            }
        }
    }
}
