package com.yourapp.wine.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.capture.ScanReport

@Composable
fun ScanStatsTable(report:ScanReport) {
    val clipboard=LocalClipboardManager.current
    val rows=remember(report.fill) { ScanStats.rows(report.fill) }
    val text=remember(report) { "Build: ${BuildConfig.BUILD_COMMIT}\n"+ScanStats.text(report.fill,report.elapsedMs) }
    Text("Automatic scan stats",style=MaterialTheme.typography.titleSmall)
    Text("Original scan, before your edits. Filled does not mean correct. Swipe horizontally for all columns.",style=MaterialTheme.typography.bodySmall)
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row { ScanStats.headers.forEach { Text(it,Modifier.width(160.dp).padding(6.dp),style=MaterialTheme.typography.labelMedium) } }
        rows.forEach { cells ->
            HorizontalDivider(Modifier.width((160*ScanStats.headers.size).dp))
            Row { cells.forEach { Text(it,Modifier.width(160.dp).padding(6.dp),style=MaterialTheme.typography.bodySmall) } }
        }
    }
    Text("Elapsed: ${report.elapsedMs} ms",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={clipboard.setText(AnnotatedString(text))}) { Text("Copy scan stats") }
}
