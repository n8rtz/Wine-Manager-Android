package com.yourapp.wine.gliner

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.capture.CaptureDecoder
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.textlab.PaddleCandidates
import com.yourapp.wine.textlab.PaddleTextDetector
import com.yourapp.wine.textlab.PaddleTextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stage 1 of the new pipeline: PP-OCRv6 Medium detection + recognition, reusing the exact pinned
 * models and preprocessing the current Lab v7 production engine uses.
 *
 * The output is deliberately minimal: line text, geometry and the CTC posterior, in detector order.
 * No ML Kit reference pass, no spatial phrases and no post-OCR modules run here — stage 3 owns
 * those, so the only structural difference from Lab v7 is what happens between OCR and
 * post-processing.
 */
internal object GlinerOcr {
    data class Outcome(val lines:List<GlinerOcrLine>, val elapsedMs:Long, val description:String)

    suspend fun recognize(context:Context,file:File,deadlineAtMs:Long):Outcome = withContext(Dispatchers.Default) {
        val started=SystemClock.elapsedRealtime()
        val spec=PaddleCandidates.all.first()
        val gate=WineScannerServices.nativeGate
        val scanLock=WineScannerServices.get(context)
        scanLock.withBenchmarkEngines { _, _ ->
            BitmapWorkspace().use { workspace ->
                val decoded=CaptureDecoder().decode(file,workspace)
                val bitmap=decoded.bitmap
                val lines=mutableListOf<GlinerOcrLine>()
                try {
                    val detection=try {
                        PaddleTextDetector.detect(context,bitmap,workspace,gate,spec)
                    } finally {
                        withContext(NonCancellable) { PaddleTextDetector.release(gate) }
                    }
                    for(region in detection.regions.lines) {
                        if(SystemClock.elapsedRealtime()>=deadlineAtMs) break
                        val box=normalizedBox(region.quadBase,bitmap.width,bitmap.height)
                        var text=""
                        var posterior=0f
                        try {
                            PaddleTextRecognizer.recognize(context,bitmap,workspace,
                                com.yourapp.wine.ocr.ScanDeadline(deadlineAtMs),gate,region.quadBase,spec) { values ->
                                val raw=values["raw_text"].orEmpty()
                                if(raw.isNotBlank()) {
                                    text=raw
                                    posterior=values["ctc_posterior"]?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f } ?: 0f
                                }
                            }
                        } catch(cancel:kotlinx.coroutines.CancellationException) { throw cancel }
                        catch(expired:com.yourapp.wine.textlab.CandidateBudgetExpired) { break }
                        catch(_:Exception) { text="" }
                        if(text.isNotBlank()) lines += GlinerOcrLine(text,box,region.quadBase,posterior)
                    }
                    val elapsed=SystemClock.elapsedRealtime()-started
                    Outcome(lines,elapsed,"${spec.detector.name} + ${spec.recognizer.name} · "+
                        "${detection.regions.lines.size} regions · ${lines.size} readable lines"+
                        (if(detection.regions.truncated) " · detector truncated" else ""))
                } finally {
                    withContext(NonCancellable) {
                        try { PaddleTextRecognizer.release(gate) } finally { PaddleTextDetector.release(gate) }
                    }
                }
            }
        }
    }

    private fun normalizedBox(quad:List<android.graphics.PointF>,width:Int,height:Int):RectF {
        if(width<=0 || height<=0 || quad.isEmpty()) return RectF()
        val left=(quad.minOf { it.x }/width).coerceIn(0f,1f)
        val top=(quad.minOf { it.y }/height).coerceIn(0f,1f)
        val right=(quad.maxOf { it.x }/width).coerceIn(0f,1f)
        val bottom=(quad.maxOf { it.y }/height).coerceIn(0f,1f)
        return RectF(left,top,maxOf(left,right),maxOf(top,bottom))
    }

    /** Whether the two pinned PP-OCRv6 files are actually present in the debug/generated assets. */
    fun modelsPresent(context:Context):Boolean = runCatching {
        val names=context.assets.list("text-lab-paddle-v1").orEmpty().toSet()
        PaddleCandidates.all.first().let { spec ->
            listOf(spec.detector.asset,spec.recognizer.asset,spec.dictionary.asset).all { it in names }
        }
    }.getOrDefault(false)
}
