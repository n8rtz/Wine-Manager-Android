@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.aromawheel.AromaSelection
import com.n8rtz.winetracker.data.local.SensoryData
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.displayVintage
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.ui.components.OverallScoreSlider
import com.n8rtz.winetracker.ui.components.SensoryEvaluationFields
import com.n8rtz.winetracker.ui.components.WheelPicker
import com.n8rtz.winetracker.ui.components.datePickerMillisToTimestamp
import com.n8rtz.winetracker.ui.components.timestampToDatePickerMillis
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TastingNoteEditorScreen(
    viewModel: TastingNoteViewModel,
    noteId: Long? = null,
    presetDefinitionId: Long? = null,
    presetVintage: String? = null,
    presetDateTasted: Long? = null,
    linkedEventId: Long? = null,
    onBack: () -> Unit
) {
    val definitions by viewModel.allDefinitions.collectAsState(initial = emptyList())
    val cellarVintages by viewModel.cellarVintages.collectAsState()
    val cellarPrices by viewModel.cellarPrices.collectAsState()
    val reviewerNames by viewModel.reviewerNames.collectAsState()
    var entryMode by remember(noteId) { mutableStateOf(TastingNoteEntryMode.CELLAR) }
    var manualMaker by remember(noteId) { mutableStateOf("") }
    var manualWineName by remember(noteId) { mutableStateOf("") }
    var manualVintage by remember(noteId) { mutableStateOf("") }
    var manualPrice by remember(noteId) { mutableStateOf("") }
    var manualCountry by remember(noteId) { mutableStateOf("") }
    var manualRegion by remember(noteId) { mutableStateOf("") }
    var manualSubregion by remember(noteId) { mutableStateOf("") }
    var loaded by remember(noteId) { mutableStateOf(noteId == null) }
    var missingNote by remember(noteId) { mutableStateOf(false) }
    var existingNote by remember(noteId) { mutableStateOf<TastingNote?>(null) }

    var selectedDefinitionId by remember(noteId, presetDefinitionId) { mutableStateOf(presetDefinitionId) }
    var wineSearch by remember { mutableStateOf("") }
    var wineMenuExpanded by remember { mutableStateOf(false) }
    var vintage by remember(noteId, presetVintage) { mutableStateOf(presetVintage ?: "NV") }
    var dateTasted by remember(noteId, presetDateTasted) {
        mutableStateOf(presetDateTasted ?: System.currentTimeMillis())
    }
    var rating by remember { mutableStateOf(88f) }
    var ratingSelected by remember(noteId) { mutableStateOf(noteId == null) }
    var notes by remember { mutableStateOf("") }
    var rebuy by remember { mutableStateOf(false) }
    var recommendedBy by remember { mutableStateOf("") }
    var reviewerMenuExpanded by remember { mutableStateOf(false) }
    var reviewerRating by remember { mutableStateOf(80) }
    var valueScore by remember(noteId) { mutableStateOf(5) }
    var valueScoreSelected by remember(noteId) { mutableStateOf(noteId == null) }
    var sensoryData by remember { mutableStateOf(SensoryData()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        if (noteId != null) {
            val note = viewModel.getTastingNoteById(noteId)
            if (note == null) {
                missingNote = true
            } else {
                existingNote = note
                selectedDefinitionId = note.definitionId
                vintage = note.vintage
                dateTasted = note.dateTasted
                rating = note.rating.toFloat().takeIf { it > 0f }?.coerceIn(50f, 100f) ?: 88f
                ratingSelected = note.rating > 0.0
                notes = note.notes.orEmpty()
                rebuy = note.rebuy
                recommendedBy = note.recommendedBy.orEmpty()
                reviewerRating = note.reviewerRating?.coerceIn(50, 100) ?: 80
                valueScore = note.valueScore?.coerceIn(1, 10) ?: 5
                valueScoreSelected = note.valueScore != null
                manualPrice = note.price?.toString().orEmpty()
                // Normalize the stored selection so retired/unknown ids never render as chips.
                val loadedSensory = note.sensoryData ?: SensoryData()
                sensoryData = loadedSensory.copy(
                    aromas = AromaSelection.normalize(loadedSensory.aromas).ids
                )
            }
            loaded = true
        }
    }

    val selectedDefinition = definitions.firstOrNull { it.definitionId == selectedDefinitionId }
    val selectedCellarPrice = selectedDefinitionId?.let { definitionId ->
        cellarPrices[definitionId to displayVintage(vintage)]
    }
    val selectedCellarPriceText = if (selectedCellarPrice != null) {
        "$${"%.2f".format(selectedCellarPrice)}"
    } else {
        "Not recorded"
    }
    val manualPriceValue = manualPrice.trim()
        .takeIf { it.isNotEmpty() }
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }
    val manualPriceIsValid = entryMode != TastingNoteEntryMode.MANUAL ||
        manualPrice.isBlank() || manualPriceValue != null
    val availableWineChoices = remember(
        definitions,
        cellarVintages,
        presetDefinitionId,
        presetVintage,
        selectedDefinitionId,
        vintage
    ) {
        definitions.flatMap { definition ->
            val routeVintages = buildList {
                if (definition.definitionId == presetDefinitionId) add(presetVintage ?: "NV")
                if (definition.definitionId == selectedDefinitionId) add(vintage)
            }.map { displayVintage(it) }
            val knownVintages = (cellarVintages[definition.definitionId].orEmpty() + routeVintages).distinct()
            knownVintages.map { knownVintage -> WineVintageChoice(definition, knownVintage) }
        }.sortedWith(
            compareBy<WineVintageChoice> { it.definition.maker.lowercase(Locale.ROOT) }
                .thenBy { it.definition.name.lowercase(Locale.ROOT) }
                .thenByDescending { it.vintage.toIntOrNull() ?: Int.MIN_VALUE }
        )
    }
    val matchingWineChoices = remember(availableWineChoices, wineSearch) {
        val query = wineSearch.trim()
        availableWineChoices.filter { choice ->
            val searchText = listOf(
                choice.definition.maker,
                choice.definition.name,
                displayVintage(choice.vintage),
                formatWineChoice(choice.definition, choice.vintage)
            ).joinToString(" ")
            query.isBlank() || searchText.contains(query, ignoreCase = true)
        }.take(30)
    }
    val reviewerOptions = remember(reviewerNames, recommendedBy) {
        (reviewerNames + listOfNotNull(recommendedBy.takeIf { it.isNotBlank() }))
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedBy { it.lowercase(Locale.ROOT) }
    }
    val makerSuggestions = remember(definitions) {
        distinctSuggestions(definitions.map { it.maker })
    }
    val wineNameSuggestions = remember(definitions, manualMaker) {
        val definitionsForMaker = definitions.filter {
            manualMaker.isBlank() || matchesSuggestion(it.maker, manualMaker)
        }.ifEmpty { definitions }
        distinctSuggestions(definitionsForMaker.map { it.name })
    }
    val countrySuggestions = remember(definitions) {
        distinctSuggestions(WineRepository.locationData.keys + definitions.map { it.country })
    }
    val regionSuggestions = remember(definitions, manualCountry) {
        val catalogRegions = WineRepository.locationData
            .filterKeys { manualCountry.isBlank() || matchesSuggestion(it, manualCountry) }
            .values
            .flatMap { it.keys }
        val knownRegions = definitions
            .filter { manualCountry.isBlank() || matchesSuggestion(it.country, manualCountry) }
            .mapNotNull { it.region }
        distinctSuggestions(catalogRegions + knownRegions)
    }
    val subregionSuggestions = remember(definitions, manualCountry, manualRegion) {
        val catalogSubregions = WineRepository.locationData
            .filterKeys { manualCountry.isBlank() || matchesSuggestion(it, manualCountry) }
            .values
            .flatMap { regionMap ->
                regionMap.filterKeys { manualRegion.isBlank() || matchesSuggestion(it, manualRegion) }
                    .values
                    .flatten()
            }
        val knownSubregions = definitions
            .filter {
                (manualCountry.isBlank() || matchesSuggestion(it.country, manualCountry)) &&
                    (manualRegion.isBlank() || it.region?.let { region -> matchesSuggestion(region, manualRegion) } == true)
            }
            .mapNotNull { it.subregion }
        distinctSuggestions(catalogSubregions + knownSubregions)
    }

    LaunchedEffect(selectedDefinition, vintage) {
        selectedDefinition?.let { definition ->
            wineSearch = formatWineChoice(definition, vintage)
        }
    }

    val formattedDate = remember(dateTasted) {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(dateTasted))
    }
    val wineFieldsAreValid = when (entryMode) {
        TastingNoteEntryMode.CELLAR -> selectedDefinition != null && vintage.isNotBlank()
        TastingNoteEntryMode.MANUAL -> manualMaker.isNotBlank() &&
            manualWineName.isNotBlank() && manualVintage.isNotBlank()
    }
    val canSave = loaded && !missingNote && wineFieldsAreValid && ratingSelected && manualPriceIsValid

    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    if (missingNote) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tasting note not found")
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (noteId == null) "Add Tasting Note" else "Edit Tasting Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (noteId != null) {
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete note")
                        }
                    }
                },
                windowInsets = WindowInsets(0.dp)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (noteId == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            entryMode = TastingNoteEntryMode.CELLAR
                            wineMenuExpanded = false
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (entryMode == TastingNoteEntryMode.CELLAR) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (entryMode == TastingNoteEntryMode.CELLAR) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    ) {
                        Text("Cellar")
                    }
                    Button(
                        onClick = {
                            if (manualMaker.isBlank() && manualWineName.isBlank() && selectedDefinition != null) {
                                manualMaker = selectedDefinition.maker
                                manualWineName = selectedDefinition.name
                                manualVintage = vintage
                                manualCountry = selectedDefinition.country.takeUnless {
                                    it.isBlank() || it.equals("Unknown", ignoreCase = true)
                                }.orEmpty()
                                manualRegion = selectedDefinition.region?.takeUnless {
                                    it.isBlank() || it.equals("Unknown", ignoreCase = true)
                                }.orEmpty()
                                manualSubregion = selectedDefinition.subregion?.takeUnless {
                                    it.isBlank() || it.equals("Unknown", ignoreCase = true)
                                }.orEmpty()
                            }
                            entryMode = TastingNoteEntryMode.MANUAL
                            wineMenuExpanded = false
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (entryMode == TastingNoteEntryMode.MANUAL) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (entryMode == TastingNoteEntryMode.MANUAL) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    ) {
                        Text("Manual")
                    }
                }
            }

            if (entryMode == TastingNoteEntryMode.CELLAR) {
                ExposedDropdownMenuBox(
                    expanded = wineMenuExpanded,
                    onExpandedChange = { wineMenuExpanded = !wineMenuExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = wineSearch,
                        onValueChange = {
                            wineSearch = it
                            selectedDefinitionId = null
                            wineMenuExpanded = true
                        },
                        label = { Text("Wine") },
                        placeholder = { Text("Search producer or wine") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (wineSearch.isNotEmpty()) {
                                    IconButton(onClick = {
                                        wineSearch = ""
                                        selectedDefinitionId = null
                                        wineMenuExpanded = true
                                    }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear wine search")
                                    }
                                }
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = wineMenuExpanded)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(
                            androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryEditable,
                            true
                        ),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = wineMenuExpanded,
                        onDismissRequest = { wineMenuExpanded = false }
                    ) {
                        if (matchingWineChoices.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No matching wines") },
                                enabled = false,
                                onClick = {}
                            )
                        } else {
                            matchingWineChoices.forEach { choice ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            formatWineChoice(choice.definition, choice.vintage),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    onClick = {
                                        selectedDefinitionId = choice.definition.definitionId
                                        vintage = choice.vintage
                                        wineSearch = formatWineChoice(choice.definition, choice.vintage)
                                        wineMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = formattedDate,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Date tasted") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = "Choose date")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    "Manual entry saves a tasting note only; it does not add a bottle to your cellar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AutocompleteTextField(
                        value = manualMaker,
                        onValueChange = { manualMaker = it },
                        label = "Wine maker",
                        suggestions = makerSuggestions,
                        modifier = Modifier.weight(1f)
                    )
                    AutocompleteTextField(
                        value = manualWineName,
                        onValueChange = { manualWineName = it },
                        label = "Wine name",
                        suggestions = wineNameSuggestions,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AutocompleteTextField(
                        value = manualCountry,
                        onValueChange = { manualCountry = it },
                        label = "Country",
                        suggestions = countrySuggestions,
                        modifier = Modifier.weight(1f)
                    )
                    AutocompleteTextField(
                        value = manualRegion,
                        onValueChange = { manualRegion = it },
                        label = "Region",
                        suggestions = regionSuggestions,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AutocompleteTextField(
                        value = manualSubregion,
                        onValueChange = { manualSubregion = it },
                        label = "Subregion",
                        suggestions = subregionSuggestions,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = manualVintage,
                        onValueChange = { manualVintage = it },
                        label = { Text("Vintage") },
                        placeholder = { Text("e.g. 2018 or NV") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = manualPrice,
                    onValueChange = { manualPrice = it },
                    label = { Text("Price (optional)") },
                    placeholder = { Text("e.g. 24.99") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !manualPriceIsValid,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (!manualPriceIsValid) {
                    Text(
                        "Enter a valid non-negative price.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedTextField(
                    value = formattedDate,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Date tasted") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = "Choose date")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (entryMode == TastingNoteEntryMode.CELLAR && selectedDefinition != null) {
                Text(
                    text = "${selectedDefinition.maker} - ${selectedDefinition.name} (${displayVintage(vintage)})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = listOfNotNull(selectedDefinition.subregion, selectedDefinition.region, selectedDefinition.country)
                        .filter { it.isNotBlank() }
                        .joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Price: $selectedCellarPriceText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OverallScoreSlider(
                score = rating,
                scoreSelected = ratingSelected,
                onScoreChange = {
                    rating = it
                    ratingSelected = true
                    sensoryData = sensoryData.copy(overallScore = it.roundToInt().coerceIn(50, 100))
                }
            )
            if (!ratingSelected) {
                Text(
                    "Choose an overall score to save this note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            FilterChip(
                selected = rebuy,
                onClick = { rebuy = !rebuy },
                label = { Text(if (rebuy) "Would buy again" else "Recommend / rebuy") }
            )

            SensoryEvaluationFields(
                data = sensoryData,
                onChange = { sensoryData = it }
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Tasting notes and impressions") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Value",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                if (valueScoreSelected) valueScore.toString() else "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text("/10", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Slider(
                    value = valueScore.toFloat(),
                    onValueChange = {
                        valueScore = it.roundToInt().coerceIn(1, 10)
                        valueScoreSelected = true
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        activeTickColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = reviewerMenuExpanded,
                    onExpandedChange = { reviewerMenuExpanded = !reviewerMenuExpanded },
                    modifier = Modifier.weight(2f)
                ) {
                    OutlinedTextField(
                        value = recommendedBy,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Recommended by") },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (recommendedBy.isNotBlank()) {
                                    IconButton(onClick = {
                                        recommendedBy = ""
                                        reviewerMenuExpanded = false
                                    }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear reviewer")
                                    }
                                }
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = reviewerMenuExpanded)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(
                            androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                            true
                        ),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = reviewerMenuExpanded,
                        onDismissRequest = { reviewerMenuExpanded = false }
                    ) {
                        if (reviewerOptions.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Add reviewers in Settings") },
                                enabled = false,
                                onClick = {}
                            )
                        } else {
                            reviewerOptions.forEach { reviewer ->
                                DropdownMenuItem(
                                    text = { Text(reviewer) },
                                    onClick = {
                                        recommendedBy = reviewer
                                        reviewerRating = 80
                                        reviewerMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                WheelPicker(
                    items = remember { (50..100).map { it.toString() } },
                    selectedIndex = (reviewerRating - 50).coerceIn(0, 50),
                    onSelectedChange = { reviewerRating = 50 + it },
                    label = "Rating",
                    itemHeight = 32.dp,
                    visibleCount = 3,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val definition = selectedDefinition
                    val note = TastingNote(
                        noteId = existingNote?.noteId ?: 0L,
                        definitionId = definition?.definitionId ?: 0L,
                        vintage = normalizeVintageInput(
                            if (entryMode == TastingNoteEntryMode.CELLAR) vintage else manualVintage
                        ),
                        dateTasted = dateTasted,
                        rating = rating.toDouble(),
                        valueScore = valueScore.takeIf { valueScoreSelected } ?: existingNote?.valueScore,
                        price = when {
                            entryMode == TastingNoteEntryMode.MANUAL -> manualPriceValue
                            existingNote != null -> existingNote?.price
                            else -> selectedCellarPrice
                        },
                        notes = notes.takeIf { it.isNotBlank() },
                        rebuy = rebuy,
                        recommendedBy = recommendedBy.takeIf { it.isNotBlank() },
                        reviewerRating = reviewerRating.takeIf {
                            recommendedBy.isNotBlank() || existingNote?.reviewerRating != null
                        },
                        initialRating = existingNote?.initialRating,
                        bottleId = if (entryMode == TastingNoteEntryMode.CELLAR) existingNote?.bottleId else null,
                        sensoryData = sensoryData.copy(overallScore = rating.roundToInt().coerceIn(50, 100)),
                        importKey = existingNote?.importKey
                    )
                    if (entryMode == TastingNoteEntryMode.CELLAR) {
                        val cellarDefinition = definition ?: return@Button
                        viewModel.saveTastingNote(
                            note.copy(definitionId = cellarDefinition.definitionId),
                            linkedEventId
                        ) { onBack() }
                    } else {
                        viewModel.saveManualTastingNote(
                            note = note.copy(definitionId = 0L, bottleId = null),
                            maker = manualMaker.trim(),
                            name = manualWineName.trim(),
                            country = manualCountry.trim(),
                            region = manualRegion.trim().takeIf { it.isNotEmpty() },
                            subregion = manualSubregion.trim().takeIf { it.isNotEmpty() },
                            onSaved = { onBack() }
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (noteId == null) "Save Tasting Note" else "Save Changes")
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = timestampToDatePickerMillis(dateTasted)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { dateTasted = datePickerMillisToTimestamp(it) }
                    showDatePicker = false
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showDeleteConfirmation && noteId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete tasting note?") },
            text = { Text("This permanently deletes this note. It does not change inventory or consumption history.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTastingNote(noteId, onBack)
                    showDeleteConfirmation = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AutocompleteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier
) {
    var expanded by remember(label) { mutableStateOf(false) }
    val matchingSuggestions = remember(value, suggestions) {
        suggestions.filter { matchesSuggestion(it, value) }.take(6)
    }
    val menuExpanded = expanded && matchingSuggestions.isNotEmpty()

    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(
                androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryEditable,
                true
            ),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 240.dp)
        ) {
            matchingSuggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun distinctSuggestions(values: Iterable<String>): List<String> =
    values.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && normalizeSuggestionValue(it).isNotEmpty() }
        .distinctBy(::normalizeSuggestionValue)
        .sortedBy { it.lowercase(Locale.ROOT) }
        .toList()

private fun matchesSuggestion(suggestion: String, query: String): Boolean {
    if (query.isBlank()) return true
    val normalizedQuery = normalizeSuggestionValue(query)
    return if (normalizedQuery.isEmpty()) {
        suggestion.contains(query.trim(), ignoreCase = true)
    } else {
        normalizeSuggestionValue(suggestion).contains(normalizedQuery)
    }
}

private fun normalizeSuggestionValue(value: String): String =
    value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private enum class TastingNoteEntryMode { CELLAR, MANUAL }

private data class WineVintageChoice(
    val definition: WineDefinition,
    val vintage: String
)

private fun formatWineChoice(definition: WineDefinition, vintage: String): String =
    "${definition.maker} - ${definition.name} (${displayVintage(vintage)})"

private fun normalizeVintageInput(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.equals("(NV)", ignoreCase = true) || trimmed.equals("NV", ignoreCase = true)) "NV" else trimmed
}
