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
    val tastingHistory by viewModel.tastingHistory.collectAsState(initial = emptyList())
    
    val ratingsMap = remember(tastingHistory) {
        tastingHistory
            .filter { it.note.rating > 0 }
            .groupBy { it.note.definitionId to it.note.vintage }
            .mapValues { (_, notes) ->
                val ratings = notes.map { it.note.rating }
                val min = ratings.minOrNull()?.toInt() ?: 0
                val max = ratings.maxOrNull()?.toInt() ?: 0
                if (min == max) min.toString() else "$min-$max"
            }
    }

    val dateFormat = remember { SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()) }

    val formattedDatesMap = remember(historyBottles, dateFormat) {
        historyBottles.associate { item ->
            val dateStr = item.bottle.dateConsumed?.let { 
                dateFormat.format(Date(it)) 
            } ?: "N/A"
            item.bottle.bottleId to dateStr
        }
    }

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

                    val dateStr = formattedDatesMap[bottle.bottleId] ?: "N/A"
                    val ratingStr = ratingsMap[definition.definitionId to bottle.vintage]

                    HistoryWineCard(
                        maker = definition.maker,
                        name = definition.name,
                        vintage = bottle.vintage,
                        date = dateStr,
                        quantity = bottle.quantity,
                        rating = ratingStr
                    )
                }
            }
        }
    }
}
