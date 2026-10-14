package com.n8rtz.winetracker.scanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class ScannerManager internal constructor(
    context: Context,
    /** Null when the app has no product-registry access: scans then rely on the label photo alone. */
    private val identityResolver: LabelIdentityResolver?
) {
    /** Scans without a product registry: the label photo alone decides the suggestions. */
    constructor(context: Context) : this(context, null)

    private val appContext = context.applicationContext
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeReader = LabelBarcodeReader()
    private val labelParser = WineLabelParser()

    /** Legacy bitmap entry point retained for existing callers. */
    suspend fun scanImage(bitmap: Bitmap): ScannedWine = try {
        processImage(InputImage.fromBitmap(bitmap, 0), WineReferenceDictionary.builtIn())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ScannedWine()
    }

    /**
     * Scans a captured still: measures the photo, reads the barcode, then OCRs the photo one view at a
     * time. Each view is merged with the ones before it, escalation follows what the reading still
     * lacks (a blurry photo stops it, a resolved barcode ends it), and the whole decision trail goes
     * to Logcat. Keep the photo alive until every pass finishes, even if the UI stops waiting.
     */
    suspend fun scanImage(file: File): ScannedWine = scanImage(file, WineReferenceDictionary.builtIn())

    internal suspend fun scanImage(file: File, dictionary: WineReferenceDictionary): ScannedWine {
        val inputImage = InputImage.fromFilePath(appContext, Uri.fromFile(file))
        return withContext(NonCancellable) {
            // Measure before OCR: a soft photo changes what the ladder should even attempt.
            val quality = withContext(Dispatchers.IO) { ImageQualityAnalyzer.assess(file) }
            val session = LabelScanSession(dictionary)
            session.addPass(
                hypothesis = recognizer.process(inputImage).await().toHypothesis(LabelOcrHypothesis.Role.FULL_VIEW),
                viewLabel = FRAME_VIEW
            )

            // A barcode names a bottle exactly, so try it before spending time on more OCR passes.
            // Most front-label photos contain none, in which case nothing is looked up.
            val barcode = barcodeReader.read(inputImage)
            val identity = barcode?.let { code -> identityResolver?.resolve(code) }

            while (true) {
                val plan = session.nextPassPlan(identityResolved = identity != null, quality = quality) ?: break
                val read = readView(file, plan) ?: break
                session.addPass(read.hypothesis, read.mapping, plan.logLabel)
            }

            val outcome = session.finish(barcode = barcode, identity = identity, quality = quality)
            Log.i(TAG, outcome.summary(barcode, identity))
            outcome.wine
        }
    }

    private class ReadView(val hypothesis: LabelOcrHypothesis, val mapping: LabelCropMapping)

    /** One escalation step: prepare the view the plan asks for and OCR it. */
    private suspend fun readView(file: File, plan: LabelPassPlan): ReadView? {
        val prepared = prepareView(file, plan) ?: return null
        return try {
            val text = recognizer.process(InputImage.fromBitmap(prepared.bitmap, 0)).await()
            ReadView(text.toHypothesis(plan.role), prepared.mapping)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "${plan.logLabel} label pass failed; keeping the views already read", error)
            null
        } finally {
            prepared.bitmap.recycle()
        }
    }

    private suspend fun prepareView(file: File, plan: LabelPassPlan): LabelImageEnhancer.PreparedView? = try {
        withContext(Dispatchers.IO) { LabelImageEnhancer.createView(file, plan) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Could not prepare the ${plan.logLabel} view of the label", error)
        null
    }

    fun close() {
        recognizer.close()
        barcodeReader.close()
    }

    /**
     * Keeps each line's box and the block it belongs to, so a contested field can be zoomed into
     * rather than guessed at again.
     */
    private fun Text.toHypothesis(role: LabelOcrHypothesis.Role): LabelOcrHypothesis {
        val lines = mutableListOf<LabelOcrLine>()
        textBlocks.forEachIndexed { blockIndex, block ->
            block.lines.forEach { line ->
                lines += LabelOcrLine(
                    text = line.text,
                    box = line.boundingBox?.let { rect -> LabelBox(rect.left, rect.top, rect.right, rect.bottom) },
                    blockIndex = blockIndex
                )
            }
        }
        return LabelOcrHypothesis(text = text, lines = lines, role = role)
    }

    private suspend fun processImage(
        image: InputImage,
        dictionary: WineReferenceDictionary
    ): ScannedWine {
        val result = recognizer.process(image).await()
        return labelParser.parse(result.text, dictionary)
    }

    private companion object {
        const val TAG = "ScannerManager"
        const val FRAME_VIEW = "frame"
    }
}
