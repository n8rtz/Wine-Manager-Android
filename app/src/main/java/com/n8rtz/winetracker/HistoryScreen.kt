package com.n8rtz.winetracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: WineViewModel) {
    val archivedBottles by viewModel.archivedInventory.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Consumption History") }
            )
        }
    ) { padding ->
        if (archivedBottles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No consumption history found.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(archivedBottles) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { 
                                Text(
                                    text = "${item.definition.maker} - ${item.definition.name}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                ) 
                            },
                            supportingContent = { 
                                Column {
                                    Text("Vintage: ${item.bottle.vintage}")
                                    Text("Consumed on: ${item.bottle.dateAdded ?: "Unknown Date"}")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
