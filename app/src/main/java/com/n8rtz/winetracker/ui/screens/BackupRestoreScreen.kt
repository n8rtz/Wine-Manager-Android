@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.n8rtz.winetracker.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.backup.BackupConfigRules
import com.n8rtz.winetracker.backup.BackupDestinationId
import com.n8rtz.winetracker.backup.BackupHealth
import com.n8rtz.winetracker.backup.BackupInterval
import com.n8rtz.winetracker.backup.BackupIntervalUnit
import com.n8rtz.winetracker.backup.BackupSettings
import com.n8rtz.winetracker.backup.BackupViewModel
import com.n8rtz.winetracker.viewmodels.RestoreImportState
import com.n8rtz.winetracker.viewmodels.WineViewModel
import kotlinx.coroutines.launch

/**
 * Profile -> Settings -> Backup & Restore. The main menu holds only the destination toggles;
 * tapping a destination opens its submenu with the actions and the shared schedule/retention
 * settings. Restore feeds the existing validated, transactional JSON importer.
 */
@Composable
fun BackupRestoreScreen(
    backupViewModel: BackupViewModel,
    wineViewModel: WineViewModel,
    onBack: () -> Unit,
    onImportComplete: () -> Unit
) {
    val state by backupViewModel.state.collectAsState()
    val importState by wineViewModel.importState
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val bordeauxRed = Color(0xFF722F37)

    var activeDestination by remember { mutableStateOf<BackupDestinationId?>(null) }
    var showRestoreSnapshots by remember { mutableStateOf(false) }
    var pendingRestoreName by remember { mutableStateOf<String?>(null) }
    var pendingRestoreDestination by remember { mutableStateOf<BackupDestinationId?>(null) }
    var showGitHubSetup by remember { mutableStateOf(false) }
    var showWebDavSetup by remember { mutableStateOf(false) }
    var showGitHubWarning by remember { mutableStateOf(false) }
    var githubRepoDraft by remember { mutableStateOf("") }
    var githubTokenDraft by remember { mutableStateOf("") }
    var webDavUrlDraft by remember { mutableStateOf("") }
    var webDavUserDraft by remember { mutableStateOf("") }
    var webDavPasswordDraft by remember { mutableStateOf("") }
    var unitMenuExpanded by remember { mutableStateOf(false) }
    var countText by remember(state.settings.interval.count) {
        mutableStateOf(state.settings.interval.count.toString())
    }

    val driveFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> backupViewModel.onDriveFolderPicked(uri) }

    fun openSetup(id: BackupDestinationId) {
        when (id) {
            BackupDestinationId.GITHUB -> {
                githubRepoDraft = ""
                githubTokenDraft = ""
                showGitHubSetup = true
            }
            BackupDestinationId.WEBDAV -> {
                webDavUrlDraft = ""
                webDavUserDraft = ""
                webDavPasswordDraft = ""
                showWebDavSetup = true
            }
            BackupDestinationId.GOOGLE_DRIVE -> driveFolderLauncher.launch(null)
            BackupDestinationId.LOCAL -> Unit
        }
    }

    LaunchedEffect(Unit) { backupViewModel.refresh() }

    LaunchedEffect(importState) {
        when (val current = importState) {
            is RestoreImportState.Success -> {
                snackbarHostState.showSnackbar(current.message)
                wineViewModel.resetImportState()
                if (current.restoredBackup) onImportComplete()
            }
            is RestoreImportState.Error -> {
                snackbarHostState.showSnackbar("Restore failed: ${current.message}")
                wineViewModel.resetImportState()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(activeDestination?.displayName ?: "Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (activeDestination != null) activeDestination = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (state.warningBanner != null) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Backup needs attention", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(state.warningBanner!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (state.overdueNotice != null) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(state.overdueNotice!!, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { backupViewModel.dismissNotices() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            if (state.runMessage != null) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            state.runMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { backupViewModel.dismissNotices() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            val destination = activeDestination
            if (destination == null) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Backup status",
                            style = MaterialTheme.typography.titleSmall,
                            color = bordeauxRed
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(state.protectionLine, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Schedule: " + if (state.settings.automaticEnabled) {
                                "automatic, every ${state.settings.interval.count} ${unitLabel(state.settings.interval.unit)}"
                            } else {
                                "manual only"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Copies kept: " + if (state.settings.pruneEnabled) {
                                "newest ${state.settings.keepCount}"
                            } else {
                                "all"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                SectionHeader("Destinations", bordeauxRed)
                state.settings.destinationPriority.forEach { id ->
                    val enabled = id in state.settings.enabledDestinations
                    val configured = state.needsSetup[id] != true
                    // Only toggled-on destinations show their last confirmed date/time.
                    val lastBackup = if (enabled) state.lastBackupLines[id] else null
                    ListItem(
                        headlineContent = {
                            Column {
                                Text(id.displayName)
                                if (lastBackup != null) {
                                    Text(
                                        lastBackup,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    if (checked && id == BackupDestinationId.GITHUB && !configured) {
                                        showGitHubWarning = true
                                    } else {
                                        backupViewModel.setDestinationEnabled(id, checked)
                                    }
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable { activeDestination = id }
                    )
                    HorizontalDivider()
                }
                ListItem(
                    headlineContent = { Text("Restore from backup…") },
                    leadingContent = {
                        Icon(Icons.Filled.Upload, contentDescription = null, tint = bordeauxRed)
                    },
                    modifier = Modifier.fillMaxWidth().clickable {
                        backupViewModel.loadRestoreBackups()
                        showRestoreSnapshots = true
                    }
                )
            } else {
                val id = destination
                val configured = state.needsSetup[id] != true
                Card(
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Status",
                            style = MaterialTheme.typography.titleSmall,
                            color = bordeauxRed
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.statusLines[id] ?: "No status yet",
                            style = MaterialTheme.typography.bodySmall
                        )
                        val detail = state.setupDetails[id].orEmpty()
                        if (detail.isNotBlank() && id != BackupDestinationId.LOCAL) {
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                ListItem(
                    headlineContent = {
                        Text(if (state.runningDestination == id) "Backing up…" else "Back up now")
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = bordeauxRed)
                    },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isRunning) {
                        backupViewModel.backUpNow(id)
                    }
                )
                HorizontalDivider()
                if (id != BackupDestinationId.LOCAL) {
                    ListItem(
                        headlineContent = { Text(if (configured) "Change setup" else "Set up…") },
                        leadingContent = {
                            Icon(Icons.Filled.Settings, contentDescription = null, tint = bordeauxRed)
                        },
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.setupInProgress) {
                            openSetup(id)
                        }
                    )
                    HorizontalDivider()
                    if (configured) {
                        ListItem(
                            headlineContent = { Text("Disconnect") },
                            leadingContent = {
                                Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = bordeauxRed)
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                backupViewModel.disconnect(id)
                            }
                        )
                        HorizontalDivider()
                    }
                }
                SectionHeader("Schedule", bordeauxRed)
                ListItem(
                    headlineContent = { Text("Automatic backup") },
                    leadingContent = {
                        Icon(Icons.Filled.History, contentDescription = null, tint = bordeauxRed)
                    },
                    trailingContent = {
                        Switch(
                            checked = state.settings.automaticEnabled,
                            onCheckedChange = { backupViewModel.setAutomaticEnabled(it) }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()

                // Label and controls get separate lines so the unit button is never squeezed
                // into a narrow column ("months" used to wrap into "mon" / "ths").
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("Backup frequency", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = countText,
                            onValueChange = { newValue ->
                                val digits = newValue.filter { it.isDigit() }
                                countText = digits
                                val parsed = digits.toIntOrNull()
                                if (parsed != null && parsed in BackupInterval.MIN_COUNT..BackupInterval.MAX_COUNT) {
                                    backupViewModel.setInterval(parsed, state.settings.interval.unit)
                                }
                            },
                            label = { Text("Every") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(88.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box {
                            TextButton(onClick = { unitMenuExpanded = true }) {
                                Text(
                                    unitLabel(state.settings.interval.unit),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            DropdownMenu(
                                expanded = unitMenuExpanded,
                                onDismissRequest = { unitMenuExpanded = false }
                            ) {
                                BackupIntervalUnit.values().forEach { unit ->
                                    DropdownMenuItem(
                                        text = { Text(unitLabel(unit), maxLines = 1, softWrap = false) },
                                        onClick = {
                                            unitMenuExpanded = false
                                            backupViewModel.setInterval(
                                                state.settings.interval.count,
                                                unit
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()

                SectionHeader("Old backups", bordeauxRed)
                ListItem(
                    headlineContent = { Text("Delete old backups") },
                    leadingContent = {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = bordeauxRed)
                    },
                    trailingContent = {
                        Switch(
                            checked = state.settings.pruneEnabled,
                            onCheckedChange = { backupViewModel.setPruneEnabled(it) }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Keep the newest ${state.settings.keepCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { backupViewModel.setKeepCount(state.settings.keepCount - 1) },
                        enabled = state.settings.pruneEnabled &&
                            state.settings.keepCount > BackupSettings.MIN_KEEP_COUNT
                    ) { Text("−") }
                    TextButton(
                        onClick = { backupViewModel.setKeepCount(state.settings.keepCount + 1) },
                        enabled = state.settings.pruneEnabled &&
                            state.settings.keepCount < BackupSettings.MAX_KEEP_COUNT
                    ) { Text("+") }
                }
            }
        }
    }

    if (showRestoreSnapshots) {
        AlertDialog(
            onDismissRequest = {
                showRestoreSnapshots = false
                backupViewModel.clearRestoreSelection()
            },
            title = { Text("Saved backups") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (state.restoreLoading) {
                        Text("Loading backups…")
                    } else {
                        if (state.restoreError != null) {
                            Text(
                                state.restoreError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (state.restoreEntries.isEmpty() && state.restoreError == null) {
                            Text("No backups found.")
                        }
                        state.restoreEntries.forEach { entry ->
                            TextButton(
                                onClick = {
                                    pendingRestoreDestination = entry.destination
                                    pendingRestoreName = entry.name
                                    showRestoreSnapshots = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("${entry.destination.displayName} · ${entry.name}") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreSnapshots = false
                    backupViewModel.clearRestoreSelection()
                }) { Text("Cancel") }
            }
        )
    }

    if (pendingRestoreName != null) {
        AlertDialog(
            onDismissRequest = { pendingRestoreName = null },
            title = { Text("Replace app data?") },
            text = {
                Text("Restoring ${pendingRestoreDestination?.displayName.orEmpty()} · ${pendingRestoreName} replaces all current data. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = pendingRestoreName
                    val destination = pendingRestoreDestination
                        ?: BackupDestinationId.LOCAL
                    pendingRestoreName = null
                    pendingRestoreDestination = null
                    if (name != null) {
                        coroutineScope.launch {
                            try {
                                val uri = backupViewModel.restoreUri(destination, name)
                                wineViewModel.importJsonFile(uri)
                            } catch (error: Exception) {
                                snackbarHostState.showSnackbar(
                                    "Could not open that backup: ${error.message ?: "unknown error"}"
                                )
                            }
                        }
                    }
                }) {
                    Text("Replace and restore", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRestoreName = null
                    pendingRestoreDestination = null
                }) { Text("Cancel") }
            }
        )
    }

    if (showGitHubWarning) {
        AlertDialog(
            onDismissRequest = { showGitHubWarning = false },
            title = { Text("Back up to GitHub?") },
            text = {
                Text(
                    "Backups are plain JSON and GitHub keeps old versions in git history: deleting " +
                        "a backup here cannot fully erase earlier copies. Use a PRIVATE repository " +
                        "and only enable this if you accept that."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showGitHubWarning = false
                    backupViewModel.setDestinationEnabled(BackupDestinationId.GITHUB, true)
                    openSetup(BackupDestinationId.GITHUB)
                }) { Text("I understand") }
            },
            dismissButton = {
                TextButton(onClick = { showGitHubWarning = false }) { Text("Cancel") }
            }
        )
    }

    if (showGitHubSetup) {
        AlertDialog(
            onDismissRequest = { showGitHubSetup = false },
            title = { Text("GitHub setup") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = githubRepoDraft,
                        onValueChange = { githubRepoDraft = it },
                        label = { Text("owner/repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = githubTokenDraft,
                        onValueChange = { githubTokenDraft = it },
                        label = { Text("Fine-grained token (Contents: read/write)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGitHubSetup = false
                        backupViewModel.saveGitHubConfig(githubRepoDraft, githubTokenDraft)
                    },
                    enabled = !state.setupInProgress
                ) { Text("Save and test") }
            },
            dismissButton = {
                TextButton(onClick = { showGitHubSetup = false }) { Text("Cancel") }
            }
        )
    }

    if (showWebDavSetup) {
        AlertDialog(
            onDismissRequest = { showWebDavSetup = false },
            title = { Text("WebDAV setup (TrueNAS SCALE)") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = webDavUrlDraft,
                        onValueChange = { webDavUrlDraft = it },
                        label = { Text("WebDAV folder URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = webDavUserDraft,
                        onValueChange = { webDavUserDraft = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = webDavPasswordDraft,
                        onValueChange = { webDavPasswordDraft = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (BackupConfigRules.isInsecureWebDavUrl(webDavUrlDraft)) {
                        Text(
                            "Plain http sends your data unencrypted. Use https.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWebDavSetup = false
                        backupViewModel.saveWebDavConfig(
                            webDavUrlDraft, webDavUserDraft, webDavPasswordDraft
                        )
                    },
                    enabled = !state.setupInProgress
                ) { Text("Save and test") }
            },
            dismissButton = {
                TextButton(onClick = { showWebDavSetup = false }) { Text("Cancel") }
            }
        )
    }

    if (state.setupMessage != null) {
        AlertDialog(
            onDismissRequest = { backupViewModel.dismissSetupMessage() },
            title = { Text("Backup setup") },
            text = { Text(state.setupMessage!!) },
            confirmButton = {
                TextButton(onClick = { backupViewModel.dismissSetupMessage() }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

private fun unitLabel(unit: BackupIntervalUnit): String = when (unit) {
    BackupIntervalUnit.DAYS -> "days"
    BackupIntervalUnit.WEEKS -> "weeks"
    BackupIntervalUnit.MONTHS -> "months"
}
