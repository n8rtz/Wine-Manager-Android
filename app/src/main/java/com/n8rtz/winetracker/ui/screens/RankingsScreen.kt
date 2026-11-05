package com.n8rtz.winetracker.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
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
import com.n8rtz.winetracker.ui.components.FloatingSearchFilterControls
import com.n8rtz.winetracker.ui.components.EmptyState
import com.n8rtz.winetracker.ui.components.LocationWheelPicker
import kotlinx.coroutines.launch

import com.n8rtz.winetracker.viewmodels.RankingsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RankingsScreen(viewModel: RankingsViewModel) {
    val rankedWines by viewModel.filteredRankings.collectAsState()
    val rankingsFilterState by viewModel.rankingsFilterState.collectAsState()
    val rankingsLocationData by viewModel.rankingsLocationData.collectAsState()
    val rankingsPrices by viewModel.rankingsPrices.collectAsState()
    val searchQuery by viewModel.rankingsSearchQuery.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    // Not saveable: Compose Navigation restores tab state, so a saved list state would reopen this
    // screen where it was scrolled to instead of at the top.
    val rankingsListState = remember { LazyListState() }
    LaunchedEffect(Unit) { rankingsListState.scrollToItem(0) }

    // Opening search or changing a filter keeps the list exactly where it is.
    val isFilterActive = rankingsFilterState.types.isNotEmpty() ||
            rankingsFilterState.priceRange != null ||
            rankingsFilterState.ratingRange != null ||
            rankingsFilterState.country != null

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = rankingsListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 60.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "rankings_header") {
                Text(
                    "Rankings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (rankedWines.isEmpty()) {
                item(key = "rankings_empty") {
                    EmptyState(Icons.Default.EmojiEvents, "No Ranked Wines", "Rate a wine to see it here")
                }
            } else {
                items(rankedWines, key = { it.item.note.noteId }) { displayItem ->
                    val item = displayItem.item
                    WineCard(
                        title = run {
                            "${item.definition.maker} - ${item.definition.name} (${com.n8rtz.winetracker.data.local.displayVintage(item.note.vintage)})"
                        },
                        subtitle = run {
                            val validSub = item.definition.subregion?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                            val validRegion = item.definition.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                            val validCountry = item.definition.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                            listOfNotNull(validSub, validRegion, validCountry).joinToString(", ")
                        },
                        type = item.definition.type,
                        metadata = displayItem.priceStr,
                        imageUrl = item.definition.imageUrl.takeIf { item.imageAsset == null },
                        imageLocalPath = item.imageAsset?.relativePath,
                        rating = item.note.rating.toInt().toString(),
                        details = {
                            Column {
                                Text("Tasted: ${displayItem.dateStr}")
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

        FloatingSearchFilterControls(
            searchQuery = searchQuery,
            onSearchQueryChange = { viewModel.updateRankingsSearchQuery(it) },
            searchExpanded = searchExpanded,
            onOpenSearch = { searchExpanded = true },
            onCloseSearch = { searchExpanded = false },
            onOpenFilters = { showFilterSheet = true },
            filterActive = isFilterActive,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            RankingsFilterSheet(
                currentState = rankingsFilterState,
                locationData = rankingsLocationData,
                maxPrice = rankingsPrices.values.filterNotNull().maxOrNull()?.toFloat() ?: 500f,
                onApply = { newState ->
                    viewModel.updateRankingsFilterState(newState)
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

    var priceMinText by remember {
        mutableStateOf(state.priceRange?.start?.toInt()?.toString() ?: "")
    }
    var priceMaxText by remember {
        mutableStateOf(state.priceRange?.endInclusive?.toInt()?.toString() ?: "")
    }
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
                Text("Filter Rankings", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val cleared = RankingsFilterState()
                            state = cleared
                            priceMinText = ""
                            priceMaxText = ""
                            ratingMinText = ""
                            ratingMaxText = ""
                            onApply(cleared)
                        }
                    ) {
                        Text("Clear All")
                    }
                    IconButton(onClick = onClose) {
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
                listOf("Rating (High)", "Rating (Low)", "Price (High)", "Price (Low)").forEach { option ->
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

            Text("Rating Range", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            val ratingSliderRange = 70f..100f
            val currentRatingRange = state.ratingRange ?: ratingSliderRange

            RangeSlider(
                value = currentRatingRange.start.coerceIn(ratingSliderRange)..currentRatingRange.endInclusive.coerceIn(ratingSliderRange),
                onValueChange = { range ->
                    val newState = state.copy(ratingRange = range)
                    state = newState
                    ratingMinText = range.start.toInt().toString()
                    ratingMaxText = range.endInclusive.toInt().toString()
                    onApply(newState)
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
                            val newState = state.copy(ratingRange = min.coerceIn(70f, max)..max)
                            state = newState
                            onApply(newState)
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
                            val newState = state.copy(ratingRange = min..max.coerceAtLeast(min))
                            state = newState
                            onApply(newState)
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
