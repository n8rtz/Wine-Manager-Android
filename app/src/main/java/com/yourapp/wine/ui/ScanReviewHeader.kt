package com.yourapp.wine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState

/** No image ownership: text diagnostics are opt-in in all builds; original-coordinate wireframes stay debug-only. */
@Composable
fun ScanReviewHeader(
    report: ScanReport,
    form: ScanFormState,
    scanId: Long,
    allowDebugOverlay: Boolean = false,
    canRetake: Boolean = true,
    onRetake: () -> Unit
) {
    // Key by the capture request, not data-class equality: even identical retakes start collapsed.
    var showDetails by remember(scanId) { mutableStateOf(false) }
    val populated = report.fill.fields.values.count { it.state != UiState.RED && it.value.isNotBlank() }
    val pending = form.fields.values.count { !it.confirmed && it.value.isNotBlank() }
    val identityRead = listOf(FieldId.PRODUCER, FieldId.WINE_NAME).any {
        report.fill.fields.getValue(it).let { field -> field.state != UiState.RED && field.value.isNotBlank() }
    }
    val onlyMetadata = populated > 0 && !identityRead
    val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when {
                populated == 0 -> "No label details read"
                onlyMetadata -> "Only partial label details read"
                else -> "Review scanned details"
            }, style = MaterialTheme.typography.titleMedium)
            Text(when {
                populated == 0 -> "Retake the photo, or enter the wine details in the form below."
                onlyMetadata -> "The scan did not read a producer or wine name. Check the partial details below, then retake or enter the identity manually."
                else -> "$populated of 6 label fields read. Check the suggestions in the form below."
            }, style = MaterialTheme.typography.bodyMedium)
            if (pending > 0) Text(
                if (pending == 1) "1 field needs your confirmation below."
                else "$pending fields need your confirmation below.",
                style = MaterialTheme.typography.bodySmall)
            report.fill.overallHint?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetake, colors = actionColors, enabled = canRetake) { Text("Retake photo") }
                TextButton(onClick = { showDetails = !showDetails }, colors = actionColors) {
                    Text(if (showDetails) "Hide scan details" else "Show scan details")
                }
            }
            if (showDetails) {
                ScanDiagnosticsPanel(report, scanId, allowDebugOverlay)
            }
        }
    }
}
