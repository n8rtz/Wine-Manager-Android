package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.n8rtz.winetracker.ui.components.WineCard
import com.n8rtz.winetracker.ui.components.EmptyState

@Composable
fun RankingsScreen(viewModel: WineViewModel) {
    val history by viewModel.tastingHistory.collectAsState(initial = emptyList())
    val rankedWines = history.filter { it.note.rating > 0 }.sortedByDescending { it.note.rating }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Rankings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        
        if (rankedWines.isEmpty()) {
            EmptyState(Icons.Default.EmojiEvents, "No Ranked Wines", "Rate a wine to see it here")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rankedWines) { item ->
                    WineCard(
                        title = run {
                            val vintage = item.note.vintage.takeIf { it.isNotBlank() && it.lowercase() != "null" } ?: "NV"
                            "${item.note.vintage} ${item.definition.maker} - ${item.definition.name}"
                        },
                        subtitle = run {
                            val validRegion = item.definition.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                            val validCountry = item.definition.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                            listOfNotNull(validRegion, validCountry).joinToString(", ")
                        },
                        type = item.definition.type,
                        metadata = "Rating: ${item.note.rating.toInt()}",
                        imageUrl = item.definition.imageUrl,
                        rating = item.note.rating.toInt(),
                        details = {
                            Column {
                                Text("Tasted: ${java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(item.note.dateTasted))}")
                                if (!item.note.notes.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Notes: ${item.note.notes}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        onClick = {}
                    )
                }
            }
        }
    }
}
