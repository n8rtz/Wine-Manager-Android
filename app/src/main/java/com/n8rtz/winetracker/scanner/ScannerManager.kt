package com.n8rtz.winetracker.scanner

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class ScannedWine(
    val maker: String? = null,
    val name: String? = null,
    val vintage: String? = null
)

class ScannerManager {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun scanImage(bitmap: Bitmap): ScannedWine {
        val image = InputImage.fromBitmap(bitmap, 0)
        return processImage(image)
    }

    @OptIn(ExperimentalGetImage::class)
    suspend fun scanImageProxy(imageProxy: ImageProxy): ScannedWine {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            return processImage(image)
        }
        return ScannedWine()
    }

    private suspend fun processImage(image: InputImage): ScannedWine {
        return try {
            val result = recognizer.process(image).await()
            parseText(result.text)
        } catch (e: Exception) {
            ScannedWine()
        }
    }

    private fun parseText(text: String): ScannedWine {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        
        // Vintage: 4-digit year (1900-2099)
        val vintageRegex = Regex("\\b(19|20)\\d{2}\\b")
        val vintageMatch = vintageRegex.find(text)
        val vintage = vintageMatch?.value

        // Basic heuristic for Maker and Name:
        // Usually, the Maker is one of the top-most lines and is prominent.
        // The Name is often the rest of the descriptive text.
        
        val maker = lines.getOrNull(0)
        val name = lines.filterIndexed { index, s -> 
            index > 0 && s != vintage 
        }.joinToString(" ")

        return ScannedWine(
            maker = maker,
            name = name.takeIf { it.isNotEmpty() },
            vintage = vintage
        )
    }
}
