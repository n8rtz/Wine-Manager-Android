package com.n8rtz.winetracker.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.data.local.WineImageAssetWithDefinition
import com.n8rtz.winetracker.image.WineImageKind
import com.n8rtz.winetracker.image.resolveWineImageModel
import com.n8rtz.winetracker.viewmodels.RestoreImportState
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import com.n8rtz.winetracker.viewmodels.OfflineImageManagementState
import com.n8rtz.winetracker.viewmodels.WineImageImportState
import com.n8rtz.winetracker.viewmodels.WineImageImportViewModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: WineViewModel,
    wineImageImportViewModel: WineImageImportViewModel,
    tastingNoteViewModel: TastingNoteViewModel,
    onAppInfoClick: () -> Unit = {},
    onParetoClick: () -> Unit = {},
    onImportComplete: () -> Unit
) {
    val context = LocalContext.current
    val importState by viewModel.importState
    val imageImportState by wineImageImportViewModel.state.collectAsState()
    val storageStatus by wineImageImportViewModel.storageStatus.collectAsState()
    val offlineImageManagement by wineImageImportViewModel.offlineImageManagement.collectAsState()
    val reviewerNames by tastingNoteViewModel.reviewerNames.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showReviewerSettings by remember { mutableStateOf(false) }
    var showStorageBudgetDialog by remember { mutableStateOf(false) }
    var showOfflineImageManager by remember { mutableStateOf(false) }
    var pendingOfflineImageRemoval by remember { mutableStateOf<WineImageAssetWithDefinition?>(null) }
    var storageBudgetMiB by remember(storageStatus.softBudgetBytes) {
        mutableStateOf(storageStatus.softBudgetBytes / (1024f * 1024f))
    }
    var reviewerDraft by remember { mutableStateOf(emptyList<String>()) }
    var newReviewer by remember { mutableStateOf("") }

    LaunchedEffect(showOfflineImageManager) {
        if (showOfflineImageManager) wineImageImportViewModel.observeOfflineImages()
    }

    val bordeauxRed = Color(0xFF722F37)

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            pendingRestoreUri = it
            showRestoreConfirmation = true
        }
    }

    val importLegacyNotesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importLegacyCsv(it, com.n8rtz.winetracker.data.importer.LegacyCsvImportType.TASTING_NOTES) } }

    val importLegacyHistoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importLegacyCsv(it, com.n8rtz.winetracker.data.importer.LegacyCsvImportType.CONSUMPTION_HISTORY) } }

    LaunchedEffect(importState) {
        when (importState) {
            is RestoreImportState.Success -> {
                val success = importState as RestoreImportState.Success
                snackbarHostState.showSnackbar(success.message)
                viewModel.resetImportState()
                if (success.restoredBackup) onImportComplete()
            }
            is RestoreImportState.Error -> {
                snackbarHostState.showSnackbar("Error: ${(importState as RestoreImportState.Error).message}")
                viewModel.resetImportState()
            }
            else -> {}
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            viewModel.performExport(selectedUri)
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                
                Text(
                    text = "Utility Hub",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                ListItem(
                    headlineContent = { Text("Price vs. Rating") },
                    supportingContent = { Text("Explore wine value with a Pareto frontier") },
                    leadingContent = {
                        Icon(Icons.Filled.ScatterPlot, contentDescription = null, tint = bordeauxRed)
                    },
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onParetoClick)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Import Data (JSON)
                ListItem(
                    headlineContent = { Text("Import Data (JSON)") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Upload,
                            contentDescription = "Import Data",
                            tint = bordeauxRed
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            importJsonLauncher.launch(arrayOf("application/json", "text/*"))
                        }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = { Text("Import Legacy Tasting Notes (CSV)") },
                    supportingContent = { Text("Adds dated notes; unrated rows are kept, and rows without a date or exact wine match are skipped.") },
                    leadingContent = {
                        Icon(Icons.Filled.Upload, contentDescription = null, tint = bordeauxRed)
                    },
                    modifier = Modifier.fillMaxWidth().clickable {
                        importLegacyNotesLauncher.launch(arrayOf("text/*", "application/vnd.ms-excel"))
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = { Text("Import Legacy History Log (CSV)") },
                    supportingContent = { Text("Adds dated consumption events; rows without a usable date or wine are skipped.") },
                    leadingContent = {
                        Icon(Icons.Filled.History, contentDescription = null, tint = bordeauxRed)
                    },
                    modifier = Modifier.fillMaxWidth().clickable {
                        importLegacyHistoryLauncher.launch(arrayOf("text/*", "application/vnd.ms-excel"))
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Export Data
                ListItem(
                    headlineContent = { Text("Export Data") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Export Data",
                            tint = bordeauxRed
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.prepareExport(exportJsonLauncher)
                        }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Reset Database
                ListItem(
                    headlineContent = { Text("Reset Database") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.DeleteForever,
                            contentDescription = "Reset Database",
                            tint = bordeauxRed
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showResetDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                // Candidate search is user-driven; only per-image approval writes a durable file.
                ListItem(
                    headlineContent = { Text("Review Missing Images (Open Food Facts)") },
                    supportingContent = {
                        Text(
                            when (val state = imageImportState) {
                                WineImageImportState.LoadingQueue -> "Preparing the review queue…"
                                is WineImageImportState.Searching -> "Searching ${state.position}/${state.total}: ${state.wine.maker} — ${state.wine.name}"
                                is WineImageImportState.Reviewing -> "Review ${state.position}/${state.total}: confirm the wine and label before choosing an image."
                                is WineImageImportState.Preparing -> "Preparing a compressed offline copy…"
                                is WineImageImportState.Saving -> "Saving approved image ${state.position}/${state.total}…"
                                is WineImageImportState.SearchError -> "Search failed for ${state.wine.name}. Retry or skip."
                                is WineImageImportState.ImportError -> "Could not save ${state.wine.name}. Retry or skip."
                                is WineImageImportState.BatchBudgetWarning -> "Review queue is ready; estimated size is above the soft budget."
                                is WineImageImportState.ImageBudgetWarning -> "This image would exceed the soft storage budget."
                                is WineImageImportState.Finished -> if (state.imported == 0 && state.skipped == 0 && state.failed == 0) {
                                    "No wines currently need an image."
                                } else {
                                    "Last batch: ${state.imported} imported, ${state.skipped} skipped, ${state.failed} failed."
                                }
                                WineImageImportState.Idle -> "Searches Open Food Facts with wine identity/location only. Preview first; only approved images are compressed and saved offline."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Download, contentDescription = "Review wine image candidates", tint = bordeauxRed)
                    },
                    modifier = Modifier.fillMaxWidth().clickable(
                        enabled = imageImportState is WineImageImportState.Idle || imageImportState is WineImageImportState.Finished
                    ) { wineImageImportViewModel.startImport() }
                )
                ListItem(
                    headlineContent = { Text("Offline wine image storage") },
                    supportingContent = {
                        Text(
                            "${formatMiB(storageStatus.usedBytes)} MiB used · " +
                                "${formatMiB(storageStatus.softBudgetBytes)} MiB soft budget"
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = bordeauxRed)
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = { showOfflineImageManager = true }) { Text("Manage") }
                            TextButton(onClick = {
                                storageBudgetMiB = storageStatus.softBudgetBytes / (1024f * 1024f)
                                showStorageBudgetDialog = true
                            }) { Text("Change") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("Wine reviewers") },
                    supportingContent = {
                        Text("${reviewerNames.size} choices for the tasting-note reviewer dropdown")
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = bordeauxRed)
                    },
                    modifier = Modifier.fillMaxWidth().clickable {
                        reviewerDraft = reviewerNames
                        newReviewer = ""
                        showReviewerSettings = true
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (BuildConfig.DEBUG) {
                    ListItem(
                        headlineContent = { Text("OCR Text Lab — draw a crop") },
                        supportingContent = { Text("Choose a photo, box a word or phrase, and inspect raw OCR") },
                        modifier = Modifier.fillMaxWidth().clickable {
                            context.startActivity(android.content.Intent().setClassName(context,
                                "com.yourapp.wine.textlab.OcrTextLabActivity"))
                        }
                    )
                    ListItem(
                        headlineContent = { Text("OCR Lab — multi-image tests") },
                        supportingContent = { Text("Enter actual label details and compare 30 treatments per image") },
                        modifier = Modifier.fillMaxWidth().clickable {
                            context.startActivity(android.content.Intent().setClassName(context,
                                "com.yourapp.wine.benchmark.OcrLabActivity"))
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Export OCR test data (CSV)") },
                        supportingContent = { Text("Export saved test history, including expected and recognized fields") },
                        leadingContent = { Icon(Icons.Filled.Download, contentDescription = null, tint = bordeauxRed) },
                        modifier = Modifier.fillMaxWidth().clickable {
                            context.startActivity(android.content.Intent().setClassName(context,
                                "com.yourapp.wine.benchmark.OcrLabActivity").putExtra("export_csv", true))
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                ListItem(
                    headlineContent = { Text("App Information") },
                    supportingContent = {
                        val commit = BuildConfig.BUILD_COMMIT.takeIf { it.isNotBlank() && it != "unknown" } ?: "unavailable"
                        Text("Installed build: $commit\nApp version: ${BuildConfig.VERSION_NAME}")
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = bordeauxRed)
                    },
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onAppInfoClick)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            if (importState is RestoreImportState.Loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    if (showReviewerSettings) {
        AlertDialog(
            onDismissRequest = { showReviewerSettings = false },
            title = { Text("Wine reviewers") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Edit the choices shown in the Recommended by dropdown.")
                    reviewerDraft.forEachIndexed { index, reviewer ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = reviewer,
                                onValueChange = { updatedName ->
                                    reviewerDraft = reviewerDraft.toMutableList().also {
                                        it[index] = updatedName
                                    }
                                },
                                label = { Text("Reviewer ${index + 1}") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(onClick = {
                                reviewerDraft = reviewerDraft.filterIndexed { itemIndex, _ -> itemIndex != index }
                            }) {
                                Icon(Icons.Filled.DeleteForever, contentDescription = "Remove reviewer")
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newReviewer,
                            onValueChange = { newReviewer = it },
                            label = { Text("Add reviewer") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        TextButton(
                            enabled = newReviewer.isNotBlank(),
                            onClick = {
                                val name = newReviewer.trim()
                                if (name.isNotEmpty()) reviewerDraft = reviewerDraft + name
                                newReviewer = ""
                            }
                        ) {
                            Text("Add")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    tastingNoteViewModel.updateReviewerNames(reviewerDraft)
                    showReviewerSettings = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showReviewerSettings = false }) { Text("Cancel") }
            }
        )
    }

    if (showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showRestoreConfirmation = false
                pendingRestoreUri = null
            },
            title = { Text("Replace app data?") },
            text = {
                Text("Restoring this JSON backup replaces all current wines, cellar inventory, tasting notes, ratings, and consumption history. This cannot be undone. Continue only if this is the backup you want to restore.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreUri?.let(viewModel::importJsonFile)
                    showRestoreConfirmation = false
                    pendingRestoreUri = null
                }) { Text("Replace and restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreConfirmation = false
                    pendingRestoreUri = null
                }) { Text("Cancel") }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Database") },
            text = { Text("Are you sure you want to clear all data? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetDatabase {
                            coroutineScope.launch { snackbarHostState.showSnackbar("Database cleared.") }
                        }
                        showResetDialog = false
                    }
                ) {
                    Text("Reset", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showStorageBudgetDialog) {
        AlertDialog(
            onDismissRequest = { showStorageBudgetDialog = false },
            title = { Text("Offline image soft budget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Set a warning threshold for selected offline images. The app never removes images to stay under this value; it asks before a batch or image exceeds it.")
                    Text("${storageBudgetMiB.roundToInt()} MiB", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = storageBudgetMiB,
                        onValueChange = { storageBudgetMiB = it },
                        valueRange = 64f..1_024f,
                        steps = 14
                    )
                    Text("Available range: 64–1,024 MiB. Each image is separately limited to 500 KB.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    wineImageImportViewModel.setSoftBudgetMiB(storageBudgetMiB.roundToInt())
                    showStorageBudgetDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showStorageBudgetDialog = false }) { Text("Cancel") }
            }
        )
    }

    WineImageImportDialog(imageImportState, wineImageImportViewModel)

    if (showOfflineImageManager) {
        OfflineImageManagementDialog(
            state = offlineImageManagement,
            usedBytes = storageStatus.usedBytes,
            onDismiss = {
                showOfflineImageManager = false
                wineImageImportViewModel.clearOfflineImageManagementMessage()
            },
            onReviewMissingImages = {
                showOfflineImageManager = false
                wineImageImportViewModel.clearOfflineImageManagementMessage()
                wineImageImportViewModel.startImport()
            },
            onRemoveRequested = { pendingOfflineImageRemoval = it }
        )
    }

    pendingOfflineImageRemoval?.let { image ->
        AlertDialog(
            onDismissRequest = { pendingOfflineImageRemoval = null },
            title = { Text("Remove offline copy?") },
            text = {
                Text(
                    "Remove the ${formatKiB(image.imageAsset.sizeBytes)} offline copy of " +
                        "${image.definition.maker} — ${image.definition.name}? The wine, source details, and history stay. " +
                        "Saving a new offline copy later requires reviewed approval."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = offlineImageManagement.removingDefinitionId == null,
                    onClick = {
                        wineImageImportViewModel.removeOfflineCopy(image)
                        pendingOfflineImageRemoval = null
                    }
                ) { Text("Remove offline copy", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingOfflineImageRemoval = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun WineImageImportDialog(
    state: WineImageImportState,
    viewModel: WineImageImportViewModel
) {
    when (state) {
        WineImageImportState.Idle -> Unit
        WineImageImportState.LoadingQueue -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Preparing image review") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Checking wines that do not have an image candidate yet.")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = viewModel::cancelImport) { Text("Cancel") } }
        )
        is WineImageImportState.BatchBudgetWarning -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Image storage estimate") },
            text = {
                Text(
                    "${state.wineCount} missing wine image(s) may use about " +
                        "${formatMiB(state.estimatedBytes)} MiB. Current selected images use " +
                        "${formatMiB(state.storedBytes)} MiB, above the ${formatMiB(state.budgetBytes)} MiB soft budget. " +
                        "Nothing will be removed; actual compressed sizes are checked before saving."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::continueAfterBatchBudgetWarning) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) { Text("Cancel batch") }
            }
        )
        is WineImageImportState.Searching -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Searching Open Food Facts") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${state.position} of ${state.total}: ${state.wine.maker} — ${state.wine.name}")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Search results are candidates only; no image is saved unless you approve it.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::cancelImport) { Text("Cancel batch") }
            }
        )
        is WineImageImportState.SearchError -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Could not search this wine") },
            text = { Text("${state.wine.maker} — ${state.wine.name}\n${state.message}") },
            confirmButton = {
                TextButton(onClick = viewModel::retrySearch) { Text("Retry") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::skipSearchFailure) { Text("Skip wine") }
                    TextButton(onClick = viewModel::cancelImport) { Text("Cancel") }
                }
            }
        )
        is WineImageImportState.Reviewing -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Review image candidates")
                    Text("${state.position} of ${state.total}", style = MaterialTheme.typography.labelMedium)
                }
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val targetLocation = listOfNotNull(
                        state.wine.subregion?.takeIf { it.isNotBlank() },
                        state.wine.region?.takeIf { it.isNotBlank() },
                        state.wine.country.takeIf { it.isNotBlank() }
                    ).distinct().joinToString(", ")
                    Text(
                        "Target: ${state.wine.maker} — ${state.wine.name}" +
                            targetLocation.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() +
                            ". This definition-level image may appear with all its vintages. Verify the exact wine and label before selecting.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Candidates are from Open Food Facts. Previewing does not save them; only your selection creates a compressed offline copy. Review the wine identity; source and contributor details are retained for attribution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.candidates.forEach { candidate ->
                        CandidateReviewCard(
                            candidate = candidate,
                            onChoose = { kind -> viewModel.approveCandidate(candidate, kind) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::skipCurrent) { Text("Skip this wine") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) { Text("Cancel batch") }
            }
        )
        is WineImageImportState.Preparing -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Preparing approved image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${state.wine.name} · ${state.kind.displayName}")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Downloading once, validating, resizing, and compressing for offline use…", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::cancelImport) { Text("Cancel batch") } }
        )
        is WineImageImportState.ImageBudgetWarning -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Soft storage budget reached") },
            text = {
                Text(
                    "This approved image is ${formatKiB(state.preparedBytes)}. Keeping it would use about " +
                        "${formatMiB(state.storedBytes + state.preparedBytes)} MiB, above your " +
                        "${formatMiB(state.budgetBytes)} MiB soft budget. No selected image will be evicted."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::continuePastImageBudgetWarning) { Text("Keep this image") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::skipCurrent) { Text("Skip image") }
            }
        )
        is WineImageImportState.Saving -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Saving offline image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${state.wine.maker} — ${state.wine.name}")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = viewModel::cancelImport) { Text("Cancel batch") } }
        )
        is WineImageImportState.ImportError -> AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Image was not saved") },
            text = { Text("${state.wine.name}\n${state.message}") },
            confirmButton = {
                TextButton(onClick = viewModel::retryImport) { Text("Retry") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::skipCurrent) { Text("Skip") }
                    TextButton(onClick = viewModel::cancelImport) { Text("Cancel batch") }
                }
            }
        )
        is WineImageImportState.Finished -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(if (state.cancelled) "Image review canceled" else "Image review complete") },
            text = {
                Text(
                    if (state.imported == 0 && state.skipped == 0 && state.failed == 0) {
                        "No wines currently need an image."
                    } else {
                        "Imported ${state.imported}; skipped ${state.skipped}; failed ${state.failed}. " +
                            "Only approved images were saved."
                    }
                )
            },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("Done") } }
        )
    }
}

