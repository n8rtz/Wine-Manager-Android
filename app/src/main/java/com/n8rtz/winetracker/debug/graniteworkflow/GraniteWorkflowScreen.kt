package com.n8rtz.winetracker.debug.graniteworkflow

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import java.util.Locale

private val MlKitOverlayBlue = Color(0xFF2979FF)
private val PpOcrOverlayGreen = Color(0xFF00C853)

private val graniteFieldLabels = listOf(
    "producer" to "Producer",
    "wine_name" to "Wine name",
    "country" to "Country",
    "region" to "Region",
    "subregion" to "Subregion",
    "vintage" to "Vintage"
)

/**
 * Isolated debug/testing Compose screen for the **Dual OCR + Granite 4.2 4B Workflow**.
 *
 * Formatted to mirror `GlinerLabScreen.kt` (`GLiNER lane lab`):
 * - Direct `LazyColumn` with `statusBarsPadding()`, `navigationBarsPadding()`,
 *   `padding(horizontal = 16.dp, vertical = 12.dp)`, and `Arrangement.spacedBy(12.dp)`
 * - `SelectionContainer` header block (`headlineSmall` title, `bodySmall` build/config/bundle lines)
 * - Compact `TextButton` action row (`Import`, `Reset cache`, `Re-check`) and horizontal parameter strip
 * - Run status, `Granite completed: X/Y`, `LinearProgressIndicator`, primary `Button` + `Run GLiNER dataset (124)`,
 *   bordered 3-column `RunTimers`, and `Stop` / `Close` `TextButton` row
 * - `Scored results` summary and field tables (`ScoreSummaryTable` + `ScoreFieldTable`)
 * - `Your images` section with `Choose images`, `Snap photo`, `Use GLiNER dataset (124)`, `Sample label`,
 *   `Run your images`, `Remove all`, `Hide`/`Show`
 * - 4-Section Results Inspector rendered in `GlinerLabScreen`-style `Card`s and `0.5.dp` `outlineVariant` bordered cells
 * - `Incomplete labels (N)` and `All labels (124)` expandable accordions for the shared GLiNER wine label dataset
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraniteWorkflowScreen(
    state: GraniteWorkflowUiState,
    onNavigateBack: () -> Unit,
    onRefreshModels: () -> Unit,
    onResetCache: () -> Unit,
    onImportGraniteGguf: () -> Unit,
    onPickGalleryImage: () -> Unit,
    onSnapCameraPhoto: () -> Unit,
    onLoadSampleLabel: () -> Unit,
    onUseGlinerDataset: () -> Unit = {},
    onSelectDatasetSample: (String) -> Unit = {},
    onRunDatasetSample: (String) -> Unit = {},
    onRunAllGlinerDataset: () -> Unit = {},
    onClearImage: () -> Unit,
    onRunWorkflow: () -> Unit,
    onStopWorkflow: () -> Unit,
    onOcrModeChange: (OcrAblationMode) -> Unit,
    onThinkModeChange: (Boolean) -> Unit,
    onClaheChange: (Boolean) -> Unit,
    onDbRagChange: (Boolean) -> Unit = {},
    onToggleMlKitBoxes: (Boolean) -> Unit,
    onTogglePpOcrBoxes: (Boolean) -> Unit,
    onShareJson: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var showImages by remember { mutableStateOf(true) }
    var showModelPaths by remember { mutableStateOf(false) }
    var showSection1 by remember { mutableStateOf(true) }
    var showSection2 by remember { mutableStateOf(true) }
    var showSection3 by remember { mutableStateOf(true) }
    var showSection4 by remember { mutableStateOf(true) }
    var showIncomplete by remember { mutableStateOf(false) }
    var showAllDatasetLabels by remember { mutableStateOf(false) }
    var selectedIncompleteFields by remember { mutableStateOf( emptySet<String>() ) }
    var expandedIncompleteRaw by remember { mutableStateOf( emptySet<String>() ) }

    val result = state.lastResult
    val missingPrompt = state.missingModelsPrompt
    val configSignature = remember(state.ocrMode, state.thinkModeEnabled, state.claheEnabled, state.dbRagEnabled) {
        val modeKey = when (state.ocrMode) {
            OcrAblationMode.DUAL -> "dual-ocr"
            OcrAblationMode.ML_KIT_ONLY -> "mlkit-only"
            OcrAblationMode.PP_OCRV6_ONLY -> "ppocrv6-only"
        }
        "$modeKey · db-rag=${if (state.dbRagEnabled) "on" else "off"} · think=${if (state.thinkModeEnabled) "on" else "off"} · clahe=${if (state.claheEnabled) "on" else "off"}"
    }

    val incompleteRows = remember(state.datasetRows) {
        state.datasetRows.filter { row ->
            (row.runResult != null || row.status == "error") && !row.complete
        }
    }
    fun failedFieldKeys(row: GraniteDatasetRow): Set<String> =
        row.fieldResults.filterNot { it.isCorrect }.map { it.field }.toSet()
    val filteredIncomplete = remember(incompleteRows, selectedIncompleteFields) {
        if (selectedIncompleteFields.isEmpty()) {
            incompleteRows
        } else {
            incompleteRows.filter { row -> failedFieldKeys(row).any { it in selectedIncompleteFields } }
        }
    }
    val datasetCount = state.datasetRows.size.takeIf { it > 0 } ?: 124

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("granite_workflow_list")
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header, Bundle/Model Status, Action Buttons, and Parameter Strip (mirrors GlinerLabScreen item 1)
        item {
            SelectionContainer {
                Column(
                    modifier = Modifier.testTag("import_models_prompt_card"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Dual OCR + Granite 4.2 4B Workflow",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        "build ${BuildConfig.BUILD_COMMIT} · $configSignature",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val jniBadge = if (state.modelsStatus.nativeLlamaJniLoaded) "llama.cpp JNI ✓" else "JNI fallback"
                    Text(
                        text = when {
                            state.modelsStatus.isGraniteReady ->
                                "model ready (${state.modelsStatus.graniteGguf.fileName} · ${state.modelsStatus.graniteGguf.sizeFormatted}) · Q4_K_M ✓ · $jniBadge · PP-OCRv6 ${if (state.modelsStatus.isPpOcrReady) "ready" else "missing"}"
                            state.modelsStatus.graniteGguf.sizeBytes > 0L ->
                                "model invalid · ${state.modelsStatus.graniteGguf.notes} · $jniBadge"
                            else ->
                                "model missing · Import Model Files to Run (${state.modelsStatus.graniteGguf.fileName}) · $jniBadge · ML Kit & PP-OCRv6 shared (${if (state.modelsStatus.isPpOcrReady) "ready" else "checking"})"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.modelsStatus.isGraniteReady) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    if (result != null) {
                        val t = result.timingAndMemory
                        Text(
                            "last run: preprocess ${t.preprocessMs} ms + ML Kit ${t.mlKitMs} ms + PP-OCRv6 ${t.ppOcrV6Ms} ms + DB RAG ${t.dbRagMs} ms + Granite ${t.graniteMs} ms · total ${t.totalMs} ms",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!state.modelsStatus.isPpOcrReady) {
                        Text(
                            "PP-OCRv6 bundled assets not yet staged in text-lab-paddle-v1",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onImportGraniteGguf,
                    enabled = !state.isRunning && !state.isImportingModel
                ) {
                    Text(if (state.isImportingModel) "Importing…" else "Import")
                }
                TextButton(
                    onClick = onResetCache,
                    enabled = !state.isRunning && !state.isImportingModel
                ) {
                    Text("Reset cache")
                }
                TextButton(
                    onClick = onRefreshModels,
                    enabled = !state.isRunning
                ) {
                    Text("Re-check")
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OcrAblationMode.entries.forEach { mode ->
                    val selected = state.ocrMode == mode
                    TextButton(
                        onClick = { onOcrModeChange(mode) },
                        enabled = !state.isRunning,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = if (selected) "[${mode.displayLabel}]" else mode.displayLabel,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
                TextButton(
                    onClick = { onDbRagChange(!state.dbRagEnabled) },
                    enabled = !state.isRunning,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.testTag("db_rag_toggle_button")
                ) {
                    Text(
                        "DB RAG ${if (state.dbRagEnabled) "ON" else "OFF"}",
                        fontWeight = if (state.dbRagEnabled) FontWeight.Bold else FontWeight.Normal
                    )
                }
                TextButton(
                    onClick = { onThinkModeChange(!state.thinkModeEnabled) },
                    enabled = !state.isRunning,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("<think> ${if (state.thinkModeEnabled) "ON" else "OFF"}")
                }
                TextButton(
                    onClick = { onClaheChange(!state.claheEnabled) },
                    enabled = !state.isRunning,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("CLAHE ${if (state.claheEnabled) "ON" else "OFF"}")
                }
            }
        }

        // 2. Run Controls, Progress, RunTimers, and Stop/Close (mirrors GlinerLabScreen item 2)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isRunning && state.activeStageMessage.isNotBlank()) {
                    Text(
                        text = state.activeStageMessage,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    val bannerText = state.errorBannerMessage
                        ?: state.statusBannerMessage
                        ?: missingPrompt
                    if (!bannerText.isNullOrBlank()) {
                        SelectionContainer {
                            Text(
                                text = bannerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.errorBannerMessage != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                val showBatchProgress = state.isDatasetBatchRunning || (state.datasetRan && !state.isRunning && state.useGlinerDatasetMode)
                if (showBatchProgress) {
                    val total = state.datasetTotalCount.takeIf { it > 0 } ?: datasetCount
                    val done = state.datasetDoneCount
                    val pct = (done * 100) / total.coerceAtLeast(1)
                    val tail = when {
                        state.datasetCurrentLabel.isNotBlank() -> " · ${state.datasetCurrentLabel}"
                        !state.isRunning && done > 0 -> " · finished"
                        else -> ""
                    }
                    Text(
                        "Granite completed: $done/$total ($pct%)$tail",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.isRunning && done == 0) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { (done.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    val done = if (result != null && !state.isRunning) 1 else 0
                    val total = if (state.previewBitmap != null || result != null || state.isRunning) 1 else 0
                    val pct = if (total > 0) (done * 100) / total else 0
                    val tail = when {
                        state.isRunning -> " · ${state.imageSourceLabel}"
                        result != null -> " · finished"
                        else -> ""
                    }
                    Text(
                        "Granite completed: $done/$total ($pct%)$tail",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (state.isRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total.toFloat() else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (state.previewBitmap == null && !state.useGlinerDatasetMode) {
                                onLoadSampleLabel()
                            } else {
                                onRunWorkflow()
                            }
                        },
                        enabled = !state.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when {
                                state.isRunning && !state.isDatasetBatchRunning -> "Running Granite…"
                                result != null -> "New Granite run"
                                else -> "Start Granite run"
                            }
                        )
                    }
                    Button(
                        onClick = onRunAllGlinerDataset,
                        enabled = !state.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("run_gliner_dataset_button")
                    ) {
                        Text(
                            when {
                                state.isDatasetBatchRunning -> "Running $datasetCount…"
                                state.datasetRan -> "Rerun GLiNER set ($datasetCount)"
                                else -> "Run GLiNER set ($datasetCount)"
                            }
                        )
                    }
                }

                if (state.isDatasetBatchRunning || (state.datasetBatchElapsedMs > 0L && !state.isRunning && state.useGlinerDatasetMode)) {
                    GraniteDatasetBatchTimers(
                        elapsedMs = state.datasetBatchElapsedMs,
                        done = state.datasetDoneCount,
                        total = state.datasetTotalCount.takeIf { it > 0 } ?: datasetCount
                    )
                } else {
                    val timerTotalMs = if (state.isRunning) {
                        state.liveTotalMs
                    } else {
                        result?.timingAndMemory?.totalMs ?: 0L
                    }
                    val timerOcrMs = if (state.isRunning) {
                        state.liveOcrMs
                    } else {
                        (result?.timingAndMemory?.mlKitMs ?: 0L) + (result?.timingAndMemory?.ppOcrV6Ms ?: 0L)
                    }
                    val timerGraniteMs = if (state.isRunning) {
                        state.liveGraniteMs
                    } else {
                        result?.timingAndMemory?.graniteMs ?: 0L
                    }
                    GraniteRunTimers(
                        elapsedMs = timerTotalMs,
                        ocrMs = timerOcrMs,
                        graniteMs = timerGraniteMs,
                        hasResult = result != null || state.isRunning
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onStopWorkflow,
                        enabled = state.isRunning
                    ) {
                        Text("Stop")
                    }
                    TextButton(onClick = onNavigateBack) {
                        Text("Close")
                    }
                }
            }
        }

        // 3. Scored Results Table (mirrors GlinerLabScreen ScoredResults: ScoreSummaryTable + ScoreFieldTable)
        if (state.isDatasetBatchRunning || state.datasetAggregate != null || state.useGlinerDatasetMode) {
            item(key = "granite_curated_scores") {
                val liveAggregate = state.datasetAggregate
                    ?: GraniteLabCorpus.aggregate(state.datasetRows, stopped = false)
                GraniteDatasetScoredResults(aggregate = liveAggregate)
            }
        } else if (result != null) {
            item(key = "granite_single_scores") {
                GraniteSingleImageScoredResults(result = result)
            }
        }

        // 4. "Your images" + "GLiNER dataset (124)" Section (mirrors GlinerLabScreen item 4)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val activeCount = if (state.useGlinerDatasetMode) {
                    datasetCount
                } else if (state.previewBitmap != null) {
                    1
                } else {
                    0
                }
                Text(
                    if (state.useGlinerDatasetMode) {
                        "Your images · GLiNER dataset ($activeCount)"
                    } else {
                        "Your images ($activeCount)"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Select a gallery photo, snap a label, or use the packaged 124-label GLiNER / Lab v7 dataset (assets/wine-app-labels-v1). Reuses existing ML Kit and PP-OCRv6 files.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = onPickGalleryImage,
                        enabled = !state.isRunning
                    ) {
                        Text("Choose images")
                    }
                    TextButton(
                        onClick = onSnapCameraPhoto,
                        enabled = !state.isRunning
                    ) {
                        Text("Snap photo")
                    }
                    TextButton(
                        onClick = onUseGlinerDataset,
                        enabled = !state.isRunning,
                        modifier = Modifier.testTag("use_gliner_dataset_button")
                    ) {
                        Text(
                            if (state.useGlinerDatasetMode) {
                                "[GLiNER dataset ($datasetCount)]"
                            } else {
                                "Use GLiNER dataset ($datasetCount)"
                            },
                            fontWeight = if (state.useGlinerDatasetMode) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    TextButton(
                        onClick = onLoadSampleLabel,
                        enabled = !state.isRunning
                    ) {
                        Text("Sample label")
                    }
                    TextButton(
                        onClick = onRunWorkflow,
                        enabled = !state.isRunning && (state.previewBitmap != null || state.activeDatasetImage != null)
                    ) {
                        Text("Run your images")
                    }
                    if (state.previewBitmap != null || state.useGlinerDatasetMode) {
                        TextButton(
                            onClick = onClearImage,
                            enabled = !state.isRunning
                        ) {
                            Text("Remove all")
                        }
                        TextButton(onClick = { showImages = !showImages }) {
                            Text(if (showImages) "Hide" else "Show")
                        }
                    }
                }

                // GLiNER 124-label dataset quick selector strip when GLiNER dataset mode is active
                if (state.useGlinerDatasetMode && state.datasetRows.isNotEmpty()) {
                    val currentIdx = state.datasetRows.indexOfFirst { it.sample.image == state.activeDatasetImage }
                        .coerceAtLeast(0)
                    val currentRow = state.datasetRows[currentIdx]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = {
                                val prevIdx = if (currentIdx > 0) currentIdx - 1 else state.datasetRows.lastIndex
                                onSelectDatasetSample(state.datasetRows[prevIdx].sample.image)
                            },
                            enabled = !state.isRunning
                        ) {
                            Text("‹ Prev")
                        }
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Label ${currentIdx + 1}/${state.datasetRows.size}: ${currentRow.displayName} (${currentRow.status})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        TextButton(
                            onClick = {
                                val nextIdx = if (currentIdx < state.datasetRows.lastIndex) currentIdx + 1 else 0
                                onSelectDatasetSample(state.datasetRows[nextIdx].sample.image)
                            },
                            enabled = !state.isRunning
                        ) {
                            Text("Next ›")
                        }
                        TextButton(
                            onClick = { onRunDatasetSample(currentRow.sample.image) },
                            enabled = !state.isRunning
                        ) {
                            Text("Run label")
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.datasetRows.take(24).forEach { row ->
                            val isSelected = row.sample.image == state.activeDatasetImage
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectDatasetSample(row.sample.image) },
                                enabled = !state.isRunning,
                                label = {
                                    val badge = when {
                                        row.complete -> " ✓"
                                        row.runResult != null -> " (${row.errorCount} err)"
                                        else -> ""
                                    }
                                    Text("${row.displayName}$badge", maxLines = 1)
                                }
                            )
                        }
                        if (state.datasetRows.size > 24) {
                            FilterChip(
                                selected = showAllDatasetLabels,
                                onClick = { showAllDatasetLabels = !showAllDatasetLabels },
                                label = {
                                    Text("All ${state.datasetRows.size} labels ${if (showAllDatasetLabels) "▲" else "▼"}")
                                }
                            )
                        }
                    }
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.previewBitmap != null,
                            onClick = {
                                if (state.previewBitmap == null) onLoadSampleLabel() else onRunWorkflow()
                            },
                            label = { Text(state.imageSourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
        }

        // 5. 4-Section Results Inspector (formatted in GlinerLabScreen Card style)
        if (showImages) {
            // Section 1: Preprocessed Image + Dual-OCR Bounding Box Overlay
            item {
                InspectorSection1OverlayCard(
                    state = state,
                    expanded = showSection1,
                    onToggleExpanded = { showSection1 = !showSection1 },
                    onToggleMlKitBoxes = onToggleMlKitBoxes,
                    onTogglePpOcrBoxes = onTogglePpOcrBoxes
                )
            }

            // Section 2: Side-by-Side Raw OCR Comparison
            item {
                InspectorSection2SideBySideOcrCard(
                    state = state,
                    expanded = showSection2,
                    onToggleExpanded = { showSection2 = !showSection2 }
                )
            }

            // Section 3: Granite 4.2 Reconciled Output (Formatted JSON + <think> Reasoning Trace + Timing/RAM)
            item {
                InspectorSection3GraniteOutputCard(
                    state = state,
                    expanded = showSection3,
                    onToggleExpanded = { showSection3 = !showSection3 }
                )
            }

            // Section 4: Copy & Share Actions + Quick Field Table
            item {
                InspectorSection4ActionsCard(
                    state = state,
                    expanded = showSection4,
                    onToggleExpanded = { showSection4 = !showSection4 },
                    onCopyJson = { jsonText ->
                        clipboard.setText(AnnotatedString(jsonText))
                    },
                    onShareJson = onShareJson
                )
            }
        }

        // 6. Export Row (mirrors GlinerLabScreen "Export" section)
        if (result != null || state.datasetRan) {
            item {
                Text("Export", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(buildGraniteReportText(state)))
                        }
                    ) {
                        Text("Copy all results")
                    }
                    if (result != null) {
                        TextButton(
                            onClick = { onShareJson(result.prettyJsonText) },
                            enabled = !state.isRunning
                        ) {
                            Text("Share JSON")
                        }
                    }
                }
            }
        }

        // 7. Expandable "Incomplete labels (N)" Accordion (mirrors GlinerLabScreen lines 279-313)
        item {
            TextButton(
                onClick = { showIncomplete = !showIncomplete },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Incomplete labels (${incompleteRows.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (showIncomplete) "▲" else "▼",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (showIncomplete) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        graniteFieldLabels.forEach { (key, label) ->
                            val selected = key in selectedIncompleteFields
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedIncompleteFields = if (selected) {
                                        selectedIncompleteFields - key
                                    } else {
                                        selectedIncompleteFields + key
                                    }
                                },
                                label = {
                                    Text("$label (${incompleteRows.count { key in failedFieldKeys(it) }})")
                                }
                            )
                        }
                    }
                    if (selectedIncompleteFields.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Showing ${filteredIncomplete.size}/${incompleteRows.size}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { selectedIncompleteFields = emptySet() }) {
                                Text("Clear filters")
                            }
                        }
                    }
                    if (!state.datasetRan) {
                        Text("No scored dataset labels yet.", style = MaterialTheme.typography.bodySmall)
                    } else if (incompleteRows.isEmpty()) {
                        Text("All scored dataset labels are completely correct.", style = MaterialTheme.typography.bodySmall)
                    } else if (filteredIncomplete.isEmpty()) {
                        Text("No labels failed the selected filter(s).", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(filteredIncomplete, key = { "incomplete:${it.sample.image}" }) { row ->
                val rawOpen = row.sample.image in expandedIncompleteRaw
                GraniteIncompleteDatasetCard(
                    row = row,
                    rawOpen = rawOpen,
                    onToggleRaw = {
                        expandedIncompleteRaw = if (rawOpen) {
                            expandedIncompleteRaw - row.sample.image
                        } else {
                            expandedIncompleteRaw + row.sample.image
                        }
                    },
                    onInspect = { onSelectDatasetSample(row.sample.image) },
                    onRerun = { onRunDatasetSample(row.sample.image) },
                    enabled = !state.isRunning
                )
            }
        }

        // 8. Expandable "All labels (124)" Accordion (mirrors GlinerLabScreen lines 314-325)
        item {
            TextButton(
                onClick = { showAllDatasetLabels = !showAllDatasetLabels },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("all_gliner_labels_toggle")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "All labels (${state.datasetRows.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (showAllDatasetLabels) "▲" else "▼",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (showAllDatasetLabels) {
            items(state.datasetRows, key = { "all:${it.sample.image}" }) { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SelectionContainer(Modifier.weight(1f)) {
                        val scoreNote = when {
                            row.complete -> " · 6/6 correct"
                            row.runResult != null -> " · ${row.errorCount} error(s)"
                            else -> ""
                        }
                        Text(
                            "${row.displayName} · ${row.status}$scoreNote",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(
                        onClick = { onSelectDatasetSample(row.sample.image) },
                        enabled = !state.isRunning
                    ) {
                        Text("Load")
                    }
                    TextButton(
                        onClick = { onRunDatasetSample(row.sample.image) },
                        enabled = !state.isRunning
                    ) {
                        Text("Run")
                    }
                }
            }
        }

        // 9. Expandable "Model files & storage paths" Accordion
        item {
            TextButton(
                onClick = { showModelPaths = !showModelPaths },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Model files & databases (8)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (showModelPaths) "▲" else "▼",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (showModelPaths) {
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                state.modelsStatus.graniteGguf,
                                state.modelsStatus.mlKitEngine,
                                state.modelsStatus.ppOcrDetOnnx,
                                state.modelsStatus.ppOcrRecOnnx,
                                state.modelsStatus.ppOcrDict,
                                state.modelsStatus.producerGazetteerDb,
                                state.modelsStatus.linkedWineDb,
                                state.modelsStatus.appellationCatalogDb
                            ).forEach { slot ->
                                Text(
                                    "${if (slot.present) "✓" else "✗"} ${slot.slotName} · ${slot.fileName} (${slot.sizeFormatted})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (slot.present) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                            if (state.modelsStatus.graniteDirectoryPath.isNotBlank()) {
                                Text(
                                    "Granite dir: ${state.modelsStatus.graniteDirectoryPath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (state.modelsStatus.sharedPpOcrDirectoryPath.isNotBlank()) {
                                Text(
                                    "Shared PP-OCRv6 dir: ${state.modelsStatus.sharedPpOcrDirectoryPath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraniteIncompleteDatasetCard(
    row: GraniteDatasetRow,
    rawOpen: Boolean,
    onToggleRaw: () -> Unit,
    onInspect: () -> Unit,
    onRerun: () -> Unit,
    enabled: Boolean
) {
    val result = row.runResult
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${row.displayName} · ${row.status} · ${row.errorCount} field error(s)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onInspect, enabled = enabled) {
                        Text("Inspect")
                    }
                    TextButton(onClick = onRerun, enabled = enabled) {
                        Text("Run")
                    }
                }
                if (row.message.isNotBlank()) {
                    Text(row.message, style = MaterialTheme.typography.bodySmall)
                }
                row.fieldResults.filterNot { it.isCorrect }.forEach { field ->
                    Text(
                        "${field.label}: got \"${field.observed.ifBlank { "(blank)" }}\" · expected \"${field.expected.ifBlank { "(blank)" }}\" (${field.outcome})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (result != null) {
                    val t = result.timingAndMemory
                    Text(
                        "ML Kit ${result.mlKitBlocks.size} blk (${t.mlKitMs} ms) · PP-OCRv6 ${result.ppOcrV6Blocks.size} blk (${t.ppOcrV6Ms} ms) · Granite ${t.graniteMs} ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onToggleRaw) {
                        Text(if (rawOpen) "Hide raw OCR + JSON" else "Raw OCR + JSON")
                    }
                    if (rawOpen) {
                        Text("ML Kit OCR:", style = MaterialTheme.typography.labelMedium)
                        result.mlKitBlocks.forEach { b ->
                            Text("${b.formatCenterWhTag()} ${b.text}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("PP-OCRv6 OCR:", style = MaterialTheme.typography.labelMedium)
                        result.ppOcrV6Blocks.forEach { b ->
                            Text("${b.formatCenterWhTag()} ${b.text}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Granite JSON:", style = MaterialTheme.typography.labelMedium)
                        Text(
                            result.prettyJsonText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/**
 * Mirrors `RunTimers` from `GlinerLabScreen.kt` for multi-image dataset runs (`Run time`, `Avg / image`, `Est. remaining`).
 */
