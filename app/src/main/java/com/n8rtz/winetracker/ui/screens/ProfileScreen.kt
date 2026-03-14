package com.n8rtz.winetracker.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: WineViewModel = viewModel(),
    onImportComplete: () -> Unit
) {
    val isImporting by viewModel.isImporting.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }
    
    val bordeauxRed = Color(0xFF722F37)

    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            viewModel.importCsvFile(selectedUri)
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            viewModel.performExport(selectedUri)
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            
            // NEW: Batch Image Processor Progress UI
            AnimatedVisibility(
                visible = viewModel.imageImportProgress != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = viewModel.imageImportStatus,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { viewModel.imageImportProgress ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                            strokeCap = StrokeCap.Round
                        )
                    }
                }
            }

            Text(
                text = "Utility Hub",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // RESET DATABASE BUTTON
            ListItem(
                headlineContent = { Text("Reset Database") },
                supportingContent = { Text("Clears all existing database records.") },
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

            // FETCH MISSING IMAGES BUTTON
            ListItem(
                headlineContent = { Text("Fetch Missing Images") },
                supportingContent = { Text("Search and cache images for wines.") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = "Fetch Images",
                        tint = bordeauxRed
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.startImageImport() }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Import Data Item
            ListItem(
                headlineContent = { Text("Import Data (CSV)") },
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
                        importCsvLauncher.launch("*/*")
                    }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Rescue Import
            ListItem(
                headlineContent = { Text("Rescue Import") },
                supportingContent = { Text("Import from legacy assets.") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.Upload,
                        contentDescription = "Rescue Import",
                        tint = bordeauxRed
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        coroutineScope.launch {
                            viewModel.triggerCsvImportFromAssets()
                            snackbarHostState.showSnackbar("Database imported.")
                        }
                    }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Export Data Item
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
                        viewModel.prepareExport(exportCsvLauncher)
                    }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Spacer(modifier = Modifier.weight(1f))
            
            Text(
                text = "Version 0.1-ALPHA",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Database") },
            text = { Text("Are you sure you want to clear all data? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.resetDatabase()
                            snackbarHostState.showSnackbar("Database cleared.")
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
