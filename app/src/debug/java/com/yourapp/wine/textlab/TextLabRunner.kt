package com.yourapp.wine.textlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.capture.CaptureDecoder
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest

/** One prepared crop, one ML Kit reference, then independent serial candidate budgets. */
internal object TextLabRunner {
    fun preview(input:File,output:File):TextPhoto {
        BitmapWorkspace().use { workspace ->
            val decoded=CaptureDecoder().decode(input,workspace)
            output.outputStream().use { check(decoded.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
            return TextPhoto(input,output,decoded.bitmap.width,decoded.bitmap.height,decoded.origW,decoded.origH)
        }
    }
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(32768)
            while(true) { val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    suspend fun recognize(context:Context,photo:TextPhoto,selection:TextCrop,enlarge:Boolean,output:File,journal:TextLabJournal,checkpoint:()->Unit={}) {
        require(journal.selected.isNotEmpty())
        val serviceStart=SystemClock.elapsedRealtime()
        val scanner=WineScannerServices.get(context)
        journal.metadata["services_setup_ms"]=(SystemClock.elapsedRealtime()-serviceStart).toString()
        val lockStart=SystemClock.elapsedRealtime()
        scanner.withBenchmarkEngines { engines,_ ->
            journal.metadata["scan_lock_wait_ms"]=(SystemClock.elapsedRealtime()-lockStart).toString()
            BitmapWorkspace().use { workspace ->
                val started=SystemClock.elapsedRealtime()
                val prepared=withTimeoutOrNull(8000) {
                    val decoded=CaptureDecoder().decode(photo.input,workspace)
                    val source=decoded.bitmap
                    val box=requireNotNull(selection.pixels(source.width,source.height)) { "Draw a larger crop" }
                    val (width,height)=box.outputSize(enlarge)
                    val crop=workspace.create(width,height)
                    Canvas(crop).drawBitmap(source,Rect(box.x,box.y,box.x+box.width,box.y+box.height),
                        RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
                    val mapping=box.toOriginal(source.width,source.height,decoded.origW,decoded.origH,width,height)
                    workspace.release(source)
                    output.outputStream().use { check(crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                    journal.metadata.putAll(mapOf("input_sha256" to hash(photo.input),"crop_sha256" to hash(output),
                        "original_width" to decoded.origW.toString(),"original_height" to decoded.origH.toString(),
                        "working_width" to photo.width.toString(),"working_height" to photo.height.toString(),
                        "crop_x" to box.x.toString(),"crop_y" to box.y.toString(),"crop_width" to box.width.toString(),"crop_height" to box.height.toString(),
                        "input_width" to width.toString(),"input_height" to height.toString(),"enlarge" to enlarge.toString(),
                        "base_to_original" to mapping.values.joinToString(" "),"original_to_base" to mapping.inverse().values.joinToString(" "),
                        "preparation_ms" to (SystemClock.elapsedRealtime()-started).toString(),
                        "device" to android.os.Build.MODEL,"android_api" to android.os.Build.VERSION.SDK_INT.toString()))
                    currentCoroutineContext().ensureActive()
                    crop to mapping
                }
                if(prepared==null) { journal.add("shared","preparation","timed_out");error("Shared crop preparation timed out") }
                val (crop,mapping)=prepared
                // ML Kit is a shared reference, not rerun or charged against any Paddle candidate.
                val mlStarted=SystemClock.elapsedRealtime()
                var mlStatus="failed"
                try {
                    val deadline=ScanDeadline(mlStarted+TextLabBudgets.OCR_MS)
                    val result=deadline.run {
                        val engine=engines.firstOrNull { it.id=="mlkit" && it.available } ?: error("ML Kit unavailable")
                        engine.recognize(crop,"manual-text-crop",deadline)
                    }
                    mlStatus=if(result==null) "timed_out" else if(result.isEmpty()) "no_text" else "complete"
                    result?.forEachIndexed { index,token ->
                        val original=token.polygon.map(mapping::map)
                        journal.add("mlkit_reference","recognition",values=mapOf("region_id" to (index+1).toString(),"raw_text" to token.text,
                            "mlkit_confidence" to token.score.toString(),"confidence_estimated" to token.scoreIsEstimated.toString(),
                            "base_polygon" to token.polygon.joinToString(";") { "${it.x} ${it.y}" },
                            "original_polygon" to original.joinToString(";") { "${it.x} ${it.y}" },
                            "base_box_normalized" to PaddleDetectionGeometry.normalizedBox(token.polygon,crop.width,crop.height),
                            "original_box_normalized" to PaddleDetectionGeometry.normalizedBox(original,photo.originalW,photo.originalH)))
                    }
                } catch(cancel:CancellationException) { mlStatus="cancelled";throw cancel }
                catch(error:Exception) { journal.add("mlkit_reference","error","failed",details=error.message.orEmpty()) }
                catch(error:LinkageError) { mlStatus="unavailable";journal.add("mlkit_reference","error","unavailable",details=error.message.orEmpty()) }
                finally { journal.add("mlkit_reference","summary",mlStatus,mapOf("elapsed_ms" to (SystemClock.elapsedRealtime()-mlStarted).toString())) }
                val gate=WineScannerServices.nativeGate
                for(spec in PaddleCandidates.selected(journal.selected)) {
                    currentCoroutineContext().ensureActive()
                    journal.add(spec.id,"candidate_start","running",TextLabMemory.sample())
                    checkpoint()
                    val caseStart=SystemClock.elapsedRealtime()
                    val deadline=ScanDeadline(caseStart+TextLabBudgets.OCR_MS)
                    var status="failed"
                    var outcome:PaddleLabelPipeline.Outcome?=null
                    try {
                        val done=deadline.run {
                            if(journal.detectLines) {
                                outcome=PaddleLabelPipeline.recognize(context,crop,workspace,deadline,gate,mapping,
                                    photo.originalW,photo.originalH,spec,journal)
                            } else {
                                var read=0
                                val report=PaddleTextRecognizer.recognize(context,crop,workspace,deadline,gate,spec=spec) { values ->
                                    if(!values["raw_text"].isNullOrEmpty()) read=1
                                    journal.add(spec.id,"recognition",if(read==0) "no_text" else "complete",values+("region_id" to "1"))
                                }
                                journal.add(spec.id,"line_report",details=report)
                                outcome=PaddleLabelPipeline.Outcome(if(read==0) "no_text" else "complete",1,1,read,0)
                            }
                            true
                        }
                        status=if(done==null) "timed_out" else requireNotNull(outcome).status
                    } catch(expired:CandidateBudgetExpired) { status="timed_out" }
                    catch(cancel:CancellationException) { status="cancelled";throw cancel }
                    catch(error:Exception) { journal.add(spec.id,"error","failed",details=error.message.orEmpty().take(2000)) }
                    catch(error:LinkageError) { journal.add(spec.id,"error","unavailable",details=error.message.orEmpty().take(2000));status="unavailable" }
                    catch(error:OutOfMemoryError) { status="memory_pressure";throw error }
                    finally {
                        val workMs=SystemClock.elapsedRealtime()-caseStart
                        val cleanupStart=SystemClock.elapsedRealtime()
                        try {
                            withContext(NonCancellable) {
                                try { PaddleTextDetector.release(gate) } finally { PaddleTextRecognizer.release(gate) }
                            }
                        } catch(error:Exception) {
                            status="cleanup_failed"
                            journal.add(spec.id,"error",status,details=error.message.orEmpty())
                            throw error
                        } finally {
                            journal.add(spec.id,"memory_after_cleanup",values=TextLabMemory.sample())
                            journal.add(spec.id,"candidate_summary",status,mapOf("elapsed_ms" to (SystemClock.elapsedRealtime()-caseStart).toString(),
                                "work_ms" to workMs.toString(),"cleanup_ms" to (SystemClock.elapsedRealtime()-cleanupStart).toString(),
                                "bitmap_peak_count" to workspace.peakCount.toString(),
                                "regions" to (outcome?.regions?.toString() ?: ""),"lines_attempted" to (outcome?.attempted?.toString() ?: ""),
                                "lines_read" to (outcome?.read?.toString() ?: ""),"lines_failed" to (outcome?.failed?.toString() ?: "")))
                            checkpoint()
                        }
                    }
                }
                IndraLabCase.run(context,crop,mapping,photo,gate,journal,checkpoint)
                IndraLabCase.run(context,crop,mapping,photo,gate,journal,checkpoint,enhanced=true)
                IndraMultiPass.run(context,crop,mapping,photo,gate,journal,checkpoint)
            }
        }
    }
}
