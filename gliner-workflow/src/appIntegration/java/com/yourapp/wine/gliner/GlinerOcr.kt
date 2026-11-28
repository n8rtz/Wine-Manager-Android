package com.yourapp.wine.gliner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.capture.CaptureDecoder
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.ocr.MlKitOcrEngine
import com.yourapp.wine.ocr.ScanDeadline
import com.yourapp.wine.textlab.CandidateBudgetExpired
import com.n8rtz.winetracker.scanner.LabelScanProgressStep
import com.yourapp.wine.textlab.PaddleCandidates
import com.yourapp.wine.textlab.PaddleTextDetector
import com.yourapp.wine.textlab.PaddleTextRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stage 1: both OCR engines read the same decoded label, then their lines are fused for GLiNER.
 *
 * This is the ensemble the production scanner already uses — bundled ML Kit Latin plus PP-OCRv6
 * Medium det/line-rec — not a single-engine shortcut. The two reads are started together on the
 * same bitmap and awaited as a pair; `NativeEngineGate` (one native lease app-wide, the same gate
 * the production ensemble takes) serialises the actual native calls, so a phone never holds two
 * OCR sessions at once, exactly like the existing scanner.
 *
 * No ML Kit reference pass, no auto-zoom and no spatial phrases run here: stage 3 owns all of the
 * interpretation, so the only structural difference from Lab v7 is what happens between OCR and
 * post-processing.
 */
internal object GlinerOcr {
    /** Bundled Latin recognizer id, identical to [MlKitOcrEngine.id]. */
    const val MLKIT_ID=GlinerEngines.MLKIT

    /** Pinned PP-OCRv6 Medium det+rec id, identical to the production Paddle engine id. */
    const val PADDLE_ID=GlinerEngines.PADDLE

    data class Outcome(
        val lines:List<GlinerOcrLine>,
        /** Original independent engine readings; fusion is only for model input. */
        val readings:List<GlinerFusion.Reading>,
        val elapsedMs:Long,
        val description:String,
        val engineReadings:Map<String,Int>,
        val originalWidth:Int,
        val originalHeight:Int,
        val workingWidth:Int,
        val workingHeight:Int,
        val peakWorkingBitmaps:Int,
        /** True when both engines read a conservative crop around the detected text instead of the full photo. */
        val croppedToTextRegion:Boolean=false,
    )

    /** App-scoped client: the recognizer is bundled, so constructing one per photo is waste. */
    private val mlKit by lazy { MlKitOcrEngine(WineScannerServices.nativeGate) }

    /** Loads the bundled ML Kit model on a dummy image so the first real read does not pay it. */
    suspend fun warmMlKit() {
        val dummy=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        try { mlKit.recognize(dummy,"warm",ScanDeadline(SystemClock.elapsedRealtime()+30_000)) }
        catch(cancel:CancellationException) { throw cancel }
        catch(_:Exception) { }
    }

