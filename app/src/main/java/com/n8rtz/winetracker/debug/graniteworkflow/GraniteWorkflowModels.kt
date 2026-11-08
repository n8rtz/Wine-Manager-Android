package com.n8rtz.winetracker.debug.graniteworkflow

import android.graphics.Bitmap
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Identifies which OCR engine produced an [OcrBlock].
 */
enum class OcrEngineSource(val id: String, val label: String) {
    ML_KIT("mlkit", "ML Kit"),
    PP_OCRV6("ppocrv6", "PP-OCRv6")
}

/**
 * Normalized OCR text block produced by either Google ML Kit v2 or PP-OCRv6 ONNX.
 *
 * [normBbox] holds normalized `[0.0..1.0]` `[left, top, right, bottom]` coordinates on the
 * preprocessed image, while [xCenter], [yCenter], [width], and [height] expose the exact
 * normalized `[0.0..1.0]` `[x_center, y_center, width, height]` representation used for sorting
 * and Granite LLM spatial prompting.
 */
data class OcrBlock(
    val text: String,
    val confidence: Float,
    val normBbox: RectF,
    val source: OcrEngineSource = OcrEngineSource.ML_KIT
) {
    val xCenter: Float
        get() = normBbox.centerX().coerceIn(0f, 1f)

    val yCenter: Float
        get() = normBbox.centerY().coerceIn(0f, 1f)

    val width: Float
        get() = normBbox.width().coerceIn(0f, 1f)

    val height: Float
        get() = normBbox.height().coerceIn(0f, 1f)

    /** Returns `[x_center, y_center, width, height]` normalized to `[0.0..1.0]`. */
    fun normalizedCenterWh(): FloatArray = floatArrayOf(xCenter, yCenter, width, height)

    /** Formatted coordinate tag `[x=0.500, y=0.220, w=0.640, h=0.050]` for prompts and UI inspection. */
    fun formatCenterWhTag(): String = String.format(
        Locale.US,
        "[x=%.3f, y=%.3f, w=%.3f, h=%.3f]",
        xCenter,
        yCenter,
        width,
        height
    )

    companion object {
        /**
         * Construct an [OcrBlock] directly from normalized `[x_center, y_center, width, height]`
         * coordinates in `[0.0..1.0]`.
         */
        fun fromCenterWh(
            text: String,
            confidence: Float,
            xCenter: Float,
            yCenter: Float,
            width: Float,
            height: Float,
            source: OcrEngineSource = OcrEngineSource.ML_KIT
        ): OcrBlock {
            val clampedW = width.coerceIn(0f, 1f)
            val clampedH = height.coerceIn(0f, 1f)
            val halfW = clampedW / 2f
            val halfH = clampedH / 2f
            val cx = xCenter.coerceIn(0f, 1f)
            val cy = yCenter.coerceIn(0f, 1f)
            val left = (cx - halfW).coerceIn(0f, 1f)
            val top = (cy - halfH).coerceIn(0f, 1f)
            val right = (cx + halfW).coerceIn(left, 1f)
            val bottom = (cy + halfH).coerceIn(top, 1f)
            return OcrBlock(
                text = text.trim(),
                confidence = confidence.coerceIn(0f, 1f),
                normBbox = RectF(left, top, right, bottom),
                source = source
            )
        }

        /**
         * Construct an [OcrBlock] from pixel bounding coordinates and image dimensions.
         */
        fun fromPixelBounds(
            text: String,
            confidence: Float,
            pixelLeft: Float,
            pixelTop: Float,
            pixelRight: Float,
            pixelBottom: Float,
            imageWidth: Int,
            imageHeight: Int,
            source: OcrEngineSource = OcrEngineSource.ML_KIT
        ): OcrBlock {
            if (imageWidth <= 0 || imageHeight <= 0) {
                return OcrBlock(text.trim(), confidence.coerceIn(0f, 1f), RectF(0f, 0f, 0f, 0f), source)
            }
            val normLeft = (minOf(pixelLeft, pixelRight) / imageWidth.toFloat()).coerceIn(0f, 1f)
            val normTop = (minOf(pixelTop, pixelBottom) / imageHeight.toFloat()).coerceIn(0f, 1f)
            val normRight = (maxOf(pixelLeft, pixelRight) / imageWidth.toFloat()).coerceIn(normLeft, 1f)
            val normBottom = (maxOf(pixelTop, pixelBottom) / imageHeight.toFloat()).coerceIn(normTop, 1f)
            return OcrBlock(
                text = text.trim(),
                confidence = confidence.coerceIn(0f, 1f),
                normBbox = RectF(normLeft, normTop, normRight, normBottom),
                source = source
            )
        }

        /**
         * Standard top-to-bottom (`y_center`), then left-to-right (`x_center`) comparator.
         */
        val TOP_TO_BOTTOM_LEFT_TO_RIGHT: Comparator<OcrBlock> =
            compareBy<OcrBlock> { it.yCenter }.thenBy { it.xCenter }
    }
}

