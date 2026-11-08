package com.n8rtz.winetracker.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.viewmodels.RestoreImportState
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.yourapp.wine.gliner.GlinerLabActivity
import kotlinx.coroutines.launch

/** Which of the two Profile-tab screens [ProfileScreen] shows. They share one state/dialog host. */
enum class ProfileSection { HOME, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: WineViewModel,
    tastingNoteViewModel: TastingNoteViewModel,
    section: ProfileSection = ProfileSection.HOME,
    onSettingsClick: () -> Unit = {},
    onBack: () -> Unit = {},
    onAppInfoClick: () -> Unit = {},
    onBackupRestoreClick: () -> Unit = {},
    onDatabaseLookupClick: () -> Unit = {},
    onParetoClick: () -> Unit = {},
    onImportComplete: () -> Unit
) {
    val context = LocalContext.current
    val importState by viewModel.importState
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

    LaunchedEffect(importState) {
        when (importState) {
            is RestoreImportState.Success -> {
                val success = importState as RestoreImportState.Success
                snackbarHostState.showSnackbar(success.message)
                viewModel.resetImportState()
                if (success.restoredBackup) onImportComplete()
            }
            is RestoreImportState.Error -> {
                snackbarHostState.showSnackbar(
                    "Error: ${(importState as RestoreImportState.Error).message}"
                )
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

    // Keep the title, but let the header scroll away with the content.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = if (section == ProfileSection.SETTINGS) {
            Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        } else {
            Modifier
        },
        topBar = {
            if (section == ProfileSection.SETTINGS) {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (section == ProfileSection.HOME) {
                    Text(
                        text = "Profile",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    ListItem(
                        headlineContent = { Text("Price vs. Rating") },
                        supportingContent = {
                            Text("Explore wine value with a Pareto frontier")
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.ScatterPlot,
                                contentDescription = null,
                                tint = bordeauxRed
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onParetoClick)
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    ListItem(
                        headlineContent = { Text("Wine reviewers") },
                        supportingContent = {
                            Text("${reviewerNames.size} choices for the tasting-note reviewer dropdown")
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = bordeauxRed
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable {
                            reviewerDraft = reviewerNames
                            newReviewer = ""
                            showReviewerSettings = true
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    ListItem(
                        headlineContent = { Text("Settings") },
                        supportingContent = {
                            Text("Backup and restore, data, app information")
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = null,
                                tint = bordeauxRed
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onSettingsClick)
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    Text(
                        text = "Backup & data",
                        style = MaterialTheme.typography.titleSmall,
                        color = bordeauxRed,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )

                    // Backup & Restore submenu (automatic local backups and off-device destinations).
                    ListItem(
                        headlineContent = { Text("Backup & Restore") },
                        supportingContent = {
                            Text("Automatic backups, schedule, copies to keep, destinations, restore")
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = null,
                                tint = bordeauxRed
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onBackupRestoreClick)
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

                    // These activities are supplied by the debug source set only.
                    if (BuildConfig.DEBUG) {
                        Text(
                            text = "Scanner testing",
                            style = MaterialTheme.typography.titleSmall,
                            color = bordeauxRed,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )

                        ListItem(
                            headlineContent = {
                                Text("Large image dataset test")
                            },
                            supportingContent = {
                                Text("Fresh OCR over all 124 retained workbook labels — on-device comparison")
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                context.startActivity(
                                    android.content.Intent().setClassName(
                                        context,
                                        "com.yourapp.wine.phonecorpus.WineAppLabelsActivity"
                                    )
                                )
                            }
                        )

                        ListItem(
                            headlineContent = {
                                Text("GLiNER lane lab — ML Kit + PP-OCRv6 ➔ GLiNER-Small INT4")
                            },
                            supportingContent = {
                                Text("New structure: both OCR engines read the label, GLiNER groups it into producer / wine name / appellation, then the existing Lab v7 post-processing")
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                context.startActivity(android.content.Intent(context, GlinerLabActivity::class.java))
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleSmall,
                        color = bordeauxRed,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    ListItem(
                        headlineContent = { Text("Database Lookup") },
                        supportingContent = {
                            Text("Fuzzy-search producer, wine-name, and appellation entries in the local scan database")
                        },
                        leadingContent = {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = bordeauxRed)
                        },
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onDatabaseLookupClick)
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ListItem(
                        headlineContent = { Text("App Information") },
                        supportingContent = {
                            val commit = BuildConfig.BUILD_COMMIT.takeIf {
                                it.isNotBlank() &&
                                    !it.equals("unknown", ignoreCase = true)
                            } ?: "unavailable"

                            Text(
                                "Installed build: $commit\n" +
                                    "App version: ${BuildConfig.VERSION_NAME}"
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = bordeauxRed
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onAppInfoClick)
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
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
                    modifier = Modifier.heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
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
                                reviewerDraft = reviewerDraft.filterIndexed { itemIndex, _ ->
                                    itemIndex != index
                                }
                            }) {
                                Icon(
                                    Icons.Filled.DeleteForever,
                                    contentDescription = "Remove reviewer"
                                )
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
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReviewerSettings = false }) {
                    Text("Cancel")
                }
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
                }) {
                    Text(
                        "Replace and restore",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreConfirmation = false
                    pendingRestoreUri = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Database") },
            text = {
                Text("Are you sure you want to clear all data? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetDatabase {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Database cleared.")
                            }
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
