package com.n8rtz.winetracker.scanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
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
     * Runs OCR on the full-resolution still photo saved by CameraX. Keep the photo alive until
     * ML Kit has finished reading it, even if the UI stops waiting for the result.
     */
    suspend fun scanImage(file: File): ScannedWine = scanImage(file, WineReferenceDictionary.builtIn())

    internal suspend fun scanImage(file: File, dictionary: WineReferenceDictionary): ScannedWine {
        val inputImage = InputImage.fromFilePath(appContext, Uri.fromFile(file))
        return withContext(NonCancellable) { processImage(inputImage, dictionary) }
    }

    fun close() {
        recognizer.close()
    }

    private suspend fun processImage(
        image: InputImage,
        dictionary: WineReferenceDictionary
    ): ScannedWine {
        val result = recognizer.process(image).await()
        return labelParser.parse(result.text, dictionary)
    }
}