@Composable
private fun OfflineImageManagementDialog(
    state: OfflineImageManagementState,
    usedBytes: Long,
    onDismiss: () -> Unit,
    onReviewMissingImages: () -> Unit,
    onRemoveRequested: (WineImageAssetWithDefinition) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Manage offline images", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${state.images.size} local copies · ${formatMiB(usedBytes)} MiB used",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close image manager")
                    }
                }
                Text(
                    "Remove only the selected offline file. Wine data and source details stay; no other image is evicted.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = onReviewMissingImages) {
                    Text("Review missing images")
                }

                state.message?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                state.error?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                when {
                    state.isLoading && state.images.isEmpty() -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    state.images.isEmpty() -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (state.error != null) "Offline images could not be loaded. Close and reopen this screen to retry."
                            else "No offline image copies are stored. Use Review Missing Images to search for a copy; every new save requires approval.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.images, key = { it.imageAsset.definitionId }) { image ->
                            OfflineImageManagementRow(
                                item = image,
                                isRemoving = state.removingDefinitionId == image.imageAsset.definitionId,
                                removeEnabled = state.removingDefinitionId == null,
                                onRemove = { onRemoveRequested(image) }
                            )
                        }
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun OfflineImageManagementRow(
    item: WineImageAssetWithDefinition,
    isRemoving: Boolean,
    removeEnabled: Boolean,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val asset = item.imageAsset
    val wine = item.definition
    val imageModel = remember(context, asset.relativePath) {
        resolveWineImageModel(context, asset.relativePath, null)
    }
    val imageKind = when (asset.kind) {
        WineImageKind.BOTTLE.databaseValue -> WineImageKind.BOTTLE.displayName
        WineImageKind.LABEL.databaseValue -> WineImageKind.LABEL.displayName
        else -> "Wine image"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(width = 68.dp, height = 88.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageModel != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageModel)
                                .crossfade(true)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .build(),
                            contentDescription = "$imageKind image for ${wine.name}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Filled.WineBar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${wine.maker} — ${wine.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(imageKind, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${formatKiB(asset.sizeBytes)} · Imported ${DateFormat.getDateInstance().format(Date(asset.importedAt))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        buildString {
                            append(asset.sourceName)
                            asset.attribution?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    asset.sourcePageUrl?.takeIf { it.isNotBlank() }?.let { sourcePage ->
                        Text(
                            "View source listing",
                            modifier = Modifier.clickable { runCatching { uriHandler.openUri(sourcePage) } },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRemove, enabled = removeEnabled) {
                    if (isRemoving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRemoving) "Removing…" else "Remove offline copy")
                }
            }
        }
    }
}

