package com.n8rtz.winetracker.debug.graniteworkflow

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * Completely decoupled Parallel Dual-OCR Engine for the Granite 4.2 workflow.
 *
 * - Engine A: Google ML Kit Text Recognition v2 (Latin script) on the preprocessed [Bitmap].
 * - Engine B: PP-OCRv6 via ONNX Runtime Android (detection + optional angle classification + recognition)
 *   loaded from `context.getExternalFilesDir("models/ppocrv6")`.
 * - Runs both engines concurrently via `async(Dispatchers.Default)` + `awaitAll()`.
 * - Normalizes every text block into `OcrBlock(text, confidence, normBbox)` with `[0.0..1.0]`
 *   normalized `[x_center, y_center, width, height]` coordinates.
 * - Sorts each engine's detections top-to-bottom (`y_center`), then left-to-right (`x_center`).
 */
class DualOcrEngine : AutoCloseable {
    private val mlKitRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val ppOcrRunner = StandalonePpOcrV6OnnxRunner()

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
        coroutineScope {
            val deferredTasks = buildList {
                if (ablationMode.runsMlKit) {
                    add(
                        async(Dispatchers.Default) {
                            val start = SystemClock.elapsedRealtime()
                            val blocks = runMlKit(bitmap)
                            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
                            EngineTaskOutcome.MlKitOutcome(blocks, elapsed)
                        }
                    )
                }
                if (ablationMode.runsPpOcrV6) {
                    add(
                        async(Dispatchers.Default) {
                            val start = SystemClock.elapsedRealtime()
                            val (blocks, status) = ppOcrRunner.recognize(context, bitmap)
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

    private suspend fun runMlKit(bitmap: Bitmap): List<OcrBlock> {
        currentCoroutineContext().ensureActive()
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return emptyList()

        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = mlKitRecognizer.process(inputImage).await()
            val detected = mutableListOf<OcrBlock>()

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val cleanText = scrubText(line.text)
                    if (cleanText.isBlank()) continue

                    val cornerPts = line.cornerPoints
                    val bbox = line.boundingBox
                    val (left, top, right, bottom) = when {
                        !cornerPts.isNullOrEmpty() -> {
                            val minX = cornerPts.minOf { it.x }.toFloat().coerceIn(0f, width.toFloat())
                            val minY = cornerPts.minOf { it.y }.toFloat().coerceIn(0f, height.toFloat())
                            val maxX = cornerPts.maxOf { it.x }.toFloat().coerceIn(minX, width.toFloat())
                            val maxY = cornerPts.maxOf { it.y }.toFloat().coerceIn(minY, height.toFloat())
                            floatArrayOf(minX, minY, maxX, maxY)
                        }
                        bbox != null -> {
                            val minX = bbox.left.toFloat().coerceIn(0f, width.toFloat())
                            val minY = bbox.top.toFloat().coerceIn(0f, height.toFloat())
                            val maxX = bbox.right.toFloat().coerceIn(minX, width.toFloat())
                            val maxY = bbox.bottom.toFloat().coerceIn(minY, height.toFloat())
                            floatArrayOf(minX, minY, maxX, maxY)
                        }
                        else -> continue
                    }

                    if (right <= left || bottom <= top) continue

                    val lineConf = line.confidence?.takeIf { it.isFinite() && it in 0f..1f }
                    val elemConf = line.elements
                        .mapNotNull { it.confidence?.takeIf { c -> c.isFinite() && c in 0f..1f } }
                        .takeIf { it.isNotEmpty() }
                        ?.average()
                        ?.toFloat()
                    val confidence = (lineConf ?: elemConf ?: 0.75f).coerceIn(0f, 1f)

                    detected.add(
                        OcrBlock.fromPixelBounds(
                            text = cleanText,
                            confidence = confidence,
                            pixelLeft = left,
                            pixelTop = top,
                            pixelRight = right,
                            pixelBottom = bottom,
                            imageWidth = width,
                            imageHeight = height,
                            source = OcrEngineSource.ML_KIT
                        )
                    )
                }
            }

            sortBlocksTopToBottomLeftToRight(detected)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit OCR failed: ${e.message}", e)
            emptyList()
        }
    }

    override fun close() {
        runCatching { mlKitRecognizer.close() }
        runCatching { ppOcrRunner.close() }
    }

    companion object {
        private const val TAG = "GraniteDualOcr"

        fun sortBlocksTopToBottomLeftToRight(blocks: List<OcrBlock>): List<OcrBlock> =
            blocks.sortedWith(OcrBlock.TOP_TO_BOTTOM_LEFT_TO_RIGHT)

        internal fun scrubText(raw: String): String =
            raw.replace(Regex("[\\r\\n\\t]+"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
    }
}

/**
 * Self-contained PP-OCRv6 ONNX runner loading `det.onnx`, optional `cls.onnx`, `rec.onnx`, and
 * `ppocrv6_dict.utf8` from `context.getExternalFilesDir("models/ppocrv6")`.
 *
 * Uses zero classes from `com.yourapp.wine.*` or OpenCV so it is 100% isolated.
 */
internal class StandalonePpOcrV6OnnxRunner : AutoCloseable {
    private val mutex = Mutex()

    private var detSession: OrtSession? = null
    private var detPathLoaded: String? = null

    private var clsSession: OrtSession? = null
    private var clsPathLoaded: String? = null

    private var recSession: OrtSession? = null
    private var recPathLoaded: String? = null

    private var dictionary: List<String> = emptyList()
    private var dictPathLoaded: String? = null

    private data class DetectedBox(
        val pixelRect: RectF,
        val score: Float
    )

    suspend fun recognize(
        context: Context,
        bitmap: Bitmap
    ): Pair<List<OcrBlock>, String> = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val detFile = GraniteModelStorage.resolvePpOcrDetFile(context)
        val clsFile = GraniteModelStorage.resolvePpOcrClsFile(context)
        val recFile = GraniteModelStorage.resolvePpOcrRecFile(context)
        val dictFile = GraniteModelStorage.resolvePpOcrDictFile(context, autoSeedFromAssets = true)

        if (!detFile.isFile || detFile.length() == 0L || !recFile.isFile || recFile.length() == 0L) {
            return@withLock emptyList<OcrBlock>() to
                "PP-OCRv6 ONNX files missing in ${GraniteModelStorage.ppOcrDir(context).absolutePath} (import det.onnx & rec.onnx)"
        }
        if (!dictFile.isFile || dictFile.length() == 0L) {
            return@withLock emptyList<OcrBlock>() to
                "PP-OCRv6 dictionary missing in ${GraniteModelStorage.ppOcrDir(context).absolutePath}"
        }

        try {
            val env = OrtEnvironment.getEnvironment()
            val activeDet = ensureSession(env, detFile, detSession, detPathLoaded).also {
                detSession = it
                detPathLoaded = "${detFile.absolutePath}:${detFile.length()}:${detFile.lastModified()}"
            }
            val activeCls = if (clsFile.isFile && clsFile.length() > 0L) {
                runCatching {
                    ensureSession(env, clsFile, clsSession, clsPathLoaded).also {
                        clsSession = it
                        clsPathLoaded = "${clsFile.absolutePath}:${clsFile.length()}:${clsFile.lastModified()}"
                    }
                }.getOrNull()
            } else {
                null
            }
            val activeRec = ensureSession(env, recFile, recSession, recPathLoaded).also {
                recSession = it
                recPathLoaded = "${recFile.absolutePath}:${recFile.length()}:${recFile.lastModified()}"
            }
            val activeDict = ensureDictionary(dictFile)

            val candidateBoxes = runDetection(env, activeDet, bitmap)
            val blocks = mutableListOf<OcrBlock>()
            var rotated180Count = 0

            for (box in candidateBoxes) {
                currentCoroutineContext().ensureActive()
                val cropLeft = box.pixelRect.left.roundToInt().coerceIn(0, bitmap.width - 1)
                val cropTop = box.pixelRect.top.roundToInt().coerceIn(0, bitmap.height - 1)
                val cropRight = box.pixelRect.right.roundToInt().coerceIn(cropLeft + 1, bitmap.width)
                val cropBottom = box.pixelRect.bottom.roundToInt().coerceIn(cropTop + 1, bitmap.height)
                val cropW = cropRight - cropLeft
                val cropH = cropBottom - cropTop
                if (cropW < 4 || cropH < 4) continue

                var lineCrop = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropW, cropH)
                if (cropH > cropW * 1.6f) {
                    // Rotate vertical text strips 90 degrees counter-clockwise for horizontal recognition
                    val m = Matrix().apply { postRotate(-90f) }
                    lineCrop = Bitmap.createBitmap(lineCrop, 0, 0, lineCrop.width, lineCrop.height, m, true)
                }

                if (activeCls != null && shouldRotate180(env, activeCls, lineCrop)) {
                    val m = Matrix().apply { postRotate(180f) }
                    lineCrop = Bitmap.createBitmap(lineCrop, 0, 0, lineCrop.width, lineCrop.height, m, true)
                    rotated180Count++
                }

                val (text, confidence) = runRecognition(env, activeRec, lineCrop, activeDict)
                val clean = DualOcrEngine.scrubText(text)
                if (clean.isNotBlank()) {
                    blocks.add(
                        OcrBlock.fromPixelBounds(
                            text = clean,
                            confidence = confidence,
                            pixelLeft = box.pixelRect.left,
                            pixelTop = box.pixelRect.top,
                            pixelRight = box.pixelRect.right,
                            pixelBottom = box.pixelRect.bottom,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                            source = OcrEngineSource.PP_OCRV6
                        )
                    )
                }
            }

            val sorted = DualOcrEngine.sortBlocksTopToBottomLeftToRight(blocks)
            val clsNote = if (activeCls != null) "cls.onnx active ($rotated180Count rotated)" else "cls.onnx optional (upright)"
            sorted to "PP-OCRv6 (${detFile.name} + ${recFile.name}, $clsNote): ${sorted.size} blocks"
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w("GranitePpOcrV6", "PP-OCRv6 execution error: ${e.message}", e)
            emptyList<OcrBlock>() to "PP-OCRv6 error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun ensureSession(
        env: OrtEnvironment,
        file: File,
        existing: OrtSession?,
        loadedKey: String?
    ): OrtSession {
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        if (existing != null && loadedKey == key) {
            return existing
        }
        runCatching { existing?.close() }
        return OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(1)
            env.createSession(file.absolutePath, opts)
        }
    }

    private fun ensureDictionary(dictFile: File): List<String> {
        val key = "${dictFile.absolutePath}:${dictFile.length()}:${dictFile.lastModified()}"
        if (dictionary.isNotEmpty() && dictPathLoaded == key) {
            return dictionary
        }
        val loaded = dictFile.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        dictionary = loaded
        dictPathLoaded = key
        return loaded
    }

    private suspend fun runDetection(
        env: OrtEnvironment,
        session: OrtSession,
        bitmap: Bitmap
    ): List<DetectedBox> {
        val origW = bitmap.width
        val origH = bitmap.height
        val scale = min(1.0, DET_MAX_EDGE.toDouble() / max(origW, origH).toDouble())
        val detW = (((origW * scale) / 32.0).roundToInt() * 32).coerceIn(32, DET_MAX_EDGE)
        val detH = (((origH * scale) / 32.0).roundToInt() * 32).coerceIn(32, DET_MAX_EDGE)

        val scaled = if (detW == origW && detH == origH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, detW, detH, true)
        }

        val floatBuffer = ByteBuffer.allocateDirect(3 * detW * detH * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val row = IntArray(detW)
        val planeSize = detW * detH
        for (y in 0 until detH) {
            currentCoroutineContext().ensureActive()
            scaled.getPixels(row, 0, detW, 0, y, detW, 1)
            val rowBase = y * detW
            for (x in 0 until detW) {
                val px = row[x]
                // Paddle DBNet uses BGR order with ImageNet mean/std
                val b = (px and 0xFF) / 255f
                val g = ((px ushr 8) and 0xFF) / 255f
                val r = ((px ushr 16) and 0xFF) / 255f
                floatBuffer.put(0 * planeSize + rowBase + x, (b - 0.485f) / 0.229f)
                floatBuffer.put(1 * planeSize + rowBase + x, (g - 0.456f) / 0.224f)
                floatBuffer.put(2 * planeSize + rowBase + x, (r - 0.406f) / 0.225f)
            }
        }

        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1L, 3L, detH.toLong(), detW.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val outTensor = result[0] as? OnnxTensor ?: return emptyList()
                val shape = outTensor.info.shape
                val mapH = if (shape.size == 4) shape[2].toInt() else detH
                val mapW = if (shape.size == 4) shape[3].toInt() else detW
                val probs = FloatArray(mapW * mapH)
                outTensor.floatBuffer.get(probs)
                return decodeDbBoxesPureKotlin(probs, mapW, mapH, origW, origH)
            }
        }
    }

