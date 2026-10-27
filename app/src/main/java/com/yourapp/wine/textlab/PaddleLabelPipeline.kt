package com.yourapp.wine.textlab

import android.content.Context
import android.graphics.Bitmap
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.geom.Matrix3
import com.yourapp.wine.ocr.NativeEngineGate
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.*

/** No ML Kit boxes, expected answers or shared detections between candidates. */
internal object PaddleLabelPipeline {
    data class Outcome(val status:String,val regions:Int,val attempted:Int,val read:Int,val failed:Int)
    suspend fun recognize(context:Context,base:Bitmap,workspace:BitmapWorkspace,deadline:ScanDeadline,
        gate:NativeEngineGate,baseToOriginal:Matrix3,originalWidth:Int,originalHeight:Int,
        spec:PaddleCandidate,journal:TextLabJournal):Outcome {
        val id=spec.id
        val detection=try {
            PaddleTextDetector.detect(context,base,workspace,gate,spec) { values -> journal.add(id,values["diagnostic_stage"] ?: "detection",values=values) }
        } finally {
            // Do not retain a Medium detector while loading a Medium recognizer.
            withContext(NonCancellable) { PaddleTextDetector.release(gate) }
        }
        journal.add(id,"detection_report",details=detection.description)
        if(detection.regions.lines.isEmpty()) return Outcome("no_regions",0,0,0,0)
        fun coordinates(region:PaddleRegion):Map<String,String> {
            val original=region.quadBase.map(baseToOriginal::map)
            return mapOf("detector_score" to region.detectionScore.toString(),
                "base_polygon" to region.quadBase.joinToString(";") { "${it.x} ${it.y}" },
                "original_polygon" to original.joinToString(";") { "${it.x} ${it.y}" },
                "base_box_normalized" to PaddleDetectionGeometry.normalizedBox(region.quadBase,base.width,base.height),
                "original_box_normalized" to PaddleDetectionGeometry.normalizedBox(original,originalWidth,originalHeight))
        }
        detection.regions.lines.forEachIndexed { index,region ->
            journal.add(id,"region",values=coordinates(region)+("region_id" to (index+1).toString()))
        }
        var attempted=0;var read=0;var failed=0
        for((index,region) in detection.regions.lines.withIndex()) {
            currentCoroutineContext().ensureActive()
            if(deadline.expired) throw CandidateBudgetExpired()
            val location=coordinates(region)+("region_id" to (index+1).toString())
            journal.add(id,"line_start","running",location)
            try {
                val report=PaddleTextRecognizer.recognize(context,base,workspace,deadline,gate,region.quadBase,spec) { values ->
                    if(!values["raw_text"].isNullOrEmpty()) read++
                    journal.add(id,"recognition",if(values["raw_text"].isNullOrEmpty()) "no_text" else "complete",location+values)
                }
                journal.add(id,"line_report",values=mapOf("region_id" to (index+1).toString()),details=report)
            } catch(cancel:CancellationException) { throw cancel }
            catch(expired:CandidateBudgetExpired) { throw expired }
            catch(error:Exception) { failed++;journal.add(id,"recognition","failed",location, error.message.orEmpty().take(1500)) }
            attempted++
        }
        return Outcome(if(failed>0) "partial_failed" else if(detection.regions.truncated) "truncated" else if(read==0) "no_text" else "complete",
            detection.regions.lines.size,attempted,read,failed)
    }
}
