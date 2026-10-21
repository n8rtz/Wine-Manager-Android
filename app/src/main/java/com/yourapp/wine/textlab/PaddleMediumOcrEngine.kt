package com.yourapp.wine.textlab

import android.content.Context
import android.graphics.Bitmap
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.ocr.*
import kotlinx.coroutines.*

/** Production v6 Medium detector + Medium recognizer. Only base rectified-label pixels are accepted.
 * The scanner owns base; this workspace owns only one transient resize/line, never base itself.
 * Scanner serialization also protects shared sessions from Text Lab candidate changes.
 */
internal class PaddleMediumOcrEngine private constructor(
    private val context:Context, private val gate:NativeEngineGate
):OcrEngine {
    override val id="paddle-v6-medium"
    override val available=true
    override fun supportsVariant(variantId:String)=variantId=="V0"
    private val spec=PaddleCandidates.all.single { it.id=="candidate_3" }

    override suspend fun recognize(bitmap:Bitmap,variantId:String,deadline:ScanDeadline):List<RawToken> =
        recognizeStreaming(bitmap,variantId,deadline) {}

    override suspend fun recognizeStreaming(bitmap:Bitmap,variantId:String,deadline:ScanDeadline,
        emit:(List<RawToken>)->Unit):List<RawToken> = withContext(Dispatchers.Default) {
        require(supportsVariant(variantId))
        val readings=mutableListOf<RawToken>()
        BitmapWorkspace().use { scratch ->
            try {
                currentCoroutineContext().ensureActive()
                if(deadline.expired) return@withContext readings
                val detection=try { PaddleTextDetector.detect(context,bitmap,scratch,gate,spec) }
                finally { withContext(NonCancellable) { PaddleTextDetector.release(gate) } }
                for(region in detection.regions.lines) {
                    currentCoroutineContext().ensureActive()
                    if(deadline.expired) break
                    try {
                        PaddleTextRecognizer.recognize(context,bitmap,scratch,deadline,gate,region.quadBase,spec) { values ->
                            val text=values["raw_text"].orEmpty()
                            val posterior=values["ctc_posterior"]?.toFloatOrNull()
                            if(text.isNotBlank() && posterior!=null && posterior.isFinite() && posterior in 0f..1f) {
                                // CTC is uncalibrated: preserve text as editable evidence, never let its
                                // near-1 score alone establish a GREEN field or inflate independent support.
                                val token=RawToken(text,posterior,region.quadBase,id,variantId,scoreIsEstimated=true)
                                readings+=token
                                emit(listOf(token))
                            }
                        }
                    } catch(cancel:CancellationException) { throw cancel }
                    catch(_:CandidateBudgetExpired) { break }
                    // An isolated unreadable line must not discard other completed detections.
                    catch(error:Exception) { android.util.Log.w("WineScanner","Paddle line failed: ${error.javaClass.simpleName}") }
                }
            } finally {
                withContext(NonCancellable) {
                    try { PaddleTextRecognizer.release(gate) } finally { PaddleTextDetector.release(gate) }
                }
            }
        }
        readings
    }

    companion object {
        fun create(context:Context,gate:NativeEngineGate):OcrEngine {
            val spec=PaddleCandidates.all.single { it.id=="candidate_3" }
            val names=context.assets.list("text-lab-paddle-v1").orEmpty().toSet()
            return if(listOf(spec.detector.asset,spec.recognizer.asset,spec.dictionary.asset).all { it in names })
                PaddleMediumOcrEngine(context.applicationContext,gate)
            else NotAvailable("paddle-v6-medium","Medium detector/recognizer/dictionary missing; retaining ML Kit")
        }
    }
}