/**
 * UI ablation toggle for the Dual-OCR engine.
 */
enum class OcrAblationMode(val displayLabel: String) {
    DUAL("Dual (ML Kit + PP-OCRv6)"),
    ML_KIT_ONLY("ML Kit Only"),
    PP_OCRV6_ONLY("PP-OCRv6 Only");

    val runsMlKit: Boolean
        get() = this == DUAL || this == ML_KIT_ONLY

    val runsPpOcrV6: Boolean
        get() = this == DUAL || this == PP_OCRV6_ONLY
}

/**
 * Strict JSON schema representation for the Granite 4.2 wine label reconciliation output.
 */
data class GraniteWineLabelJson(
    val producerOrWinery: String? = null,
    val wineNameOrCuvee: String? = null,
    val vintage: Int? = null,
    val varietals: List<String> = emptyList(),
    val appellationOrRegion: String? = null,
    val country: String? = null,
    val classification: String? = null,
    val abvPercentage: Double? = null,
    val bottleVolumeMl: Int? = null,
    val isNonVintage: Boolean = false,
    val ocrDiscrepanciesResolved: List<String> = emptyList(),
    val confidenceNotes: String? = null,
    val region: String? = null,
    val subregion: String? = null
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("ocr_discrepancies_resolved", JSONArray(ocrDiscrepanciesResolved))
        obj.put("confidence_notes", confidenceNotes ?: JSONObject.NULL)
        obj.put("country", country ?: JSONObject.NULL)
        obj.put("region", region ?: JSONObject.NULL)
        obj.put("subregion", subregion ?: JSONObject.NULL)
        obj.put("appellation_or_region", appellationOrRegion ?: subregion ?: region ?: JSONObject.NULL)
        obj.put("varietals", JSONArray(varietals))
        obj.put("classification", classification ?: JSONObject.NULL)
        obj.put("vintage", vintage ?: JSONObject.NULL)
        obj.put("is_non_vintage", isNonVintage)
        obj.put("abv_percentage", abvPercentage ?: JSONObject.NULL)
        obj.put("bottle_volume_ml", bottleVolumeMl ?: JSONObject.NULL)
        obj.put("producer_or_winery", producerOrWinery ?: JSONObject.NULL)
        obj.put("wine_name_or_cuvee", wineNameOrCuvee ?: JSONObject.NULL)
        return obj
    }

    fun toPrettyJson(indentSpaces: Int = 2): String = toJsonObject().toString(indentSpaces)

    companion object {
        val REQUIRED_KEYS = listOf(
            "producer_or_winery",
            "wine_name_or_cuvee",
            "vintage",
            "varietals",
            "appellation_or_region",
            "country",
            "classification",
            "abv_percentage",
            "bottle_volume_ml",
            "is_non_vintage",
            "ocr_discrepancies_resolved",
            "confidence_notes"
        )

        fun fromJsonString(rawJson: String): GraniteWineLabelJson {
            val cleaned = extractJsonObjectSubstring(rawJson)
            val obj = JSONObject(cleaned)

            fun optNullableString(key: String): String? {
                if (!obj.has(key) || obj.isNull(key)) return null
                val value = obj.optString(key, "").trim()
                return value.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            }

            fun optNullableInt(key: String): Int? {
                if (!obj.has(key) || obj.isNull(key)) return null
                val raw = obj.opt(key)
                return when (raw) {
                    is Number -> raw.toInt()
                    is String -> raw.trim().toIntOrNull()
                    else -> null
                }
            }

            fun optNullableDouble(key: String): Double? {
                if (!obj.has(key) || obj.isNull(key)) return null
                val raw = obj.opt(key)
                return when (raw) {
                    is Number -> raw.toDouble().takeIf { it.isFinite() }
                    is String -> raw.trim().removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                    else -> null
                }
            }

            fun optStringList(key: String): List<String> {
                val arr = obj.optJSONArray(key) ?: return emptyList()
                val result = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    if (!arr.isNull(i)) {
                        val item = arr.optString(i, "").trim()
                        if (item.isNotEmpty() && !item.equals("null", ignoreCase = true)) {
                            result.add(item)
                        }
                    }
                }
                return result
            }

            val vintageVal = optNullableInt("vintage")
            val nonVintageVal = if (obj.has("is_non_vintage") && !obj.isNull("is_non_vintage")) {
                obj.optBoolean("is_non_vintage", false)
            } else {
                false
            }

            return GraniteWineLabelJson(
                producerOrWinery = optNullableString("producer_or_winery"),
                wineNameOrCuvee = optNullableString("wine_name_or_cuvee"),
                vintage = if (nonVintageVal) null else vintageVal,
                varietals = optStringList("varietals"),
                appellationOrRegion = optNullableString("appellation_or_region"),
                country = optNullableString("country"),
                classification = optNullableString("classification"),
                abvPercentage = optNullableDouble("abv_percentage"),
                bottleVolumeMl = optNullableInt("bottle_volume_ml"),
                isNonVintage = nonVintageVal,
                ocrDiscrepanciesResolved = optStringList("ocr_discrepancies_resolved"),
                confidenceNotes = optNullableString("confidence_notes"),
                region = optNullableString("region"),
                subregion = optNullableString("subregion")
            )
        }

        fun extractJsonObjectSubstring(text: String): String {
            val closeTagRegex = Regex("</think>", RegexOption.IGNORE_CASE)
            val lastCloseMatch = closeTagRegex.findAll(text).lastOrNull()
            val afterCloseThink = if (lastCloseMatch != null) {
                text.substring(lastCloseMatch.range.last + 1)
            } else {
                text
            }
            val withoutThink = afterCloseThink
                .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
                .trim()
            val start = withoutThink.indexOf('{')
            val end = withoutThink.lastIndexOf('}')
            require(start >= 0 && end > start) { "No JSON object found in LLM output" }
            return withoutThink.substring(start, end + 1)
        }
    }
}

