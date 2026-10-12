package com.n8rtz.winetracker.scanner

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ScannerManager {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val labelParser = WineLabelParser()

    /** Legacy bitmap entry point retained for existing callers; the camera flow uses ImageAnalysis. */
    suspend fun scanImage(bitmap: Bitmap): ScannedWine = try {
        processImage(InputImage.fromBitmap(bitmap, 0))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ScannedWine()
    }

    /**
     * Processes one YUV ImageAnalysis frame directly, avoiding a full-resolution Bitmap copy.
     * This method owns the proxy and closes it after ML Kit completes, including error paths.
     */
    @OptIn(ExperimentalGetImage::class)
    suspend fun scanImageProxy(imageProxy: ImageProxy): ScannedWine {
        try {
            val mediaImage = imageProxy.image
                ?: throw IllegalStateException("Camera frame did not contain an image")
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            // Task.await can stop waiting on coroutine cancellation while ML Kit still reads the
            // MediaImage. Wait for the task to settle before the finally block releases its proxy.
            return withContext(NonCancellable) { processImage(inputImage) }
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        recognizer.close()
    }

    private suspend fun processImage(image: InputImage): ScannedWine {
        val result = recognizer.process(image).await()
        return labelParser.parse(result.text)
    }
}
