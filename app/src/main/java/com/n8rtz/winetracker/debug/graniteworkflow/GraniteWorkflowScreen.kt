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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.Locale

private val BordeauxRed = Color(0xFF722F37)
private val MlKitBlue = Color(0xFF2196F3)
private val PpOcrGreen = Color(0xFF2E7D32)
private val PpOcrGreenBright = Color(0xFF4CAF50)
private val StatusAmber = Color(0xFFE65100)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GraniteWorkflowScreen(
    state: GraniteWorkflowUiState,
    onBack: () -> Unit,
    onRefreshModels: () -> Unit,
    onImportGraniteGguf: () -> Unit,
    onPickGalleryImage: () -> Unit,
    onCameraPhotoCaptured: (File) -> Unit,
    onLoadSampleLabel: () -> Unit,
    onSelectOcrMode: (OcrAblationMode) -> Unit,
    onToggleThinkMode: (Boolean) -> Unit,
    onToggleClahe: (Boolean) -> Unit,
    onToggleShowMlKitBoxes: (Boolean) -> Unit,
    onToggleShowPpOcrBoxes: (Boolean) -> Unit,
    onRunWorkflow: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showCameraDialog by remember { mutableStateOf(false) }
    var expandModelPaths by remember { mutableStateOf(false) }
    var expandThinkOutput by remember { mutableStateOf(true) }
    var expandRawOcrAccordion by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showCameraDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Dual OCR + Granite 4.2 4B Workflow",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Isolated ML Kit v2 + PP-OCRv6 ONNX ➔ Granite 4.2 3B GGUF",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshModels) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh model status")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("granite_workflow_list"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Friendly "Import Model Files to Run" prompt when models are not yet copied onto the device
            item {
                val missingPrompt = state.missingModelsPrompt
                if (missingPrompt != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3E0)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_models_prompt_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = StatusAmber
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Import Model Files to Run",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4E342E)
                                )
                                Text(
                                    text = missingPrompt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF5D4037)
                                )
                            }
                        }
                    }
                }
            }

            // Status / Error Banner
            if (state.statusBannerMessage != null || state.errorBannerMessage != null || state.isRunning) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.errorBannerMessage != null) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (state.isRunning) {
                                Text(
                                    text = state.activeStageMessage.ifBlank { "Running workflow..." },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            state.statusBannerMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            state.errorBannerMessage?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // 1. Model File Status & Importer Card
            item {
                ModelFileStatusAndImporterCard(
                    modelsStatus = state.modelsStatus,
                    isImporting = state.isImportingModel,
                    expandPaths = expandModelPaths,
                    onToggleExpandPaths = { expandModelPaths = !expandModelPaths },
                    onImportGraniteGguf = onImportGraniteGguf
                )
            }

            // 2. Image Input Card (Pick from Gallery or Snap Photo)
            item {
                ImageInputCard(
                    imageSourceLabel = state.imageSourceLabel,
                    isRunning = state.isRunning,
                    hasImage = state.previewBitmap != null,
                    onPickGallery = onPickGalleryImage,
                    onSnapPhoto = {
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
                    onLoadSample = onLoadSampleLabel,
                    onRunWorkflow = onRunWorkflow
                )
            }

            // 3. Test Toggles Card
            item {
                TestTogglesCard(
                    ocrMode = state.ocrMode,
                    thinkModeEnabled = state.thinkModeEnabled,
                    claheEnabled = state.claheEnabled,
                    onSelectOcrMode = onSelectOcrMode,
                    onToggleThinkMode = onToggleThinkMode,
                    onToggleClahe = onToggleClahe
                )
            }

            // 4-Section Results Inspector
            item {
                Text(
                    text = "4-Section Results Inspector",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxRed,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Inspector Section 1: Image + Bounding Box Overlay
            item {
                InspectorSection1ImageOverlay(
                    bitmap = state.lastResult?.preprocessedBitmap ?: state.previewBitmap,
                    result = state.lastResult,
                    showMlKitBoxes = state.showMlKitBoxes,
                    showPpOcrBoxes = state.showPpOcrBoxes,
                    onToggleMlKitBoxes = onToggleShowMlKitBoxes,
                    onTogglePpOcrBoxes = onToggleShowPpOcrBoxes
                )
            }

            // Inspector Section 2: Timing & Memory Strip
            item {
                InspectorSection2TimingAndMemoryStrip(
                    timing = state.lastResult?.timingAndMemory ?: WorkflowTimingAndMemory(),
                    result = state.lastResult
                )
            }

            // Inspector Section 3: Final Structured JSON Card
            item {
                InspectorSection3StructuredJsonCard(
                    result = state.lastResult,
                    expandThinkOutput = expandThinkOutput,
                    onToggleExpandThink = { expandThinkOutput = !expandThinkOutput },
                    onCopyJson = { jsonText ->
                        clipboard.setText(AnnotatedString(jsonText))
                    },
                    onShareJson = { jsonText ->
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "Granite 4.2 Wine Label Extraction JSON")
                            putExtra(Intent.EXTRA_TEXT, jsonText)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share Extraction JSON"))
                    }
                )
            }

            // Inspector Section 4: Expandable Raw OCR Accordion
            item {
                InspectorSection4RawOcrAccordion(
                    result = state.lastResult,
                    expanded = expandRawOcrAccordion,
                    onToggleExpanded = { expandRawOcrAccordion = !expandRawOcrAccordion }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelFileStatusAndImporterCard(
    modelsStatus: WorkflowModelsStatus,
    isImporting: Boolean,
    expandPaths: Boolean,
    onToggleExpandPaths: () -> Unit,
    onImportGraniteGguf: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Model File Status & Importer",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BordeauxRed
                )
                TextButton(onClick = onToggleExpandPaths) {
                    Text(if (expandPaths) "Hide Paths" else "Storage Paths")
                }
            }

            ModelSlotRow(
                slot = modelsStatus.graniteGguf,
                onImportClick = onImportGraniteGguf
            )
            HorizontalDivider()
            ModelSlotRow(
                slot = modelsStatus.mlKitEngine,
                onImportClick = null
            )
            ModelSlotRow(
                slot = modelsStatus.ppOcrDetOnnx,
                onImportClick = null
            )
            ModelSlotRow(
                slot = modelsStatus.ppOcrRecOnnx,
                onImportClick = null
            )
            ModelSlotRow(
                slot = modelsStatus.ppOcrDict,
                onImportClick = null
            )

            if (isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onImportGraniteGguf,
                    enabled = !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = BordeauxRed)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import Granite .gguf File")
                }
            }

            AnimatedVisibility(visible = expandPaths) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Granite GGUF directory:\n${modelsStatus.graniteDirectoryPath}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Shared PP-OCRv6 directory (reused, no 2nd copy):\n${modelsStatus.sharedPpOcrDirectoryPath}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "JNI Runtime: ${if (modelsStatus.nativeLlamaJniLoaded) "libgranite_llama_jni.so loaded" else "Standby (spatial reconciler fallback ready)"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSlotRow(
    slot: ModelFileSlotStatus,
    onImportClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (slot.present) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (slot.present) PpOcrGreen else if (slot.required) StatusAmber else Color.Gray,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = slot.slotName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = buildString {
                        append("${slot.fileName} · ${slot.sizeFormatted}")
                        if (slot.notes.isNotBlank()) {
                            append(" · ${slot.notes}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onImportClick != null) {
            TextButton(onClick = onImportClick) {
                Text("Import")
            }
        } else {
            Text(
                text = "Reused",
                style = MaterialTheme.typography.labelSmall,
                color = PpOcrGreen,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageInputCard(
    imageSourceLabel: String,
    isRunning: Boolean,
    hasImage: Boolean,
    onPickGallery: () -> Unit,
    onSnapPhoto: () -> Unit,
    onLoadSample: () -> Unit,
    onRunWorkflow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Image Input",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BordeauxRed
            )
            Text(
                text = imageSourceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onPickGallery,
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = BordeauxRed)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pick from Gallery")
                }

                Button(
                    onClick = onSnapPhoto,
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = BordeauxRed)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Snap Photo")
                }

                OutlinedButton(
                    onClick = onLoadSample,
                    enabled = !isRunning
                ) {
                    Text("Sample Label")
                }

                if (hasImage) {
                    OutlinedButton(
                        onClick = onRunWorkflow,
                        enabled = !isRunning
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Re-run")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestTogglesCard(
    ocrMode: OcrAblationMode,
    thinkModeEnabled: Boolean,
    claheEnabled: Boolean,
    onSelectOcrMode: (OcrAblationMode) -> Unit,
    onToggleThinkMode: (Boolean) -> Unit,
    onToggleClahe: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Test Toggles",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BordeauxRed
            )

            // OCR Mode: Dual (ML Kit + PP-OCRv6) | ML Kit Only | PP-OCRv6 Only
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "OCR Mode",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OcrAblationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = ocrMode == mode,
                            onClick = { onSelectOcrMode(mode) },
                            label = { Text(mode.displayLabel) }
                        )
                    }
                }
            }

            HorizontalDivider()

            // Granite <think> Mode: OFF (Fast) | ON (Reasoning)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Granite <think> Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (thinkModeEnabled) "ON (Reasoning)" else "OFF (Fast)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !thinkModeEnabled,
                        onClick = { onToggleThinkMode(false) },
                        label = { Text("OFF (Fast)") }
                    )
                    FilterChip(
                        selected = thinkModeEnabled,
                        onClick = { onToggleThinkMode(true) },
                        label = { Text("ON (Reasoning)") }
                    )
                }
            }

            HorizontalDivider()

            // Glare Preprocessing (CLAHE): OFF | ON
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Glare Preprocessing (CLAHE)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (claheEnabled) "ON (Mild 8x8 CLAHE + foil highlight roll-off)" else "OFF (Standard resize <=1280px)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = claheEnabled,
                    onCheckedChange = onToggleClahe
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "1. Image + Bounding Box Overlay",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showMlKitBoxes, onCheckedChange = onToggleMlKitBoxes)
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(MlKitBlue, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Blue = ML Kit (${result?.mlKitBlocks?.size ?: 0})",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showPpOcrBoxes, onCheckedChange = onTogglePpOcrBoxes)
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(PpOcrGreenBright, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Green = PP-OCRv6 (${result?.ppOcrV6Blocks?.size ?: 0})",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (bitmap == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Pick an image from Gallery, Snap Photo, or tap Sample Label to inspect bounding boxes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                val aspect = (bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f))
                    .coerceIn(0.45f, 2.0f)
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
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
                        text = "Original: ${res.originalWidth}×${res.originalHeight}px ➔ Preprocessed: ${res.preprocessedWidth}×${res.preprocessedHeight}px · CLAHE=${if (res.claheApplied) "ON" else "OFF"}",
                        style = MaterialTheme.typography.labelSmall,
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "2. Timing & Memory Strip",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricPill(label = "Preprocess ms", value = "${timing.preprocessMs} ms")
                MetricPill(label = "ML Kit ms", value = "${timing.mlKitMs} ms")
                MetricPill(label = "PP-OCRv6 ms", value = "${timing.ppOcrV6Ms} ms")
                MetricPill(label = "Granite 4.2 ms", value = "${timing.graniteMs} ms")
                MetricPill(label = "Total ms", value = "${timing.totalMs} ms")
                MetricPill(
                    label = "Peak Heap/Native RAM MB",
                    value = String.format(
                        Locale.US,
                        "%.1f MB (JVM %.1f / Nat %.1f)",
                        timing.peakCombinedRamMb,
                        timing.peakJvmHeapMb,
                        timing.peakNativeHeapMb
                    )
                )
            }

            result?.let { res ->
                Text(
                    text = "${res.ppOcrStatusNote} · ${res.executionBackendNote}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "3. Final Structured JSON Card",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onCopyJson(jsonText) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy JSON")
                    }
                    IconButton(onClick = { onShareJson(jsonText) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share JSON")
                    }
                }
            }

            // Expandable raw <think> output if enabled or present
            if (result?.thinkModeEnabled == true || !result?.rawThinkingTrace.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onToggleExpandThink),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Raw <think> Reasoning Trace",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = BordeauxRed
                            )
                            Icon(
                                imageVector = if (expandThinkOutput) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle raw think output"
                            )
                        }
                        AnimatedVisibility(visible = expandThinkOutput) {
                            SelectionContainer {
                                Text(
                                    text = result?.rawThinkingTrace ?: "(No <think> trace emitted)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1E1E1E),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = jsonText,
                        color = Color(0xFFE8F5E9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "4. Expandable Raw OCR Accordion",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Side-by-side coordinate-tagged lines · ML Kit (${mlBlocks.size}) vs PP-OCRv6 (${ppBlocks.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand or collapse raw OCR accordion"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Left column: ML Kit
                    RawOcrEngineColumn(
                        title = "ML Kit v2 (Blue)",
                        headerColor = MlKitBlue,
                        blocks = mlBlocks,
                        modifier = Modifier.weight(1f)
                    )

                    // Right column: PP-OCRv6
                    RawOcrEngineColumn(
                        title = "PP-OCRv6 (Green)",
                        headerColor = PpOcrGreen,
                        blocks = ppBlocks,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RawOcrEngineColumn(
    title: String,
    headerColor: Color,
    blocks: List<OcrBlock>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = headerColor
            )
            HorizontalDivider()
            if (blocks.isEmpty()) {
                Text(
                    text = "(No lines)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        blocks.forEachIndexed { idx, block ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = headerColor.copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(6.dp)
                            ) {
                                Text(
                                    text = "#${idx + 1} ${block.formatCenterWhTag()} conf=${
                                        String.format(Locale.US, "%.2f", block.confidence)
                                    }",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = block.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
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

                // Framing guide overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
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
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = BordeauxRed)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
                        Spacer(Modifier.width(6.dp))
                        Text(if (isCapturing) "Saving..." else "Capture Label")
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
