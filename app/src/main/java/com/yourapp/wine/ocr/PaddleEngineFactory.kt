package com.yourapp.wine.ocr

import android.content.Context
import android.util.Log
import org.json.JSONObject

/** Real model filenames only; custom recognition replaces rec, never det. No weights are manufactured. */
data class PaddleModelSet(val family: String, val detectorAsset: String, val classifierAsset: String,
    val recognizerAsset: String, val dictionaryAsset: String, val customRecognition: Boolean)
data class PaddlePresence(val models: PaddleModelSet?, val reason: String, val installedBytes: Long = 0)

/** Integration hook for a supplied, genuine Paddle Lite/JNI implementation, not an .nb file decoder. */
fun interface RealPaddleBridgeFactory {
    /** RECTIFIED input only: engine must detect -> invertible line crop -> cls/rec -> one token/line with rectified polygons, sharing gate/deadline. */
    fun buildEngine(context: Context, models: PaddleModelSet, nativeGate: NativeEngineGate): OcrEngine
}

object PaddleEngineFactory {
    const val OPTIONAL_ASSET_LIMIT = 80L * 1024 * 1024

    /** No coordinates: checks actual assets/ocr files and a stated manifest without loading or pretending to decode weights. */
    fun inspect(context: Context): PaddlePresence {
        val assets = context.assets
        var bytes = 0L
        var inventoryComplete = false
        return try {
            fun count(path: String, depth: Int) {
                require(depth <= 4) { "Model directory nesting exceeds limit" }
                val children = assets.list(path).orEmpty()
                if (children.isNotEmpty()) children.forEach { count("$path/$it", depth + 1) }
                else assets.open(path).use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer); if (read < 0) break
                        bytes += read
                        require(bytes < OPTIONAL_ASSET_LIMIT) { "Optional OCR/NER assets exceed 80 MiB" }
                    }
                }
            }
            // Invalid/unused model files still consume installed asset budget; never hide them as zero bytes.
            for (directory in listOf("ocr", "ner")) if (!assets.list(directory).isNullOrEmpty()) count(directory, 0)
            inventoryComplete = true
            if (assets.list("ocr").isNullOrEmpty()) return PaddlePresence(null, "assets/ocr/ missing — using bundled ML Kit only", bytes)
            val manifest = JSONObject(assets.open("ocr/manifest.json").bufferedReader().use { reader ->
                val text = StringBuilder(); val chunk = CharArray(2048)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    require(text.length + count <= 16_384) { "Paddle manifest exceeds 16 KiB" }
                    text.append(chunk, 0, count)
                }
                text.toString()
            })
            require(manifest.getString("schema") == "paddle-lite/v1") { "Expected paddle-lite/v1 manifest" }
            val family = manifest.getString("family")
            require(family in setOf("PP-OCRv4-mobile", "PP-OCRv5-mobile")) { "Supply the real mobile model family" }
            fun path(key: String): String {
                val filename = manifest.getString(key)
                require(!filename.startsWith('/') && !filename.contains("..") && filename.matches(Regex("[A-Za-z0-9_./-]+"))) { "Unsafe model filename" }
                return "ocr/$filename"
            }
            val custom = assets.list("ocr").orEmpty().filter { it.startsWith("rec_custom.") && it.endsWith(".nb") }.sorted().firstOrNull()
            val models = PaddleModelSet(family, path("det"), path("cls"), custom?.let { "ocr/$it" } ?: path("rec"),
                path("dictionary"), custom != null)
            listOf(models.detectorAsset, models.classifierAsset, models.recognizerAsset, models.dictionaryAsset)
                .forEach { assets.open(it).use { stream -> require(stream.read() >= 0) { "Empty model/dictionary: $it" } } }
            PaddlePresence(models, "Real files present; genuine Paddle Lite bridge still required", bytes)
        } catch (error: Exception) {
            PaddlePresence(null, "Paddle skipped: ${error.message}", if (inventoryComplete) bytes else maxOf(bytes, OPTIONAL_ASSET_LIMIT))
        }
    }

    /** Builds an app-scoped rectified-label engine ONLY when both actual models and a genuine bridge were supplied. */
    fun create(context: Context, presence: PaddlePresence, nativeGate: NativeEngineGate,
               bridge: RealPaddleBridgeFactory? = null): OcrEngine {
        val engine = when {
            presence.models == null -> NotAvailable("paddle", presence.reason)
            bridge == null -> NotAvailable("paddle", "Models found, but no genuine Paddle Lite/JNI bridge registered")
            else -> bridge.buildEngine(context.applicationContext, presence.models, nativeGate).also { created ->
                if (!created.id.startsWith("paddle")) {
                    val error = IllegalArgumentException("Paddle bridge must preserve its distinct engine identity")
                    runCatching { created.close() }.onFailure(error::addSuppressed)
                    throw error
                }
            }
        }
        if (engine is NotAvailable) Log.d("WineScanner", engine.reason)
        return engine
    }
}
