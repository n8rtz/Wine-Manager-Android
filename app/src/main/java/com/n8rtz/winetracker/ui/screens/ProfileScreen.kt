package com.n8rtz.winetracker.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.viewmodels.RestoreImportState
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: WineViewModel,
    scannerViewModel: ScannerViewModel,
    tastingNoteViewModel: TastingNoteViewModel,
    onAppInfoClick: () -> Unit = {},
    onImportComplete: () -> Unit
) {
    val importState by viewModel.importState
    
    val imageImportStatus = scannerViewModel.imageImportStatus
    val imageImportProgress = scannerViewModel.imageImportProgress
    val reviewerNames by tastingNoteViewModel.reviewerNames.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showReviewerSettings by remember { mutableStateOf(false) }
    var reviewerDraft by remember { mutableStateOf(emptyList<String>()) }
    var newReviewer by remember { mutableStateOf("") }
    
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
                // Fetch Missing Images
                ListItem(
                    headlineContent = { Text("Find Missing Images (OpenFoodFacts)") },
                    supportingContent = {
                        if (imageImportProgress != null) {
                            Column {
                                LinearProgressIndicator(
                                    progress = { imageImportProgress ?: 0f },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    color = bordeauxRed
                                )
                                Text(imageImportStatus, style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (imageImportStatus.isNotEmpty()) {
                            Text(imageImportStatus, style = MaterialTheme.typography.bodySmall, color = bordeauxRed)
                        } else {
                            Text("Searches API by Producer + Wine Name")
                        }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Find Images",
                            tint = bordeauxRed
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = imageImportProgress == null) {
                            scannerViewModel.startImageImport()
                        }
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
                ListItem(
                    headlineContent = { Text("App Information") },
                    supportingContent = { Text("App version, database version, and build branch") },
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
}
