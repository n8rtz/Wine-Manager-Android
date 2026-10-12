package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import com.n8rtz.winetracker.ui.components.LocationWheelPicker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TastingNotesScreen(
    tastingNoteViewModel: TastingNoteViewModel = viewModel(),
    onNoteClick: (Long) -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val history by tastingNoteViewModel.tastingNotes.collectAsState(initial = emptyList())
    
    val historyByVintage = remember(history) {
        history.groupBy { it.note.definitionId to com.n8rtz.winetracker.data.local.displayVintage(it.note.vintage) }
    }
    
    val ratingsMap = remember(historyByVintage) {
        historyByVintage.mapValues { (_, notes) ->
            val ratings = notes.filter { it.note.rating > 0 }.map { it.note.rating }
            if (ratings.isEmpty()) return@mapValues null
            val min = ratings.minOrNull()?.toInt() ?: 0
            val max = ratings.maxOrNull()?.toInt() ?: 0
            if (min == max) min.toString() else "$min-$max"
        }
    }

    val filterState by tastingNoteViewModel.filterState.collectAsState()
    val locationData by tastingNoteViewModel.tastingNotesLocationData.collectAsState()
    val prices by tastingNoteViewModel.rankingsPrices.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showFilterSheet by remember { mutableStateOf(false) }
    var expandedGroupKey by remember { mutableStateOf<Pair<Long, String>?>(null) }

    val filteredHistory = remember(history, searchQuery, filterState, prices) {
        history.filter { item ->
            val matchesSearch = searchQuery.isBlank() ||
                    item.definition.maker.contains(searchQuery, ignoreCase = true) ||
                    item.definition.name.contains(searchQuery, ignoreCase = true) ||
                    com.n8rtz.winetracker.data.local.displayVintage(item.note.vintage).contains(searchQuery, ignoreCase = true)

            val matchesType = filterState.types.isEmpty() || item.definition.type in filterState.types
            
            val matchesStatus = when (filterState.status) {
                "Needs Notes" -> item.note.notes.isNullOrBlank()
                "Completed" -> !item.note.notes.isNullOrBlank()
                else -> true
            }

            val price = prices[Pair(item.note.definitionId, com.n8rtz.winetracker.data.local.displayVintage(item.note.vintage))]
            val matchesPrice = filterState.priceRange?.let { range ->
                price != null && price.toFloat() in range
            } ?: true

            val matchesCountry = filterState.country == null || item.definition.country.trim().equals(filterState.country, ignoreCase = true)
            val matchesRegion = filterState.region == null || item.definition.region?.trim()?.equals(filterState.region, ignoreCase = true) == true
            val matchesSubregion = filterState.subregion == null || item.definition.subregion?.trim()?.equals(filterState.subregion, ignoreCase = true) == true

            matchesSearch && matchesType && matchesStatus && matchesPrice &&
                    matchesCountry && matchesRegion && matchesSubregion
        }.sortedWith { a, b ->
            val priceA = prices[Pair(a.note.definitionId, com.n8rtz.winetracker.data.local.displayVintage(a.note.vintage))] ?: 0.0
            val priceB = prices[Pair(b.note.definitionId, com.n8rtz.winetracker.data.local.displayVintage(b.note.vintage))] ?: 0.0
            when (filterState.sortOrder) {
                "Newest" -> compareByDescending<TastingNoteWithDefinition> { it.note.dateTasted }.thenByDescending { it.note.noteId }.compare(a, b)
                "Price (High)" -> priceB.compareTo(priceA)
                "Price (Low)" -> priceA.compareTo(priceB)
                else -> compareByDescending<TastingNoteWithDefinition> { it.note.dateTasted }.thenByDescending { it.note.noteId }.compare(a, b)
            }
        }
    }

    val groupedHistory = remember(filteredHistory) {
        filteredHistory.groupBy { it.note.definitionId to com.n8rtz.winetracker.data.local.displayVintage(it.note.vintage) }
            .values.toList()
    }

    val listDateFormat = remember { java.text.SimpleDateFormat("M/d/yy", java.util.Locale.getDefault()) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Tasting Notes") },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "View History")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search Producer, Name, or Vintage") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { showFilterSheet = true },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val isFilterActive = filterState.types.isNotEmpty() ||
                            filterState.status != "All" ||
                            filterState.country != null || filterState.region != null || filterState.subregion != null ||
                            filterState.priceRange != null
                    Icon(
                        Icons.Default.Tune, 
                        contentDescription = "Filter Notes",
                        tint = if (isFilterActive) Color(0xFF722F37) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (groupedHistory.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Description, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("No notes found", style = MaterialTheme.typography.titleLarge)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groupedHistory, key = { group ->
                        "${group.first().note.definitionId}_${com.n8rtz.winetracker.data.local.displayVintage(group.first().note.vintage)}"
                    }) { group ->
                        val firstItem = group.first()
                        val groupKey = firstItem.note.definitionId to com.n8rtz.winetracker.data.local.displayVintage(firstItem.note.vintage)
                        CompactNoteItem(
                            group = group,
                            expanded = expandedGroupKey == groupKey,
                            rating = ratingsMap[groupKey],
                            listDateFormat = listDateFormat,
                            onExpandToggle = {
                                expandedGroupKey = if (expandedGroupKey == groupKey) null else groupKey
                            },
                            price = prices[groupKey],
                            onNoteClick = onNoteClick
                        )
                    }
                }
            }
        }

        if (showFilterSheet) {
            val maxPrice = prices.values.filterNotNull().maxOrNull()?.toFloat() ?: 500f
            TastingNotesFilterSheet(
                currentState = filterState,
                locationData = locationData,
                maxPrice = maxPrice,
                onDismiss = { showFilterSheet = false },
                onApply = { newState ->
                    tastingNoteViewModel.updateFilterState(newState)
                },
                onClearAll = { searchQuery = "" }
            )
        }
    }
}

