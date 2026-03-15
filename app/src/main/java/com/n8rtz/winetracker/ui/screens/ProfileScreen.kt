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
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.n8rtz.winetracker.viewmodels.RestoreImportState
import com.n8rtz.winetracker.viewmodels.WineViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: WineViewModel,
    onImportComplete: () -> Unit
) {
    val importState by viewModel.importState
    
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }
    
    val bordeauxRed = Color(0xFF722F37)

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            viewModel.importJsonFile(selectedUri)
        }
    }

    LaunchedEffect(importState) {
        when (importState) {
            is RestoreImportState.Success -> {
                snackbarHostState.showSnackbar("Database restored successfully.")
                viewModel.resetImportState()
                onImportComplete()
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
                            importJsonLauncher.launch("*/*")
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
            }
            
            if (importState is RestoreImportState.Loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
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
