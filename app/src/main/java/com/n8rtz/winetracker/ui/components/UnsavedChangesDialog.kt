package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Shared "Unsaved changes" prompt for editable pages: shown when the user
 * goes back (in-app arrow or Android Back) with changes pending. Save runs
 * the page's own save action, Don't save leaves discarding them, Cancel
 * stays on the page.
 */
@Composable
fun UnsavedChangesDialog(
    text: String,
    saveEnabled: Boolean = true,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved changes") },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onSave, enabled = saveEnabled) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Don't save") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}
