package com.yourapp.wine.textlab

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.geom.Matrix3
import com.yourapp.wine.ocr.NativeEngineGate
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Debug only. Every crop comes from the independent detector, never ML Kit's boxes or expected text. */
internal object PaddleLabelPipeline {
    suspend fun recognize(context:Context,base:Bitmap,workspace:BitmapWorkspace,deadline:ScanDeadline,
        gate:NativeEngineGate,baseToOriginal:Matrix3,originalWidth:Int,originalHeight:Int,append:(String)->Unit) {
        val started=SystemClock.elapsedRealtime()
        append("Starting independent full-label detection; base = user-selected crop (not automatic label localization).")
        val detection=PaddleTextDetector.detect(context,base,workspace,gate)
        append(detection.description)
        append("Base crop→original matrix: ${baseToOriginal.values.joinToString(" ")}\n"+
            "Original→base crop matrix: ${baseToOriginal.inverse().values.joinToString(" ")}")
        if(detection.regions.lines.isEmpty()) {
            append("No text regions detected. Recognition was not run; no whole-label single-line fallback.")
            return
        }
        // Publish ALL detected boxes before recognition, so a timeout still distinguishes detection from reading.
        detection.regions.lines.forEachIndexed { index,region ->
            val original=region.quadBase.map(baseToOriginal::map)
            append("Region ${index+1}: detector box mean ${String.format(Locale.ROOT,"%.3f",region.detectionScore)}\n"+
                "Base-crop polygon: ${region.quadBase.joinToString { "(${it.x}, ${it.y})" }}\n"+
                "Base-crop normalized box (left top right bottom): ${PaddleDetectionGeometry.normalizedBox(region.quadBase,base.width,base.height)}\n"+
                "Original polygon: ${original.joinToString { "(${it.x}, ${it.y})" }}\n"+
                "Original normalized box (left top right bottom): ${PaddleDetectionGeometry.normalizedBox(original,originalWidth,originalHeight)}")
        }
        var completed=0
        for((index,region) in detection.regions.lines.withIndex()) {
            currentCoroutineContext().ensureActive()
            if(deadline.expired) { append("Budget reached before region ${index+1}; completed $completed/${detection.regions.lines.size} line attempts.");return }
            append("Reading region ${index+1}…")
            val report=try {
                PaddleTextRecognizer.recognize(context,base,workspace,deadline,gate,region.quadBase)
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) { "Region ${index+1} failed: ${error.message?.take(1200)}. No reading assigned." }
            append(report)
            completed++
        }
        append("Paddle pipeline finished: $completed/${detection.regions.lines.size} line attempts; ${SystemClock.elapsedRealtime()-started} ms total. No wine-field assignment.")
    }
}
