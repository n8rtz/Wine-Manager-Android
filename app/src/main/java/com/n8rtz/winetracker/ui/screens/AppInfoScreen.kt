@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import android.content.pm.PackageManager
import java.security.MessageDigest
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.data.local.WineDatabase
import kotlinx.coroutines.Dispatchers
import java.text.DateFormat
import java.util.Date
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
    val branchName = BuildConfig.BUILD_BRANCH.takeIf { it.isNotBlank() } ?: "Unknown"
    val commitName = BuildConfig.BUILD_COMMIT.takeIf { it.isNotBlank() } ?: "Unknown"
    val buildCommitText = if (BuildConfig.BUILD_DIRTY) "$commitName + uncommitted changes" else commitName
    val installInfo = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            val key = signer?.let { MessageDigest.getInstance("SHA-256").digest(it) }
                ?.take(6)?.joinToString(":") { "%02X".format(it) }
            Triple(dateFormat.format(Date(info.firstInstallTime)), dateFormat.format(Date(info.lastUpdateTime)), key ?: "Unavailable")
        }.getOrNull()
    }
    val builtAtText = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(BuildConfig.BUILD_TIME_MILLIS))
    }

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

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    AppInfoRow("App version", BuildConfig.VERSION_NAME)
                    HorizontalDivider()
                    AppInfoRow("Version code", BuildConfig.VERSION_CODE.toString())
                    HorizontalDivider()
                    AppInfoRow("Built", builtAtText)
                    HorizontalDivider()
                    AppInfoRow("Build commit", buildCommitText)
                    HorizontalDivider()
                    AppInfoRow("Database version", databaseVersionText)
                    HorizontalDivider()
                    AppInfoRow("Build branch", branchName)
                    HorizontalDivider()
                    AppInfoRow("First installed", installInfo?.first ?: "Unavailable")
                    HorizontalDivider()
                    AppInfoRow("Last updated", installInfo?.second ?: "Unavailable")
                    HorizontalDivider()
                    AppInfoRow("Signing key", installInfo?.third ?: "Unavailable")
                    HorizontalDivider()
                    AppInfoRow("Application ID", context.packageName)
                }
            }
            Text(
                text = "The database version is read from the installed database. The build time, commit, uncommitted-changes flag and branch are recorded when this app is built. After reinstalling, check that “Built” changed to confirm you are running the latest iteration. If “First installed” changed since your last test, Android removed the app and installed it fresh (which erases its data); if two builds show a different “Signing key”, Android cannot update one over the other and must uninstall first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppInfoRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}
