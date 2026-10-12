package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                        }
                    )
                }
            }
        }
    }
}