@Composable
private fun CandidateReviewCard(
    candidate: com.n8rtz.winetracker.image.WineImageCandidate,
    onChoose: (WineImageKind) -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(candidate.previewUrl)
                        .crossfade(true)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                    contentDescription = "Open Food Facts candidate preview for ${candidate.productTitle}",
                    modifier = Modifier.size(width = 68.dp, height = 92.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(candidate.productTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(candidate.sourceName, style = MaterialTheme.typography.bodySmall)
                    Text(
                        candidate.attribution?.let { "Image contributor: $it" } ?: "Image contributor not listed",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (candidate.width != null && candidate.height != null) {
                        Text("${candidate.width} × ${candidate.height} px", style = MaterialTheme.typography.labelSmall)
                    }
                    candidate.sourcePageUrl?.let { sourcePage ->
                        Text(
                            "View source listing",
                            modifier = Modifier.clickable { runCatching { uriHandler.openUri(sourcePage) } },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { onChoose(WineImageKind.BOTTLE) }, modifier = Modifier.weight(1f)) {
                    Text("Use as bottle")
                }
                OutlinedButton(onClick = { onChoose(WineImageKind.LABEL) }, modifier = Modifier.weight(1f)) {
                    Text("Use as label")
                }
            }
        }
    }
}

private fun formatMiB(bytes: Long): String = "%.1f".format(bytes / (1024.0 * 1024.0))
private fun formatKiB(bytes: Long): String = "%.0f KB".format(bytes / 1024.0)
