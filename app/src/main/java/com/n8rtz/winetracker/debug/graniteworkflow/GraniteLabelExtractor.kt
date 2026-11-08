package com.n8rtz.winetracker.debug.graniteworkflow

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Result of Granite 4.2 3B GGUF label reconciliation.
 */
data class GraniteExtractionOutcome(
    val structuredJson: GraniteWineLabelJson,
    val prettyJson: String,
    val rawThinkTrace: String?,
    val rawLlmOutput: String,
    val elapsedMs: Long,
    val backendUsed: String,
    val usedNativeLlm: Boolean = false,
    val nativeFallbackReason: String? = null
)

/**
 * Isolated on-device LLM reconciliation engine (`GraniteLabelExtractor`) for IBM Granite 4.2 3B
 * (`granite-4.2-3b-Q4_K_M.gguf`) loaded from `context.getExternalFilesDir("models/granite")`.
 *
 * - Default `<think>` mode is `OFF` (`thinkModeEnabled = false`).
 * - Enforces strict JSON output matching the 12-field wine label schema via GBNF grammar.
 * - Accepts Candidate Retrieval (RAG) context from `GraniteDatabaseRagRetriever` (the exact same
 *   SQLite Producer Gazetteer, Linked Wine Index, `SqliteWineKnowledgeBase`, `ReferenceWineCatalog`,
 *   `BundledOrigins`, and `BundledGrapes` databases used by `GLiNER lane lab`).
 * - Reconciles conflicts between Google ML Kit v2 and PP-OCRv6 using normalized spatial `[x, y, w, h]`
 *   coordinates and database candidates, preserves European diacritics, never guesses missing fields,
 *   and enforces the `"NV"` / `"Non-Vintage"` / sparkling wine non-vintage rule.
 */
class GraniteLabelExtractor : AutoCloseable {

    suspend fun extract(
        context: Context,
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ablationMode: OcrAblationMode,
        thinkModeEnabled: Boolean = DEFAULT_THINK_MODE_ENABLED,
        ragContext: GraniteDatabaseRagContext = GraniteDatabaseRagContext(enabled = false)
    ): GraniteExtractionOutcome = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        val resolvedGguf = GraniteModelStorage.resolveGraniteGgufFile(context)
        val ggufFile = if (resolvedGguf.isFile && resolvedGguf.length() >= GraniteModelStorage.MIN_VALID_GGUF_BYTES) {
            withContext(Dispatchers.IO) {
                GraniteModelStorage.ensureDirectFilesystemGguf(context, resolvedGguf)
            }
        } else {
            resolvedGguf
        }
        val prompt = buildGranitePrompt(
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = ppOcrV6Blocks,
            ablationMode = ablationMode,
            thinkModeEnabled = thinkModeEnabled,
            ragContext = ragContext
        )
        val grammar = if (thinkModeEnabled) {
            WINE_LABEL_THINK_PLUS_JSON_GBNF
        } else {
            WINE_LABEL_JSON_GBNF
        }

        var nativeFallbackReason: String? = null
        if (!GraniteLlamaBridge.isNativeLibraryLoaded() && ggufFile.isFile && ggufFile.length() > 0L) {
            val loadErr = GraniteLlamaBridge.lastNativeError().ifBlank { "libgranite_llama_jni.so not loaded" }
            nativeFallbackReason = "JNI library load failed: $loadErr"
        } else if (GraniteLlamaBridge.isNativeLibraryLoaded() && ggufFile.isFile && ggufFile.length() > 0L) {
            val rawOutput = runCatching {
                GraniteLlamaBridge.generateWithGguf(
                    ggufFile = ggufFile,
                    prompt = prompt,
                    gbnfGrammar = grammar,
                    maxTokens = if (thinkModeEnabled) 360 else 240,
                    temperature = 0.0f
                )
            }.onFailure { err ->
                nativeFallbackReason = err.message ?: GraniteLlamaBridge.lastNativeError()
            }.getOrNull()

            if (!rawOutput.isNullOrBlank()) {
                val thinkTrace = extractThinkContent(rawOutput)
                val parsed = runCatching { GraniteWineLabelJson.fromJsonString(rawOutput) }.getOrNull()
                if (parsed != null) {
                    val enforced = enforcePostRules(parsed, mlKitBlocks, ppOcrV6Blocks, ragContext)
                    val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                    val ragTag = if (ragContext.enabled) " + DB RAG" else ""
                    return@withContext GraniteExtractionOutcome(
                        structuredJson = enforced,
                        prettyJson = enforced.toPrettyJson(2),
                        rawThinkTrace = thinkTrace,
                        rawLlmOutput = rawOutput,
                        elapsedMs = elapsed,
                        backendUsed = "llama.cpp JNI (${ggufFile.name})$ragTag",
                        usedNativeLlm = true,
                        nativeFallbackReason = null
                    )
                } else {
                    nativeFallbackReason = "JSON parse fallback (${rawOutput.take(80)})"
                }
            } else if (nativeFallbackReason == null) {
                nativeFallbackReason = GraniteLlamaBridge.lastNativeError().ifBlank { "empty GGUF completion" }
            }
        }