    suspend fun recognize(context:Context,file:File,deadlineAtMs:Long,
        onStage:(LabelScanProgressStep)->Unit={}):Outcome =
        withContext(Dispatchers.Default) {
            val started=SystemClock.elapsedRealtime()
            val spec=PaddleCandidates.all.first()
            val scanLock=WineScannerServices.get(context)
            scanLock.withBenchmarkEngines { _, _ ->
                BitmapWorkspace().use { workspace ->
                    onStage(LabelScanProgressStep.PREPARE_PHOTO)
                    val decoded=CaptureDecoder().decode(file,workspace)
                    val bitmap=decoded.bitmap
                    val deadline=ScanDeadline(deadlineAtMs)
                    onStage(LabelScanProgressStep.READ_TEXT)
                    // Crop before OCR only when a conservative crop is
                    // clearly safe (see textRegionCrop): the detector
                    // (which runs anyway) localizes the text, the crop
                    // keeps half a text-union of slack on every side —
                    // a line the detector missed but ML Kit can read is
                    // usually a neighbour of detected lines, so it stays
                    // inside — and cropping only happens when even the
                    // slackened crop excludes at least a fifth of the
                    // frame. Anything closer to the full photo reads the
                    // full photo, with readings identical to no crop.
                    // (A tight 10%-margin crop used to sit here; the lab
                    // A/B showed it amputating ML-Kit-only lines.)
                    val detection=detectRegions(context,bitmap,workspace,spec)
                    val crop=textRegionCrop(detection,bitmap.width,bitmap.height)
                    val photoWidth=bitmap.width
                    val photoHeight=bitmap.height
                    val ocrBitmap=if(crop!=null) workspace.own(Bitmap.createBitmap(bitmap,crop[0],crop[1],crop[2],crop[3])) else bitmap
                    // The crop holds every pixel either engine still
                    // needs, so release the full-size bitmap:
                    // BitmapWorkspace allows at most two owned bitmaps
                    // and the line recognizer's warp (workspace.create)
                    // needs the second slot. (Leaving it owned once
                    // silently zeroed PP-OCRv6 on cropped photos.)
                    if(crop!=null) workspace.release(bitmap)
                    val regions=detection.regions.lines.map { region ->
                        if(crop==null) region else region.copy(quadBase=region.quadBase.map { point -> PointF(point.x-crop[0],point.y-crop[1]) })
                    }
                    val (mlKitReadings,paddleResult)=coroutineScope {
                        val mlkit=async(Dispatchers.Default) { readMlKit(ocrBitmap,deadline) }
                        val paddle=async(Dispatchers.Default) { readPaddle(context,ocrBitmap,workspace,spec,deadlineAtMs,regions) }
                        val first=mlkit.await()
                        // The reads run together; only report the checking phase when the second
                        // read is still running, so a fast pair never flashes a stale step.
                        if(!paddle.isCompleted) onStage(LabelScanProgressStep.CHECK_READ)
                        first to paddle.await()
                    }
                    val paddleReadings=paddleResult.first
                    val paddleError=paddleResult.second
                    val fused=GlinerFusion.fuse(mlKitReadings+paddleReadings)
                    val engineReadings=linkedMapOf(MLKIT_ID to mlKitReadings.size,PADDLE_ID to paddleReadings.size)
                    val elapsed=SystemClock.elapsedRealtime()-started
                    Outcome(fused,mlKitReadings+paddleReadings,elapsed,"${spec.detector.name} + ${spec.recognizer.name} = ${paddleReadings.size} lines; "+
                        "ML Kit Latin = ${mlKitReadings.size} lines; fused = ${fused.size} lines; "+
                        (if(crop!=null) "crop ${crop[2]}x${crop[3]} @ ${crop[0]},${crop[1]} of ${photoWidth}x$photoHeight" else "full frame")+
                        (paddleError?.let { " · paddle: $it" } ?: "")+
                        (if(elapsed>deadlineAtMs-started) " · budget reached" else ""),engineReadings,
                        decoded.origW,decoded.origH,ocrBitmap.width,ocrBitmap.height,workspace.peakCount,crop!=null)
                }
            }
        }

    /** ML Kit Latin over the decoded label. A client failure leaves PP-OCRv6 as the only reading. */
    private suspend fun readMlKit(bitmap:Bitmap,deadline:ScanDeadline):List<GlinerFusion.Reading> =
        try {
            mlKit.recognize(bitmap,"V0",deadline).mapNotNull { token ->
                val box=normalizedBox(token.polygon,bitmap.width,bitmap.height)
                if(token.text.isBlank() || box.width()<=0f || box.height()<=0f ||
                    !token.score.isFinite() || token.score !in 0f..1f) null
                else GlinerFusion.Reading(MLKIT_ID,token.text,box,token.polygon,token.score,
                    variantId="V0",scoreIsEstimated=token.scoreIsEstimated)
            }
        } catch(cancel:CancellationException) { throw cancel }
        catch(_:Exception) { emptyList() }

    /** Pinned PP-OCRv6 Medium detector pass; its regions drive the line recognizer. */
    private suspend fun detectRegions(
        context:Context,
        bitmap:Bitmap,
        workspace:BitmapWorkspace,
        spec:com.yourapp.wine.textlab.PaddleCandidate,
    ):PaddleTextDetector.Detection {
        // The session stays open for the next photo/scan: releasing and
        // reopening per photo re-verified (SHA-256 over ~138 MB of model
        // files) and re-created both ONNX sessions every time (~1 s/photo),
        // which also defeats the warm-up that pre-creates them. Other
        // flows release after their own use; a closed session simply
        // reopens on the next detect.
        return PaddleTextDetector.detect(context,bitmap,workspace,WineScannerServices.nativeGate,spec)
    }