    /**
     * Pure-Kotlin connected-components + DB box unclipping on the probability map so the
     * Granite workflow has zero coupling to OpenCV or production detector classes.
     */
    private suspend fun decodeDbBoxesPureKotlin(
        prob: FloatArray,
        mapW: Int,
        mapH: Int,
        origW: Int,
        origH: Int
    ): List<DetectedBox> {
        val visited = BooleanArray(mapW * mapH)
        val queue = IntArray(mapW * mapH)
        val scaleX = origW.toFloat() / mapW.toFloat()
        val scaleY = origH.toFloat() / mapH.toFloat()
        val detected = mutableListOf<DetectedBox>()

        for (idx in prob.indices) {
            if (visited[idx] || prob[idx] <= DB_PIXEL_THRESHOLD) continue
            currentCoroutineContext().ensureActive()

            var head = 0
            var tail = 0
            queue[tail++] = idx
            visited[idx] = true

            var minX = idx % mapW
            var maxX = minX
            var minY = idx / mapW
            var maxY = minY
            var probSum = 0f

            while (head < tail) {
                val curr = queue[head++]
                val cx = curr % mapW
                val cy = curr / mapW
                probSum += prob[curr].coerceIn(0f, 1f)

                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy

                // 4-connected neighbors (+ diagonals for 8-connectivity)
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny !in 0 until mapH) continue
                    val nRow = ny * mapW
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        if (nx !in 0 until mapW) continue
                        val nIdx = nRow + nx
                        if (!visited[nIdx] && prob[nIdx] > DB_PIXEL_THRESHOLD) {
                            visited[nIdx] = true
                            queue[tail++] = nIdx
                        }
                    }
                }
            }

            val area = tail
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (area < 6 || boxW < 3 || boxH < 3) continue

            // Compute mean probability across the bounding box
            var rectSum = 0f
            var rectCount = 0
            for (y in minY..maxY) {
                val rOff = y * mapW
                for (x in minX..maxX) {
                    rectSum += prob[rOff + x].coerceIn(0f, 1f)
                    rectCount++
                }
            }
            val boxScore = if (rectCount > 0) rectSum / rectCount else (probSum / area)
            if (boxScore < DB_BOX_THRESHOLD) continue

            // DB unclip expansion for rectangular text regions
            val wF = boxW.toFloat()
            val hF = boxH.toFloat()
            val distance = (wF * hF * DB_UNCLIP_RATIO) / (2f * (wF + hF))
            val expLeft = ((minX - distance) * scaleX).coerceIn(0f, origW.toFloat())
            val expTop = ((minY - distance) * scaleY).coerceIn(0f, origH.toFloat())
            val expRight = ((maxX + 1 + distance) * scaleX).coerceIn(expLeft, origW.toFloat())
            val expBottom = ((maxY + 1 + distance) * scaleY).coerceIn(expTop, origH.toFloat())

            if (expRight - expLeft >= 4f && expBottom - expTop >= 4f) {
                detected.add(DetectedBox(RectF(expLeft, expTop, expRight, expBottom), boxScore))
            }
        }

        return detected
            .sortedWith(compareBy<DetectedBox> { it.pixelRect.centerY() }.thenBy { it.pixelRect.centerX() })
            .take(MAX_DETECTED_LINES)
    }

    /**
     * Runs the optional PP-OCR angle classification model (`cls.onnx`, input `[1, 3, 48, 192]`).
     * Returns `true` if the crop is upside-down (`180°` class confidence > `0.90`).
     */
    private fun shouldRotate180(
        env: OrtEnvironment,
        clsSession: OrtSession,
        crop: Bitmap
    ): Boolean = runCatching {
        val clsH = 48
        val clsW = 192
        val resizedW = ceil(clsH.toDouble() * crop.width.toDouble() / crop.height.toDouble())
            .toInt()
            .coerceIn(1, clsW)
        val scaled = Bitmap.createScaledBitmap(crop, resizedW, clsH, true)

        val buffer = ByteBuffer.allocateDirect(3 * clsH * clsW * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val row = IntArray(resizedW)
        val plane = clsH * clsW
        for (y in 0 until clsH) {
            scaled.getPixels(row, 0, resizedW, 0, y, resizedW, 1)
            val rBase = y * clsW
            for (x in 0 until resizedW) {
                val px = row[x]
                buffer.put(0 * plane + rBase + x, ((px and 0xFF) / 127.5f) - 1f)
                buffer.put(1 * plane + rBase + x, (((px ushr 8) and 0xFF) / 127.5f) - 1f)
                buffer.put(2 * plane + rBase + x, (((px ushr 16) and 0xFF) / 127.5f) - 1f)
            }
        }
        val inputName = clsSession.inputNames.first()
        OnnxTensor.createTensor(env, buffer, longArrayOf(1L, 3L, clsH.toLong(), clsW.toLong())).use { tensor ->
            clsSession.run(mapOf(inputName to tensor)).use { out ->
                val outTensor = out[0] as? OnnxTensor ?: return false
                val fb = outTensor.floatBuffer
                if (fb.remaining() >= 2) {
                    val p0 = fb.get()
                    val p180 = fb.get()
                    p180 > p0 && p180 > 0.90f
                } else {
                    false
                }
            }
        }
    }.getOrDefault(false)

    /**
     * Runs PP-OCRv6 CTC text recognition (`rec.onnx`, input `[1, 3, 48, W]`).
     */
    private suspend fun runRecognition(
        env: OrtEnvironment,
        recSession: OrtSession,
        crop: Bitmap,
        dict: List<String>
    ): Pair<String, Float> {
        val recH = 48
        val inputInfo = recSession.inputInfo.values.first().info as? TensorInfo
        val fixedW = inputInfo?.shape?.getOrNull(3) ?: -1L

        val contentW = ceil(recH.toDouble() * crop.width.toDouble() / crop.height.toDouble())
            .toInt()
            .coerceIn(1, 1024)
        val paddedW = if (fixedW > 0L) {
            fixedW.toInt().coerceIn(contentW, 1024)
        } else {
            max(320, contentW)
        }

        val scaled = Bitmap.createScaledBitmap(crop, contentW, recH, true)
        val buffer = ByteBuffer.allocateDirect(3 * recH * paddedW * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val row = IntArray(contentW)
        val plane = recH * paddedW
        for (y in 0 until recH) {
            currentCoroutineContext().ensureActive()
            scaled.getPixels(row, 0, contentW, 0, y, contentW, 1)
            val rBase = y * paddedW
            for (x in 0 until contentW) {
                val px = row[x]
                buffer.put(0 * plane + rBase + x, ((px and 0xFF) / 127.5f) - 1f)
                buffer.put(1 * plane + rBase + x, (((px ushr 8) and 0xFF) / 127.5f) - 1f)
                buffer.put(2 * plane + rBase + x, (((px ushr 16) and 0xFF) / 127.5f) - 1f)
            }
        }

        val inputName = recSession.inputNames.first()
        OnnxTensor.createTensor(env, buffer, longArrayOf(1L, 3L, recH.toLong(), paddedW.toLong())).use { tensor ->
            recSession.run(mapOf(inputName to tensor)).use { result ->
                val outTensor = result[0] as? OnnxTensor ?: return "" to 0f
                val shape = outTensor.info.shape
                if (shape.size != 3) return "" to 0f
                val timeSteps = shape[1].toInt()
                val numClasses = shape[2].toInt()
                return decodeCtcGreedy(outTensor.floatBuffer, timeSteps, numClasses, dict)
            }
        }
    }

    private fun decodeCtcGreedy(
        floatBuffer: FloatBuffer,
        timeSteps: Int,
        numClasses: Int,
        dict: List<String>
    ): Pair<String, Float> {
        if (timeSteps <= 0 || numClasses <= 1 || floatBuffer.remaining() < timeSteps * numClasses) {
            return "" to 0f
        }
        val sb = StringBuilder()
        var prevIdx = -1
        var confSum = 0.0
        var confCount = 0

        for (t in 0 until timeSteps) {
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                val v = floatBuffer.get()
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = c
                }
            }
            if (bestIdx != 0 && bestIdx != prevIdx) {
                val charIndex = bestIdx - 1
                when {
                    charIndex in dict.indices -> sb.append(dict[charIndex])
                    bestIdx == numClasses - 1 -> sb.append(' ')
                }
                val prob = if (bestVal in 0f..1.0001f) {
                    bestVal.coerceIn(0f, 1f)
                } else {
                    (1.0f / (1.0f + exp(-bestVal))).coerceIn(0f, 1f)
                }
                confSum += prob
                confCount++
            }
            prevIdx = bestIdx
        }

        val meanConf = if (confCount > 0) (confSum / confCount).toFloat().coerceIn(0f, 1f) else 0f
        return sb.toString() to meanConf
    }

    override fun close() {
        runCatching { detSession?.close() }
        runCatching { clsSession?.close() }
        runCatching { recSession?.close() }
        detSession = null
        clsSession = null
        recSession = null
    }

    companion object {
        private const val DET_MAX_EDGE = 960
        private const val MAX_DETECTED_LINES = 40
        private const val DB_PIXEL_THRESHOLD = 0.20f
        private const val DB_BOX_THRESHOLD = 0.45f
        private const val DB_UNCLIP_RATIO = 1.4f
    }
}