        // Deterministic spatial + diacritic + Database RAG reconciliation following the exact Granite
        // system prompt rules when the native llama.cpp .so is not compiled into the current APK or before GGUF execution.
        val fallback = reconcileSpatialDeterministic(
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = ppOcrV6Blocks,
            ablationMode = ablationMode,
            thinkModeEnabled = thinkModeEnabled,
            ggufFile = ggufFile,
            ragContext = ragContext
        )
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
        val ragTag = if (ragContext.enabled) " + DB RAG" else ""
        GraniteExtractionOutcome(
            structuredJson = fallback.first,
            prettyJson = fallback.first.toPrettyJson(2),
            rawThinkTrace = fallback.second,
            rawLlmOutput = if (fallback.second != null) {
                "<think>\n${fallback.second}\n</think>\n${fallback.first.toPrettyJson(2)}"
            } else {
                fallback.first.toPrettyJson(2)
            },
            elapsedMs = elapsed,
            backendUsed = when {
                nativeFallbackReason != null ->
                    "Spatial + DB RAG Reconciler$ragTag (JNI fallback: $nativeFallbackReason)"
                ggufFile.isFile && ggufFile.length() > 0L ->
                    "Spatial + DB RAG Reconciler$ragTag (${ggufFile.name} staged; build libgranite_llama_jni.so for native GGUF token generation)"
                else ->
                    "Spatial + DB RAG Reconciler$ragTag (import granite-4.2-3b-Q4_K_M.gguf into models/granite)"
            },
            usedNativeLlm = false,
            nativeFallbackReason = nativeFallbackReason
        )
    }

    /**
     * Builds the IBM Granite 4.2 prompt incorporating the System Prompt Rules, `<think>` toggle,
     * coordinate-tagged OCR blocks from ML Kit and PP-OCRv6, and the retrieved offline database
     * candidates (RAG context).
     */
    fun buildGranitePrompt(
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ablationMode: OcrAblationMode,
        thinkModeEnabled: Boolean = DEFAULT_THINK_MODE_ENABLED,
        ragContext: GraniteDatabaseRagContext = GraniteDatabaseRagContext(enabled = false)
    ): String {
        val alignedReadingLines = buildAlignedReadingLinesSection(
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = ppOcrV6Blocks,
            ablationMode = ablationMode
        )

        val thinkingDirective = if (thinkModeEnabled) {
            "Thinking ON: <=30 words inside <think>...</think>, then compact single-line JSON."
        } else {
            "Thinking OFF: emit compact single-line JSON immediately."
        }

        val hasHeaderOrWidthAnomaly =
            alignedReadingLines.contains("LOWERCASE_INITIAL_ON_HEADER") ||
                alignedReadingLines.contains("WIDTH_MISMATCH")

        val assistantPrefix = when {
            thinkModeEnabled ->
                "<|start_of_role|>assistant<|end_of_role|><think>\n"
            hasHeaderOrWidthAnomaly ->
                "<|start_of_role|>assistant<|end_of_role|>{\"ocr_discrepancies_resolved\":"
            else ->
                "<|start_of_role|>assistant<|end_of_role|>{\"ocr_discrepancies_resolved\":[],\"confidence_notes\":null,\"country\":"
        }

        return buildString {
            append("<|start_of_role|>system<|end_of_role|>")
            append(SYSTEM_PROMPT.trim())
            append("\n")
            append(thinkingDirective)
            append("<|end_of_text|>\n")
            append("<|start_of_role|>user<|end_of_role|>")
            append("=== Spatially Aligned Cross-Engine Reading Lines (${ablationMode.displayLabel}) ===\n")
            append(alignedReadingLines)
            append("\n=== Retrieved Offline Wine Database Candidates (RAG Context) ===\n")
            append(ragContext.toPromptRagSection())
            append("\nReturn compact JSON.")
            append("<|end_of_text|>\n")
            append(assistantPrefix)
        }
    }

    /**
     * Pairs spatially overlapping ML Kit and PP-OCRv6 blocks into top-to-bottom reading lines and
     * annotates each line with relative font height scale, vertical label zone, cross-engine
     * agreement/conflict status, and clipping/script anomaly warnings.
     */
    internal fun buildAlignedReadingLinesSection(
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ablationMode: OcrAblationMode
    ): String {
        val mlActive = if (ablationMode.runsMlKit) mlKitBlocks else emptyList()
        val ppActive = if (ablationMode.runsPpOcrV6) ppOcrV6Blocks else emptyList()
        val allActive = mlActive + ppActive
        if (allActive.isEmpty()) {
            return "(No OCR blocks detected)"
        }

        val sortedHeights = allActive.map { it.height.coerceAtLeast(0.01f) }.sorted()
        val medianH = sortedHeights[sortedHeights.size / 2].coerceAtLeast(0.025f)

        data class PairedLine(
            val yCenter: Float,
            val xCenter: Float,
            val maxHeight: Float,
            val mlBlock: OcrBlock?,
            val ppBlock: OcrBlock?
        )

        val usedPp = BooleanArray(ppActive.size)
        val pairedLines = mutableListOf<PairedLine>()

        for (ml in mlActive) {
            var bestIdx = -1
            var bestCost = Float.MAX_VALUE
            for (i in ppActive.indices) {
                if (usedPp[i]) continue
                val pp = ppActive[i]
                val dy = abs(ml.yCenter - pp.yCenter)
                val dx = abs(ml.xCenter - pp.xCenter)
                val maxH = maxOf(ml.height, pp.height, 0.04f)
                if (dy <= maxOf(0.055f, maxH * 0.85f) && dx <= 0.35f) {
                    val cost = dy * 2f + dx
                    if (cost < bestCost) {
                        bestCost = cost
                        bestIdx = i
                    }
                }
            }
            if (bestIdx >= 0) {
                usedPp[bestIdx] = true
                val pp = ppActive[bestIdx]
                pairedLines += PairedLine(
                    yCenter = (ml.yCenter + pp.yCenter) * 0.5f,
                    xCenter = (ml.xCenter + pp.xCenter) * 0.5f,
                    maxHeight = maxOf(ml.height, pp.height),
                    mlBlock = ml,
                    ppBlock = pp
                )
            } else {
                pairedLines += PairedLine(
                    yCenter = ml.yCenter,
                    xCenter = ml.xCenter,
                    maxHeight = ml.height,
                    mlBlock = ml,
                    ppBlock = null
                )
            }
        }

        for (i in ppActive.indices) {
            if (!usedPp[i]) {
                val pp = ppActive[i]
                pairedLines += PairedLine(
                    yCenter = pp.yCenter,
                    xCenter = pp.xCenter,
                    maxHeight = pp.height,
                    mlBlock = null,
                    ppBlock = pp
                )
            }
        }

        val filteredPaired = pairedLines.filterNot { line ->
            val combined = "${line.mlBlock?.text.orEmpty()} ${line.ppBlock?.text.orEmpty()}".trim()
            BOILERPLATE_SKIP_REGEX.containsMatchIn(combined) &&
                !ABV_REGEX.containsMatchIn(combined) &&
                !VOLUME_ML_REGEX.containsMatchIn(combined) &&
                !VOLUME_CL_REGEX.containsMatchIn(combined) &&
                !VOLUME_L_REGEX.containsMatchIn(combined)
        }.ifEmpty { pairedLines }

        // Keep at most 22 most informative lines on dense photos so prompts stay ~400-550 tokens
        val cappedPaired = if (filteredPaired.size <= 22) {
            filteredPaired
        } else {
            filteredPaired
                .sortedByDescending { line ->
                    val combined = "${line.mlBlock?.text.orEmpty()} ${line.ppBlock?.text.orEmpty()}"
                    val hasFactCue = VINTAGE_YEAR_REGEX.containsMatchIn(combined) ||
                        ABV_REGEX.containsMatchIn(combined) ||
                        VOLUME_ML_REGEX.containsMatchIn(combined) ||
                        VOLUME_CL_REGEX.containsMatchIn(combined)
                    val zoneBonus = if (line.yCenter < 0.72f) 1.0f else 0.0f
                    val factBonus = if (hasFactCue) 1.5f else 0.0f
                    (line.maxHeight / medianH) + zoneBonus + factBonus
                }
                .take(22)
        }

        val ordered = cappedPaired.sortedWith(compareBy<PairedLine> { it.yCenter }.thenBy { it.xCenter })
        return ordered.mapIndexed { idx, line ->
            val zone = when {
                line.yCenter < 0.36f -> "TOP_HEADER"
                line.yCenter < 0.58f -> "UPPER_MAIN"
                line.yCenter < 0.78f -> "MID_BODY"
                else -> "BOTTOM_LEGAL"
            }
            val scale = (line.maxHeight / medianH).coerceIn(0.3f, 5.0f)
            val prominence = when {
                scale >= 1.55f -> "PRIMARY_DISPLAY"
                scale >= 1.15f -> "SUBHEADER"
                scale >= 0.75f -> "BODY"
                else -> "FINE_PRINT"
            }
            val anomalies = mutableListOf<String>()
            val primaryText = (line.mlBlock?.text ?: line.ppBlock?.text).orEmpty().trim()
            val firstAlpha = primaryText.firstOrNull { it.isLetter() }
            if ((zone == "TOP_HEADER" || prominence == "PRIMARY_DISPLAY") &&
                firstAlpha != null &&
                firstAlpha.isLowerCase()
            ) {
                anomalies += "LOWERCASE_INITIAL_ON_HEADER"
            }
            if (line.mlBlock != null && line.ppBlock != null) {
                val minW = minOf(line.mlBlock.width, line.ppBlock.width).coerceAtLeast(0.01f)
                val maxW = maxOf(line.mlBlock.width, line.ppBlock.width)
                if (maxW / minW >= 1.25f) {
                    anomalies += "WIDTH_MISMATCH"
                }
            }
            val leftEdge = minOf(line.mlBlock?.normBbox?.left ?: 1f, line.ppBlock?.normBbox?.left ?: 1f)
            val rightEdge = maxOf(line.mlBlock?.normBbox?.right ?: 0f, line.ppBlock?.normBbox?.right ?: 0f)
            if (leftEdge <= 0.05f || rightEdge >= 0.95f) {
                anomalies += "EDGE_CLIP"
            }

            val readingsText = when {
                line.mlBlock != null && line.ppBlock != null -> {
                    val mlText = compactOcrText(line.mlBlock.text)
                    val ppText = compactOcrText(line.ppBlock.text)
                    if (mlText.equals(ppText, ignoreCase = true) && anomalies.isEmpty()) {
                        val preferred = if (mlText.any { it.isLowerCase() } && ppText.all { !it.isLowerCase() }) mlText else ppText
                        "\"$preferred\""
                    } else {
                        "CONFLICT: MLKit=\"$mlText\" (w=${String.format(Locale.US, "%.2f", line.mlBlock.width)}) vs PP-OCRv6=\"$ppText\" (w=${String.format(Locale.US, "%.2f", line.ppBlock.width)})"
                    }
                }
                line.mlBlock != null ->
                    "MLKit=\"${compactOcrText(line.mlBlock.text)}\""
                else -> {
                    val pp = line.ppBlock!!
                    "PP-OCRv6=\"${compactOcrText(pp.text)}\""
                }
            }
            val anomalySuffix = if (anomalies.isEmpty()) "" else " [${anomalies.joinToString(",")}]"
            String.format(
                Locale.US,
                "L%d [y=%.2f, %.1fx %s, %s]: %s%s",
                idx + 1,
                line.yCenter,
                scale,
                prominence,
                zone,
                readingsText,
                anomalySuffix
            )
        }.joinToString("\n")
    }

    private fun compactOcrText(raw: String): String {
        val collapsed = raw.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= 90) collapsed else collapsed.take(90)
    }

    internal fun extractThinkContent(rawOutput: String): String? {
        val regex = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
        val match = regex.find(rawOutput)
        if (match != null) {
            return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
        val closeIdx = rawOutput.indexOf("</think>", ignoreCase = true)
        if (closeIdx > 0) {
            return rawOutput.substring(0, closeIdx).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * Enforces mandatory schema invariants (such as the NV / sparkling wine non-vintage rule)
     * and grounds fields against the retrieved database RAG candidates when available.
     */
    internal fun enforcePostRules(
        input: GraniteWineLabelJson,
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ragContext: GraniteDatabaseRagContext = GraniteDatabaseRagContext(enabled = false)
    ): GraniteWineLabelJson {
        val allText = (mlKitBlocks.map { it.text } + ppOcrV6Blocks.map { it.text }).joinToString(" ")
        val hasExplicitNv = EXPLICIT_NV_REGEX.containsMatchIn(allText)
        val isSparklingCue = SPARKLING_CUE_REGEX.containsMatchIn(allText)
        val ragVintage = ragContext.spatialContenderFields["vintage"]?.trim()?.toIntOrNull()
        val extractedYear = input.vintage ?: ragVintage ?: extractVintageYear(allText)

        val finalIsNonVintage = when {
            hasExplicitNv -> true
            input.isNonVintage -> true
            isSparklingCue && extractedYear == null -> true
            else -> false
        }
        val finalVintage = if (finalIsNonVintage) null else extractedYear

        if (!ragContext.enabled) {
            return input.copy(
                vintage = finalVintage,
                isNonVintage = finalIsNonVintage
            )
        }

        fun normKey(s: String?): String =
            Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        val nonProducerNorms = (
            ragContext.matchedGrapes +
                ragContext.matchedStyles +
                ragContext.appellationCandidates.flatMap { listOf(it.appellationOrSubregion, it.region, it.country) }
            ).map(::normKey).filter(String::isNotEmpty).toSet()

        val llmProducerKey = normKey(input.producerOrWinery)
        val matchedRagProducer = if (llmProducerKey.isNotEmpty()) {
            ragContext.producerCandidates.firstOrNull {
                normKey(it.name) == llmProducerKey ||
                    (it.distance <= 1 && normKey(it.matchedSpan) == llmProducerKey)
            }?.name
        } else {
            null
        }
        val spProducer = ragContext.spatialContenderFields["producer"]?.takeIf { it.isNotBlank() }
        val finalProducer = when {
            matchedRagProducer != null -> matchedRagProducer
            !input.producerOrWinery.isNullOrBlank() -> input.producerOrWinery
            spProducer != null -> spProducer
            else -> ragContext.producerCandidates.firstOrNull {
                it.distance <= 1 &&
                    normKey(it.name) !in nonProducerNorms &&
                    normKey(it.matchedSpan) !in nonProducerNorms
            }?.name
        }

        val finalProducerKey = normKey(finalProducer)
        val llmWineKey = normKey(input.wineNameOrCuvee)
        val matchedRagWine = if (llmWineKey.isNotEmpty()) {
            ragContext.linkedWineCandidates.firstOrNull {
                normKey(it.name) == llmWineKey ||
                    (it.distance <= 1 && normKey(it.matchedSpan) == llmWineKey)
            }?.name
        } else {
            null
        }
        val spWineName = ragContext.spatialContenderFields["wine_name"]?.takeIf { it.isNotBlank() }
        val finalWineName = when {
            matchedRagWine != null -> matchedRagWine
            !input.wineNameOrCuvee.isNullOrBlank() -> input.wineNameOrCuvee
            spWineName != null -> spWineName
            else -> ragContext.linkedWineCandidates.firstOrNull {
                it.distance <= 1 && normKey(it.name) != finalProducerKey
            }?.name
        }

        val llmAppellationKey = normKey(input.appellationOrRegion ?: input.subregion ?: input.region)
        val matchedAppellationCand = if (llmAppellationKey.isNotEmpty()) {
            ragContext.appellationCandidates.firstOrNull {
                normKey(it.appellationOrSubregion) == llmAppellationKey ||
                    normKey(it.region) == llmAppellationKey ||
                    normKey(it.matchedSpan) == llmAppellationKey
            }
        } else {
            ragContext.appellationCandidates.firstOrNull()
        }

        val spCountry = ragContext.spatialContenderFields["country"]?.takeIf { it.isNotBlank() }
            ?: matchedAppellationCand?.country?.takeIf { it.isNotBlank() }
        val spRegion = ragContext.spatialContenderFields["region"]?.takeIf { it.isNotBlank() }
            ?: matchedAppellationCand?.region?.takeIf { it.isNotBlank() }
        val spSubregion = ragContext.spatialContenderFields["subregion"]?.takeIf { it.isNotBlank() }
            ?: matchedAppellationCand?.appellationOrSubregion?.takeIf { it.isNotBlank() && it != spRegion }

        val finalCountry = input.country?.takeIf { it.isNotBlank() } ?: spCountry
        val finalRegion = input.region?.takeIf { it.isNotBlank() } ?: spRegion
        val finalSubregion = input.subregion?.takeIf { it.isNotBlank() } ?: spSubregion
        val finalAppellation = input.appellationOrRegion?.takeIf { it.isNotBlank() } ?: finalSubregion ?: finalRegion
        val finalVarietals = (input.varietals + ragContext.matchedGrapes).distinct()
        val updatedDiscrepancies = input.ocrDiscrepanciesResolved.toMutableList()
        if (!input.producerOrWinery.isNullOrBlank() &&
            finalProducer != null &&
            !finalProducer.equals(input.producerOrWinery, ignoreCase = true)
        ) {
            val note = "Database RAG canonicalized producer '${input.producerOrWinery}' -> '$finalProducer' (ProducerGazetteer)"
            if (note !in updatedDiscrepancies) {
                updatedDiscrepancies += note
            }
        }

        return input.copy(
            producerOrWinery = finalProducer,
            wineNameOrCuvee = finalWineName,
            vintage = finalVintage,
            varietals = finalVarietals,
            appellationOrRegion = finalAppellation,
            country = finalCountry,
            isNonVintage = finalIsNonVintage,
            ocrDiscrepanciesResolved = updatedDiscrepancies,
            region = finalRegion,
            subregion = finalSubregion
        )
    }

    private fun reconcileSpatialDeterministic(
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ablationMode: OcrAblationMode,
        thinkModeEnabled: Boolean,
        ggufFile: File,
        ragContext: GraniteDatabaseRagContext
    ): Pair<GraniteWineLabelJson, String?> {
        val discrepancies = mutableListOf<String>()
        val reasoningSteps = mutableListOf<String>()

        // Pair spatially overlapping lines from ML Kit and PP-OCRv6 (|dy| < 0.06, horizontal overlap)
        val mergedLines = mergeAndReconcileLines(mlKitBlocks, ppOcrV6Blocks, discrepancies, reasoningSteps)
        val fullText = mergedLines.joinToString(" ") { it.text }

        // 1. Vintage & Non-Vintage rule (combining OCR + Database RAG vintage recovery)
        val hasExplicitNv = EXPLICIT_NV_REGEX.containsMatchIn(fullText)
        val isSparkling = SPARKLING_CUE_REGEX.containsMatchIn(fullText)
        val ragVintage = if (ragContext.enabled) {
            ragContext.spatialContenderFields["vintage"]?.trim()?.toIntOrNull()
        } else {
            null
        }
        val detectedYear = if (hasExplicitNv) null else (ragVintage ?: extractVintageYear(fullText))
        val isNonVintage = hasExplicitNv || (isSparkling && detectedYear == null)
        val vintage = if (isNonVintage) null else detectedYear
        if (isNonVintage) {
            reasoningSteps.add("Detected NV / Non-Vintage or dateless sparkling wine -> vintage=null, is_non_vintage=true.")
        } else if (vintage != null) {
            reasoningSteps.add("Detected vintage year $vintage.")
        }

        // 2. ABV percentage (typically bottom fine print, e.g. "13.5% ALC./VOL." or "14% vol")
        val abv = extractAbvPercentage(mergedLines)
        if (abv != null) {
            reasoningSteps.add("Extracted ABV percentage $abv% from bottom/fine-print lines.")
        }

        // 3. Bottle volume in mL (e.g. "750 ml", "75 cl" -> 750, "1.5 L" -> 1500, "375 ml" -> 375)
        val volumeMl = extractBottleVolumeMl(mergedLines)
        if (volumeMl != null) {
            reasoningSteps.add("Extracted bottle volume ${volumeMl}mL.")
        }

        // 4. Varietals (combining BundledGrapes database matches + local varietal rules)
        val varietals = (ragContext.matchedGrapes + extractVarietals(mergedLines)).distinct()
        if (varietals.isNotEmpty()) {
            reasoningSteps.add("Matched varietals (BundledGrapes + OCR): ${varietals.joinToString(", ")}.")
        }

        // 5. Classification (AOC, DOCG, Reserva, Grand Cru, Spätlese, BundledWineStyles, etc.)
        val classification = extractClassification(mergedLines) ?: ragContext.matchedStyles.firstOrNull()
        if (classification != null) {
            reasoningSteps.add("Matched wine classification/style: $classification.")
        }

        // 6. Appellation / Region / Subregion / Country (grounding with Database RAG when enabled)
        val (localAppellation, inferredCountry) = extractAppellationAndCountry(mergedLines)
        val explicitCountry = extractExplicitCountry(mergedLines) ?: inferredCountry

        val dbCountry = if (ragContext.enabled) {
            ragContext.spatialContenderFields["country"]?.takeIf { it.isNotBlank() }
                ?: ragContext.appellationCandidates.firstOrNull()?.country?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val dbRegion = if (ragContext.enabled) {
            ragContext.spatialContenderFields["region"]?.takeIf { it.isNotBlank() }
                ?: ragContext.appellationCandidates.firstOrNull()?.region?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val dbSubregion = if (ragContext.enabled) {
            ragContext.spatialContenderFields["subregion"]?.takeIf { it.isNotBlank() }
                ?: ragContext.appellationCandidates.firstOrNull()
                    ?.appellationOrSubregion
                    ?.takeIf { it.isNotBlank() && !it.equals(dbRegion, ignoreCase = true) }
        } else {
            null
        }

        val finalCountry = dbCountry ?: explicitCountry
        val finalAppellation = dbSubregion ?: dbRegion ?: localAppellation
        if (finalAppellation != null || finalCountry != null) {
            reasoningSteps.add(
                "Matched geography: appellation=${finalAppellation ?: "null"}, region=${dbRegion ?: "null"}, subregion=${dbSubregion ?: "null"}, country=${finalCountry ?: "null"}."
            )
        }

        // 7. Producer & Wine Name using spatial [x, y] hierarchy + Database RAG (ProducerGazetteer & LinkedWineResolver)
        val candidateIdentityLines = mergedLines.filter { line ->
            val t = line.text
            !isFinePrintOrLegalLine(t) &&
                !t.equals(finalAppellation, ignoreCase = true) &&
                !t.equals(dbRegion, ignoreCase = true) &&
                !t.equals(classification, ignoreCase = true) &&
                !t.equals(finalCountry, ignoreCase = true) &&
                varietals.none { v -> t.equals(v, ignoreCase = true) } &&
                !t.matches(Regex("^\\s*(19|20)\\d{2}\\s*$")) &&
                !t.matches(Regex("^\\s*N\\.?V\\.?\\s*$", RegexOption.IGNORE_CASE))
        }.sortedWith(compareBy<OcrBlock> { it.yCenter }.thenBy { abs(it.xCenter - 0.5f) })

        val spatialProducer = selectProducer(candidateIdentityLines, mergedLines)
        val spatialWineName = selectWineName(candidateIdentityLines, spatialProducer, varietals, finalAppellation, classification)

        val dbProducer = if (ragContext.enabled) {
            val spProducer = ragContext.spatialContenderFields["producer"]?.takeIf { it.isNotBlank() }
            val spProducerGreen = ragContext.spatialContenderStates["producer"] == "GREEN"
            val nearCandidate = ragContext.producerCandidates.firstOrNull { cand ->
                cand.distance <= 1 && (
                    cand.matchedSpan.equals(spProducer, ignoreCase = true) ||
                        cand.matchedSpan.equals(spatialProducer, ignoreCase = true)
                    )
            }?.name
            when {
                spProducer != null && spProducerGreen -> spProducer
                nearCandidate != null -> nearCandidate
                spProducer != null -> spProducer
                else -> ragContext.producerCandidates.firstOrNull { cand ->
                    cand.distance <= 1 &&
                        varietals.none { v ->
                            v.equals(cand.name, ignoreCase = true) ||
                                v.equals(cand.matchedSpan, ignoreCase = true)
                        }
                }?.name
            }
        } else {
            null
        }
        val dbWineName = if (ragContext.enabled) {
            ragContext.spatialContenderFields["wine_name"]?.takeIf { it.isNotBlank() }
                ?: ragContext.linkedWineCandidates.firstOrNull { it.distance <= 1 }?.name
        } else {
            null
        }

        val finalProducer = dbProducer ?: spatialProducer
        val finalWineName = dbWineName ?: spatialWineName

        if (dbProducer != null && spatialProducer != null && !dbProducer.equals(spatialProducer, ignoreCase = true)) {
            val note = "Database RAG canonicalized producer '$spatialProducer' -> '$dbProducer' (ProducerGazetteer)"
            discrepancies.add(note)
            reasoningSteps.add(note)
        }
        if (dbWineName != null && spatialWineName != null && !dbWineName.equals(spatialWineName, ignoreCase = true)) {
            val note = "Database RAG canonicalized wine_name '$spatialWineName' -> '$dbWineName' (LinkedWineIndex / WineKB)"
            discrepancies.add(note)
            reasoningSteps.add(note)
        }
        if (finalProducer != null || finalWineName != null) {
            reasoningSteps.add(
                "Final identity assignment: producer_or_winery=${finalProducer ?: "null"}, wine_name_or_cuvee=${finalWineName ?: "null"}."
            )
        }

        val confidenceNote = buildString {
            append("Mode=${ablationMode.displayLabel}; ")
            append("ML Kit=${mlKitBlocks.size} blocks, PP-OCRv6=${ppOcrV6Blocks.size} blocks; ")
            if (ragContext.enabled) {
                append(
                    "DB RAG=${ragContext.producerCandidates.size} producers, ${ragContext.linkedWineCandidates.size} wines, ${ragContext.appellationCandidates.size} appellations (${ragContext.elapsedMs}ms); "
                )
            }
            if (discrepancies.isNotEmpty()) {
                append("${discrepancies.size} discrepancy(ies) resolved; ")
            }
            if (GraniteLlamaBridge.isNativeLibraryLoaded() && ggufFile.isFile) {
                append("Granite 4.2 3B GGUF active")
            } else if (ggufFile.isFile) {
                append("GGUF present (${ggufFile.name}); spatial + DB RAG reconciler active")
            } else {
                append("GGUF not yet imported; spatial + DB RAG reconciler active")
            }
        }

        val result = GraniteWineLabelJson(
            producerOrWinery = finalProducer,
            wineNameOrCuvee = finalWineName,
            vintage = vintage,
            varietals = varietals,
            appellationOrRegion = finalAppellation,
            country = finalCountry,
            classification = classification,
            abvPercentage = abv,
            bottleVolumeMl = volumeMl,
            isNonVintage = isNonVintage,
            ocrDiscrepanciesResolved = discrepancies,
            confidenceNotes = confidenceNote,
            region = dbRegion ?: finalAppellation,
            subregion = dbSubregion
        )

        val thinkTrace = if (thinkModeEnabled) {
            if (reasoningSteps.isEmpty()) {
                "No OCR text blocks available to reconcile. Returning null/empty fields without guessing."
            } else {
                reasoningSteps.joinToString("\n")
            }
        } else {
            null
        }

        return result to thinkTrace
    }

    private fun mergeAndReconcileLines(
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        discrepancies: MutableList<String>,
        reasoningSteps: MutableList<String>
    ): List<OcrBlock> {
        if (mlKitBlocks.isEmpty() && ppOcrV6Blocks.isEmpty()) return emptyList()
        if (ppOcrV6Blocks.isEmpty()) {
            return mlKitBlocks.map { it.copy(text = restoreKnownWineDiacritics(it.text, discrepancies)) }
        }
        if (mlKitBlocks.isEmpty()) {
            return ppOcrV6Blocks.map { it.copy(text = restoreKnownWineDiacritics(it.text, discrepancies)) }
        }

        val usedPpIndices = BooleanArray(ppOcrV6Blocks.size)
        val merged = mutableListOf<OcrBlock>()

        for (mlBlock in mlKitBlocks) {
            var bestMatchIdx = -1
            var bestDist = Float.MAX_VALUE
            for (i in ppOcrV6Blocks.indices) {
                if (usedPpIndices[i]) continue
                val ppBlock = ppOcrV6Blocks[i]
                val dy = abs(mlBlock.yCenter - ppBlock.yCenter)
                val dx = abs(mlBlock.xCenter - ppBlock.xCenter)
                if (dy <= 0.065f && dx <= 0.35f) {
                    val dist = dy * 2f + dx
                    if (dist < bestDist) {
                        bestDist = dist
                        bestMatchIdx = i
                    }
                }
            }

            if (bestMatchIdx >= 0) {
                usedPpIndices[bestMatchIdx] = true
                val ppBlock = ppOcrV6Blocks[bestMatchIdx]
                val chosenText = choosePreferredText(mlBlock, ppBlock, discrepancies, reasoningSteps)
                val restoredText = restoreKnownWineDiacritics(chosenText, discrepancies)
                merged.add(
                    mlBlock.copy(
                        text = restoredText,
                        confidence = maxOf(mlBlock.confidence, ppBlock.confidence)
                    )
                )
            } else {
                val restoredText = restoreKnownWineDiacritics(mlBlock.text, discrepancies)
                merged.add(mlBlock.copy(text = restoredText))
            }
        }

        for (i in ppOcrV6Blocks.indices) {
            if (!usedPpIndices[i]) {
                val pp = ppOcrV6Blocks[i]
                val restoredText = restoreKnownWineDiacritics(pp.text, discrepancies)
                merged.add(pp.copy(text = restoredText))
            }
        }

        return DualOcrEngine.sortBlocksTopToBottomLeftToRight(merged)
    }

    private fun choosePreferredText(
        mlBlock: OcrBlock,
        ppBlock: OcrBlock,
        discrepancies: MutableList<String>,
        reasoningSteps: MutableList<String>
    ): String {
        val mlText = mlBlock.text.trim()
        val ppText = ppBlock.text.trim()
        if (mlText == ppText) return mlText

        val mlHasDiacritics = hasEuropeanDiacritics(mlText)
        val ppHasDiacritics = hasEuropeanDiacritics(ppText)

        val chosen = when {
            ppHasDiacritics && !mlHasDiacritics -> {
                val note = "Preserved European diacritics from PP-OCRv6 '$ppText' over ML Kit '$mlText' at y=${String.format(Locale.US, "%.2f", mlBlock.yCenter)}"
                discrepancies.add(note)
                reasoningSteps.add(note)
                ppText
            }
            mlHasDiacritics && !ppHasDiacritics -> {
                val note = "Preserved European diacritics from ML Kit '$mlText' over PP-OCRv6 '$ppText' at y=${String.format(Locale.US, "%.2f", mlBlock.yCenter)}"
                discrepancies.add(note)
                reasoningSteps.add(note)
                mlText
            }
            ppBlock.confidence > mlBlock.confidence + 0.12f && ppText.length >= mlText.length -> {
                val note = "Resolved '${ppText}' (PP-OCRv6 conf=${String.format(Locale.US, "%.2f", ppBlock.confidence)}) vs '${mlText}' (ML Kit conf=${String.format(Locale.US, "%.2f", mlBlock.confidence)}) at y=${String.format(Locale.US, "%.2f", mlBlock.yCenter)}"
                discrepancies.add(note)
                reasoningSteps.add(note)
                ppText
            }
            else -> {
                if (!mlText.equals(ppText, ignoreCase = true)) {
                    val note = "Resolved '${mlText}' (ML Kit) vs '${ppText}' (PP-OCRv6) at y=${String.format(Locale.US, "%.2f", mlBlock.yCenter)}"
                    discrepancies.add(note)
                    reasoningSteps.add(note)
                }
                mlText
            }
        }
        return chosen
    }

    private fun hasEuropeanDiacritics(text: String): Boolean {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return decomposed.any { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    }

    private fun restoreKnownWineDiacritics(
        text: String,
        discrepancies: MutableList<String>
    ): String {
        var result = text
        for ((asciiPattern, accented) in DIACRITIC_RESTORATION_RULES) {
            if (asciiPattern.containsMatchIn(result)) {
                val updated = asciiPattern.replace(result, accented)
                if (updated != result) {
                    val msg = "Preserved diacritics: '$result' -> '$updated'"
                    if (msg !in discrepancies) {
                        discrepancies.add(msg)
                    }
                    result = updated
                }
            }
        }
        return result
    }

    private fun extractVintageYear(text: String): Int? {
        val matches = VINTAGE_YEAR_REGEX.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1920..2030 }
            .toList()
        return matches.firstOrNull()
    }

    private fun extractAbvPercentage(lines: List<OcrBlock>): Double? {
        val ordered = lines.sortedByDescending { it.yCenter }
        for (line in ordered) {
            val m = ABV_REGEX.find(line.text) ?: continue
            val rawNum = m.groupValues[1].replace(',', '.')
            val value = rawNum.toDoubleOrNull()
            if (value != null && value in 5.0..24.0) {
                return (value * 10.0).roundToInt() / 10.0
            }
        }
        return null
    }

    private fun extractBottleVolumeMl(lines: List<OcrBlock>): Int? {
        val ordered = lines.sortedByDescending { it.yCenter }
        for (line in ordered) {
            val mlMatch = VOLUME_ML_REGEX.find(line.text)
            if (mlMatch != null) {
                val v = mlMatch.groupValues[1].toIntOrNull()
                if (v != null && v in 187..6000) return v
            }
            val clMatch = VOLUME_CL_REGEX.find(line.text)
            if (clMatch != null) {
                val cl = clMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
                if (cl != null && cl in 18.0..600.0) return (cl * 10.0).roundToInt()
            }
            val lMatch = VOLUME_L_REGEX.find(line.text)
            if (lMatch != null) {
                val l = lMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
                if (l != null && l in 0.375..6.0) return (l * 1000.0).roundToInt()
            }
        }
        return null
    }

    private fun extractVarietals(lines: List<OcrBlock>): List<String> {
        val found = linkedSetOf<String>()
        val combined = lines.joinToString(" ") { it.text }
        for ((pattern, canonical) in KNOWN_VARIETALS) {
            if (pattern.containsMatchIn(combined)) {
                found.add(canonical)
            }
        }
        return found.toList()
    }

    private fun extractClassification(lines: List<OcrBlock>): String? {
        val combined = lines.joinToString(" ") { it.text }
        for ((pattern, canonical) in KNOWN_CLASSIFICATIONS) {
            if (pattern.containsMatchIn(combined)) {
                return canonical
            }
        }
        return null
    }

    private fun extractAppellationAndCountry(lines: List<OcrBlock>): Pair<String?, String?> {
        val combined = lines.joinToString(" ") { it.text }
        for (entry in KNOWN_APPELLATIONS) {
            if (entry.pattern.containsMatchIn(combined)) {
                return entry.canonicalAppellation to entry.country
            }
        }
        return null to null
    }

    private fun extractExplicitCountry(lines: List<OcrBlock>): String? {
        val combined = lines.joinToString(" ") { it.text }
        for ((pattern, country) in KNOWN_COUNTRIES) {
            if (pattern.containsMatchIn(combined)) {
                return country
            }
        }
        return null
    }

    private fun isFinePrintOrLegalLine(text: String): Boolean {
        return FINE_PRINT_REGEX.containsMatchIn(text)
    }

    private fun selectProducer(
        identityCandidates: List<OcrBlock>,
        allLines: List<OcrBlock>
    ): String? {
        for (line in allLines) {
            if (isFinePrintOrLegalLine(line.text)) continue
            if (PRODUCER_PREFIX_REGEX.containsMatchIn(line.text)) {
                return line.text
            }
        }
        return identityCandidates.firstOrNull { it.yCenter <= 0.65f && it.text.length >= 3 }?.text
    }

    private fun selectWineName(
        identityCandidates: List<OcrBlock>,
        producer: String?,
        varietals: List<String>,
        appellation: String?,
        classification: String?
    ): String? {
        val remaining = identityCandidates.filter { candidate ->
            val t = candidate.text
            !t.equals(producer, ignoreCase = true) &&
                !t.equals(appellation, ignoreCase = true) &&
                !t.equals(classification, ignoreCase = true) &&
                varietals.none { it.equals(t, ignoreCase = true) } &&
                candidate.yCenter <= 0.78f &&
                t.length >= 2
        }
        return remaining.firstOrNull()?.text
    }

    override fun close() {
        GraniteLlamaBridge.close()
    }

    private data class AppellationEntry(
        val pattern: Regex,
        val canonicalAppellation: String,
        val country: String
    )

    companion object {
        const val DEFAULT_THINK_MODE_ENABLED = false

        val SYSTEM_PROMPT = """
You are IBM Granite 4.2 3B, an offline wine label OCR reconciliation engine.
Inputs: Spatially Aligned Cross-Engine Reading Lines (ML Kit + PP-OCRv6 with scale, zone, and flags LOWERCASE_INITIAL_ON_HEADER, WIDTH_MISMATCH, EDGE_CLIP) plus Database Candidate Grounding (RAG) from LWIN producers, linked wines, origins, and grapes.
Rules:
1. Reason-Then-Extract: Keep "ocr_discrepancies_resolved" to [] (or <=2 items <=6 words ONLY for real clipped/misread names) and "confidence_notes" to null.
2. Spatial: TOP_HEADER/UPPER_MAIN + PRIMARY_DISPLAY/SUBHEADER -> producer_or_winery & wine_name_or_cuvee; MID_BODY -> appellation/region/subregion, varietals, classification, vintage; BOTTOM_LEGAL -> abv_percentage, bottle_volume_ml, country.
3. Dynamic Reconstruction of Incomplete or Misread OCR: Reconstruct clipped/misread headers ([LOWERCASE_INITIAL_ON_HEADER], [WIDTH_MISMATCH]) using cross-engine text and Database Candidate Grounding (RAG) ([CORROBORATED_BY_LINKED_WINE], [COUNTRY_MATCHES_LABEL]).
4. Geography: country, broad region (e.g. Bordeaux, California), specific subregion (e.g. Margaux, Napa Valley), and appellation_or_region.
5. Restore European diacritics (Château, Côtes du Rhône, Saint-Émilion, Spätlese, Crémant). Never guess absent fields (use null / []).
6. Sparkling (Champagne, Brut, Crémant, Prosecco, Cava, Sekt) without a year OR explicit "NV" -> "vintage":null, "is_non_vintage":true.
7. Output ONE compact single-line JSON object in this exact key order:
{"ocr_discrepancies_resolved":[],"confidence_notes":null,"country":null,"region":null,"subregion":null,"appellation_or_region":null,"varietals":[],"classification":null,"vintage":null,"is_non_vintage":false,"abv_percentage":null,"bottle_volume_ml":null,"producer_or_winery":null,"wine_name_or_cuvee":null}
""".trimIndent()

        /**
         * Strict `llama.cpp` GBNF grammar enforcing the Reason-Then-Context-Then-Entity JSON schema.
         */
        val WINE_LABEL_JSON_GBNF = """
root ::= ws json-object ws
json-object ::= "{" ws
  "\"ocr_discrepancies_resolved\"" ws ":" ws string-array "," ws
  "\"confidence_notes\"" ws ":" ws nullable-string "," ws
  "\"country\"" ws ":" ws nullable-string "," ws
  "\"region\"" ws ":" ws nullable-string "," ws
  "\"subregion\"" ws ":" ws nullable-string "," ws
  "\"appellation_or_region\"" ws ":" ws nullable-string "," ws
  "\"varietals\"" ws ":" ws string-array "," ws
  "\"classification\"" ws ":" ws nullable-string "," ws
  "\"vintage\"" ws ":" ws nullable-int "," ws
  "\"is_non_vintage\"" ws ":" ws boolean "," ws
  "\"abv_percentage\"" ws ":" ws nullable-number "," ws
  "\"bottle_volume_ml\"" ws ":" ws nullable-int "," ws
  "\"producer_or_winery\"" ws ":" ws nullable-string "," ws
  "\"wine_name_or_cuvee\"" ws ":" ws nullable-string ws
"}"
nullable-string ::= string | "null"
nullable-int ::= integer | "null"
nullable-number ::= number | "null"
boolean ::= "true" | "false"
string-array ::= "[" ws "]" | "[" ws string (ws "," ws string)* ws "]"
string ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
integer ::= [1-9] [0-9]{0,4}
number ::= [0-9]+ ("." [0-9]+)?
ws ::= [ \t\n\r]*
""".trimIndent()

        /**
         * GBNF grammar allowing `<think>...</think>` reasoning followed by the strict JSON object.
         */
        val WINE_LABEL_THINK_PLUS_JSON_GBNF = """
root ::= think-body "</think>" ws json-object ws
think-body ::= ([^<] | "<" [^/] | "</" [^t])*
json-object ::= "{" ws
  "\"ocr_discrepancies_resolved\"" ws ":" ws string-array "," ws
  "\"confidence_notes\"" ws ":" ws nullable-string "," ws
  "\"country\"" ws ":" ws nullable-string "," ws
  "\"region\"" ws ":" ws nullable-string "," ws
  "\"subregion\"" ws ":" ws nullable-string "," ws
  "\"appellation_or_region\"" ws ":" ws nullable-string "," ws
  "\"varietals\"" ws ":" ws string-array "," ws
  "\"classification\"" ws ":" ws nullable-string "," ws
  "\"vintage\"" ws ":" ws nullable-int "," ws
  "\"is_non_vintage\"" ws ":" ws boolean "," ws
  "\"abv_percentage\"" ws ":" ws nullable-number "," ws
  "\"bottle_volume_ml\"" ws ":" ws nullable-int "," ws
  "\"producer_or_winery\"" ws ":" ws nullable-string "," ws
  "\"wine_name_or_cuvee\"" ws ":" ws nullable-string ws
"}"
nullable-string ::= string | "null"
nullable-int ::= integer | "null"
nullable-number ::= number | "null"
boolean ::= "true" | "false"
string-array ::= "[" ws "]" | "[" ws string (ws "," ws string)* ws "]"
string ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
integer ::= [1-9] [0-9]{0,4}
number ::= [0-9]+ ("." [0-9]+)?
ws ::= [ \t\n\r]*
""".trimIndent()

        private val EXPLICIT_NV_REGEX = Regex(
            "(?:\\bN\\.?V\\.?\\b|\\bNON[-\\s]?VINTAGE\\b|\\bSANS\\s+ANN[EÉ]E\\b)",
            RegexOption.IGNORE_CASE
        )

        private val SPARKLING_CUE_REGEX = Regex(
            "\\b(CHAMPAGNE|PROSECCO|CAVA|CR[EÉ]MANT|FRANCIACORTA|SEKT|SPARKLING\\s+WINE|M[EÉ]THODE\\s+TRADITIONNELLE|BRUT\\s+NATURE|EXTRA\\s+BRUT|\\bBRUT\\b)\\b",
            RegexOption.IGNORE_CASE
        )

        private val VINTAGE_YEAR_REGEX = Regex("\\b(19[4-9]\\d|20[0-2]\\d)\\b")

        private val ABV_REGEX = Regex(
            "\\b(\\d{1,2}(?:[.,]\\d{1,2})?)\\s*%\\s*(?:ALC\\.?\\s*/?\\s*VOL\\.?|BY\\s*VOL\\.?|VOL\\.?|ALC\\.?)?",
            RegexOption.IGNORE_CASE
        )

        private val VOLUME_ML_REGEX = Regex("\\b(187|375|500|750|1000|1500|3000)\\s*m[lL]\\b")
        private val VOLUME_CL_REGEX = Regex("\\b(37[.,]5|50|75|150)\\s*c[lL]\\b")
        private val VOLUME_L_REGEX = Regex("\\b(0[.,]75|1[.,]5|3[.,]0)\\s*[lL]\\b")

        private val FINE_PRINT_REGEX = Regex(
            "(\\bALC\\.?\\s*/?\\s*VOL|\\b\\d{1,2}(?:[.,]\\d)?\\s*%|\\b(750|375|1500)\\s*ML\\b|\\b75\\s*CL\\b|\\bCONTAINS\\s+SULFITES\\b|\\bIMPORTED\\s+BY\\b|\\bGOVERNMENT\\s+WARNING\\b|\\bMIS\\s+EN\\s+BOUTEILLE\\b|\\bPRODUCE\\s+OF\\b|\\bPRODUCT\\s+OF\\b)",
            RegexOption.IGNORE_CASE
        )

        private val BOILERPLATE_SKIP_REGEX = Regex(
            "(\\bGOVERNMENT\\s+WARNING\\b|\\bSURGEON\\s+GENERAL\\b|\\bACCORDING\\s+TO\\s+THE\\b|\\bBIRTH\\s+DEFECTS\\b|\\bIMPAIRS\\s+YOUR\\s+ABILITY\\b|\\bHEALTH\\s+PROBLEMS\\b|\\bCONTAINS\\s+SULFITES\\b|\\bWWW\\.[A-Z0-9-]+\\.)",
            RegexOption.IGNORE_CASE
        )

        private val PRODUCER_PREFIX_REGEX = Regex(
            "\\b(Ch[aâ]teau|Domaine|Bodegas?|Tenuta|Cantine|Fattoria|Weingut|Schloss|Quinta|Marqu[eé]s|Maison|Champagne|Vega\\s+Sicilia|Opus\\s+One|Stag'?s\\s+Leap|Ridge|Penfolds)\\b",
            RegexOption.IGNORE_CASE
        )

        private val DIACRITIC_RESTORATION_RULES = listOf(
            Regex("\\bChateau\\b") to "Château",
            Regex("\\bCHATEAU\\b") to "CHÂTEAU",
            Regex("\\bCotes\\s+du\\s+Rhone\\b", RegexOption.IGNORE_CASE) to "Côtes du Rhône",
            Regex("\\bSpatlese\\b", RegexOption.IGNORE_CASE) to "Spätlese",
            Regex("\\bCremant\\b", RegexOption.IGNORE_CASE) to "Crémant",
            Regex("\\bSaint[-\\s]Emilion\\b", RegexOption.IGNORE_CASE) to "Saint-Émilion",
            Regex("\\bPessac[-\\s]Leognan\\b", RegexOption.IGNORE_CASE) to "Pessac-Léognan",
            Regex("\\bChateauneuf[-\\s]du[-\\s]Pape\\b", RegexOption.IGNORE_CASE) to "Châteauneuf-du-Pape",
            Regex("\\bGruner\\s+Veltliner\\b", RegexOption.IGNORE_CASE) to "Grüner Veltliner",
            Regex("\\bGewurztraminer\\b", RegexOption.IGNORE_CASE) to "Gewürztraminer",
            Regex("\\bMedoc\\b", RegexOption.IGNORE_CASE) to "Médoc",
            Regex("\\bHaut[-\\s]Medoc\\b", RegexOption.IGNORE_CASE) to "Haut-Médoc",
            Regex("\\bCuvee\\b", RegexOption.IGNORE_CASE) to "Cuvée"
        )

        private val KNOWN_VARIETALS = listOf(
            Regex("\\bCabernet\\s+Sauvignon\\b", RegexOption.IGNORE_CASE) to "Cabernet Sauvignon",
            Regex("\\bCabernet\\s+Franc\\b", RegexOption.IGNORE_CASE) to "Cabernet Franc",
            Regex("\\bPinot\\s+Noir\\b", RegexOption.IGNORE_CASE) to "Pinot Noir",
            Regex("\\bPinot\\s+Grigio\\b", RegexOption.IGNORE_CASE) to "Pinot Grigio",
            Regex("\\bPinot\\s+Gris\\b", RegexOption.IGNORE_CASE) to "Pinot Gris",
            Regex("\\bSauvignon\\s+Blanc\\b", RegexOption.IGNORE_CASE) to "Sauvignon Blanc",
            Regex("\\bChardonnay\\b", RegexOption.IGNORE_CASE) to "Chardonnay",
            Regex("\\bMerlot\\b", RegexOption.IGNORE_CASE) to "Merlot",
            Regex("\\bSyrah\\b", RegexOption.IGNORE_CASE) to "Syrah",
            Regex("\\bShiraz\\b", RegexOption.IGNORE_CASE) to "Shiraz",
            Regex("\\bGrenache\\b", RegexOption.IGNORE_CASE) to "Grenache",
            Regex("\\bTempranillo\\b", RegexOption.IGNORE_CASE) to "Tempranillo",
            Regex("\\bSangiovese\\b", RegexOption.IGNORE_CASE) to "Sangiovese",
            Regex("\\bNebbiolo\\b", RegexOption.IGNORE_CASE) to "Nebbiolo",
            Regex("\\bRiesling\\b", RegexOption.IGNORE_CASE) to "Riesling",
            Regex("\\bMalbec\\b", RegexOption.IGNORE_CASE) to "Malbec",
            Regex("\\bZinfandel\\b", RegexOption.IGNORE_CASE) to "Zinfandel",
            Regex("\\bChenin\\s+Blanc\\b", RegexOption.IGNORE_CASE) to "Chenin Blanc",
            Regex("\\bGr[uü]ner\\s+Veltliner\\b", RegexOption.IGNORE_CASE) to "Grüner Veltliner",
            Regex("\\bGew[uü]rztraminer\\b", RegexOption.IGNORE_CASE) to "Gewürztraminer",
            Regex("\\bAlbari[nñ]o\\b", RegexOption.IGNORE_CASE) to "Albariño",
            Regex("\\bGamay\\b", RegexOption.IGNORE_CASE) to "Gamay",
            Regex("\\bViognier\\b", RegexOption.IGNORE_CASE) to "Viognier",
            Regex("\\bMourv[eè]dre\\b", RegexOption.IGNORE_CASE) to "Mourvèdre"
        )

        private val KNOWN_CLASSIFICATIONS = listOf(
            Regex("\\bPremier\\s+Grand\\s+Cru\\s+Class[eé]\\b", RegexOption.IGNORE_CASE) to "Premier Grand Cru Classé",
            Regex("\\bGrand\\s+Cru\\s+Class[eé]\\b", RegexOption.IGNORE_CASE) to "Grand Cru Classé",
            Regex("\\bGrand\\s+Cru\\b", RegexOption.IGNORE_CASE) to "Grand Cru",
            Regex("\\bPremier\\s+Cru\\b|\\b1er\\s+Cru\\b", RegexOption.IGNORE_CASE) to "Premier Cru",
            Regex("\\bGran\\s+Reserva\\b", RegexOption.IGNORE_CASE) to "Gran Reserva",
            Regex("\\bReserva\\b", RegexOption.IGNORE_CASE) to "Reserva",
            Regex("\\bRiserva\\b", RegexOption.IGNORE_CASE) to "Riserva",
            Regex("\\bCrianza\\b", RegexOption.IGNORE_CASE) to "Crianza",
            Regex("\\bSp[aä]tlese\\b", RegexOption.IGNORE_CASE) to "Spätlese",
            Regex("\\bKabinett\\b", RegexOption.IGNORE_CASE) to "Kabinett",
            Regex("\\bAuslese\\b", RegexOption.IGNORE_CASE) to "Auslese",
            Regex("\\bPr[aä]dikatswein\\b", RegexOption.IGNORE_CASE) to "Prädikatswein",
            Regex("\\bDenominazione\\s+di\\s+Origine\\s+Controllata\\s+e\\s+Garantita\\b|\\bD\\.?O\\.?C\\.?G\\.?\\b", RegexOption.IGNORE_CASE) to "DOCG",
            Regex("\\bDenominazione\\s+di\\s+Origine\\s+Controllata\\b|\\bD\\.?O\\.?C\\.?\\b", RegexOption.IGNORE_CASE) to "DOC",
            Regex("\\bAppellation\\s+.*\\s+Contr[oô]l[eé]e\\b|\\bA\\.?O\\.?C\\.?\\b|\\bA\\.?O\\.?P\\.?\\b", RegexOption.IGNORE_CASE) to "AOC"
        )

        private val KNOWN_APPELLATIONS = listOf(
            AppellationEntry(Regex("\\bMargaux\\b", RegexOption.IGNORE_CASE), "Margaux", "France"),
            AppellationEntry(Regex("\\bPauillac\\b", RegexOption.IGNORE_CASE), "Pauillac", "France"),
            AppellationEntry(Regex("\\bSaint[-\\s][EÉ]milion\\b", RegexOption.IGNORE_CASE), "Saint-Émilion", "France"),
            AppellationEntry(Regex("\\bPessac[-\\s]L[eé]ognan\\b", RegexOption.IGNORE_CASE), "Pessac-Léognan", "France"),
            AppellationEntry(Regex("\\bPomerol\\b", RegexOption.IGNORE_CASE), "Pomerol", "France"),
            AppellationEntry(Regex("\\bSaint[-\\s]Julien\\b", RegexOption.IGNORE_CASE), "Saint-Julien", "France"),
            AppellationEntry(Regex("\\bSaint[-\\s]Est[eè]phe\\b", RegexOption.IGNORE_CASE), "Saint-Estèphe", "France"),
            AppellationEntry(Regex("\\bHaut[-\\s]M[eé]doc\\b", RegexOption.IGNORE_CASE), "Haut-Médoc", "France"),
            AppellationEntry(Regex("\\bC[oô]tes\\s+du\\s+Rh[oô]ne\\b", RegexOption.IGNORE_CASE), "Côtes du Rhône", "France"),
            AppellationEntry(Regex("\\bCh[aâ]teauneuf[-\\s]du[-\\s]Pape\\b", RegexOption.IGNORE_CASE), "Châteauneuf-du-Pape", "France"),
            AppellationEntry(Regex("\\bSancerre\\b", RegexOption.IGNORE_CASE), "Sancerre", "France"),
            AppellationEntry(Regex("\\bChablis\\b", RegexOption.IGNORE_CASE), "Chablis", "France"),
            AppellationEntry(Regex("\\bChampagne\\b", RegexOption.IGNORE_CASE), "Champagne", "France"),
            AppellationEntry(Regex("\\bBourgogne\\b|\\bBurgundy\\b", RegexOption.IGNORE_CASE), "Bourgogne", "France"),
            AppellationEntry(Regex("\\bBordeaux\\b", RegexOption.IGNORE_CASE), "Bordeaux", "France"),
            AppellationEntry(Regex("\\bRibera\\s+del\\s+Duero\\b", RegexOption.IGNORE_CASE), "Ribera del Duero", "Spain"),
            AppellationEntry(Regex("\\bRioja\\b", RegexOption.IGNORE_CASE), "Rioja", "Spain"),
            AppellationEntry(Regex("\\bPriorat\\b", RegexOption.IGNORE_CASE), "Priorat", "Spain"),
            AppellationEntry(Regex("\\bR[ií]as\\s+Baixas\\b", RegexOption.IGNORE_CASE), "Rías Baixas", "Spain"),
            AppellationEntry(Regex("\\bBarolo\\b", RegexOption.IGNORE_CASE), "Barolo", "Italy"),
            AppellationEntry(Regex("\\bBarbaresco\\b", RegexOption.IGNORE_CASE), "Barbaresco", "Italy"),
            AppellationEntry(Regex("\\bBrunello\\s+di\\s+Montalcino\\b", RegexOption.IGNORE_CASE), "Brunello di Montalcino", "Italy"),
            AppellationEntry(Regex("\\bChianti\\s+Classico\\b", RegexOption.IGNORE_CASE), "Chianti Classico", "Italy"),
            AppellationEntry(Regex("\\bNapa\\s+Valley\\b", RegexOption.IGNORE_CASE), "Napa Valley", "United States"),
            AppellationEntry(Regex("\\bSonoma\\s+(?:Coast|County|Valley)\\b", RegexOption.IGNORE_CASE), "Sonoma", "United States"),
            AppellationEntry(Regex("\\bWillamette\\s+Valley\\b", RegexOption.IGNORE_CASE), "Willamette Valley", "United States"),
            AppellationEntry(Regex("\\bMosel\\b", RegexOption.IGNORE_CASE), "Mosel", "Germany"),
            AppellationEntry(Regex("\\bRheingau\\b", RegexOption.IGNORE_CASE), "Rheingau", "Germany"),
            AppellationEntry(Regex("\\bMarlborough\\b", RegexOption.IGNORE_CASE), "Marlborough", "New Zealand"),
            AppellationEntry(Regex("\\bBarossa\\s+Valley\\b", RegexOption.IGNORE_CASE), "Barossa Valley", "Australia"),
            AppellationEntry(Regex("\\bMendoza\\b", RegexOption.IGNORE_CASE), "Mendoza", "Argentina")
        )

        private val KNOWN_COUNTRIES = listOf(
            Regex("\\b(?:PRODUCE|PRODUCT)\\s+OF\\s+FRANCE\\b|\\bFRANCE\\b", RegexOption.IGNORE_CASE) to "France",
            Regex("\\b(?:PRODUCE|PRODUCT)\\s+OF\\s+ITALY\\b|\\bITALIA\\b|\\bITALY\\b", RegexOption.IGNORE_CASE) to "Italy",
            Regex("\\b(?:PRODUCE|PRODUCT)\\s+OF\\s+SPAIN\\b|\\bESPA[NÑ]A\\b|\\bSPAIN\\b", RegexOption.IGNORE_CASE) to "Spain",
            Regex("\\b(?:PRODUCE|PRODUCT)\\s+OF\\s+GERMANY\\b|\\bDEUTSCHLAND\\b|\\bGERMANY\\b", RegexOption.IGNORE_CASE) to "Germany",
            Regex("\\b(?:PRODUCE|PRODUCT)\\s+OF\\s+USA\\b|\\bCALIFORNIA\\b|\\bOREGON\\b|\\bUNITED\\s+STATES\\b", RegexOption.IGNORE_CASE) to "United States",
            Regex("\\b(?:PRODUCE|PRODUCT)\\s+OF\\s+PORTUGAL\\b|\\bPORTUGAL\\b", RegexOption.IGNORE_CASE) to "Portugal",
            Regex("\\b(?:PRODUCE|PRODUCT)\\s+OF\\s+ARGENTINA\\b|\\bARGENTINA\\b", RegexOption.IGNORE_CASE) to "Argentina",
            Regex("\\b(?:PRODUCE|PRODUCT)\\s+OF\\s+CHILE\\b|\\bCHILE\\b", RegexOption.IGNORE_CASE) to "Chile",
            Regex("\\b(?:PRODUCE|PRODUCT)\\s+OF\\s+AUSTRALIA\\b|\\bAUSTRALIA\\b", RegexOption.IGNORE_CASE) to "Australia",
            Regex("\\bNEW\\s+ZEALAND\\b", RegexOption.IGNORE_CASE) to "New Zealand",
            Regex("\\bAUSTRIA\\b|\\b[OÖ]STERREICH\\b", RegexOption.IGNORE_CASE) to "Austria"
        )
    }
}
