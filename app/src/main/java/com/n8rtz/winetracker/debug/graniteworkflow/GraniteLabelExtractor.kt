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
    val backendUsed: String
)

/**
 * Isolated on-device LLM reconciliation engine (`GraniteLabelExtractor`) for IBM Granite 4.2 3B
 * (`granite-4.2-3b-Q4_K_M.gguf`) loaded from `context.getExternalFilesDir("models/granite")`.
 *
 * - Default `<think>` mode is `OFF` (`thinkModeEnabled = false`).
 * - Enforces strict JSON output matching the 12-field wine label schema via GBNF grammar.
 * - Reconciles conflicts between Google ML Kit v2 and PP-OCRv6 using normalized spatial `[x, y, w, h]`
 *   coordinates, preserves European diacritics, never guesses missing fields, and enforces the
 *   `"NV"` / `"Non-Vintage"` / sparkling wine non-vintage rule.
 */
class GraniteLabelExtractor : AutoCloseable {

    suspend fun extract(
        context: Context,
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ablationMode: OcrAblationMode,
        thinkModeEnabled: Boolean = DEFAULT_THINK_MODE_ENABLED
    ): GraniteExtractionOutcome = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        val ggufFile = GraniteModelStorage.resolveGraniteGgufFile(context)
        val prompt = buildGranitePrompt(
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = ppOcrV6Blocks,
            ablationMode = ablationMode,
            thinkModeEnabled = thinkModeEnabled
        )
        val grammar = if (thinkModeEnabled) {
            WINE_LABEL_THINK_PLUS_JSON_GBNF
        } else {
            WINE_LABEL_JSON_GBNF
        }

        if (GraniteLlamaBridge.isNativeLibraryLoaded() && ggufFile.isFile && ggufFile.length() > 0L) {
            val rawOutput = runCatching {
                GraniteLlamaBridge.generateWithGguf(
                    ggufFile = ggufFile,
                    prompt = prompt,
                    gbnfGrammar = grammar,
                    maxTokens = if (thinkModeEnabled) 768 else 384,
                    temperature = 0.0f
                )
            }.getOrNull()

            if (!rawOutput.isNullOrBlank()) {
                val thinkTrace = extractThinkContent(rawOutput)
                val parsed = runCatching { GraniteWineLabelJson.fromJsonString(rawOutput) }.getOrNull()
                if (parsed != null) {
                    val enforced = enforcePostRules(parsed, mlKitBlocks, ppOcrV6Blocks)
                    val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                    return@withContext GraniteExtractionOutcome(
                        structuredJson = enforced,
                        prettyJson = enforced.toPrettyJson(2),
                        rawThinkTrace = thinkTrace,
                        rawLlmOutput = rawOutput,
                        elapsedMs = elapsed,
                        backendUsed = "llama.cpp JNI (${ggufFile.name})"
                    )
                }
            }
        }

