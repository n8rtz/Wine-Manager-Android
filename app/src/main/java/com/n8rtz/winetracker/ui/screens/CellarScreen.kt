@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.n8rtz.winetracker.ui.components.AnalyticsCard
import com.n8rtz.winetracker.ui.components.WineCard
import com.n8rtz.winetracker.ui.components.EmptyState
import com.n8rtz.winetracker.ui.screens.CellarFilterState

@Composable
fun CellarScreen(viewModel: WineViewModel, onWineClick: (Long) -> Unit) {
    val inventory by viewModel.activeInventory.collectAsState(initial = emptyList())
    val filterState by viewModel.filterState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showFilterSheet by remember { mutableStateOf(false) }

    val totalBottles = inventory.sumOf { it.bottle.quantity }
    val uniqueWines = inventory.size
    val estimatedValue = inventory.sumOf { it.bottle.quantity * (it.bottle.price ?: 0.0) }

    val filteredInventory = inventory.filter {
        val matchesSearch = it.definition.maker.contains(searchQuery, ignoreCase = true) ||
                it.definition.name.contains(searchQuery, ignoreCase = true)
        
        val matchesType = filterState.types.isEmpty() || filterState.types.contains(it.definition.type)
        val matchesCountry = filterState.countries.isEmpty() || filterState.countries.contains(it.definition.country)
        
        matchesSearch && matchesType && matchesCountry
    }.sortedWith { a, b ->
        when (filterState.sortOrder) {
            "Newest" -> (b.bottle.dateAdded ?: "").compareTo(a.bottle.dateAdded ?: "")
            "Price (High)" -> (b.bottle.price ?: 0.0).compareTo(a.bottle.price ?: 0.0)
            "Price (Low)" -> (a.bottle.price ?: 0.0).compareTo(b.bottle.price ?: 0.0)
            else -> 0
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnalyticsCard("Total Bottles", totalBottles.toString(), Modifier.weight(1f))
            AnalyticsCard("Unique Wines", uniqueWines.toString(), Modifier.weight(1f))
            AnalyticsCard("Est. Value", "$${"%.0f".format(estimatedValue)}", Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search producer or name...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { showFilterSheet = true },
                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val isFilterActive = filterState.types.isNotEmpty() || filterState.countries.isNotEmpty()
                Icon(
                    Icons.Default.Tune, 
                    "Filter & Sort",
                    tint = if (isFilterActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredInventory) { item ->
                WineCard(
                    title = run {
                        val vintage = item.bottle.vintage.takeIf { it.isNotBlank() && it.lowercase() != "null" } ?: "NV"
                        "${item.definition.maker} - ${item.definition.name} ($vintage)"
                    },
                    subtitle = run {
                        val validRegion = item.definition.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        val validCountry = item.definition.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        listOfNotNull(validRegion, validCountry).joinToString(", ")
                    },
                    type = item.definition.type,
                    metadata = "Qty: ${item.bottle.quantity}",
                    imageUrl = item.definition.imageUrl,
                    onClick = { onWineClick(item.bottle.bottleId) }
                )
            }
            
            if (filteredInventory.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.WineBar,
                        title = if (inventory.isEmpty()) "Your Cellar is Empty" else "No matching wines",
                        subtitle = if (inventory.isEmpty()) "Scan a label or add a wine manually to get started" else "Try adjusting your search or filters"
                    )
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterSheet(
            currentState = filterState,
            onDismiss = { showFilterSheet = false },
            onApply = { newState ->
                viewModel.updateFilterState(newState)
                showFilterSheet = false
            }
        )
    }
}

@Composable
fun FilterSheet(
    currentState: CellarFilterState,
    onDismiss: () -> Unit,
    onApply: (CellarFilterState) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Filter & Sort", style = MaterialTheme.typography.titleLarge)
            
            Text("Sort By", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Newest", "Price (High)", "Price (Low)").forEach { option ->
                    FilterChip(
                        selected = currentState.sortOrder == option,
                        onClick = { onApply(currentState.copy(sortOrder = option)) },
                        label = { Text(option) }
                    )
                }
            }

            Text("Type", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Red", "White", "Rosé", "Sparkling").forEach { type ->
                    val selected = currentState.types.contains(type)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val newTypes = if (selected) currentState.types - type else currentState.types + type
                            onApply(currentState.copy(types = newTypes))
                        },
                        label = { Text(type) }
                    )
                }
            }

            Button(
                onClick = { onApply(CellarFilterState()) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Text("Reset Filters")
            }
        }
    }
}
