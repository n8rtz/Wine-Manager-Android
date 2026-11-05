@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
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
import com.n8rtz.winetracker.ui.components.FloatingSearchFilterControls
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
    val cellarLocationData by viewModel.cellarLocationData.collectAsState()
    val cellarResetKey by viewModel.cellarResetKey.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }
    var expandedBottleId by remember { mutableStateOf<Long?>(null) }
    // Not saveable: Compose Navigation restores tab state, so a saved list state would reopen this
    // screen where it was scrolled to instead of at the top.
    val cellarListState = remember { LazyListState() }
    LaunchedEffect(Unit) { cellarListState.scrollToItem(0) }
    // The restored top search row scrolls away with the list; the compact controls appear once
    // that row is out of view, and stay visible while scrolling.
    val showCompactControls by remember {
        derivedStateOf { cellarListState.firstVisibleItemIndex > 0 }
    }
    // Back at the top the full search row is visible again, so the pop-up is no longer needed.
    LaunchedEffect(showCompactControls) {
        if (!showCompactControls) searchExpanded = false
    }
    val tastingHistory by viewModel.tastingHistory.collectAsState(initial = emptyList())
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val parseFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(cellarResetKey) {
        searchQuery = ""
        searchExpanded = false
        showFilterSheet = false
        expandedBottleId = null
    }

    val ratingsMap = remember(tastingHistory) {
        tastingHistory
            .filter { it.note.rating > 0 }
            .groupBy { it.note.definitionId to com.n8rtz.winetracker.data.local.displayVintage(it.note.vintage) }
            .mapValues { (_, notes) ->
                val ratings = notes.map { it.note.rating }
                val min = ratings.minOrNull()?.toInt() ?: 0
                val max = ratings.maxOrNull()?.toInt() ?: 0
                if (min == max) min.toString() else "$min-$max"
            }
    }

    val ratingSortMap = remember(tastingHistory) {
        tastingHistory
            .filter { it.note.rating > 0.0 }
            .groupBy { it.note.definitionId to com.n8rtz.winetracker.data.local.displayVintage(it.note.vintage) }
            .mapValues { (_, notes) -> notes.map { it.note.rating }.average() }
    }

    val notesMap = remember(tastingHistory) {
        tastingHistory
            .groupBy { it.note.definitionId to com.n8rtz.winetracker.data.local.displayVintage(it.note.vintage) }
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

    val filteredInventory = remember(inventory, searchQuery, filterState, ratingSortMap) {
        inventory.filter {
            val matchesSearch = it.definition.maker.contains(searchQuery, ignoreCase = true) ||
                    it.definition.name.contains(searchQuery, ignoreCase = true)

            val matchesType = filterState.types.isEmpty() || filterState.types.contains(it.definition.type)

            val matchesCountry = filterState.country == null || it.definition.country.trim().equals(filterState.country, ignoreCase = true)
            val matchesRegion = filterState.region == null || it.definition.region?.trim()?.equals(filterState.region, ignoreCase = true) == true
            val matchesSubregion = filterState.subregion == null || it.definition.subregion?.trim()?.equals(filterState.subregion, ignoreCase = true) == true

            val matchesPrice = filterState.priceRange?.let { range ->
                val price = it.bottle.price ?: 0.0
                price.toFloat() in range
            } ?: true

            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val windowStart = it.definition.drinkingWindowStart
            val windowEnd = it.definition.drinkingWindowEnd
            val hasWindow = windowStart != null || windowEnd != null
            val matchesDrinkingWindow = when (filterState.drinkingWindowFilter) {
                "Ready Now" -> hasWindow && (windowStart == null || currentYear >= windowStart) &&
                        (windowEnd == null || currentYear <= windowEnd)
                "Too Early" -> windowStart != null && currentYear < windowStart
                "Past Peak" -> windowEnd != null && currentYear > windowEnd
                else -> true
            }
            val matchesDrinkingWindowRange = filterState.drinkingWindowRange?.let { range ->
                hasWindow && (windowStart ?: Int.MIN_VALUE) <= range.endInclusive.toInt() &&
                        (windowEnd ?: Int.MAX_VALUE) >= range.start.toInt()
            } ?: true
            val vintageYear = it.bottle.vintage.toIntOrNull()
            val matchesVintage = filterState.vintageRange?.let { range ->
                vintageYear != null && vintageYear.toFloat() in range
            } ?: true

            matchesSearch && matchesType && matchesCountry && matchesRegion && matchesSubregion &&
                    matchesPrice && matchesDrinkingWindow && matchesDrinkingWindowRange && matchesVintage
        }.sortedWith { a, b ->
            val aRating = ratingSortMap[
                a.definition.definitionId to com.n8rtz.winetracker.data.local.displayVintage(a.bottle.vintage)
            ]
            val bRating = ratingSortMap[
                b.definition.definitionId to com.n8rtz.winetracker.data.local.displayVintage(b.bottle.vintage)
            ]
            val comparison = when (filterState.sortField) {
                CellarSortField.QUANTITY -> compareSortValues(
                    a.bottle.quantity, b.bottle.quantity, filterState.sortAscending
                )
                CellarSortField.DATE_ADDED -> compareSortValues(
                    a.bottle.dateAdded, b.bottle.dateAdded, filterState.sortAscending
                )
                CellarSortField.RATING -> compareSortValues(aRating, bRating, filterState.sortAscending)
                CellarSortField.PRICE -> compareSortValues(
                    a.bottle.price, b.bottle.price, filterState.sortAscending
                )
                CellarSortField.DRINKING_WINDOW_SOONEST -> compareSortValues(
                    a.definition.drinkingWindowStart, b.definition.drinkingWindowStart, ascending = true
                )
                CellarSortField.DRINKING_WINDOW_FURTHEST -> compareSortValues(
                    a.definition.drinkingWindowEnd, b.definition.drinkingWindowEnd, ascending = false
                )
            }
            comparison
        }
    }

    // Opening search or changing a filter keeps the list exactly where it is.
    val isFilterActive = filterState.types.isNotEmpty() ||
            filterState.country != null || filterState.region != null || filterState.subregion != null ||
            filterState.priceRange != null || filterState.vintageRange != null || filterState.ratingRange != null ||
            filterState.sortField != CellarSortField.DATE_ADDED || filterState.sortAscending ||
            filterState.drinkingWindowFilter != "Any" || filterState.drinkingWindowRange != null
    val displayedBottleCount = remember(filteredInventory) { filteredInventory.sumOf { it.bottle.quantity } }

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

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = cellarListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "cellar_search") {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showStatsDialog = true },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(Icons.Default.QueryStats, "Stats")
                        }
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search", maxLines = 1) },
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
                            Icon(
                                Icons.Default.Tune,
                                "Filter & Sort",
                                tint = if (isFilterActive) Color(0xFF722F37) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "Showing $displayedBottleCount ${if (displayedBottleCount == 1) "bottle" else "bottles"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            items(filteredInventory, key = { it.bottle.bottleId }) { item ->
                val displayVintage = com.n8rtz.winetracker.data.local.displayVintage(item.bottle.vintage)
                val ratingStr = ratingsMap[item.definition.definitionId to displayVintage]
                val itemNotes = notesMap[item.definition.definitionId to displayVintage] ?: emptyList()
                val formattedAddedDate = formattedDatesMap[item.bottle.bottleId]

                WineCard(
                    title = "${item.definition.maker} - ${item.definition.name} (${com.n8rtz.winetracker.data.local.displayVintage(item.bottle.vintage)})",
                    subtitle = run {
                        val validSub = item.definition.subregion?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        val validRegion = item.definition.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        val validCountry = item.definition.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        listOfNotNull(validSub, validRegion, validCountry).joinToString(", ")
                    },
                    type = item.definition.type,
                    metadata = "Qty: ${item.bottle.quantity}",
                    imageUrl = item.definition.imageUrl.takeIf { item.imageAsset == null },
                    imageLocalPath = item.imageAsset?.relativePath,
                    rating = ratingStr,
                    isExpanded = expandedBottleId == item.bottle.bottleId,
                    onExpandToggle = {
                        expandedBottleId = if (expandedBottleId == item.bottle.bottleId) null else item.bottle.bottleId
                    },
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

        if (showCompactControls || searchExpanded) {
            FloatingSearchFilterControls(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                searchExpanded = searchExpanded,
                onOpenSearch = { searchExpanded = true },
                onCloseSearch = { searchExpanded = false },
                onOpenFilters = { showFilterSheet = true },
                filterActive = isFilterActive,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    if (showStatsDialog) {
        val (totalBottles, uniqueWines, estimatedValue) = analytics
        AlertDialog(
            onDismissRequest = { showStatsDialog = false },
            title = { Text("Cellar Stats") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsCard("Total Bottles", totalBottles.toString(), Modifier.weight(1f))
                    AnalyticsCard("Unique Wines", uniqueWines.toString(), Modifier.weight(1f))
                    AnalyticsCard("Est. Value", "$${"%.0f".format(estimatedValue)}", Modifier.weight(1f))
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatsDialog = false }) { Text("Close") }
            }
        )
    }

    if (showFilterSheet) {
        FilterSheet(
            currentState = filterState,
            locationData = cellarLocationData,
            maxPrice = inventory.mapNotNull { it.bottle.price }.maxOrNull()?.toFloat() ?: 500f,
            onDismiss = { showFilterSheet = false },
            onApply = { newState ->
                viewModel.updateFilterState(newState)
            },
            onClearAll = { searchQuery = "" }
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
    onApply: (CellarFilterState) -> Unit,
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
    var windowMinText by remember {
        mutableStateOf(currentState.drinkingWindowRange?.start?.toInt()?.toString() ?: "")
    }
    var windowMaxText by remember {
        mutableStateOf(currentState.drinkingWindowRange?.endInclusive?.toInt()?.toString() ?: "")
    }
    val windowRangeBounds = 1900f..(Calendar.getInstance().get(Calendar.YEAR) + 40).toFloat()

    fun applyWindowRange(minText: String, maxText: String) {
        if (minText.isBlank() && maxText.isBlank()) {
            val newState = state.copy(drinkingWindowRange = null)
            state = newState
            onApply(newState)
            return
        }
        val min = minText.toFloatOrNull() ?: windowRangeBounds.start
        val max = maxText.toFloatOrNull() ?: windowRangeBounds.endInclusive
        if (min <= max) {
            val newState = state.copy(drinkingWindowRange = min.coerceIn(windowRangeBounds)..max.coerceIn(windowRangeBounds))
            state = newState
            onApply(newState)
        }
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
                                val cleared = CellarFilterState()
                                state = cleared
                                priceMinText = ""
                                priceMaxText = ""
                                windowMinText = ""
                                windowMaxText = ""
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
                    listOf(
                        CellarSortField.QUANTITY to "Quantity",
                        CellarSortField.DATE_ADDED to "Date Added",
                        CellarSortField.RATING to "Rating",
                        CellarSortField.PRICE to "Price"
                    ).forEach { (field, label) ->
                        val selected = state.sortField == field
                        val ascending = selected && state.sortAscending
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val newState = state.copy(
                                    sortField = field,
                                    sortAscending = if (selected) !state.sortAscending else false
                                )
                                state = newState
                                onApply(newState)
                            },
                            label = { Text(label) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    contentDescription = if (ascending) "Ascending" else "Descending"
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF722F37),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                    listOf(
                        CellarSortField.DRINKING_WINDOW_SOONEST to "Drinking Window (Soonest)",
                        CellarSortField.DRINKING_WINDOW_FURTHEST to "Drinking Window (Furthest)"
                    ).forEach { (field, label) ->
                        FilterChip(
                            selected = state.sortField == field,
                            onClick = {
                                val newState = state.copy(sortField = field)
                                state = newState
                                onApply(newState)
                            },
                            label = { Text(label) },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF722F37),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                Text(
                    "Tap an active sort button again to reverse the order. Rating uses the average recorded score; unrated wines stay last.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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

                Text("Drinking Window", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Any", "Ready Now", "Too Early", "Past Peak").forEach { option ->
                        FilterChip(
                            selected = state.drinkingWindowFilter == option,
                            onClick = {
                                val newState = state.copy(drinkingWindowFilter = option)
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

                Spacer(Modifier.height(12.dp))
                Text("Drinking Window Years", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                val selectedWindowRange = state.drinkingWindowRange ?: windowRangeBounds
                RangeSlider(
                    value = selectedWindowRange.start.coerceIn(windowRangeBounds)..selectedWindowRange.endInclusive.coerceIn(windowRangeBounds),
                    onValueChange = { range ->
                        val newState = state.copy(drinkingWindowRange = range)
                        state = newState
                        windowMinText = range.start.toInt().toString()
                        windowMaxText = range.endInclusive.toInt().toString()
                        onApply(newState)
                    },
                    valueRange = windowRangeBounds,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF722F37),
                        activeTrackColor = Color(0xFF722F37)
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = windowMinText,
                        onValueChange = { text ->
                            windowMinText = text.filter { it.isDigit() }.take(4)
                            applyWindowRange(windowMinText, windowMaxText)
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Start year") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = windowMaxText,
                        onValueChange = { text ->
                            windowMaxText = text.filter { it.isDigit() }.take(4)
                            applyWindowRange(windowMinText, windowMaxText)
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("End year") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
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

private fun <T : Comparable<T>> compareSortValues(
    first: T?,
    second: T?,
    ascending: Boolean
): Int = when {
    first == null && second == null -> 0
    first == null -> 1
    second == null -> -1
    ascending -> first.compareTo(second)
    else -> second.compareTo(first)
}
