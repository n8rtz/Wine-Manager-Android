@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.n8rtz.winetracker.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.n8rtz.winetracker.ui.components.AnalyticsCard
import com.n8rtz.winetracker.ui.components.WineCard
import com.n8rtz.winetracker.ui.components.AromaChips
import com.n8rtz.winetracker.ui.screens.CellarRacks
import com.n8rtz.winetracker.drinkingwindow.DrinkingWindowEstimator
import com.n8rtz.winetracker.drinkingwindow.WineDescription
import android.content.Context
import androidx.activity.compose.BackHandler
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
fun CellarScreen(
    viewModel: WineViewModel,
    onAddNote: (Long, String) -> Unit,
    onEditWine: (Long) -> Unit,
    onNoteClick: (Long) -> Unit,
    onTastingNotesClick: (Long, String) -> Unit,
    onConsumed: (String, Long) -> Unit,
    onWineActionBarVisibleChange: (Boolean) -> Unit = {}
) {
    val inventory by viewModel.activeInventory.collectAsState(initial = emptyList())
    val filterState by viewModel.filterState.collectAsState()
    val cellarLocationData by viewModel.cellarLocationData.collectAsState()
    val cellarResetKey by viewModel.cellarResetKey.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }
    var expandedBottleId by remember { mutableStateOf<Long?>(null) }
    // Long-pressing a cellar wine selects it and opens the Edit/Delete popup;
    // while anything is selected every card shows a selection checkbox.
    var selectedWineIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var wineMenuBottleId by remember { mutableStateOf<Long?>(null) }
    var showWineDeleteConfirmation by remember { mutableStateOf(false) }
    var alsoDeleteTastingNotes by remember { mutableStateOf(false) }
    val wineSelectionMode = selectedWineIds.isNotEmpty()
    // The wine action popup is a transparent single-line bar docked at the
    // bottom of the screen (above the main navigation); while it is up the
    // "+" Add menu in the host scaffold is hidden.
    val wineActionBarVisible = wineMenuBottleId != null && wineSelectionMode
    // Bottles (0) and Racks (1) live in a pager so swipes and tab taps slide
    // smoothly between pages instead of swapping content instantaneously.
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val cellarTab = pagerState.currentPage
    val pagerScope = rememberCoroutineScope()
    val selectCellarTab: (Int) -> Unit = { page ->
        pagerScope.launch { pagerState.animateScrollToPage(page) }
    }
    var rackDragActive by remember { mutableStateOf(false) }
    var selectedRack by remember { mutableStateOf<String?>(null) }
    var assignmentBottleId by remember { mutableStateOf<Long?>(null) }
    var selectedAssignmentRacks by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Multi-wine rack pick: long-pressing a card's add-to-rack button starts
    // selecting wines; tapping a rack in the Racks tab assigns them all.
    var rackPickWineIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val rackPickMode = rackPickWineIds.isNotEmpty()
    var rackPickConfirmation by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var rackPickAssignmentsBefore by remember { mutableStateOf<Map<Long, Set<String>>>(emptyMap()) }
    LaunchedEffect(rackPickConfirmation) {
        if (rackPickConfirmation != null) {
            kotlinx.coroutines.delay(5000)
            rackPickConfirmation = null
        }
    }
    BackHandler(enabled = rackPickMode && cellarTab == 0) {
        rackPickWineIds = emptySet()
    }
    LaunchedEffect(wineActionBarVisible, rackPickMode, rackPickConfirmation) {
        onWineActionBarVisibleChange(wineActionBarVisible || rackPickMode || rackPickConfirmation != null)
    }
    BackHandler(enabled = wineSelectionMode && cellarTab == 0) {
        selectedWineIds = emptySet()
        wineMenuBottleId = null
    }
    BackHandler(enabled = cellarTab == 1) {
        if (rackPickMode) {
            rackPickWineIds = emptySet()
            selectCellarTab(0)
        } else if (assignmentBottleId != null) {
            assignmentBottleId = null
            selectedAssignmentRacks = emptySet()
            selectCellarTab(0)
        } else if (selectedRack != null) {
            selectedRack = null
        } else {
            selectCellarTab(0)
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val rackPreferences = remember(context) {
        context.getSharedPreferences("cellar_racks_v1", Context.MODE_PRIVATE)
    }
    val initialRackNames = remember(rackPreferences) {
        val savedUserRacks = if (rackPreferences.contains("rack_names")) {
            rackPreferences.getStringSet("rack_names", emptySet()).orEmpty().toList()
        } else {
            DEFAULT_CELLAR_RACK_NAMES + rackPreferences.getStringSet("user_racks", emptySet()).orEmpty()
        }
        val expected = (listOf(NEW_ADDITIONS_RACK) + savedUserRacks.filterNot { it == NEW_ADDITIONS_RACK }).distinct()
        val storedOrder = rackPreferences.getString("rack_order_json", null)?.let { encoded ->
            runCatching {
                val array = org.json.JSONArray(encoded)
                List(array.length()) { index -> array.getString(index) }
            }.getOrNull()
        }
        val ordered = if (storedOrder != null) {
            (storedOrder.filter { it in expected } + expected.filterNot { it in storedOrder }).distinct()
        } else {
            // Preserve the existing displayed positions on first migration, then stop re-alphabetizing.
            listOf(NEW_ADDITIONS_RACK) + savedUserRacks
                .filterNot { it == NEW_ADDITIONS_RACK }
                .distinct()
                .sortedBy { it.lowercase(Locale.getDefault()) }
        }
        rackPreferences.edit()
            .putStringSet("rack_names", ordered.filterNot { it == NEW_ADDITIONS_RACK }.toSet())
            .putString("rack_order_json", org.json.JSONArray(ordered).toString())
            .apply()
        ordered
    }
    var rackNames by remember(rackPreferences) { mutableStateOf(initialRackNames) }
    val persistRackNames: (List<String>) -> Unit = { names ->
        rackNames = names.distinct()
        rackPreferences.edit()
            .putStringSet("rack_names", rackNames.filterNot { it == NEW_ADDITIONS_RACK }.toSet())
            .putString("rack_order_json", org.json.JSONArray(rackNames).toString())
            .apply()
    }
    val moveRack: (String, String) -> Unit = { fromRack, toRack ->
        val fromIndex = rackNames.indexOf(fromRack)
        val toIndex = rackNames.indexOf(toRack)
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            val reordered = rackNames.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            persistRackNames(reordered)
        }
    }
    val addUserRack: (String) -> Unit = { requestedName ->
        val name = requestedName.trim()
        if (name.isNotEmpty() && '|' !in name && rackNames.none { it.equals(name, ignoreCase = true) }) {
            persistRackNames(rackNames + name)
        }
    }
    var excludedNewAdditionIds by remember(rackPreferences) {
        mutableStateOf(rackPreferences.getStringSet("new_additions_exclusions", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet())
    }
    var rackAssignments by remember(rackPreferences) {
        mutableStateOf(rackPreferences.getStringSet("assignments", mutableSetOf()).orEmpty().mapNotNull { entry ->
            val parts = entry.split('|', limit = 2)
            if (parts.size == 2) parts[0].toLongOrNull()?.let { it to parts[1] } else null
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() })
    }
    val updateRackAssignments: (Long, Set<String>) -> Unit = { bottleId, racks ->
        val updated = rackAssignments.toMutableMap().apply {
            if (racks.isEmpty()) remove(bottleId) else put(bottleId, racks)
        }
        rackAssignments = updated
        rackPreferences.edit().putStringSet(
            "assignments", updated.flatMap { (id, names) -> names.map { name -> "$id|$name" } }.toSet()
        ).apply()
    }
    val renameRack: (String, String) -> Unit = { oldName, requestedName ->
        val name = requestedName.trim()
        if (name.isNotEmpty() && '|' !in name && rackNames.none { it != oldName && it.equals(name, ignoreCase = true) }) {
            persistRackNames(rackNames.map { if (it == oldName) name else it })
            val updatedAssignments = rackAssignments.mapValues { (_, racks) ->
                if (oldName in racks) racks - oldName + name else racks
            }
            rackAssignments = updatedAssignments
            rackPreferences.edit().putStringSet(
                "assignments", updatedAssignments.flatMap { (id, names) -> names.map { rack -> "$id|$rack" } }.toSet()
            ).apply()
        }
    }
    val deleteRacks: (Set<String>) -> Unit = { deletedNames ->
        persistRackNames(rackNames.filterNot { it in deletedNames })
        val updatedAssignments = rackAssignments.mapNotNull { (id, racks) ->
            val remaining = racks - deletedNames
            if (remaining.isEmpty()) null else id to remaining
        }.toMap()
        rackAssignments = updatedAssignments
        rackPreferences.edit().putStringSet(
            "assignments", updatedAssignments.flatMap { (id, names) -> names.map { rack -> "$id|$rack" } }.toSet()
        ).apply()
    }
    val removeFromRack: (Set<Long>, String) -> Unit = { bottleIds, rack ->
        if (rack == NEW_ADDITIONS_RACK) {
            excludedNewAdditionIds = excludedNewAdditionIds + bottleIds
            rackPreferences.edit().putStringSet(
                "new_additions_exclusions", excludedNewAdditionIds.map { it.toString() }.toSet()
            ).apply()
        } else {
            val updated = rackAssignments.toMutableMap()
            bottleIds.forEach { bottleId ->
                val remaining = updated[bottleId].orEmpty() - rack
                if (remaining.isEmpty()) updated.remove(bottleId) else updated[bottleId] = remaining
            }
            rackAssignments = updated
            rackPreferences.edit().putStringSet(
                "assignments", updated.flatMap { (id, names) -> names.map { name -> "$id|$name" } }.toSet()
            ).apply()
        }
    }
    val startRackAssignment: (Long) -> Unit = { bottleId ->
        assignmentBottleId = bottleId
        selectedAssignmentRacks = rackAssignments[bottleId].orEmpty()
        selectedRack = null
        selectCellarTab(1)
    }

    val completeRackPick: (String) -> Unit = { rack ->
        rackPickAssignmentsBefore = rackAssignments.filterKeys { it in rackPickWineIds }
        val count = rackPickWineIds.size
        rackPickWineIds.forEach { bottleId ->
            updateRackAssignments(bottleId, rackAssignments[bottleId].orEmpty() + rack)
        }
        rackPickWineIds = emptySet()
        rackPickConfirmation = count to rack
    }
    val undoRackPick: () -> Unit = {
        // Completion cleared the pick set, so restore from the snapshot.
        rackPickAssignmentsBefore.keys.forEach { bottleId ->
            updateRackAssignments(bottleId, rackPickAssignmentsBefore[bottleId].orEmpty())
        }
        rackPickConfirmation = null
    }
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
        pagerState.animateScrollToPage(0)
        selectedRack = null
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

    // Sorting by rating uses each wine's most recent rating, not an average
    // across all of its tasting notes.
    val ratingSortMap = remember(tastingHistory) {
        tastingHistory
            .filter { it.note.rating > 0.0 }
            .groupBy { it.note.definitionId to com.n8rtz.winetracker.data.local.displayVintage(it.note.vintage) }
            .mapValues { (_, notes) -> notes.maxByOrNull { it.note.dateTasted }?.note?.rating }
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

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = cellarTab) {
            Tab(selected = cellarTab == 0, onClick = { selectCellarTab(0); selectedRack = null }, text = { Text("Bottles") })
            Tab(selected = cellarTab == 1, onClick = { selectCellarTab(1); selectedRack = null }, text = { Text("Racks") })
        }
        // The pager tracks the finger while swiping and animates (settles) to
        // the neighbouring page on release or when a tab is tapped. Paging is
        // locked while a rack is open, while assigning a bottle to racks, or
        // while a rack tile is being drag-reordered, so those gestures are
        // never stolen by a page change.
        Box(modifier = Modifier.weight(1f)) {
        HorizontalPager(
            state = pagerState,
            // Keep the off-screen page composed so swiping Bottles <-> Racks
            // and back does not reset the page's local state (wine selection
            // and its Edit/Delete bar, expanded card, scroll position, rack
            // selection state, ...). With only two pages this is cheap.
            beyondViewportPageCount = 1,
            userScrollEnabled = selectedRack == null && assignmentBottleId == null && !rackDragActive,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page == 0) {
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
                val toggleExpanded = {
                    expandedBottleId = if (expandedBottleId == item.bottle.bottleId) null else item.bottle.bottleId
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                WineCard(
                    title = "${item.definition.maker} - ${item.definition.name} (${com.n8rtz.winetracker.data.local.displayVintage(item.bottle.vintage)})",
                    subtitle = run {
                        val validSub = item.definition.subregion?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        val validRegion = item.definition.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        val validCountry = item.definition.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        listOfNotNull(validSub, validRegion, validCountry).joinToString(", ")
                    },
                    type = item.definition.type,
                    metadata = "Cellar: ${item.bottle.quantity}",
                    imageUrl = item.definition.imageUrl.takeIf { item.imageAsset == null },
                    imageLocalPath = item.imageAsset?.relativePath,
                    rating = ratingStr,
                    isExpanded = expandedBottleId == item.bottle.bottleId,
                    onExpandToggle = toggleExpanded,
                    trailingAction = {
                        RackQuickAddButton(
                            automaticNewAddition = itemNotes.none { it.note.rating > 0.0 || !it.note.notes.isNullOrBlank() },
                            onOpenRackSelection = { startRackAssignment(item.bottle.bottleId) },
                            onLongPress = { rackPickWineIds = setOf(item.bottle.bottleId) }
                        )
                    },
                    drinkingWindow = run {
                        val start = item.definition.drinkingWindowStart
                        val end = item.definition.drinkingWindowEnd
                        if (start != null && end != null) start to end
                        else DrinkingWindowEstimator.estimate(
                            WineDescription(
                                type = item.definition.type, country = item.definition.country,
                                region = item.definition.region, subregion = item.definition.subregion,
                                maker = item.definition.maker, name = item.definition.name,
                                vintage = item.bottle.vintage
                            )
                        )?.let { it.startYear to it.endYear }
                    },
                    onLongClick = {
                        if (rackPickMode) {
                            rackPickWineIds = if (item.bottle.bottleId in rackPickWineIds) {
                                rackPickWineIds - item.bottle.bottleId
                            } else {
                                rackPickWineIds + item.bottle.bottleId
                            }
                        } else {
                            // Select the long-pressed wine and pop up its menu.
                            selectedWineIds = selectedWineIds + item.bottle.bottleId
                            wineMenuBottleId = item.bottle.bottleId
                        }
                    },
                    details = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Aromas on top, then the action row, then racks.
                            // Most recent review's aromas; if the newest
                            // review has none, fall back to the newest
                            // review that does.
                            val aromaNote = itemNotes
                                .sortedByDescending { it.note.dateTasted }
                                .firstOrNull { it.note.sensoryData?.aromas.orEmpty().isNotEmpty() }
                            if (aromaNote != null) {
                                CellarSectionHeader("Aromas · ${dateFormat.format(Date(aromaNote.note.dateTasted))}")
                                AromaChips(
                                    aromaNote.note.sensoryData?.aromas.orEmpty(),
                                    maxVisible = 7,
                                    onOverflowClick = { onNoteClick(aromaNote.note.noteId) }
                                )
                                // Divider separating the aroma section from
                                // the cellar/Notes/add-note action row.
                                HorizontalDivider()
                            }
                            // Cellar count, Notes and add-note
                            // all sit inline in a single row.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    modifier = Modifier.size(36.dp),
                                    onClick = {
                                        viewModel.consumeBottle(
                                            bottleId = item.bottle.bottleId,
                                            rating = null,
                                            notes = null,
                                            rebuy = false,
                                            consumptionDate = System.currentTimeMillis(),
                                            quantity = 1
                                        ) { eventId ->
                                            onConsumed("Consumed 1 bottle of ${item.definition.name}", eventId)
                                        }
                                    },
                                    enabled = item.bottle.quantity > 0
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Consume one bottle")
                                }
                                Text(
                                    text = "Cellar: ${item.bottle.quantity}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                IconButton(
                                    onClick = { viewModel.addBottleToStock(item.bottle.bottleId) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add one bottle")
                                }
                                Spacer(Modifier.weight(1f))
                                if (itemNotes.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = { onTastingNotesClick(item.definition.definitionId, item.bottle.vintage) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Notes", maxLines = 1, softWrap = false)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        onAddNote(item.definition.definitionId, item.bottle.vintage)
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.NoteAdd, contentDescription = "Add tasting note")
                                }
                            }
                            CellarSectionHeader("Racks")
                            RackAssignmentControl(
                                currentRacks = rackAssignments[item.bottle.bottleId].orEmpty(),
                                automaticNewAddition = itemNotes.none { it.note.rating > 0.0 || !it.note.notes.isNullOrBlank() },
                                onOpenRackSelection = { startRackAssignment(item.bottle.bottleId) }
                            )
                            if (formattedAddedDate != null) {
                                Text(
                                    text = "Added on: $formattedAddedDate",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        if (rackPickMode) {
                            rackPickWineIds = if (item.bottle.bottleId in rackPickWineIds) {
                                rackPickWineIds - item.bottle.bottleId
                            } else {
                                rackPickWineIds + item.bottle.bottleId
                            }
                        } else if (wineSelectionMode) {
                            selectedWineIds = if (item.bottle.bottleId in selectedWineIds) {
                                selectedWineIds - item.bottle.bottleId
                            } else {
                                selectedWineIds + item.bottle.bottleId
                            }
                        } else {
                            toggleExpanded()
                        }
                    }
                )
                if (wineSelectionMode || rackPickMode) {
                    Checkbox(
                        checked = item.bottle.bottleId in selectedWineIds ||
                            item.bottle.bottleId in rackPickWineIds,
                        onCheckedChange = { checked ->
                            if (rackPickMode) {
                                rackPickWineIds = if (checked) {
                                    rackPickWineIds + item.bottle.bottleId
                                } else {
                                    rackPickWineIds - item.bottle.bottleId
                                }
                            } else {
                                selectedWineIds = if (checked) {
                                    selectedWineIds + item.bottle.bottleId
                                } else {
                                    selectedWineIds - item.bottle.bottleId
                                }
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = Color.Gray,
                            checkmarkColor = Color.White
                        ),
                        // Small and tucked up into the card's top-left
                        // corner so it never overlaps the wine's name.
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = 3.dp, y = 1.dp)
                            .size(28.dp)
                            .graphicsLayer { scaleX = 0.8f; scaleY = 0.8f }
                    )
                }
                }
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

        // Single-line transparent action bar docked at the bottom of the
        // screen, just above the main navigation buttons. It has no solid
        // backdrop -- the individual action pills are solid
        // so they stay readable over the list. (Animated by hand with
        // an alpha/translation fade-slide: AnimatedVisibility resolves to
        // the ColumnScope overload inside this Column-nested pager and
        // refuses to compile.)
        val wineBarProgress by animateFloatAsState(
            targetValue = if (wineActionBarVisible) 1f else 0f,
            label = "WineActionBar"
        )
        if (wineBarProgress > 0f) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        alpha = wineBarProgress
                        translationY = (1f - wineBarProgress) * 48.dp.toPx()
                    }
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Edit only makes sense for a single selected wine.
                if (selectedWineIds.size == 1) {
                    WineActionBarAction(
                        icon = Icons.Default.Edit,
                        label = "Edit",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val target = selectedWineIds.single()
                            wineMenuBottleId = null
                            onEditWine(target)
                        }
                    )
                }
                WineActionBarAction(
                    icon = Icons.Default.Delete,
                    label = if (selectedWineIds.size == 1) "Delete" else "Delete ${selectedWineIds.size}",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        wineMenuBottleId = null
                        alsoDeleteTastingNotes = false
                        showWineDeleteConfirmation = true
                    }
                )
                WineActionBarAction(
                    icon = Icons.Default.Close,
                    label = "Cancel",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    onClick = { wineMenuBottleId = null }
                )
            }
        }

        if (rackPickMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (rackPickWineIds.size == 1) "1 wine selected — swipe to Racks and tap a rack"
                        else "${rackPickWineIds.size} wines selected — swipe to Racks and tap a rack",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { rackPickWineIds = emptySet() }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
        }
            } else {
                CellarRacks(
                    inventory = inventory,
                    ratingsMap = ratingsMap,
                    notesMap = notesMap,
                    assignments = rackAssignments,
                    excludedNewAdditions = excludedNewAdditionIds,
                    rackOrder = rackNames,
                    selectedRack = selectedRack,
                    onRackSelected = { selectedRack = it },
                    assignmentBottleId = assignmentBottleId,
                    selectedAssignmentRacks = selectedAssignmentRacks,
                    onAssignmentRackToggle = { rack ->
                        selectedAssignmentRacks = if (rack in selectedAssignmentRacks) selectedAssignmentRacks - rack else selectedAssignmentRacks + rack
                    },
                    onCancelAssignment = {
                        assignmentBottleId = null
                        selectedAssignmentRacks = emptySet()
                        selectCellarTab(0)
                    },
                    onConfirmAssignment = {
                        assignmentBottleId?.let { updateRackAssignments(it, selectedAssignmentRacks) }
                        assignmentBottleId = null
                        selectedAssignmentRacks = emptySet()
                    },
                    onOpenRackSelection = startRackAssignment,
                    onCreateRack = addUserRack,
                    onRenameRack = renameRack,
                    onDeleteRacks = deleteRacks,
                    onMoveRack = moveRack,
                    onRackDragActiveChange = { rackDragActive = it },
                    onRemoveFromRack = removeFromRack,
                    onWineClick = onEditWine,
                    onTastingNotesClick = onTastingNotesClick,
                    onNoteClick = onNoteClick,
                    rackPickWineCount = rackPickWineIds.size,
                    onRackPicked = if (rackPickMode) completeRackPick else null
                )
            }
        }

        rackPickConfirmation?.let { (count, rack) ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (count == 1) "Added 1 wine to $rack" else "Added $count wines to $rack",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = undoRackPick) {
                        Text(
                            "Undo",
                            color = MaterialTheme.colorScheme.inversePrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        }
    }

    if (showWineDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showWineDeleteConfirmation = false },
            title = { Text(if (selectedWineIds.size == 1) "Delete wine?" else "Delete wines?") },
            text = {
                val selectedItems = inventory.filter { it.bottle.bottleId in selectedWineIds }
                val anySelectedHasNotes = selectedItems.any { item ->
                    notesMap[item.definition.definitionId to
                        com.n8rtz.winetracker.data.local.displayVintage(item.bottle.vintage)]
                        .orEmpty().isNotEmpty()
                }
                Column {
                    Text(
                        if (selectedWineIds.size == 1) {
                            "This wine will be removed from your cellar. This cannot be undone."
                        } else {
                            "${selectedWineIds.size} wines will be removed from your cellar. This cannot be undone."
                        }
                    )
                    if (anySelectedHasNotes) {
                        // Deleting a bottle alone keeps its tasting notes, so
                        // the wine would stay in Tasting Notes and Rankings.
                        // Offer the everywhere option right here.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = alsoDeleteTastingNotes,
                                onCheckedChange = { alsoDeleteTastingNotes = it }
                            )
                            Column {
                                Text("Also delete tasting notes", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Removes the wine from Tasting Notes and Rankings too. Leave unchecked to keep them.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val deleteNotes = alsoDeleteTastingNotes
                    selectedWineIds.forEach { bottleId ->
                        val item = inventory.firstOrNull { it.bottle.bottleId == bottleId }
                        if (deleteNotes && item != null) {
                            viewModel.deleteWineEverywhere(
                                bottleId,
                                item.definition.definitionId,
                                item.bottle.vintage
                            )
                        } else {
                            viewModel.deleteWine(bottleId)
                        }
                    }
                    selectedWineIds = emptySet()
                    alsoDeleteTastingNotes = false
                    showWineDeleteConfirmation = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showWineDeleteConfirmation = false }) { Text("Cancel") }
            }
        )
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
                    "Tap an active sort button again to reverse the order. Rating uses each wine's most recent recorded score; unrated wines stay last.",
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

/**
 * Small section header for the expanded wine card: a short line segment,
 * the header text inline with it, then a line filling the rest of the
 * card width — ------- Aromas --------------------------
 */
@Composable
private fun CellarSectionHeader(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.width(24.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/** One solid pill action in the bottom wine action bar. */
@Composable
private fun WineActionBarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                // One line, never split mid-word inside the pill.
                maxLines = 1,
                softWrap = false
            )
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
