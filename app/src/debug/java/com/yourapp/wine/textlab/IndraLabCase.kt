package com.yourapp.wine.textlab

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.yourapp.wine.geom.Matrix3
import com.yourapp.wine.ocr.NativeEngineGate
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.*

internal object IndraLabCase {
    suspend fun run(context:Context,crop:Bitmap,mapping:Matrix3,photo:TextPhoto,gate:NativeEngineGate,
        journal:TextLabJournal,checkpoint:()->Unit,enhanced:Boolean=false) {
        val candidate=if(enhanced) IndraSpec.ENHANCED_ID else IndraSpec.ID
        if(candidate !in journal.selected) return
        currentCoroutineContext().ensureActive()
        journal.add(candidate,"candidate_start","running",TextLabMemory.sample());checkpoint()
        val start=SystemClock.elapsedRealtime();val deadline=ScanDeadline(start+TextLabBudgets.OCR_MS)
        var status="failed";var outcome:PaddleLabelPipeline.Outcome?=null
        try {
            val done=deadline.run {
                outcome=IndraPipeline.recognize(context,crop,deadline,gate,mapping,photo.originalW,photo.originalH,journal,enhanced)
                true
            }
            status=if(done==null) "timed_out" else requireNotNull(outcome).status
        } catch(_:CandidateBudgetExpired) { status="timed_out" }
        catch(cancel:CancellationException) { status="cancelled";throw cancel }
        catch(error:OutOfMemoryError) { status="memory_pressure";throw error }
        catch(error:LinkageError) { status="unavailable";journal.add(candidate,"error",status,details=error.message.orEmpty().take(2000)) }
        catch(error:Exception) { journal.add(candidate,"error",status,details=error.message.orEmpty().take(2000)) }
        finally {
            // IndraPipeline's sessions, tensors, Mats and gate have all unwound before this checkpoint.
            journal.add(candidate,"memory_after_cleanup",values=TextLabMemory.sample())
            journal.add(candidate,"candidate_summary",status,mapOf("elapsed_ms" to "${SystemClock.elapsedRealtime()-start}",
                "regions" to (outcome?.regions?.toString() ?: ""),"lines_attempted" to (outcome?.attempted?.toString() ?: ""),
                "lines_read" to (outcome?.read?.toString() ?: ""),"lines_failed" to (outcome?.failed?.toString() ?: "")))
            checkpoint()
        }
    }
}
