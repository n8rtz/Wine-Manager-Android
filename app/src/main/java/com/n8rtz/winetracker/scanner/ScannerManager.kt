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

class ScannerManager(context: Context) {
    private val appContext = context.applicationContext
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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
     * Runs ML Kit on the full-resolution still and an enhanced, zoomed label crop. Keep the photo
     * alive until both passes finish, even if the UI stops waiting for the result.
     */
    suspend fun scanImage(file: File): ScannedWine = scanImage(file, WineReferenceDictionary.builtIn())

    internal suspend fun scanImage(file: File, dictionary: WineReferenceDictionary): ScannedWine {
        val inputImage = InputImage.fromFilePath(appContext, Uri.fromFile(file))
        return withContext(NonCancellable) {
            val fullFrameResult = recognizer.process(inputImage).await()
            val fullFrameHypothesis = fullFrameResult.toHypothesis()
            val enhancedBitmap = try {
                withContext(Dispatchers.IO) { LabelImageEnhancer.createEnhancedLabelCrop(file) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not prepare the enhanced label crop; using the full photo OCR", error)
                null
            }

            val enhancedHypothesis = enhancedBitmap?.let { bitmap ->
                try {
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                        .toHypothesis()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Enhanced label OCR failed; using the full photo OCR", error)
                    null
                } finally {
                    bitmap.recycle()
                }
            } ?: LabelOcrHypothesis(text = "")

            // Merge the passes line by line: the full frame keeps the printed lines, the crop
            // usually reads the decorative words the full frame drops.
            val passes = listOf(fullFrameHypothesis, enhancedHypothesis)
            val consensus = LabelOcrConsensus.merge(
                hypotheses = passes,
                parser = labelParser,
                dictionary = dictionary
            ) ?: return@withContext ScannedWine()
            val wine = labelParser.parse(consensus.text, dictionary)
            val assessment = LabelScanAssessment.assess(
                consensus = consensus,
                perPassResults = passes.filter { it.text.isNotBlank() }
                    .map { labelParser.parse(it.text, dictionary) },
                mergedResult = wine
            )
            Log.i(
                TAG,
                "Label scan agreement=${"%.2f".format(assessment.agreement)} " +
                    "supported=${consensus.supportedLines}/${consensus.totalLines} " +
                    "needsReview=${assessment.needsReview} contradictions=${assessment.contradictions}"
            )
            wine
        }
    }

    fun close() {
        recognizer.close()
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
