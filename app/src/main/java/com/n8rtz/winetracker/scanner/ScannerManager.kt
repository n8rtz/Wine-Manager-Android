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
     * Scans a captured still: reads the barcode, then OCRs the photo one pass at a time, escalating
     * to stronger crop recipes while the reading stays contradicted or unrecognised. Each pass is
     * merged with the ones before it, and a resolved barcode stops the escalation early. Keep the
     * photo alive until every pass finishes, even if the UI stops waiting for the result.
     */
    suspend fun scanImage(file: File): ScannedWine = scanImage(file, WineReferenceDictionary.builtIn())

    internal suspend fun scanImage(file: File, dictionary: WineReferenceDictionary): ScannedWine {
        val inputImage = InputImage.fromFilePath(appContext, Uri.fromFile(file))
        return withContext(NonCancellable) {
            val session = LabelScanSession(dictionary)
            session.addPass(recognizer.process(inputImage).await().toHypothesis())
            // A barcode names a bottle exactly, so try it before spending time on more OCR passes.
            // Most front-label photos contain none, in which case nothing is looked up.
            val barcode = barcodeReader.read(inputImage)
            val identity = barcode?.let { code -> identityResolver?.resolve(code) }

            while (true) {
                val recipe = session.nextRecipe(identityResolved = identity != null) ?: break
                val hypothesis = readLabelCrop(file, recipe) ?: break
                session.addPass(hypothesis)
            }

            val outcome = session.finish(barcode = barcode, identity = identity)
            Log.i(TAG, outcome.summary(barcode, identity))
            outcome.wine
        }
    }

    /** One escalation step: Ocr the photo again through a stronger crop recipe. */
    private suspend fun readLabelCrop(file: File, recipe: LabelCropRecipe): LabelOcrHypothesis? {
        val bitmap = prepareCrop(file, recipe) ?: return null
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().toHypothesis()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "${recipe.name} label crop OCR failed; keeping the passes already read", error)
            null
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun prepareCrop(file: File, recipe: LabelCropRecipe): Bitmap? = try {
        withContext(Dispatchers.IO) { LabelImageEnhancer.createLabelCrop(file, recipe) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Could not prepare the ${recipe.name} label crop", error)
        null
    }

    fun close() {
        recognizer.close()
        barcodeReader.close()
    }

    private fun Text.toHypothesis(): LabelOcrHypothesis = LabelOcrHypothesis(text)

    private suspend fun processImage(
        image: InputImage,
        dictionary: WineReferenceDictionary
    ): ScannedWine {
        val result = recognizer.process(image).await()
        return labelParser.parse(result.text, dictionary)
    }

    private companion object {
        const val TAG = "ScannerManager"
    }
}
