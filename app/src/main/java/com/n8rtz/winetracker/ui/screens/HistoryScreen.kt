package com.n8rtz.winetracker.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n8rtz.winetracker.ui.components.HistoryWineCard
import com.n8rtz.winetracker.viewmodels.WineViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: WineViewModel) {
    val historyBottles by viewModel.historyBottles.collectAsStateWithLifecycle(initialValue = emptyList())
    val dateFormat = remember { SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        "Consumption History",
                        fontWeight = FontWeight.Bold
                    ) 
                }
            )
        }
    ) { padding ->
        if (historyBottles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No wines tasted yet",
                    color = Color(0xFF722F37), // Bordeaux Red
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFFF8F9FA)),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = historyBottles,
                    key = { it.bottle.bottleId }
                ) { item ->
                    val definition = item.definition
                    val bottle = item.bottle

                    if (definition != null) {
                        val dateStr = bottle.dateConsumed?.let { 
                            dateFormat.format(Date(it)) 
                        } ?: "N/A"

                        HistoryWineCard(
                            maker = definition.maker,
                            name = definition.name,
                            vintage = bottle.vintage,
                            date = dateStr,
                            quantity = bottle.quantity
                        )
                    } else {
                        Log.w("WineTracker", "HistoryScreen: Found bottle ${bottle.bottleId} with no definition")
                    }
                }
            }
        }
    }
}
