@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
import com.n8rtz.winetracker.ui.components.LocationWheelPicker
import com.n8rtz.winetracker.ui.screens.CellarFilterState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CellarScreen(viewModel: WineViewModel, onWineClick: (Long) -> Unit) {
    val inventory by viewModel.activeInventory.collectAsState(initial = emptyList())
    val filterState by viewModel.filterState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showFilterSheet by remember { mutableStateOf(false) }
    val tastingHistory by viewModel.tastingHistory.collectAsState(initial = emptyList())
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val parseFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    
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

    val notesMap = remember(tastingHistory) {
        tastingHistory
            .groupBy { it.note.definitionId to it.note.vintage }
            .mapValues { (_, notes) ->
                notes.sortedByDescending { it.note.dateTasted }
            }
    }

    val analytics = remember(inventory) {
        val total = inventory.sumOf { it.bottle.quantity }
        val unique = inventory.size
        val value = inventory.sumOf { it.bottle.quantity * (it.bottle.price ?: 0.0) }
        Triple(total, unique, value)
    }

    val filteredInventory = remember(inventory, searchQuery, filterState) {
        inventory.filter {
            val matchesSearch = it.definition.maker.contains(searchQuery, ignoreCase = true) ||
                    it.definition.name.contains(searchQuery, ignoreCase = true)
            
            val matchesType = filterState.types.isEmpty() || filterState.types.contains(it.definition.type)
            
            val matchesCountry = filterState.country == null || it.definition.country.equals(filterState.country, ignoreCase = true)
            val matchesRegion = filterState.region == null || it.definition.region?.equals(filterState.region, ignoreCase = true) == true
            val matchesSubregion = filterState.subregion == null || it.definition.subregion?.equals(filterState.subregion, ignoreCase = true) == true

            val matchesPrice = filterState.priceRange?.let { range ->
                val price = it.bottle.price ?: 0.0
                price.toFloat() in range
            } ?: true
            
            matchesSearch && matchesType && matchesCountry && matchesRegion && matchesSubregion && matchesPrice
        }.sortedWith { a, b ->
            when (filterState.sortOrder) {
                "Newest" -> (b.bottle.dateAdded ?: "").compareTo(a.bottle.dateAdded ?: "")
                "Price (High)" -> (b.bottle.price ?: 0.0).compareTo(a.bottle.price ?: 0.0)
                "Price (Low)" -> (a.bottle.price ?: 0.0).compareTo(b.bottle.price ?: 0.0)
                else -> 0
            }
        }
    }

    val formattedDatesMap = remember(filteredInventory, dateFormat, parseFormat) {
        filteredInventory.associate { item ->
            val formatted = item.bottle.dateAdded?.let {
                try {
                    val date = parseFormat.parse(it)
                    if (date != null) dateFormat.format(date) else it
                } catch (e: Exception) {
                    it
                }
            }
            item.bottle.bottleId to formatted
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val (totalBottles, uniqueWines, estimatedValue) = analytics
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
                val isFilterActive = filterState.types.isNotEmpty() || 
                        filterState.country != null || 
                        filterState.priceRange != null
                Icon(
                    Icons.Default.Tune, 
                    "Filter & Sort",
                    tint = if (isFilterActive) Color(0xFF722F37) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val displayedBottleCount = remember(filteredInventory) { filteredInventory.sumOf { it.bottle.quantity } }
        Text(
            text = "Showing $displayedBottleCount ${if (displayedBottleCount == 1) "bottle" else "bottles"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredInventory, key = { it.bottle.bottleId }) { item ->
                val ratingStr = ratingsMap[item.definition.definitionId to item.bottle.vintage]
                val itemNotes = notesMap[item.definition.definitionId to item.bottle.vintage] ?: emptyList()
                val formattedAddedDate = formattedDatesMap[item.bottle.bottleId]

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
                    rating = ratingStr,
                    details = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (formattedAddedDate != null) {
                                Text(
                                    text = "Added on: $formattedAddedDate",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            if (itemNotes.isNotEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = "Tasting Notes",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                itemNotes.forEach { noteWithDef ->
                                    val noteDate = dateFormat.format(Date(noteWithDef.note.dateTasted))
                                    Text(
                                        text = "$noteDate: ${noteWithDef.note.notes ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    },
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
            locationData = viewModel.locationData,
            maxPrice = inventory.mapNotNull { it.bottle.price }.maxOrNull()?.toFloat() ?: 500f,
            onDismiss = { showFilterSheet = false },
            onApply = { newState ->
                viewModel.updateFilterState(newState)
                showFilterSheet = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    currentState: CellarFilterState,
    locationData: Map<String, Map<String, List<String>>>,
    maxPrice: Float,
    onDismiss: () -> Unit,
    onApply: (CellarFilterState) -> Unit
) {
    var state by remember { mutableStateOf(currentState) }
    val effectiveMaxPrice = maxPrice.coerceAtLeast(100f)

    var priceMinText by remember {
        mutableStateOf(state.priceRange?.start?.toInt()?.toString() ?: "")
    }
    var priceMaxText by remember {
        mutableStateOf(state.priceRange?.endInclusive?.toInt()?.toString() ?: "")
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Filter & Sort", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Clear, "Close")
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text("Sort By", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Newest", "Price (High)", "Price (Low)").forEach { option ->
                        FilterChip(
                            selected = state.sortOrder == option,
                            onClick = { state = state.copy(sortOrder = option) },
                            label = { Text(option) },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF722F37),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text("Wine Type", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Red", "White", "Rosé", "Sparkling").forEach { type ->
                        val isSelected = type in state.types
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                state = state.copy(
                                    types = if (isSelected) state.types - type else state.types + type
                                )
                            },
                            label = { Text(type) },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF722F37),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text("Price Range", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                val priceSliderRange = 0f..effectiveMaxPrice
                val currentPriceRange = state.priceRange ?: priceSliderRange

                RangeSlider(
                    value = currentPriceRange.start.coerceIn(priceSliderRange)..currentPriceRange.endInclusive.coerceIn(priceSliderRange),
                    onValueChange = { range ->
                        state = state.copy(priceRange = range)
                        priceMinText = range.start.toInt().toString()
                        priceMaxText = range.endInclusive.toInt().toString()
                    },
                    valueRange = priceSliderRange,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF722F37),
                        activeTrackColor = Color(0xFF722F37)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = priceMinText,
                        onValueChange = { text ->
                            priceMinText = text
                            val min = text.toFloatOrNull()
                            if (min != null) {
                                val max = state.priceRange?.endInclusive ?: effectiveMaxPrice
                                state = state.copy(priceRange = min.coerceIn(0f, max)..max)
                            } else if (text.isEmpty()) {
                                state = state.copy(priceRange = null)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Min $") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = priceMaxText,
                        onValueChange = { text ->
                            priceMaxText = text
                            val max = text.toFloatOrNull()
                            if (max != null) {
                                val min = state.priceRange?.start ?: 0f
                                state = state.copy(priceRange = min..max.coerceAtLeast(min))
                            } else if (text.isEmpty()) {
                                state = state.copy(priceRange = null)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Max $") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text("Location", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                LocationWheelPicker(
                    locationData = locationData,
                    selectedCountry = state.country,
                    selectedRegion = state.region,
                    selectedSubregion = state.subregion,
                    onLocationChange = { country, region, subregion ->
                        state = state.copy(
                            country = country,
                            region = region,
                            subregion = subregion
                        )
                    }
                )

                Spacer(Modifier.height(16.dp))
            }

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = {
                        val cleared = CellarFilterState()
                        state = cleared
                        priceMinText = ""
                        priceMaxText = ""
                        onApply(cleared)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear All")
                }
                Button(
                    onClick = { onApply(state) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF722F37)
                    )
                ) {
                    Text("Apply Filters")
                }
            }
        }
    }
}