@Composable
private fun GraniteDatasetBatchTimers(
    elapsedMs: Long,
    done: Int,
    total: Int
) {
    fun formatClock(ms: Long): String =
        String.format(Locale.ROOT, "%02dm %02ds", ms / 60000L, (ms / 1000L) % 60L)

    val avg = if (done > 0) elapsedMs / done else 0L
    val remaining = if (done > 0) avg * (total - done).coerceAtLeast(0) else 0L
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            "Run time" to formatClock(elapsedMs),
            "Avg / image" to if (done > 0) String.format(Locale.ROOT, "%.1fs", avg / 1000f) else "—",
            "Est. remaining" to if (done > 0) formatClock(remaining) else "—"
        ).forEach { (title, value) ->
            Column(
                Modifier
                    .weight(1f)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(6.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Mirrors `RunTimers` from `GlinerLabScreen.kt`: 3 equal-width `0.5.dp` `outlineVariant` bordered cells.
 */
@Composable
private fun GraniteRunTimers(
    elapsedMs: Long,
    ocrMs: Long,
    graniteMs: Long,
    hasResult: Boolean
) {
    fun formatDuration(ms: Long): String =
        if (ms >= 1000L) {
            String.format(Locale.ROOT, "%.2fs (%d ms)", ms / 1000f, ms)
        } else {
            "${ms} ms"
        }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            "Run time" to if (hasResult) formatDuration(elapsedMs) else "—",
            "Dual OCR" to if (hasResult) formatDuration(ocrMs) else "—",
            "Granite 4.2" to if (hasResult) formatDuration(graniteMs) else "—"
        ).forEach { (title, value) ->
            Column(
                Modifier
                    .weight(1f)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(6.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Mirrors `ScoredResults` (`ScoreSummaryTable` + `ScoreFieldTable`) from `GlinerLabScreen.kt`
 * using the real `PhoneCorpusMath` scores across the 124-label GLiNER dataset (`wine-app-labels-v1`).
 */
@Composable
private fun GraniteDatasetScoredResults(aggregate: GraniteDatasetAggregate) {
    val summaryRow = listOf(
        "Granite 4.2",
        aggregate.attempted.toString(),
        aggregate.eligible.toString(),
        "${aggregate.complete}/${aggregate.eligible}",
        aggregate.oneError.toString(),
        aggregate.multiError.toString(),
        aggregate.failed.toString()
    )
    val fieldPairs = graniteFieldLabels.associate { (key, _) ->
        val correct = aggregate.fieldCorrect[key] ?: 0
        val total = aggregate.fieldTotal[key] ?: 0
        key to (correct to (total - correct).coerceAtLeast(0))
    }
    Column(Modifier.testTag("granite_curated_score_tables")) {
        Text("Scored results", style = MaterialTheme.typography.titleMedium)
        GraniteScoreSummaryTable(rows = listOf(summaryRow))
        Spacer(Modifier.height(8.dp))
        GraniteScoreFieldTable(rows = listOf("Granite 4.2" to fieldPairs))
        Text(
            aggregate.text(),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Fallback `Scored results` table when only a single gallery/camera/synthetic image has been run.
 */
@Composable
private fun GraniteSingleImageScoredResults(result: GraniteWorkflowRunResult) {
    val s = result.structuredJson
    val fieldStatuses = linkedMapOf(
        "producer" to !s.producerOrWinery.isNullOrBlank(),
        "wine_name" to (!s.wineNameOrCuvee.isNullOrBlank() || s.varietals.isNotEmpty()),
        "country" to !s.country.isNullOrBlank(),
        "region" to (!s.region.isNullOrBlank() || !s.appellationOrRegion.isNullOrBlank()),
        "subregion" to (!s.subregion.isNullOrBlank() || !s.appellationOrRegion.isNullOrBlank()),
        "vintage" to (s.vintage != null || s.isNonVintage)
    )
    val populatedCount = fieldStatuses.values.count { it }
    val missingCount = fieldStatuses.size - populatedCount
    val summaryRow = listOf(
        "Granite 4.2",
        "1",
        "1",
        "$populatedCount/${fieldStatuses.size}",
        if (missingCount == 1) "1" else "0",
        if (missingCount > 1) "1" else "0",
        "0"
    )
    val fieldPairs = fieldStatuses.mapValues { (_, ok) ->
        if (ok) (1 to 0) else (0 to 1)
    }

    Column(Modifier.testTag("granite_curated_score_tables")) {
        Text("Scored results", style = MaterialTheme.typography.titleMedium)
        GraniteScoreSummaryTable(rows = listOf(summaryRow))
        Spacer(Modifier.height(8.dp))
        GraniteScoreFieldTable(rows = listOf("Granite 4.2" to fieldPairs))
        Text(
            "Backend: ${result.executionBackendNote} · PP-OCRv6: ${result.ppOcrStatusNote}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun GraniteScoreSummaryTable(rows: List<List<String>>) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        val headers = listOf("Pipeline", "Attempted", "Eligible", "Fully correct", "One error", "2+ errors", "Failed")
        Row {
            headers.forEach {
                Text(
                    it,
                    Modifier
                        .width(88.dp)
                        .padding(4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        rows.forEach { values ->
            HorizontalDivider(Modifier.width((88 * headers.size).dp))
            Row {
                values.forEach {
                    Text(
                        it,
                        Modifier
                            .width(88.dp)
                            .padding(4.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun GraniteScoreFieldTable(rows: List<Pair<String, Map<String, Pair<Int, Int>>>>) {
    val pass = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val fail = MaterialTheme.colorScheme.error
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        val columns = graniteFieldLabels + ("total_correct" to "Total correct")
        Row {
            Text(
                "Build",
                Modifier
                    .width(128.dp)
                    .padding(4.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false
            )
            columns.forEach { (_, label) ->
                Text(
                    label,
                    Modifier
                        .width(88.dp)
                        .padding(4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        rows.forEach { (title, scores) ->
            HorizontalDivider(Modifier.width((128 + 88 * columns.size).dp))
            Row {
                Text(
                    title,
                    Modifier
                        .width(128.dp)
                        .padding(4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                columns.forEach { (key, _) ->
                    val score = if (key == "total_correct") {
                        scores.values.sumOf { it.first } to scores.values.sumOf { it.second }
                    } else {
                        scores[key]
                    }
                    val cell = buildAnnotatedString {
                        if (score == null) {
                            append("—")
                        } else {
                            withStyle(SpanStyle(color = pass)) { append("${score.first}") }
                            append("/")
                            withStyle(SpanStyle(color = fail)) { append("${score.second}") }
                        }
                    }
                    Text(
                        cell,
                        Modifier
                            .width(88.dp)
                            .padding(4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorSection1OverlayCard(
    state: GraniteWorkflowUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleMlKitBoxes: (Boolean) -> Unit,
    onTogglePpOcrBoxes: (Boolean) -> Unit
) {
    val result = state.lastResult
    val bmp = result?.preprocessedBitmap ?: state.previewBitmap

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inspector_section_1_overlay")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "1. Preprocessed Image + Dual-OCR Bounding Box Overlay",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (result != null) {
                            "Original ${result.originalWidth}x${result.originalHeight} → Preprocessed ${result.preprocessedWidth}x${result.preprocessedHeight} · CLAHE ${if (result.claheApplied) "ON" else "OFF"}"
                        } else {
                            "Blue = ML Kit (${result?.mlKitBlocks?.size ?: 0}) · Green = PP-OCRv6 (${result?.ppOcrV6Blocks?.size ?: 0})"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (expanded) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.showMlKitBoxes,
                        onClick = { onToggleMlKitBoxes(!state.showMlKitBoxes) },
                        label = {
                            Text("Blue: ML Kit (${result?.mlKitBlocks?.size ?: 0})")
                        }
                    )
                    FilterChip(
                        selected = state.showPpOcrBoxes,
                        onClick = { onTogglePpOcrBoxes(!state.showPpOcrBoxes) },
                        label = {
                            Text("Green: PP-OCRv6 (${result?.ppOcrV6Blocks?.size ?: 0})")
                        }
                    )
                }

                if (bmp == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No image loaded yet. Tap 'Choose images', 'Snap photo', 'Use GLiNER dataset', or 'Sample label'.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    val aspect = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()).coerceIn(0.45f, 1.8f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(6.dp))
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Preprocessed Wine Label",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = 22f
                                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                                color = android.graphics.Color.WHITE
                            }
                            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.FILL
                            }

                            if (state.showMlKitBoxes && result != null) {
                                result.mlKitBlocks.forEachIndexed { idx, block ->
                                    val left = block.normBbox.left * size.width
                                    val top = block.normBbox.top * size.height
                                    val w = block.normBbox.width() * size.width
                                    val h = block.normBbox.height() * size.height
                                    drawRect(
                                        color = MlKitOverlayBlue,
                                        topLeft = Offset(left, top),
                                        size = Size(w, h),
                                        style = Stroke(width = 3.5f)
                                    )
                                    drawContext.canvas.nativeCanvas.apply {
                                        val tag = "M${idx + 1}"
                                        bgPaint.color = android.graphics.Color.argb(200, 41, 121, 255)
                                        val tw = labelPaint.measureText(tag)
                                        drawRect(
                                            left,
                                            (top - 26f).coerceAtLeast(0f),
                                            left + tw + 10f,
                                            top.coerceAtLeast(26f),
                                            bgPaint
                                        )
                                        drawText(tag, left + 5f, (top - 6f).coerceAtLeast(20f), labelPaint)
                                    }
                                }
                            }

                            if (state.showPpOcrBoxes && result != null) {
                                result.ppOcrV6Blocks.forEachIndexed { idx, block ->
                                    val left = block.normBbox.left * size.width
                                    val top = block.normBbox.top * size.height
                                    val w = block.normBbox.width() * size.width
                                    val h = block.normBbox.height() * size.height
                                    drawRect(
                                        color = PpOcrOverlayGreen,
                                        topLeft = Offset(left, top),
                                        size = Size(w, h),
                                        style = Stroke(width = 3.5f)
                                    )
                                    drawContext.canvas.nativeCanvas.apply {
                                        val tag = "P${idx + 1}"
                                        bgPaint.color = android.graphics.Color.argb(200, 0, 200, 83)
                                        val tw = labelPaint.measureText(tag)
                                        val rx = (left + w - tw - 10f).coerceAtLeast(0f)
                                        drawRect(
                                            rx,
                                            (top - 26f).coerceAtLeast(0f),
                                            rx + tw + 10f,
                                            top.coerceAtLeast(26f),
                                            bgPaint
                                        )
                                        drawText(tag, rx + 5f, (top - 6f).coerceAtLeast(20f), labelPaint)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorSection2SideBySideOcrCard(
    state: GraniteWorkflowUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val result = state.lastResult

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inspector_section_2_side_by_side")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "2. Side-by-Side Raw OCR Comparison",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Sorted top-to-bottom (y_center), left-to-right (x_center) with normalized [x, y, w, h] coordinates",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (expanded) {
                if (result == null) {
                    Text(
                        "Run the workflow to compare ML Kit and PP-OCRv6 raw coordinate-tagged blocks side by side.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    SelectionContainer {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OcrEngineColumnBox(
                                modifier = Modifier.weight(1f),
                                title = "ML Kit (${result.mlKitBlocks.size} · ${result.timingAndMemory.mlKitMs} ms)",
                                blocks = result.mlKitBlocks,
                                emptyNote = if (result.ocrMode.runsMlKit) "0 blocks" else "Disabled (${result.ocrMode.displayLabel})"
                            )
                            OcrEngineColumnBox(
                                modifier = Modifier.weight(1f),
                                title = "PP-OCRv6 (${result.ppOcrV6Blocks.size} · ${result.timingAndMemory.ppOcrV6Ms} ms)",
                                blocks = result.ppOcrV6Blocks,
                                emptyNote = if (result.ocrMode.runsPpOcrV6) result.ppOcrStatusNote else "Disabled (${result.ocrMode.displayLabel})"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrEngineColumnBox(
    modifier: Modifier,
    title: String,
    blocks: List<OcrBlock>,
    emptyNote: String
) {
    Column(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        HorizontalDivider()
        if (blocks.isEmpty()) {
            Text(
                text = emptyNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            blocks.forEachIndexed { idx, block ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "#${idx + 1} ${block.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${block.formatCenterWhTag()} conf=${String.format(Locale.US, "%.2f", block.confidence)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorSection3GraniteOutputCard(
    state: GraniteWorkflowUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val result = state.lastResult
    var thinkExpanded by remember { mutableStateOf(false) }
    var ragExpanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inspector_section_3_granite_output")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "3. Granite 4.2 Reconciled Output",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Strict 12-field JSON schema · Database RAG candidates · <think> trace · latency & peak RAM",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (expanded) {
                if (result == null) {
                    Text(
                        "Run the workflow to inspect the Granite 4.2 reconciled JSON output, Database RAG candidates, <think> trace, and stage timings.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    val t = result.timingAndMemory
                    val rag = result.ragContext
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Per-stage latency & peak RAM grid styled like GlinerLabScreen's RunTimers
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "Preprocess ms" to "${t.preprocessMs} ms",
                                    "ML Kit ms" to "${t.mlKitMs} ms",
                                    "PP-OCRv6 ms" to "${t.ppOcrV6Ms} ms"
                                ).forEach { (label, value) ->
                                    TimingStatBox(modifier = Modifier.weight(1f), label = label, value = value)
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "DB RAG ms" to "${t.dbRagMs} ms",
                                    "Granite 4.2 ms" to "${t.graniteMs} ms",
                                    "Total ms" to "${t.totalMs} ms",
                                    "Peak Heap/Native RAM MB" to String.format(
                                        Locale.US,
                                        "%.1f / %.1f MB",
                                        t.peakJvmHeapMb,
                                        t.peakNativeHeapMb
                                    )
                                ).forEach { (label, value) ->
                                    TimingStatBox(modifier = Modifier.weight(1f), label = label, value = value)
                                }
                            }

                            // Collapsible Database RAG Candidates section
                            TextButton(
                                onClick = { ragExpanded = !ragExpanded },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    "Database RAG Candidates (${rag.producerCandidates.size} producers · ${rag.linkedWineCandidates.size} wines · ${rag.appellationCandidates.size} appellations) " +
                                        if (ragExpanded) "▲" else "▼"
                                )
                            }
                            AnimatedVisibility(visible = ragExpanded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = rag.toPromptRagSection(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            // Collapsible <think> reasoning trace
                            TextButton(
                                onClick = { thinkExpanded = !thinkExpanded },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    "<think> Reasoning Trace (${if (result.thinkModeEnabled) "ON" else "OFF — bypassed"}) " +
                                        if (thinkExpanded) "▲" else "▼"
                                )
                            }
                            AnimatedVisibility(visible = thinkExpanded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = result.rawThinkingTrace
                                            ?: "Thinking trace disabled (<think> OFF for fast structured JSON generation). Enable '<think> ON' in the parameter bar to capture step-by-step spatial reasoning.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            // Formatted JSON output
                            Text(
                                "Formatted JSON Output:",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = result.prettyJsonText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingStatBox(
    modifier: Modifier,
    label: String,
    value: String
) {
    Column(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InspectorSection4ActionsCard(
    state: GraniteWorkflowUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCopyJson: (String) -> Unit,
    onShareJson: (String) -> Unit
) {
    val result = state.lastResult

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inspector_section_4_actions")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "4. Copy & Share Actions",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Copy JSON to clipboard or share via Android Intent",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (expanded) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = { result?.let { onCopyJson(it.prettyJsonText) } },
                        enabled = result != null,
                        modifier = Modifier.testTag("copy_json_button")
                    ) {
                        Text("Copy JSON")
                    }
                    TextButton(
                        onClick = { result?.let { onShareJson(it.prettyJsonText) } },
                        enabled = result != null,
                        modifier = Modifier.testTag("share_json_button")
                    ) {
                        Text("Share JSON")
                    }
                }

                if (result != null) {
                    val s = result.structuredJson
                    HorizontalDivider()
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            FieldSummaryLine("Producer / Winery", s.producerOrWinery ?: "null")
                            FieldSummaryLine("Wine Name / Cuvée", s.wineNameOrCuvee ?: "null")
                            FieldSummaryLine(
                                "Vintage",
                                if (s.isNonVintage) "null (NV / Non-Vintage = true)" else (s.vintage?.toString() ?: "null")
                            )
                            FieldSummaryLine(
                                "Varietals",
                                if (s.varietals.isEmpty()) "[]" else s.varietals.joinToString(", ")
                            )
                            FieldSummaryLine("Appellation / Region", s.appellationOrRegion ?: "null")
                            FieldSummaryLine("Country", s.country ?: "null")
                            FieldSummaryLine("Classification", s.classification ?: "null")
                            FieldSummaryLine("ABV / Volume", "${s.abvPercentage?.let { "$it%" } ?: "null"} · ${s.bottleVolumeMl?.let { "${it}mL" } ?: "null"}")
                            if (s.ocrDiscrepanciesResolved.isNotEmpty()) {
                                FieldSummaryLine(
                                    "Discrepancies Resolved",
                                    s.ocrDiscrepanciesResolved.joinToString("; ")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldSummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.58f)
        )
    }
}

private fun buildGraniteReportText(state: GraniteWorkflowUiState): String {
    val result = state.lastResult
    return buildString {
        appendLine("Dual OCR + Granite 4.2 4B Workflow · ${BuildConfig.BUILD_COMMIT}")
        appendLine("OCR Mode: ${state.ocrMode.displayLabel} · <think>: ${state.thinkModeEnabled} · CLAHE: ${state.claheEnabled}")
        appendLine("Image: ${state.imageSourceLabel}")
        state.datasetAggregate?.let { agg ->
            appendLine("GLiNER 124-label dataset: ${agg.text()}")
        }
        if (result != null) {
            val t = result.timingAndMemory
            appendLine(
                "Timing: preprocess ${t.preprocessMs} ms · ML Kit ${t.mlKitMs} ms · PP-OCRv6 ${t.ppOcrV6Ms} ms · Granite ${t.graniteMs} ms · total ${t.totalMs} ms"
            )
            appendLine(
                String.format(
                    Locale.US,
                    "Peak RAM: JVM %.1f MB · Native %.1f MB · Combined %.1f MB",
                    t.peakJvmHeapMb,
                    t.peakNativeHeapMb,
                    t.peakCombinedRamMb
                )
            )
            appendLine("ML Kit blocks (${result.mlKitBlocks.size}):")
            result.mlKitBlocks.forEach { b ->
                appendLine("  ${b.formatCenterWhTag()} conf=${String.format(Locale.US, "%.2f", b.confidence)} ${b.text}")
            }
            appendLine("PP-OCRv6 blocks (${result.ppOcrV6Blocks.size}):")
            result.ppOcrV6Blocks.forEach { b ->
                appendLine("  ${b.formatCenterWhTag()} conf=${String.format(Locale.US, "%.2f", b.confidence)} ${b.text}")
            }
            if (!result.rawThinkingTrace.isNullOrBlank()) {
                appendLine("<think>:")
                appendLine(result.rawThinkingTrace)
            }
            appendLine("Granite 4.2 JSON:")
            appendLine(result.prettyJsonText)
        }
    }
}