@Composable
fun CompactNoteItem(
    group: List<TastingNoteWithDefinition>,
    expanded: Boolean,
    rating: String?,
    listDateFormat: java.text.SimpleDateFormat,
    price: Double?,
    onExpandToggle: () -> Unit,
    onNoteClick: (Long) -> Unit
) {
    val firstItem = group.first()
    com.n8rtz.winetracker.ui.components.WineCard(
        title = run {
            "${firstItem.definition.maker} - ${firstItem.definition.name} (${com.n8rtz.winetracker.data.local.displayVintage(firstItem.note.vintage)})"
        },
        subtitle = run {
            val validSub = firstItem.definition.subregion?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
            val validRegion = firstItem.definition.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
            val validCountry = firstItem.definition.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
            listOfNotNull(validSub, validRegion, validCountry).joinToString(", ")
        },
        type = firstItem.definition.type,
        metadata = if (price != null && price > 0.0) "$${"%.2f".format(price)}" else "",
        imageUrl = firstItem.definition.imageUrl,
        rating = rating,
        isExpanded = expanded,
        onClick = onExpandToggle,
        details = {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                group.sortedByDescending { it.note.dateTasted }.forEach { item ->
                    val dateStr = listDateFormat.format(java.util.Date(item.note.dateTasted))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNoteClick(item.note.noteId) }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "$dateStr - ${item.note.notes ?: "No notes written..."}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.note.notes.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TastingNotesFilterSheet(
    currentState: TastingNotesFilterState,
    locationData: Map<String, Map<String, List<String>>>,
    maxPrice: Float,
    onDismiss: () -> Unit,
    onApply: (TastingNotesFilterState) -> Unit,
    onClearAll: () -> Unit = {}
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                val cleared = TastingNotesFilterState()
                                state = cleared
                                priceMinText = ""
                                priceMaxText = ""
                                onApply(cleared)
                                onClearAll()
                            }
                        ) {
                            Text("Clear All")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Clear, "Close")
                        }
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
                            onClick = { 
                                val newState = state.copy(sortOrder = option)
                                state = newState
                                onApply(newState)
                            },
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

                Text("Completion Status", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Needs Notes", "Completed").forEach { status ->
                        FilterChip(
                            selected = state.status == status,
                            onClick = { 
                                val newState = state.copy(status = status)
                                state = newState
                                onApply(newState)
                            },
                            label = { Text(status) },
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
                                val newState = state.copy(
                                    types = if (isSelected) state.types - type else state.types + type
                                )
                                state = newState
                                onApply(newState)
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
                        val newState = state.copy(priceRange = range)
                        state = newState
                        priceMinText = range.start.toInt().toString()
                        priceMaxText = range.endInclusive.toInt().toString()
                        onApply(newState)
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
                                val newState = state.copy(priceRange = min.coerceIn(0f, max)..max)
                                state = newState
                                onApply(newState)
                            } else if (text.isEmpty()) {
                                val newState = state.copy(priceRange = null)
                                state = newState
                                onApply(newState)
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
                                val newState = state.copy(priceRange = min..max.coerceAtLeast(min))
                                state = newState
                                onApply(newState)
                            } else if (text.isEmpty()) {
                                val newState = state.copy(priceRange = null)
                                state = newState
                                onApply(newState)
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
                        val newState = state.copy(
                            country = country,
                            region = region,
                            subregion = subregion
                        )
                        state = newState
                        onApply(newState)
                    }
                )

                Spacer(Modifier.height(16.dp))
            }

        }
    }
}
