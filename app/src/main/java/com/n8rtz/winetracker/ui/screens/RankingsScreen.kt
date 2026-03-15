package com.n8rtz.winetracker.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.n8rtz.winetracker.ui.components.WineCard
import com.n8rtz.winetracker.ui.components.EmptyState
import com.n8rtz.winetracker.ui.components.LocationWheelPicker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RankingsScreen(viewModel: WineViewModel) {
    val history by viewModel.tastingHistory.collectAsState(initial = emptyList())
    val rankingsFilterState by viewModel.rankingsFilterState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showFilterSheet by remember { mutableStateOf(false) }

    // Price lookup map: (definitionId, vintage) -> price
    var priceMap by remember { mutableStateOf<Map<Pair<Long, String>, Double?>>(emptyMap()) }
    val coroutineScope = rememberCoroutineScope()

    // Load prices whenever history changes
    LaunchedEffect(history) {
        val map = mutableMapOf<Pair<Long, String>, Double?>()
        history.forEach { item ->
            val key = Pair(item.note.definitionId, item.note.vintage)
            if (!map.containsKey(key)) {
                try {
                    // Try specific vintage first
                    var price = viewModel.inventoryDao.getBottlePrice(item.note.definitionId, item.note.vintage)
                    
                    // Fallback to any non-zero price for this wine if vintage-specific is missing or 0
                    if (price == null || price <= 0.0) {
                        price = viewModel.inventoryDao.getLatestAnyPrice(item.note.definitionId)
                    }
                    
                    map[key] = price
                } catch (e: Exception) {
                    Log.w("WineTracker", "RankingsScreen: Failed to load price for ${item.note.definitionId}", e)
                }
            }
        }
        priceMap = map
    }

    val dateFormat = remember { java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()) }

    // Filter and sort ranked wines
    val rankedWines = remember(history, rankingsFilterState, priceMap, searchQuery) {
        history
            .filter { it.note.rating > 0 }
            .filter { item ->
                val matchesSearch = searchQuery.isBlank() ||
                        item.definition.maker.contains(searchQuery, ignoreCase = true) ||
                        item.definition.name.contains(searchQuery, ignoreCase = true) ||
                        item.note.vintage.contains(searchQuery)

                val matchesType = rankingsFilterState.types.isEmpty() ||
                        item.definition.type in rankingsFilterState.types

                val matchesRating = rankingsFilterState.ratingRange?.let { range ->
                    item.note.rating.toFloat() in range
                } ?: true

                val price = priceMap[Pair(item.note.definitionId, item.note.vintage)]
                val matchesPrice = rankingsFilterState.priceRange?.let { range ->
                    price != null && price.toFloat() in range
                } ?: true

                val matchesCountry = rankingsFilterState.country?.let { country ->
                    item.definition.country.equals(country, ignoreCase = true)
                } ?: true

                val matchesRegion = rankingsFilterState.region?.let { region ->
                    item.definition.region?.equals(region, ignoreCase = true) == true
                } ?: true

                val matchesSubregion = rankingsFilterState.subregion?.let { subregion ->
                    item.definition.subregion?.equals(subregion, ignoreCase = true) == true
                } ?: true

                matchesSearch && matchesType && matchesRating && matchesPrice &&
                        matchesCountry && matchesRegion && matchesSubregion
            }
            .sortedWith { a, b ->
                val priceA = priceMap[Pair(a.note.definitionId, a.note.vintage)] ?: 0.0
                val priceB = priceMap[Pair(b.note.definitionId, b.note.vintage)] ?: 0.0
                when (rankingsFilterState.sortOrder) {
                    "Rating (High)" -> b.note.rating.compareTo(a.note.rating)
                    "Rating (Low)" -> a.note.rating.compareTo(b.note.rating)
                    "Price (High)" -> priceB.compareTo(priceA)
                    "Price (Low)" -> priceA.compareTo(priceB)
                    else -> b.note.rating.compareTo(a.note.rating)
                }
            }
    }

    val displayDataMap = remember(rankedWines, priceMap, dateFormat) {
        rankedWines.associate { item ->
            val price = priceMap[Pair(item.note.definitionId, item.note.vintage)]
            val priceStr = if (price != null && price > 0.0) {
                "${"$"}${"%.2f".format(price)}"
            } else ""
            val dateStr = dateFormat.format(java.util.Date(item.note.dateTasted))
            item.note.noteId to (priceStr to dateStr)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        Text(
            "Rankings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        // Search bar + filter button
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
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                val isFilterActive = rankingsFilterState.types.isNotEmpty() ||
                        rankingsFilterState.priceRange != null ||
                        rankingsFilterState.ratingRange != null ||
                        rankingsFilterState.country != null
                Icon(
                    Icons.Default.Tune,
                    "Filter & Sort",
                    tint = if (isFilterActive) Color(0xFF722F37) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (rankedWines.isEmpty()) {
            EmptyState(Icons.Default.EmojiEvents, "No Ranked Wines", "Rate a wine to see it here")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(rankedWines, key = { it.note.noteId }) { item ->
                    val (priceStr, dateStr) = displayDataMap[item.note.noteId] ?: ("" to "")

                    WineCard(
                        title = run {
                            val vintage = item.note.vintage.takeIf { it.isNotBlank() && it.lowercase() != "null" } ?: "NV"
                            "${item.definition.maker} - ${item.definition.name} ($vintage)"
                        },
                        subtitle = run {
                            val validRegion = item.definition.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                            val validCountry = item.definition.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                            listOfNotNull(validRegion, validCountry).joinToString(", ")
                        },
                        type = item.definition.type,
                        metadata = priceStr,
                        imageUrl = item.definition.imageUrl,
                        rating = item.note.rating.toInt().toString(),
                        details = {
                            Column {
                                Text("Tasted: $dateStr")
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

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            RankingsFilterSheet(
                currentState = rankingsFilterState,
                locationData = viewModel.locationData,
                maxPrice = priceMap.values.filterNotNull().maxOrNull()?.toFloat() ?: 500f,
                onApply = { newState ->
                    viewModel.updateRankingsFilterState(newState)
                    showFilterSheet = false
                },
                onClose = { showFilterSheet = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RankingsFilterSheet(
    currentState: RankingsFilterState,
    locationData: Map<String, Map<String, List<String>>>,
    maxPrice: Float,
    onApply: (RankingsFilterState) -> Unit,
    onClose: () -> Unit
) {
    var state by remember { mutableStateOf(currentState) }
    val effectiveMaxPrice = maxPrice.coerceAtLeast(100f)

    // Price range text inputs
    var priceMinText by remember {
        mutableStateOf(state.priceRange?.start?.toInt()?.toString() ?: "")
    }
    var priceMaxText by remember {
        mutableStateOf(state.priceRange?.endInclusive?.toInt()?.toString() ?: "")
    }

    // Rating range text inputs
    var ratingMinText by remember {
        mutableStateOf(state.ratingRange?.start?.toInt()?.toString() ?: "")
    }
    var ratingMaxText by remember {
        mutableStateOf(state.ratingRange?.endInclusive?.toInt()?.toString() ?: "")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Filter Rankings", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Clear, "Close")
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── SORT BY ──
            Text("Sort By", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Rating (High)", "Rating (Low)", "Price (High)", "Price (Low)").forEach { option ->
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

            // ── WINE TYPE ──
            Text("Wine Type", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Red", "White", "Rosé", "Sparkling", "Dessert").forEach { type ->
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

            // ── PRICE RANGE ──
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

            // ── RATING RANGE ──
            Text("Rating Range", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            val ratingSliderRange = 70f..100f
            val currentRatingRange = state.ratingRange ?: ratingSliderRange

            RangeSlider(
                value = currentRatingRange.start.coerceIn(ratingSliderRange)..currentRatingRange.endInclusive.coerceIn(ratingSliderRange),
                onValueChange = { range ->
                    state = state.copy(ratingRange = range)
                    ratingMinText = range.start.toInt().toString()
                    ratingMaxText = range.endInclusive.toInt().toString()
                },
                valueRange = ratingSliderRange,
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
                    value = ratingMinText,
                    onValueChange = { text ->
                        ratingMinText = text
                        val min = text.toFloatOrNull()
                        if (min != null) {
                            val max = state.ratingRange?.endInclusive ?: 100f
                            state = state.copy(ratingRange = min.coerceIn(70f, max)..max)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Min") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = ratingMaxText,
                    onValueChange = { text ->
                        ratingMaxText = text
                        val max = text.toFloatOrNull()
                        if (max != null) {
                            val min = state.ratingRange?.start ?: 70f
                            state = state.copy(ratingRange = min..max.coerceAtLeast(min))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Max") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // ── LOCATION (WHEEL PICKER) ──
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

        // ── STICKY FOOTER ──
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                onClick = {
                    val cleared = RankingsFilterState()
                    state = cleared
                    priceMinText = ""
                    priceMaxText = ""
                    ratingMinText = ""
                    ratingMaxText = ""
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