    /**
     * Conservative crop around the union of detected text regions: the
     * union grows by half its own width/height on every side (clamped to
     * the photo), so lines the detector missed beside ones it found stay
     * inside. Returns [left, top, width, height], or null when cropping
     * is pointless (no text, or even the slackened crop covers >80% of
     * the frame — near-identical to reading the full photo) or
     * suspicious (a speck of text).
     */
    private fun textRegionCrop(detection:PaddleTextDetector.Detection,width:Int,height:Int):IntArray? {
        val lines=detection.regions.lines
        if(lines.isEmpty() || width<=0 || height<=0) return null
        var left=Float.MAX_VALUE; var top=Float.MAX_VALUE; var right=0f; var bottom=0f
        lines.forEach { region -> region.quadBase.forEach { point ->
            left=minOf(left,point.x); top=minOf(top,point.y); right=maxOf(right,point.x); bottom=maxOf(bottom,point.y)
        } }
        val unionW=right-left; val unionH=bottom-top
        if(unionW<=0f || unionH<=0f) return null
        val marginX=unionW*0.5f; val marginY=unionH*0.5f
        val cropLeft=maxOf(0f,left-marginX).toInt()
        val cropTop=maxOf(0f,top-marginY).toInt()
        val cropRight=minOf(width.toFloat(),right+marginX).toInt().coerceAtLeast(cropLeft+1)
        val cropBottom=minOf(height.toFloat(),bottom+marginY).toInt().coerceAtLeast(cropTop+1)
        val cropW=cropRight-cropLeft; val cropH=cropBottom-cropTop
        val areaFraction=cropW.toDouble()*cropH/(width.toDouble()*height)
        if(areaFraction>0.80 || areaFraction<0.03) return null
        return intArrayOf(cropLeft,cropTop,cropW,cropH)
    }

    /**
     * Pinned PP-OCRv6 Medium line recognizer over pre-detected regions, CTC
     * posterior per line. Returns the readings plus the first per-region
     * failure (if any): a region failure drops that line, but a systematic
     * failure must stay visible in the outcome note instead of silently
     * reading as "0 lines".
     */
    private suspend fun readPaddle(
        context:Context,
        bitmap:Bitmap,
        workspace:BitmapWorkspace,
        spec:com.yourapp.wine.textlab.PaddleCandidate,
        deadlineAtMs:Long,
        regions:List<com.yourapp.wine.textlab.PaddleRegion>,
    ):Pair<List<GlinerFusion.Reading>,String?> {
        val gate=WineScannerServices.nativeGate
        val lines=mutableListOf<GlinerFusion.Reading>()
        var firstError:String?=null
        // Sessions intentionally stay open across photos (see detectRegions).
        for(region in regions) {
                if(SystemClock.elapsedRealtime()>=deadlineAtMs) break
                val box=normalizedBox(region.quadBase,bitmap.width,bitmap.height)
                var text=""
                var posterior=0f
                try {
                    PaddleTextRecognizer.recognize(context,bitmap,workspace,
                        ScanDeadline(deadlineAtMs),gate,region.quadBase,spec) { values ->
                        val raw=values["raw_text"].orEmpty()
                        if(raw.isNotBlank()) {
                            text=raw
                            posterior=values["ctc_posterior"]?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f } ?: 0f
                        }
                    }
                } catch(cancel:CancellationException) { throw cancel }
                catch(expired:CandidateBudgetExpired) { break }
                catch(error:Exception) {
                    if(firstError==null) firstError="${error.javaClass.simpleName}: ${error.message ?: "no detail"}"
                    text=""
                }
                if(text.isNotBlank()) lines += GlinerFusion.Reading(PADDLE_ID,text,box,region.quadBase,
                    posterior.takeIf { it>0f }?.coerceIn(0.01f,0.99f) ?: 0.5f,
                    variantId="V0",scoreIsEstimated=true)
        }
        return lines to firstError
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
