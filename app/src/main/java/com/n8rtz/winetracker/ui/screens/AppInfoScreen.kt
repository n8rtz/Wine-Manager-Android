@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.data.local.WineDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppInfoScreen(
    database: WineDatabase,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var databaseVersion by remember { mutableStateOf<Int?>(null) }
    var databaseVersionUnavailable by remember { mutableStateOf(false) }

    LaunchedEffect(database) {
        try {
            databaseVersion = withContext(Dispatchers.IO) {
                database.openHelper.readableDatabase.version
            }
        } catch (_: Exception) {
            databaseVersionUnavailable = true
        }
    }

    val databaseVersionText = databaseVersion?.toString()
        ?: if (databaseVersionUnavailable) "Unavailable" else "Checking…"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Information") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Wine Manager",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Build and database details for this installation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AppBuildDetails(databaseVersionText = databaseVersionText, applicationId = context.packageName)
            Text(
                text = "The commit and source state describe the code used to build this installed app, not the latest code online. After updating Git, rebuild and Run to install the update. The database version comes from the installed database.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** No image coordinates: immutable build metadata is displayed without opening the database or contacting GitHub. */
@Composable
internal fun AppBuildDetails(
    databaseVersionText: String,
    applicationId: String,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
    branch: String = BuildConfig.BUILD_BRANCH,
    commit: String = BuildConfig.BUILD_COMMIT,
    workingTree: String = BuildConfig.BUILD_WORKING_TREE
) {
    val commitText = commit.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        ?: "Unavailable (Git metadata not found)"
    val sourceText = when (workingTree) {
        "clean" -> "Clean checkout"
        "dirty" -> "Includes uncommitted local changes"
        else -> "Unknown"
    }
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            AppInfoRow("Installed build / commit", commitText, selectable = true)
            HorizontalDivider()
            AppInfoRow("App version", versionName)
            HorizontalDivider()
            AppInfoRow("Version code", versionCode.toString())
            HorizontalDivider()
            AppInfoRow("Source state", sourceText)
            HorizontalDivider()
            AppInfoRow("Build branch", branch.takeIf { it.isNotBlank() } ?: "Unknown")
            HorizontalDivider()
            AppInfoRow("Database version", databaseVersionText)
            HorizontalDivider()
            AppInfoRow("Application ID", applicationId)
        }
    }
}

@Composable
private fun AppInfoRow(label: String, value: String, selectable: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (selectable) {
            SelectionContainer {
                Text(text = value, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
            }
        } else {
            Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}
