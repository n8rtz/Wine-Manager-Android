package com.n8rtz.winetracker.debug.graniteworkflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.ocr.MlKitOcrEngine
import com.yourapp.wine.ocr.NativeEngineGate
import com.yourapp.wine.ocr.ScanDeadline
import com.yourapp.wine.textlab.CandidateBudgetExpired
import com.yourapp.wine.textlab.PaddleCandidates
import com.yourapp.wine.textlab.PaddleTextDetector
import com.yourapp.wine.textlab.PaddleTextRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Result of running the Parallel Dual-OCR Engine.
 */
data class DualOcrResult(
    val mlKitBlocks: List<OcrBlock>,
    val ppOcrV6Blocks: List<OcrBlock>,
    val mlKitElapsedMs: Long,
    val ppOcrV6ElapsedMs: Long,
    val ppOcrStatusMessage: String
)

/**
 * Parallel Dual-OCR Engine for the Granite 4.2 workflow.
 *
 * Reuses the app's existing OCR engines and bundled model assets without downloading or storing
 * second copies:
 * - **Engine A (Google ML Kit Text Recognition v2):** Reuses [MlKitOcrEngine] (`com.google.mlkit:text-recognition`).
 * - **Engine B (PP-OCRv6 via ONNX Runtime Android):** Reuses [PaddleTextDetector], [PaddleTextRecognizer],
 *   and [PaddleCandidates] (`text-lab-paddle-v1/v6-medium-det.onnx`, `v6-medium-rec.onnx`, `ppocrv6_dict.utf8`).
 *   Both ONNX Runtime sessions (`PaddleTextDetector` and `PaddleTextRecognizer`) are kept warm in memory
 *   across photos so `v6-medium-det.onnx` (62 MB) and `v6-medium-rec.onnx` (76 MB) are not re-verified
 *   or re-compiled on every image; they are released only when [close] is called.
 * - Runs both engines concurrently via `async(Dispatchers.Default)` + `awaitAll()`.
 * - Normalizes every text block into `OcrBlock(text, confidence, normBbox)` with `[0.0..1.0]`
 *   normalized `[x_center, y_center, width, height]` coordinates.
 * - Sorts each engine's detections top-to-bottom (`y_center`), then left-to-right (`x_center`).
 */
class DualOcrEngine : AutoCloseable {
    private val mlKitGate = NativeEngineGate()
    private val paddleGate = NativeEngineGate()
    private val mlKitEngine by lazy { MlKitOcrEngine(mlKitGate) }
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private sealed interface EngineTaskOutcome {
        data class MlKitOutcome(val blocks: List<OcrBlock>, val elapsedMs: Long) : EngineTaskOutcome
        data class PpOcrOutcome(
            val blocks: List<OcrBlock>,
            val elapsedMs: Long,
            val statusMessage: String
        ) : EngineTaskOutcome
    }

    suspend fun recognize(
        context: Context,
        bitmap: Bitmap,
        ablationMode: OcrAblationMode
    ): DualOcrResult = withContext(Dispatchers.Default) {
        val deadlineAtMs = SystemClock.elapsedRealtime() + OCR_DEADLINE_BUDGET_MS
        val deadline = ScanDeadline(deadlineAtMs)

        coroutineScope {
            val deferredTasks = buildList {
                if (ablationMode.runsMlKit) {
                    add(
                        async(Dispatchers.Default) {
                            val start = SystemClock.elapsedRealtime()
                            val blocks = runMlKit(bitmap, deadline)
                            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
                            EngineTaskOutcome.MlKitOutcome(blocks, elapsed)
                        }
                    )
                }
                if (ablationMode.runsPpOcrV6) {
                    add(
                        async(Dispatchers.Default) {
                            val start = SystemClock.elapsedRealtime()
                            val (blocks, status) = runExistingPpOcrV6(context, bitmap, deadlineAtMs)
                            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
                            EngineTaskOutcome.PpOcrOutcome(blocks, elapsed, status)
                        }
                    )
                }
            }

            val outcomes = deferredTasks.awaitAll()
            val mlKitOutcome = outcomes.filterIsInstance<EngineTaskOutcome.MlKitOutcome>().firstOrNull()
            val ppOcrOutcome = outcomes.filterIsInstance<EngineTaskOutcome.PpOcrOutcome>().firstOrNull()

            DualOcrResult(
                mlKitBlocks = mlKitOutcome?.blocks.orEmpty(),
                ppOcrV6Blocks = ppOcrOutcome?.blocks.orEmpty(),
                mlKitElapsedMs = mlKitOutcome?.elapsedMs ?: 0L,
                ppOcrV6ElapsedMs = ppOcrOutcome?.elapsedMs ?: 0L,
                ppOcrStatusMessage = ppOcrOutcome?.statusMessage
                    ?: if (!ablationMode.runsPpOcrV6) "Skipped (ML Kit Only mode)" else "Not run"
            )
        }
    }