        // Deterministic spatial + diacritic reconciliation following the exact Granite system prompt rules
        // when the native llama.cpp .so is not compiled into the current APK or before GGUF execution.
        val fallback = reconcileSpatialDeterministic(
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = ppOcrV6Blocks,
            ablationMode = ablationMode,
            thinkModeEnabled = thinkModeEnabled,
            ggufFile = ggufFile
        )
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
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
            backendUsed = if (ggufFile.isFile && ggufFile.length() > 0L) {
                "Spatial Reconciler (${ggufFile.name} staged; build libgranite_llama_jni.so for native GGUF token generation)"
            } else {
                "Spatial Reconciler (import granite-4.2-3b-Q4_K_M.gguf into models/granite)"
            }
        )
    }

    /**
     * Builds the IBM Granite 4.2 prompt incorporating the System Prompt Rules, `<think>` toggle,
     * and coordinate-tagged OCR blocks from ML Kit and PP-OCRv6.
     */
    fun buildGranitePrompt(
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ablationMode: OcrAblationMode,
        thinkModeEnabled: Boolean = DEFAULT_THINK_MODE_ENABLED
    ): String {
        val mlKitFormatted = if (!ablationMode.runsMlKit) {
            "(Disabled by ablation mode: ${ablationMode.displayLabel})"
        } else if (mlKitBlocks.isEmpty()) {
            "(No text detected by ML Kit)"
        } else {
            mlKitBlocks.joinToString("\n") { b ->
                String.format(
                    Locale.US,
                    "- %s conf=%.2f text=\"%s\"",
                    b.formatCenterWhTag(),
                    b.confidence,
                    b.text
                )
            }
        }

        val ppOcrFormatted = if (!ablationMode.runsPpOcrV6) {
            "(Disabled by ablation mode: ${ablationMode.displayLabel})"
        } else if (ppOcrV6Blocks.isEmpty()) {
            "(No text detected by PP-OCRv6)"
        } else {
            ppOcrV6Blocks.joinToString("\n") { b ->
                String.format(
                    Locale.US,
                    "- %s conf=%.2f text=\"%s\"",
                    b.formatCenterWhTag(),
                    b.confidence,
                    b.text
                )
            }
        }

        val thinkingDirective = if (thinkModeEnabled) {
            "Thinking mode is ON: first analyze spatial coordinates and OCR conflicts inside <think>...</think>, then output ONLY the valid JSON object."
        } else {
            "Thinking mode is OFF: do NOT emit <think> tags or prose. Output ONLY the valid JSON object immediately."
        }

        val assistantPrefix = if (thinkModeEnabled) {
            "<|start_of_role|>assistant<|end_of_role|><think>\n"
        } else {
            "<|start_of_role|>assistant<|end_of_role|><think>\n</think>\n"
        }

        return buildString {
            append("<|start_of_role|>system<|end_of_role|>")
            append(SYSTEM_PROMPT.trim())
            append("\n")
            append(thinkingDirective)
            append("<|end_of_text|>\n")
            append("<|start_of_role|>user<|end_of_role|>")
            append("OCR Mode: ${ablationMode.displayLabel}\n\n")
            append("=== Engine A: Google ML Kit Text Recognition v2 (sorted top-to-bottom y, left-to-right x) ===\n")
            append(mlKitFormatted)
            append("\n\n=== Engine B: PP-OCRv6 ONNX (sorted top-to-bottom y, left-to-right x) ===\n")
            append(ppOcrFormatted)
            append("\n\nReconcile the OCR detections above into the exact JSON schema.")
            append("<|end_of_text|>\n")
            append(assistantPrefix)
        }
    }

    internal fun extractThinkContent(rawOutput: String): String? {
        val regex = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
        val match = regex.find(rawOutput)
        val content = match?.groupValues?.getOrNull(1)?.trim()
        return content?.takeIf { it.isNotEmpty() }
    }

    /**
     * Enforces mandatory schema invariants such as the NV / sparkling wine non-vintage rule.
     */
    internal fun enforcePostRules(
        input: GraniteWineLabelJson,
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>
    ): GraniteWineLabelJson {
        val allText = (mlKitBlocks.map { it.text } + ppOcrV6Blocks.map { it.text }).joinToString(" ")
        val hasExplicitNv = EXPLICIT_NV_REGEX.containsMatchIn(allText)
        val isSparklingCue = SPARKLING_CUE_REGEX.containsMatchIn(allText)
        val extractedYear = input.vintage ?: extractVintageYear(allText)

        val finalIsNonVintage = when {
            hasExplicitNv -> true
            input.isNonVintage -> true
            isSparklingCue && extractedYear == null -> true
            else -> false
        }
        val finalVintage = if (finalIsNonVintage) null else extractedYear

        return input.copy(
            vintage = finalVintage,
            isNonVintage = finalIsNonVintage
        )
    }

    private fun reconcileSpatialDeterministic(
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ablationMode: OcrAblationMode,
        thinkModeEnabled: Boolean,
        ggufFile: File
    ): Pair<GraniteWineLabelJson, String?> {
        val discrepancies = mutableListOf<String>()
        val reasoningSteps = mutableListOf<String>()

        // Pair spatially overlapping lines from ML Kit and PP-OCRv6 (|dy| < 0.06, horizontal overlap)
        val mergedLines = mergeAndReconcileLines(mlKitBlocks, ppOcrV6Blocks, discrepancies, reasoningSteps)
        val fullText = mergedLines.joinToString(" ") { it.text }

        // 1. Vintage & Non-Vintage rule
        val hasExplicitNv = EXPLICIT_NV_REGEX.containsMatchIn(fullText)
        val isSparkling = SPARKLING_CUE_REGEX.containsMatchIn(fullText)
        val detectedYear = if (hasExplicitNv) null else extractVintageYear(fullText)
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

        // 4. Varietals
        val varietals = extractVarietals(mergedLines)
        if (varietals.isNotEmpty()) {
            reasoningSteps.add("Matched varietals: ${varietals.joinToString(", ")}.")
        }

        // 5. Classification (AOC, DOCG,Reserva, Grand Cru, Spätlese, etc.)
        val classification = extractClassification(mergedLines)
        if (classification != null) {
            reasoningSteps.add("Matched wine classification: $classification.")
        }

        // 6. Appellation / Region & Country
        val (appellation, inferredCountry) = extractAppellationAndCountry(mergedLines)
        val explicitCountry = extractExplicitCountry(mergedLines) ?: inferredCountry
        if (appellation != null || explicitCountry != null) {
            reasoningSteps.add("Matched appellation=${appellation ?: "null"}, country=${explicitCountry ?: "null"}.")
        }

        // 7. Producer & Wine Name using spatial [x, y] hierarchy (top-center = producer/wine name;
        //    exclude bottom fine print y > 0.78 containing ABV/volume/importer)
        val candidateIdentityLines = mergedLines.filter { line ->
            val t = line.text
            !isFinePrintOrLegalLine(t) &&
                !t.equals(appellation, ignoreCase = true) &&
                !t.equals(classification, ignoreCase = true) &&
                !t.equals(explicitCountry, ignoreCase = true) &&
                varietals.none { v -> t.equals(v, ignoreCase = true) } &&
                !t.matches(Regex("^\\s*(19|20)\\d{2}\\s*$")) &&
                !t.matches(Regex("^\\s*N\\.?V\\.?\\s*$", RegexOption.IGNORE_CASE))
        }.sortedWith(compareBy<OcrBlock> { it.yCenter }.thenBy { abs(it.xCenter - 0.5f) })

        val producer = selectProducer(candidateIdentityLines, mergedLines)
        val wineName = selectWineName(candidateIdentityLines, producer, varietals, appellation, classification)
        if (producer != null || wineName != null) {
            reasoningSteps.add(
                "Spatial top-center assignment: producer_or_winery=${producer ?: "null"}, wine_name_or_cuvee=${wineName ?: "null"}."
            )
        }

        val confidenceNote = buildString {
            append("Mode=${ablationMode.displayLabel}; ")
            append("ML Kit=${mlKitBlocks.size} blocks, PP-OCRv6=${ppOcrV6Blocks.size} blocks; ")
            if (discrepancies.isNotEmpty()) {
                append("${discrepancies.size} OCR discrepancy(ies) resolved; ")
            }
            if (GraniteLlamaBridge.isNativeLibraryLoaded() && ggufFile.isFile) {
                append("Granite 4.2 3B GGUF active")
            } else if (ggufFile.isFile) {
                append("GGUF present (${ggufFile.name}); spatial reconciler active")
            } else {
                append("GGUF not yet imported; spatial reconciler active")
            }
        }

        val result = GraniteWineLabelJson(
            producerOrWinery = producer,
            wineNameOrCuvee = wineName,
            vintage = vintage,
            varietals = varietals,
            appellationOrRegion = appellation,
            country = explicitCountry,
            classification = classification,
            abvPercentage = abv,
            bottleVolumeMl = volumeMl,
            isNonVintage = isNonVintage,
            ocrDiscrepanciesResolved = discrepancies,
            confidenceNotes = confidenceNote
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
        // Prefer bottom lines first, then all lines
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
        // Explicit producer prefixes ("Château ...", "Domaine ...", "Bodegas ...", "Tenuta ...", "Weingut ...")
        for (line in allLines) {
            if (isFinePrintOrLegalLine(line.text)) continue
            if (PRODUCER_PREFIX_REGEX.containsMatchIn(line.text)) {
                return line.text
            }
        }
        // Top-most prominent centered identity line in upper 65% of label
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
You are an expert wine label extraction and OCR reconciliation engine running on-device (IBM Granite 4.2 3B).
You receive parallel OCR detections from two engines:
1. Engine A: Google ML Kit Text Recognition v2 (Latin script)
2. Engine B: PP-OCRv6 via ONNX Runtime

Each text block includes normalized [0.0..1.0] spatial coordinates [x=x_center, y=y_center, w=width, h=height] and confidence.

STRICT SYSTEM RULES:
1. Spatial Coordinate Reconciliation: Reconcile conflicts between ML Kit and PP-OCRv6 using spatial [x, y] coordinates:
   - Top-to-upper-center (y in 0.08..0.55, x near 0.50) = producer_or_winery and wine_name_or_cuvee.
   - Middle section (y in 0.40..0.75) = appellation_or_region, varietals, classification, vintage.
   - Bottom fine print (y > 0.75) = abv_percentage, bottle_volume_ml, country/importer legal text.
2. Preserve European Diacritics: Always preserve and prefer proper European diacritics (e.g., "Château", "Côtes du Rhône", "Spätlese", "Ribera del Duero", "Crémant", "Saint-Émilion", "Pessac-Léognan", "Grüner Veltliner").
3. Zero Hallucination Policy: NEVER guess or hallucinate missing fields. Return null for any scalar field (or [] for "varietals" and "ocr_discrepancies_resolved") when uncertain or absent from the OCR text.
4. Non-Vintage & Sparkling Rule: If "NV" or "Non-Vintage" appears anywhere on the label, OR if no vintage year is printed on a sparkling wine (e.g., Champagne, Brut, Crémant, Prosecco, Cava, Sekt), set "vintage": null and "is_non_vintage": true.
5. Output Format: Respond with strict JSON matching this exact schema:
{
  "producer_or_winery": "string | null",
  "wine_name_or_cuvee": "string | null",
  "vintage": "integer | null",
  "varietals": ["string"],
  "appellation_or_region": "string | null",
  "country": "string | null",
  "classification": "string | null",
  "abv_percentage": "number | null",
  "bottle_volume_ml": "integer | null",
  "is_non_vintage": "boolean",
  "ocr_discrepancies_resolved": ["string"],
  "confidence_notes": "string | null"
}
""".trimIndent()

        /**
         * Strict `llama.cpp` GBNF grammar enforcing the exact 12-field JSON schema.
         */
        val WINE_LABEL_JSON_GBNF = """
root ::= ws json-object ws
json-object ::= "{" ws
  "\"producer_or_winery\"" ws ":" ws nullable-string "," ws
  "\"wine_name_or_cuvee\"" ws ":" ws nullable-string "," ws
  "\"vintage\"" ws ":" ws nullable-int "," ws
  "\"varietals\"" ws ":" ws string-array "," ws
  "\"appellation_or_region\"" ws ":" ws nullable-string "," ws
  "\"country\"" ws ":" ws nullable-string "," ws
  "\"classification\"" ws ":" ws nullable-string "," ws
  "\"abv_percentage\"" ws ":" ws nullable-number "," ws
  "\"bottle_volume_ml\"" ws ":" ws nullable-int "," ws
  "\"is_non_vintage\"" ws ":" ws boolean "," ws
  "\"ocr_discrepancies_resolved\"" ws ":" ws string-array "," ws
  "\"confidence_notes\"" ws ":" ws nullable-string ws
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
  "\"producer_or_winery\"" ws ":" ws nullable-string "," ws
  "\"wine_name_or_cuvee\"" ws ":" ws nullable-string "," ws
  "\"vintage\"" ws ":" ws nullable-int "," ws
  "\"varietals\"" ws ":" ws string-array "," ws
  "\"appellation_or_region\"" ws ":" ws nullable-string "," ws
  "\"country\"" ws ":" ws nullable-string "," ws
  "\"classification\"" ws ":" ws nullable-string "," ws
  "\"abv_percentage\"" ws ":" ws nullable-number "," ws
  "\"bottle_volume_ml\"" ws ":" ws nullable-int "," ws
  "\"is_non_vintage\"" ws ":" ws boolean "," ws
  "\"ocr_discrepancies_resolved\"" ws ":" ws string-array "," ws
  "\"confidence_notes\"" ws ":" ws nullable-string ws
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
