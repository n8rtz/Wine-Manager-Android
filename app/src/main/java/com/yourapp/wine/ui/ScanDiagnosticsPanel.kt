package com.yourapp.wine.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.diagnostics.AndroidScanEnvironment
import com.yourapp.wine.diagnostics.ScanDiagnosticFormatter
import com.yourapp.wine.diagnostics.ScanEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No image ownership: user-requested text diagnostics are generated off Main, then copied only on an explicit tap. */
@Composable
fun ScanDiagnosticsPanel(report: ScanReport, scanId: Long, allowDebugOverlay: Boolean,
                         environment: ScanEnvironment? = null) {
    val context = LocalContext.current
    var text by remember(scanId) { mutableStateOf<String?>(null) }
    var status by remember(scanId) { mutableStateOf<String?>(null) }
    var showBoxes by remember(scanId) { mutableStateOf(false) }
    val actions = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    LaunchedEffect(report, scanId, environment) {
        text = null
        status = null
        text = withContext(Dispatchers.Default) {
            runCatching { ScanDiagnosticFormatter.format(report, environment ?: AndroidScanEnvironment.current(), scanId) }
                .getOrElse { "WINE-SCAN-DIAGNOSTICS v1\nReport generation failed: ${it.javaClass.simpleName}\n" }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("scan_debug_details"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Scan diagnostics", style = MaterialTheme.typography.titleSmall)
        Text("Includes label text and device/build information. No photo or GPS data is attached. Review before sharing.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = text != null, colors = actions, onClick = {
            val content = text ?: return@TextButton
            runCatching {
                val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
                val clip = ClipData.newPlainText("Wine scan diagnostics", content)
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
                clipboard.setPrimaryClip(clip)
            }.onSuccess { status = "Diagnostics copied" }
                .onFailure { status = "Could not access the clipboard. Select and copy the report text below." }
        }) { Text("Copy diagnostics") }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        SelectionContainer {
            Text(text ?: "Preparing diagnostics…", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                    .testTag("scan_diagnostic_text"))
        }
        if (allowDebugOverlay && report.tokens.isNotEmpty()) {
            TextButton(onClick = { showBoxes = !showBoxes }, colors = actions) {
                Text(if (showBoxes) "Hide OCR boxes" else "Show OCR boxes")
            }
            if (showBoxes) Column(Modifier.testTag("scan_debug_overlay")) { ScanDebugOverlay(report) }
        }
    }
}
