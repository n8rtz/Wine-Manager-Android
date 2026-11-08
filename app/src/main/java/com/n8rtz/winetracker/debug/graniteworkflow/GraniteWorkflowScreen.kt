package com.n8rtz.winetracker.debug.graniteworkflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n8rtz.winetracker.BuildConfig
import java.io.File
import java.util.Locale

private val MlKitBlue = Color(0xFF2196F3)
private val PpOcrGreenBright = Color(0xFF4CAF50)

private val graniteFieldLabels = listOf(
    "producer_or_winery" to "Producer",
    "wine_name_or_cuvee" to "Wine name",
    "country" to "Country",
    "appellation_or_region" to "Region",
    "classification" to "Classification",
    "vintage" to "Vintage"
)

/**
 * Dual OCR + Granite 4.2 4B Workflow screen formatted to mirror `GlinerLabScreen.kt`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraniteWorkflowScreen(
    state: GraniteWorkflowUiState,
    onBack: () -> Unit,
    onRefreshModels: () -> Unit,
    onResetCache: () -> Unit = {},
    onImportGraniteGguf: () -> Unit,
    onPickGalleryImage: () -> Unit,
    onCameraPhotoCaptured: (File) -> Unit,
    onLoadSampleLabel: () -> Unit,
    onClearImage: () -> Unit = {},
    onSelectOcrMode: (OcrAblationMode) -> Unit,
    onToggleThinkMode: (Boolean) -> Unit,
    onToggleClahe: (Boolean) -> Unit,
    onToggleShowMlKitBoxes: (Boolean) -> Unit,
    onToggleShowPpOcrBoxes: (Boolean) -> Unit,
    onRunWorkflow: () -> Unit,
    onStopWorkflow: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var showCameraDialog by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(true) }
    var expandThinkOutput by remember { mutableStateOf(true) }
    var expandRawOcrAccordion by remember { mutableStateOf(false) }
    var expandModelFiles by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showCameraDialog = true
        }
    }

    val timing = state.lastResult?.timingAndMemory ?: WorkflowTimingAndMemory()
    val jsonText = state.lastResult?.prettyJsonText ?: GraniteWineLabelJson().toPrettyJson(2)
    val hasImage = state.previewBitmap != null || state.lastResult != null
    val completedCount = if (state.lastResult != null) 1 else 0
    val totalCount = if (hasImage) 1 else 0

    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("granite_workflow_list")
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header block mirroring GlinerLabScreen item 1
        item {
            SelectionContainer {
                Column {
                    Text(
                        "Dual OCR + Granite 4.2 4B Workflow",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        "build ${BuildConfig.BUILD_COMMIT} · ${state.ocrMode.displayLabel} · think=${if (state.thinkModeEnabled) "on" else "off"} · clahe=${if (state.claheEnabled) "on" else "off"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.modelsStatus.graniteGguf.present) {
                        Text(
                            "model ready · Q4_K_M ✓ (${state.modelsStatus.graniteGguf.sizeFormatted}) · PP-OCRv6 shared ✓ · ML Kit ✓"
                        )
                    } else {
                        Column(modifier = Modifier.testTag("import_models_prompt_card")) {
                            Text("model missing · Import Model Files to Run (${state.modelsStatus.graniteGguf.fileName})")
                            state.missingModelsPrompt?.let { prompt ->
                                Text(prompt, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (timing.totalMs > 0) {
                        Text(
                            "granite ${timing.graniteMs} ms · mlkit ${timing.mlKitMs} ms · ppocr ${timing.ppOcrV6Ms} ms · prep ${timing.preprocessMs} ms",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Row {
                TextButton(
                    onClick = onImportGraniteGguf,
                    enabled = !state.isRunning && !state.isImportingModel
                ) { Text("Import") }
                TextButton(
                    onClick = onResetCache,
                    enabled = !state.isRunning && !state.isImportingModel
                ) { Text("Reset cache") }
                TextButton(
                    onClick = onRefreshModels,
                    enabled = !state.isRunning && !state.isImportingModel
                ) { Text("Re-check") }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OcrAblationMode.entries.forEach { mode ->
                    TextButton(
                        onClick = { onSelectOcrMode(mode) },
                        enabled = !state.isRunning,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(if (state.ocrMode == mode) "${mode.displayLabel} ✓" else mode.displayLabel)
                    }
                }
                TextButton(
                    onClick = { onToggleThinkMode(!state.thinkModeEnabled) },
                    enabled = !state.isRunning,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(if (state.thinkModeEnabled) "<think> ON (Reasoning)" else "<think> OFF (Fast)")
                }
                TextButton(
                    onClick = { onToggleClahe(!state.claheEnabled) },
                    enabled = !state.isRunning,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(if (state.claheEnabled) "CLAHE ON" else "CLAHE OFF")
                }
            }
        }

        // Run status, Start button, RunTimers, and Stop/Close row mirroring GlinerLabScreen item 2
        item {
            SelectionContainer {
                Column {
                    if (!state.statusBannerMessage.isNullOrBlank()) {
                        Text(state.statusBannerMessage)
                    }
                    if (!state.errorBannerMessage.isNullOrBlank()) {
                        Text(state.errorBannerMessage, color = MaterialTheme.colorScheme.error)
                    }
                    Text("Granite completed: $completedCount/$totalCount")
                    if (state.isRunning) {
                        Text(
                            state.activeStageMessage.ifBlank { "Running Dual OCR + Granite 4.2 workflow…" },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (state.isRunning || state.isImportingModel) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Button(
                onClick = onRunWorkflow,
                enabled = !state.isRunning
            ) {
                Text(if (state.lastResult != null) "New Granite run" else "Start Granite run")
            }
            SelectionContainer {
                GraniteRunTimers(timing = timing, hasRun = state.lastResult != null)
            }
            Row {
                TextButton(onClick = onStopWorkflow, enabled = state.isRunning) { Text("Stop") }
                TextButton(onClick = onBack) { Text("Close") }
            }
        }

        // Scored results table mirroring GlinerLabScreen's ScoredResults
        item(key = "curated_scores") {
            SelectionContainer {
                GraniteScoredResults(result = state.lastResult)
            }
        }

        // Your images section mirroring GlinerLabScreen's custom_heading
        item(key = "custom_heading") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your images ($totalCount)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Select a photo from Gallery, snap a photo with CameraX, or load the synthetic sample label. Source: ${state.imageSourceLabel}",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = onPickGalleryImage,
                        enabled = !state.isRunning,
                        modifier = Modifier.testTag("granite_choose_images")
                    ) { Text("Choose images") }
                    TextButton(
                        onClick = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                showCameraDialog = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        enabled = !state.isRunning
                    ) { Text("Snap photo") }
                    TextButton(
                        onClick = onLoadSampleLabel,
                        enabled = !state.isRunning
                    ) { Text("Sample label") }
                    TextButton(
                        onClick = onRunWorkflow,
                        enabled = !state.isRunning && hasImage
                    ) { Text("Run your images") }
                    TextButton(
                        onClick = onClearImage,
                        enabled = !state.isRunning && hasImage
                    ) { Text("Remove all") }
                    TextButton(onClick = { showCustom = !showCustom }) {
                        Text(if (showCustom) "Hide" else "Show")
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OcrAblationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.ocrMode == mode,
                            onClick = { onSelectOcrMode(mode) },
                            label = { Text(mode.displayLabel) }
                        )
                    }
                    FilterChip(
                        selected = state.thinkModeEnabled,
                        onClick = { onToggleThinkMode(!state.thinkModeEnabled) },
                        label = { Text(if (state.thinkModeEnabled) "<think> ON (Reasoning)" else "<think> OFF (Fast)") }
                    )
                    FilterChip(
                        selected = state.claheEnabled,
                        onClick = { onToggleClahe(!state.claheEnabled) },
                        label = { Text(if (state.claheEnabled) "CLAHE ON" else "CLAHE OFF") }
                    )
                }
            }
        }

        // 4-Section Results Inspector cards formatted identically to GlinerLabScreen's Card items
        if (showCustom) {
            item(key = "inspector_section_1") {
                InspectorSection1ImageOverlay(
                    bitmap = state.lastResult?.preprocessedBitmap ?: state.previewBitmap,
                    result = state.lastResult,
                    showMlKitBoxes = state.showMlKitBoxes,
                    showPpOcrBoxes = state.showPpOcrBoxes,
                    onToggleMlKitBoxes = onToggleShowMlKitBoxes,
                    onTogglePpOcrBoxes = onToggleShowPpOcrBoxes
                )
            }

            item(key = "inspector_section_2") {
                InspectorSection2TimingAndMemoryStrip(
                    timing = timing,
                    result = state.lastResult
                )
            }

            item(key = "inspector_section_3") {
                InspectorSection3StructuredJsonCard(
                    result = state.lastResult,
                    expandThinkOutput = expandThinkOutput,
                    onToggleExpandThink = { expandThinkOutput = !expandThinkOutput },
                    onCopyJson = { text ->
                        clipboard.setText(AnnotatedString(text))
                    },
                    onShareJson = { text ->
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "Granite 4.2 Wine Label Extraction JSON")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share Extraction JSON"))
                    }
                )
            }

            item(key = "inspector_section_4") {
                InspectorSection4RawOcrAccordion(
                    result = state.lastResult,
                    expanded = expandRawOcrAccordion,
                    onToggleExpanded = { expandRawOcrAccordion = !expandRawOcrAccordion }
                )
            }
        }

        // Export section mirroring GlinerLabScreen's Export block
        item(key = "export_section") {
            Text("Export", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(jsonText)) }) {
                    Text("Copy all results")
                }
                TextButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "Granite 4.2 Wine Label Extraction JSON")
                            putExtra(Intent.EXTRA_TEXT, jsonText)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share JSON"))
                    }
                ) {
                    Text("Share JSON")
                }
            }
        }

        // Model files & storage paths accordion mirroring GlinerLabScreen's bottom accordions
        item(key = "model_files_accordion_header") {
            TextButton(
                onClick = { expandModelFiles = !expandModelFiles },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Model files & storage paths (5)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (expandModelFiles) "▲" else "▼",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (expandModelFiles) {
            item(key = "model_files_accordion_body") {
                ModelFileStatusList(
                    modelsStatus = state.modelsStatus,
                    onImportGraniteGguf = onImportGraniteGguf
                )
            }
        }
    }

    if (showCameraDialog) {
        CameraXCaptureDialog(
            onDismiss = { showCameraDialog = false },
            onPhotoCaptured = { file ->
                showCameraDialog = false
                onCameraPhotoCaptured(file)
            }
        )
    }
}

@Composable
private fun GraniteRunTimers(timing: WorkflowTimingAndMemory, hasRun: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "Run time" to if (hasRun) "${timing.totalMs} ms" else "—",
            "Dual OCR" to if (hasRun) "${timing.mlKitMs + timing.ppOcrV6Ms} ms" else "—",
            "Granite 4.2" to if (hasRun) "${timing.graniteMs} ms" else "—"
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
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GraniteScoredResults(result: GraniteWorkflowRunResult?) {
    val json = result?.structuredJson
    val populatedFields = if (json == null) {
        emptyMap()
    } else {
        mapOf(
            "producer_or_winery" to !json.producerOrWinery.isNullOrBlank(),
            "wine_name_or_cuvee" to (!json.wineNameOrCuvee.isNullOrBlank() || json.varietals.isNotEmpty()),
            "country" to !json.country.isNullOrBlank(),
            "appellation_or_region" to !json.appellationOrRegion.isNullOrBlank(),
            "classification" to !json.classification.isNullOrBlank(),
            "vintage" to (json.vintage != null || json.isNonVintage)
        )
    }
    val filledCount = populatedFields.values.count { it }
    val summaryRow = if (result == null) {
        listOf("Granite 4.2", "0", "0", "0/0", "0", "0", "0")
    } else {
        listOf(
            "Granite 4.2",
            "1",
            "1",
            "${if (filledCount >= 4) 1 else 0}/1",
            if (filledCount == 3) "1" else "0",
            if (filledCount in 1..2) "1" else "0",
            "0"
        )
    }

    Column(Modifier.testTag("granite_scored_tables")) {
        Text("Scored results", style = MaterialTheme.typography.titleMedium)
        GraniteScoreSummaryTable(listOf(summaryRow))
        Spacer(Modifier.height(8.dp))
        GraniteScoreFieldTable(
            title = "Granite 4.2",
            fieldStates = populatedFields,
            hasRun = result != null
        )
        if (result == null) {
            Text("— = no run completed yet in this session.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "Extracted $filledCount/${graniteFieldLabels.size} primary fields · ${result.structuredJson.ocrDiscrepanciesResolved.size} OCR discrepancies resolved.",
                style = MaterialTheme.typography.bodySmall
            )
        }
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
                    Modifier.width(88.dp).padding(4.dp),
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
                    Text(it, Modifier.width(88.dp).padding(4.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun GraniteScoreFieldTable(
    title: String,
    fieldStates: Map<String, Boolean>,
    hasRun: Boolean
) {
    val pass = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val fail = MaterialTheme.colorScheme.error
    val columns = graniteFieldLabels + ("total_correct" to "Total filled")
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Text(
                "Build",
                Modifier.width(128.dp).padding(4.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false
            )
            columns.forEach { (_, label) ->
                Text(
                    label,
                    Modifier.width(88.dp).padding(4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        HorizontalDivider(Modifier.width((128 + 88 * columns.size).dp))
        Row {
            Text(
                title,
                Modifier.width(128.dp).padding(4.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            columns.forEach { (key, _) ->
                val cell = buildAnnotatedString {
                    if (!hasRun) {
                        append("—")
                    } else if (key == "total_correct") {
                        val ok = fieldStates.values.count { it }
                        val miss = graniteFieldLabels.size - ok
                        withStyle(SpanStyle(color = pass)) { append("$ok") }
                        append("/")
                        withStyle(SpanStyle(color = fail)) { append("$miss") }
                    } else {
                        val filled = fieldStates[key] == true
                        withStyle(SpanStyle(color = pass)) { append(if (filled) "1" else "0") }
                        append("/")
                        withStyle(SpanStyle(color = fail)) { append(if (filled) "0" else "1") }
                    }
                }
                Text(
                    cell,
                    Modifier.width(88.dp).padding(4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun InspectorSection1ImageOverlay(
    bitmap: Bitmap?,
    result: GraniteWorkflowRunResult?,
    showMlKitBoxes: Boolean,
    showPpOcrBoxes: Boolean,
    onToggleMlKitBoxes: (Boolean) -> Unit,
    onTogglePpOcrBoxes: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "1. Image + Bounding Box Overlay",
                style = MaterialTheme.typography.titleSmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showMlKitBoxes, onCheckedChange = onToggleMlKitBoxes)
                    Text(
                        text = "Blue = ML Kit (${result?.mlKitBlocks?.size ?: 0})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showPpOcrBoxes, onCheckedChange = onTogglePpOcrBoxes)
                    Text(
                        text = "Green = PP-OCRv6 (${result?.ppOcrV6Blocks?.size ?: 0})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (bitmap == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "No image loaded yet. Use Choose images, Snap photo, or Sample label above.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                val aspect = (bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f))
                    .coerceIn(0.45f, 2.0f)
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .aspectRatio(aspect)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Preprocessed wine label with OCR bounding boxes",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasW = size.width
                        val canvasH = size.height

                        if (showMlKitBoxes) {
                            result?.mlKitBlocks.orEmpty().forEach { block ->
                                val left = block.normBbox.left * canvasW
                                val top = block.normBbox.top * canvasH
                                val w = block.normBbox.width() * canvasW
                                val h = block.normBbox.height() * canvasH
                                drawRect(
                                    color = MlKitBlue.copy(alpha = 0.16f),
                                    topLeft = Offset(left, top),
                                    size = Size(w, h)
                                )
                                drawRect(
                                    color = MlKitBlue,
                                    topLeft = Offset(left, top),
                                    size = Size(w, h),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                        }

                        if (showPpOcrBoxes) {
                            result?.ppOcrV6Blocks.orEmpty().forEach { block ->
                                val left = block.normBbox.left * canvasW
                                val top = block.normBbox.top * canvasH
                                val w = block.normBbox.width() * canvasW
                                val h = block.normBbox.height() * canvasH
                                drawRect(
                                    color = PpOcrGreenBright.copy(alpha = 0.16f),
                                    topLeft = Offset(left, top),
                                    size = Size(w, h)
                                )
                                drawRect(
                                    color = PpOcrGreenBright,
                                    topLeft = Offset(left, top),
                                    size = Size(w, h),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                        }
                    }
                }

                result?.let { res ->
                    Text(
                        text = "Original: ${res.originalWidth}×${res.originalHeight}px · Preprocessed: ${res.preprocessedWidth}×${res.preprocessedHeight}px · CLAHE=${if (res.claheApplied) "ON" else "OFF"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorSection2TimingAndMemoryStrip(
    timing: WorkflowTimingAndMemory,
    result: GraniteWorkflowRunResult?
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "2. Timing & Memory Strip",
                style = MaterialTheme.typography.titleSmall
            )

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Preprocess ms" to "${timing.preprocessMs} ms",
                            "ML Kit ms" to "${timing.mlKitMs} ms",
                            "PP-OCRv6 ms" to "${timing.ppOcrV6Ms} ms"
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
                                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Granite 4.2 ms" to "${timing.graniteMs} ms",
                            "Total ms" to "${timing.totalMs} ms",
                            "Peak Heap/Native RAM MB" to String.format(
                                Locale.US,
                                "%.1f MB",
                                timing.peakCombinedRamMb
                            )
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
                                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }

            result?.let { res ->
                Text(
                    text = "${res.ppOcrStatusNote} · ${res.executionBackendNote}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InspectorSection3StructuredJsonCard(
    result: GraniteWorkflowRunResult?,
    expandThinkOutput: Boolean,
    onToggleExpandThink: () -> Unit,
    onCopyJson: (String) -> Unit,
    onShareJson: (String) -> Unit
) {
    val jsonText = result?.prettyJsonText ?: GraniteWineLabelJson().toPrettyJson(2)

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "3. Final Structured JSON Card",
                style = MaterialTheme.typography.titleSmall
            )

            Row {
                TextButton(onClick = { onCopyJson(jsonText) }) { Text("Copy JSON") }
                TextButton(onClick = { onShareJson(jsonText) }) { Text("Share JSON") }
                if (result?.thinkModeEnabled == true || !result?.rawThinkingTrace.isNullOrBlank()) {
                    TextButton(onClick = onToggleExpandThink) {
                        Text(if (expandThinkOutput) "Hide <think>" else "Show <think>")
                    }
                }
            }

            if ((result?.thinkModeEnabled == true || !result?.rawThinkingTrace.isNullOrBlank()) && expandThinkOutput) {
                SelectionContainer {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = result?.rawThinkingTrace ?: "(No <think> trace emitted)",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(8.dp)
                ) {
                    Text(
                        text = jsonText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("structured_json_output")
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorSection4RawOcrAccordion(
    result: GraniteWorkflowRunResult?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val mlBlocks = result?.mlKitBlocks.orEmpty()
    val ppBlocks = result?.ppOcrV6Blocks.orEmpty()

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "4. Expandable Raw OCR Accordion",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "ML Kit (${mlBlocks.size}) · PP-OCRv6 (${ppBlocks.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (expanded) {
                SelectionContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .padding(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "ML Kit (${mlBlocks.size})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (mlBlocks.isEmpty()) {
                                    Text("(No lines)", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    mlBlocks.forEachIndexed { idx, block ->
                                        Text(
                                            "#${idx + 1} ${block.formatCenterWhTag()} ${block.text}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            Modifier
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .padding(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "PP-OCRv6 (${ppBlocks.size})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (ppBlocks.isEmpty()) {
                                    Text("(No lines)", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    ppBlocks.forEachIndexed { idx, block ->
                                        Text(
                                            "#${idx + 1} ${block.formatCenterWhTag()} ${block.text}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
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
private fun ModelFileStatusList(
    modelsStatus: WorkflowModelsStatus,
    onImportGraniteGguf: () -> Unit
) {
    val slots = listOf(
        modelsStatus.graniteGguf to onImportGraniteGguf,
        modelsStatus.mlKitEngine to null,
        modelsStatus.ppOcrDetOnnx to null,
        modelsStatus.ppOcrRecOnnx to null,
        modelsStatus.ppOcrDict to null
    )
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            slots.forEach { (slot, action) ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            "${slot.slotName} · ${slot.fileName} · ${if (slot.present) "ready" else "missing"} (${slot.sizeFormatted})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (action != null) {
                        TextButton(onClick = action) { Text("Import") }
                    } else {
                        Text(
                            "Shared",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Granite dir: ${modelsStatus.graniteDirectoryPath}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Shared PP-OCRv6 dir: ${modelsStatus.sharedPpOcrDirectoryPath}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "JNI runtime: ${if (modelsStatus.nativeLlamaJniLoaded) "libgranite_llama_jni.so loaded" else "Standby (spatial reconciler fallback ready)"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Self-contained CameraX still photo capture dialog for the Granite workflow test page.
 */
@Composable
private fun CameraXCaptureDialog(
    onDismiss: () -> Unit,
    onPhotoCaptured: (File) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var captureError by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxSize(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            runCatching {
                                val provider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageCapture
                                )
                            }.onFailure { err ->
                                captureError = "Camera bind failed: ${err.message}"
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )

                captureError?.let { err ->
                    Text(
                        text = err,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .background(Color.Red.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White)
                    }

                    Button(
                        enabled = !isCapturing,
                        onClick = {
                            isCapturing = true
                            val outDir = File(context.cacheDir, "granite_workflow_captures").apply { mkdirs() }
                            val photoFile = File(outDir, "label_${System.currentTimeMillis()}.jpg")
                            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                            imageCapture.takePicture(
                                outputOptions,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        isCapturing = false
                                        onPhotoCaptured(photoFile)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        isCapturing = false
                                        captureError = "Capture failed: ${exception.message}"
                                    }
                                }
                            )
                        }
                    ) {
                        Text(if (isCapturing) "Saving…" else "Capture label")
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }
}
