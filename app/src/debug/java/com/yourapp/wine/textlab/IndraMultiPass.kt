package com.yourapp.wine.textlab

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.yourapp.wine.geom.Matrix3
import com.yourapp.wine.ocr.NativeEngineGate
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.*

internal object IndraMultiPass {
    suspend fun run(context:Context,crop:Bitmap,mapping:Matrix3,photo:TextPhoto,gate:NativeEngineGate,
        journal:TextLabJournal,checkpoint:()->Unit) {
        val id=IndraSpec.MULTIPASS_ID
        if(id !in journal.selected) return
        currentCoroutineContext().ensureActive()
        val start=SystemClock.elapsedRealtime();val deadline=ScanDeadline(start+TextLabBudgets.OCR_MS)
        var finalStatus="complete";var completed=0;var issues=0
        journal.add(id,"candidate_start","running",TextLabMemory.sample());checkpoint()
        try {
            for(pass in IndraPassPlan.all) {
                currentCoroutineContext().ensureActive()
                if(deadline.expired) { finalStatus="timed_out";break }
                journal.passContext=pass.fields
                val passStart=SystemClock.elapsedRealtime();var status="failed"
                var outcome:PaddleLabelPipeline.Outcome?=null
                journal.add(id,"pass_start","running")
                try {
                    val done=deadline.run {
                        outcome=IndraPipeline.recognize(context,crop,deadline,gate,mapping,photo.originalW,photo.originalH,journal,true,pass)
                        true
                    }
                    status=if(done==null) "timed_out" else requireNotNull(outcome).status
                    if(done==null) { finalStatus="timed_out";break }
                    completed++
                    if(status !in setOf("complete","no_text","no_regions")) issues++
                } catch(_:CandidateBudgetExpired) { status="timed_out";finalStatus="timed_out";break }
                catch(cancel:CancellationException) { status="cancelled";throw cancel }
                catch(error:OutOfMemoryError) { status="memory_pressure";throw error }
                catch(error:LinkageError) { status="unavailable";finalStatus="unavailable";journal.add(id,"error",status,details=error.message.orEmpty().take(1500));break }
                catch(error:Exception) { issues++;journal.add(id,"error",status,details="${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1500)) }
                finally {
                    journal.add(id,"pass_summary",status,mapOf("elapsed_ms" to "${SystemClock.elapsedRealtime()-passStart}",
                        "regions" to (outcome?.regions?.toString() ?: ""),"lines_read" to (outcome?.read?.toString() ?: ""),
                        "lines_attempted" to (outcome?.attempted?.toString() ?: ""),"lines_failed" to (outcome?.failed?.toString() ?: ""))+TextLabMemory.sample())
                    journal.passContext=emptyMap();checkpoint()
                }
            }
            if(finalStatus=="complete" && issues>0) finalStatus="complete_with_issues"
        } catch(cancel:CancellationException) { finalStatus="cancelled";throw cancel }
        catch(error:OutOfMemoryError) { finalStatus="memory_pressure";throw error }
        catch(error:Exception) { finalStatus="failed";throw error }
        finally {
            journal.passContext=emptyMap()
            IndraPassPlan.finishPending(journal,"not_run_$finalStatus")
            journal.add(id,"candidate_summary",finalStatus,mapOf("elapsed_ms" to "${SystemClock.elapsedRealtime()-start}",
                "passes_completed" to "$completed","passes_with_issues" to "$issues","passes_planned" to "12")+TextLabMemory.sample())
            checkpoint()
        }
    }
}
