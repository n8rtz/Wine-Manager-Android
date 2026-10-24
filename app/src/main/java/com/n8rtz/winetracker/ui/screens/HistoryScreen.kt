package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n8rtz.winetracker.data.local.ConsumptionEventWithDefinition
import com.n8rtz.winetracker.data.local.displayVintage
import com.n8rtz.winetracker.ui.components.HistoryWineCard
import com.n8rtz.winetracker.viewmodels.WineViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: WineViewModel,
    onOpenNote: (noteId: Long?, eventId: Long, definitionId: Long, vintage: String, consumedAt: Long?) -> Unit
) {
    val history by viewModel.consumptionHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    // Long-pressing a card opens the action sheet for that entry; the chosen action then asks to confirm.
    var sheetTarget by remember { mutableStateOf<ConsumptionEventWithDefinition?>(null) }
    // The entry waiting for the owner to confirm "Restore to cellar" (true) or "Delete log entry" (false).
    var pendingAction by remember { mutableStateOf<Pair<ConsumptionEventWithDefinition, Boolean>?>(null) }
    val haptics = LocalHapticFeedback.current

    sheetTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { sheetTarget = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "${target.definition.maker} - ${target.definition.name} (${displayVintage(target.event.vintage)})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                ListItem(
                    headlineContent = { Text("Restore to cellar") },
                    supportingContent = { Text("Put the bottle back and remove this entry") },
                    leadingContent = { Icon(Icons.Default.Restore, contentDescription = null) },
                    modifier = Modifier.clickable {
                        pendingAction = target to true
                        sheetTarget = null
                    }
                )
                ListItem(
                    headlineContent = { Text("Delete log entry", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Remove this entry only; the cellar is unchanged") },
                    leadingContent = {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable {
                        pendingAction = target to false
                        sheetTarget = null
                    }
                )
            }
        }
    }

    pendingAction?.let { (target, isRestore) ->
        val label = "${target.definition.maker} - ${target.definition.name} (${displayVintage(target.event.vintage)})"
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(if (isRestore) "Restore to cellar?" else "Delete log entry?") },
            text = {
                Text(
                    if (isRestore) {
                        "$label goes back into the cellar (quantity +${target.event.quantity}) and this history entry is removed. Any tasting note you wrote is kept."
                    } else {
                        "This removes the history entry for $label. The wine is NOT returned to the cellar, and any tasting note you wrote is kept."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isRestore) viewModel.restoreConsumptionToCellar(target.event.eventId)
                    else viewModel.deleteConsumptionLog(target.event.eventId)
                    pendingAction = null
                }) { Text(if (isRestore) "Restore" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Consumption History", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No wines consumed yet",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                items(history, key = { it.event.eventId }) { item ->
                    val event = item.event
                    val note = item.note
                    val date = event.consumedAt?.let { dateFormat.format(Date(it)) } ?: "Date not recorded"
                    val rating = note?.rating?.takeIf { it > 0.0 }?.toInt()?.toString()
                    HistoryWineCard(
                        maker = item.definition.maker,
                        name = item.definition.name,
                        vintage = displayVintage(event.vintage),
                        date = date,
                        quantity = event.quantity,
                        rating = rating,
                        hasNote = note != null,
                        onClick = {
                            onOpenNote(note?.noteId, event.eventId, event.definitionId, event.vintage, event.consumedAt)
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            sheetTarget = item
                        }
                    )
                }
            }
        }
    }
}