    /**
     * Reuses existing [MlKitOcrEngine] over the preprocessed [Bitmap].
     */
    private suspend fun runMlKit(bitmap: Bitmap, deadline: ScanDeadline): List<OcrBlock> {
        currentCoroutineContext().ensureActive()
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return emptyList()

        return try {
            val tokens = mlKitEngine.recognize(bitmap, "V0", deadline)
            val blocks = tokens.mapNotNull { token ->
                val clean = scrubText(token.text)
                if (clean.isBlank() || token.polygon.isEmpty()) return@mapNotNull null
                polygonToOcrBlock(
                    text = clean,
                    confidence = token.score,
                    polygon = token.polygon,
                    imageWidth = width,
                    imageHeight = height,
                    source = OcrEngineSource.ML_KIT
                )
            }
            sortBlocksTopToBottomLeftToRight(blocks)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit OCR failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Reuses existing [PaddleTextDetector] and [PaddleTextRecognizer] with the shared
     * `text-lab-paddle-v1` (`v6-medium-det.onnx`, `v6-medium-rec.onnx`, `ppocrv6_dict.utf8`) files.
     * Sessions remain warm across images until [close] is invoked.
     */
    private suspend fun runExistingPpOcrV6(
        context: Context,
        bitmap: Bitmap,
        deadlineAtMs: Long
    ): Pair<List<OcrBlock>, String> {
        currentCoroutineContext().ensureActive()
        val spec = PaddleCandidates.all.first()
        val blocks = mutableListOf<OcrBlock>()

        return try {
            BitmapWorkspace().use { workspace ->
                val detection = PaddleTextDetector.detect(context, bitmap, workspace, paddleGate, spec)

                for (region in detection.regions.lines) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() >= deadlineAtMs) break
                    var recognizedText = ""
                    var ctcPosterior = 0f

                    try {
                        PaddleTextRecognizer.recognize(
                            context = context,
                            crop = bitmap,
                            workspace = workspace,
                            deadline = ScanDeadline(deadlineAtMs),
                            gate = paddleGate,
                            lineQuad = region.quadBase,
                            spec = spec
                        ) { values ->
                            val raw = values["raw_text"].orEmpty()
                            if (raw.isNotBlank()) {
                                recognizedText = raw
                                ctcPosterior = values["ctc_posterior"]
                                    ?.toFloatOrNull()
                                    ?.takeIf { it.isFinite() && it in 0f..1f }
                                    ?: 0f
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: CandidateBudgetExpired) {
                        break
                    } catch (lineErr: Exception) {
                        Log.w(TAG, "PP-OCRv6 line failed: ${lineErr.javaClass.simpleName}")
                    }

                    val clean = scrubText(recognizedText)
                    if (clean.isNotBlank()) {
                        val block = polygonToOcrBlock(
                            text = clean,
                            confidence = ctcPosterior.takeIf { it > 0f }?.coerceIn(0.01f, 0.99f) ?: 0.5f,
                            polygon = region.quadBase,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                            source = OcrEngineSource.PP_OCRV6
                        )
                        if (block != null) {
                            blocks.add(block)
                        }
                    }
                }
            }
            val sorted = sortBlocksTopToBottomLeftToRight(blocks)
            sorted to "PP-OCRv6 (${spec.detector.asset} + ${spec.recognizer.asset}, warm): ${sorted.size} blocks"
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "PP-OCRv6 execution error: ${e.message}", e)
            emptyList<OcrBlock>() to "PP-OCRv6 error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun polygonToOcrBlock(
        text: String,
        confidence: Float,
        polygon: List<PointF>,
        imageWidth: Int,
        imageHeight: Int,
        source: OcrEngineSource
    ): OcrBlock? {
        if (imageWidth <= 0 || imageHeight <= 0 || polygon.isEmpty()) return null
        val left = polygon.minOf { it.x }.coerceIn(0f, imageWidth.toFloat())
        val top = polygon.minOf { it.y }.coerceIn(0f, imageHeight.toFloat())
        val right = polygon.maxOf { it.x }.coerceIn(left, imageWidth.toFloat())
        val bottom = polygon.maxOf { it.y }.coerceIn(top, imageHeight.toFloat())
        if (right <= left || bottom <= top) return null
        return OcrBlock.fromPixelBounds(
            text = text,
            confidence = confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.65f,
            pixelLeft = left,
            pixelTop = top,
            pixelRight = right,
            pixelBottom = bottom,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            source = source
        )
    }

    suspend fun releaseWarmPaddleSessions() {
        withContext(NonCancellable) {
            runCatching { PaddleTextRecognizer.release(paddleGate) }
            runCatching { PaddleTextDetector.release(paddleGate) }
        }
    }

    override fun close() {
        runCatching { mlKitEngine.close() }
        cleanupScope.launch {
            withContext(NonCancellable) {
                runCatching { PaddleTextRecognizer.release(paddleGate) }
                runCatching { PaddleTextDetector.release(paddleGate) }
            }
        }
    }

    companion object {
        private const val TAG = "GraniteDualOcr"
        private const val OCR_DEADLINE_BUDGET_MS = 45_000L

        fun sortBlocksTopToBottomLeftToRight(blocks: List<OcrBlock>): List<OcrBlock> =
            blocks.sortedWith(OcrBlock.TOP_TO_BOTTOM_LEFT_TO_RIGHT)

        internal fun scrubText(raw: String): String =
            raw.replace(Regex("[\\r\\n\\t]+"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
    }
}