/**
 * Per-stage latency and memory telemetry for the 4-Section Results Inspector.
 */
data class WorkflowTimingAndMemory(
    val preprocessMs: Long = 0L,
    val mlKitMs: Long = 0L,
    val ppOcrV6Ms: Long = 0L,
    val dbRagMs: Long = 0L,
    val graniteMs: Long = 0L,
    val totalMs: Long = 0L,
    val peakJvmHeapMb: Double = 0.0,
    val peakNativeHeapMb: Double = 0.0,
    val peakCombinedRamMb: Double = 0.0
)

/**
 * Status of a model file or bundled engine asset.
 */
data class ModelFileSlotStatus(
    val slotName: String,
    val fileName: String,
    val absolutePath: String,
    val present: Boolean,
    val sizeBytes: Long,
    val required: Boolean,
    val isBuiltInShared: Boolean = false,
    val notes: String = ""
) {
    val sizeFormatted: String
        get() = when {
            !present -> "Missing"
            sizeBytes <= 0L && isBuiltInShared -> "Bundled in APK"
            sizeBytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
            sizeBytes >= 1024L -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
}

/**
 * Overall status of the models and offline databases used by the workflow.
 *
 * ML Kit, PP-OCRv6, and the Producer/Wine/Appellation databases reuse the app's existing bundled
 * assets (`MlKitOcrEngine`, `text-lab-paddle-v1`, `producer-gazetteer-expanded-v1`,
 * `producer-gazetteer-v1`, `ReferenceWineCatalog`, and `SqliteWineKnowledgeBase`) so no second
 * copy or extra download is required. Only `granite-4.2-3b-Q4_K_M.gguf` needs to be imported.
 */
data class WorkflowModelsStatus(
    val graniteDirectoryPath: String = "",
    val sharedPpOcrDirectoryPath: String = "",
    val graniteGguf: ModelFileSlotStatus = ModelFileSlotStatus(
        slotName = "Granite 4.2 3B GGUF",
        fileName = "granite-4.2-3b-Q4_K_M.gguf",
        absolutePath = "",
        present = false,
        sizeBytes = 0L,
        required = true
    ),
    val mlKitEngine: ModelFileSlotStatus = ModelFileSlotStatus(
        slotName = "Google ML Kit v2 (Latin)",
        fileName = "Bundled com.google.mlkit:text-recognition",
        absolutePath = "APK bundled",
        present = true,
        sizeBytes = 0L,
        required = true,
        isBuiltInShared = true,
        notes = "Reuses existing MlKitOcrEngine"
    ),
    val ppOcrDetOnnx: ModelFileSlotStatus = ModelFileSlotStatus(
        slotName = "PP-OCRv6 Detection ONNX",
        fileName = "v6-medium-det.onnx",
        absolutePath = "",
        present = false,
        sizeBytes = 62032837L,
        required = true,
        isBuiltInShared = true,
        notes = "Reuses existing text-lab-paddle-v1"
    ),
    val ppOcrRecOnnx: ModelFileSlotStatus = ModelFileSlotStatus(
        slotName = "PP-OCRv6 Recognition ONNX",
        fileName = "v6-medium-rec.onnx",
        absolutePath = "",
        present = false,
        sizeBytes = 76554979L,
        required = true,
        isBuiltInShared = true,
        notes = "Reuses existing text-lab-paddle-v1"
    ),
    val ppOcrDict: ModelFileSlotStatus = ModelFileSlotStatus(
        slotName = "PP-OCRv6 Dictionary",
        fileName = "ppocrv6_dict.utf8",
        absolutePath = "",
        present = false,
        sizeBytes = 74947L,
        required = true,
        isBuiltInShared = true,
        notes = "Reuses existing text-lab-paddle-v1"
    ),
    val producerGazetteerDb: ModelFileSlotStatus = ModelFileSlotStatus(
        slotName = "Expanded Producer Gazetteer (SQLite)",
        fileName = "producers-expanded.sqlite",
        absolutePath = "assets/producer-gazetteer-expanded-v1",
        present = true,
        sizeBytes = 0L,
        required = true,
        isBuiltInShared = true,
        notes = "Shared with GLiNER lab (LWIN + supplemental)"
    ),
    val linkedWineDb: ModelFileSlotStatus = ModelFileSlotStatus(
        slotName = "Linked Wine Index (SQLite)",
        fileName = "wines.sqlite",
        absolutePath = "assets/producer-gazetteer-v1",
        present = true,
        sizeBytes = 0L,
        required = true,
        isBuiltInShared = true,
        notes = "Shared with GLiNER lab (LWIN7 producer-wine links)"
    ),
    val appellationCatalogDb: ModelFileSlotStatus = ModelFileSlotStatus(
        slotName = "Appellation & Wine Knowledge Base",
        fileName = "SqliteWineKnowledgeBase + ReferenceWineCatalog + BundledOrigins",
        absolutePath = "APK bundled",
        present = true,
        sizeBytes = 0L,
        required = true,
        isBuiltInShared = true,
        notes = "Shared with GLiNER lab (French AOC, Italian DOC/DOCG, US AVA, Grapes)"
    ),
    val nativeLlamaJniLoaded: Boolean = false
) {
    val isPpOcrReady: Boolean
        get() = ppOcrDetOnnx.present && ppOcrRecOnnx.present && ppOcrDict.present

    val isGraniteReady: Boolean
        get() = graniteGguf.present

    fun missingModelsPrompt(ocrMode: OcrAblationMode): String? {
        val missing = mutableListOf<String>()
        if (!graniteGguf.present) {
            missing.add("granite-4.2-3b-Q4_K_M.gguf")
        }
        return if (missing.isEmpty()) {
            null
        } else {
            "Import Model Files to Run: place or import ${missing.joinToString(", ")} (ML Kit, PP-OCRv6 & Producer/Wine/Appellation DBs reuse existing app files)"
        }
    }
}

/**
 * Complete result of a Dual-OCR + Database RAG + Granite 4.2 reconciliation run.
 */
data class GraniteWorkflowRunResult(
    val preprocessedBitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val preprocessedWidth: Int,
    val preprocessedHeight: Int,
    val claheApplied: Boolean,
    val ocrMode: OcrAblationMode,
    val thinkModeEnabled: Boolean,
    val mlKitBlocks: List<OcrBlock>,
    val ppOcrV6Blocks: List<OcrBlock>,
    val timingAndMemory: WorkflowTimingAndMemory,
    val structuredJson: GraniteWineLabelJson,
    val prettyJsonText: String,
    val rawThinkingTrace: String?,
    val rawLlmResponse: String,
    val executionBackendNote: String,
    val ppOcrStatusNote: String,
    val ragContext: GraniteDatabaseRagContext = GraniteDatabaseRagContext(enabled = false)
)
