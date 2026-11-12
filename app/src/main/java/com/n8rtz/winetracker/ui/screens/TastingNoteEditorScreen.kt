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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
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
import com.n8rtz.winetracker.data.local.AromaObservation
import com.n8rtz.winetracker.data.local.SensoryData
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.TastingPhase
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.displayVintage
import com.n8rtz.winetracker.data.local.normalizeSensoryData
import com.n8rtz.winetracker.data.local.validateForSave
import com.n8rtz.winetracker.data.local.withDerivedAromas
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.ui.components.LegacyAromaPhaseSheet
import com.n8rtz.winetracker.ui.components.OverallScoreSlider
import com.n8rtz.winetracker.ui.components.AromaRemovalUndoBar
import com.n8rtz.winetracker.ui.components.PhaseAromaWheelDialog
import com.n8rtz.winetracker.ui.components.SectionedSensoryFields
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
    var showAromaWheel by remember { mutableStateOf(false) }
    var activePhase by remember { mutableStateOf(TastingPhase.AROMA_BEFORE_TASTING) }
    var showLegacyPhaseSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    // Bottom undo popup state after an aroma chip is deleted.
    var aromaUndoLabel by remember { mutableStateOf<String?>(null) }
    var aromaUndoAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(aromaUndoLabel, aromaUndoAction) {
        if (aromaUndoLabel != null) {
            kotlinx.coroutines.delay(4000)
            aromaUndoLabel = null
            aromaUndoAction = null
        }
    }

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
                val loadedSensory = normalizeSensoryData(note.sensoryData ?: SensoryData())
                sensoryData = loadedSensory
                showLegacyPhaseSheet = loadedSensory.aromaObservations.isEmpty() && loadedSensory.aromas.isNotEmpty()
                activePhase = TastingPhase.AROMA_BEFORE_TASTING
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
    // Price shown with the Value section: the note's recorded price if
    // it has one, otherwise the cellar price.
    val displayPriceText = existingNote?.price?.let { "$${"%.2f".format(it)}" }
        ?: selectedCellarPriceText
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
    val legacyAromasUnresolved = sensoryData.aromaObservations.isEmpty() && sensoryData.aromas.isNotEmpty()
    val canSave = loaded && !missingNote && wineFieldsAreValid && ratingSelected && manualPriceIsValid &&
        !legacyAromasUnresolved && !showLegacyPhaseSheet && validateForSave(sensoryData).isEmpty()

    // Unsaved-changes guard for both the in-app and Android back buttons.
    var initialSnapshot by remember(noteId) { mutableStateOf<TastingNoteFormSnapshot?>(null) }
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    fun currentSnapshot() = TastingNoteFormSnapshot(
        entryMode = entryMode,
        selectedDefinitionId = selectedDefinitionId,
        vintage = vintage,
        dateTasted = dateTasted,
        rating = rating,
        ratingSelected = ratingSelected,
        notes = notes,
        rebuy = rebuy,
        recommendedBy = recommendedBy,
        reviewerRating = reviewerRating,
        valueScore = valueScore,
        valueScoreSelected = valueScoreSelected,
        manualMaker = manualMaker,
        manualWineName = manualWineName,
        manualVintage = manualVintage,
        manualPrice = manualPrice,
        manualCountry = manualCountry,
        manualRegion = manualRegion,
        manualSubregion = manualSubregion,
        sensoryData = sensoryData
    )
    LaunchedEffect(loaded) {
        if (loaded && initialSnapshot == null) initialSnapshot = currentSnapshot()
    }
    val hasUnsavedChanges = initialSnapshot != null && initialSnapshot != currentSnapshot()
    fun requestDismiss() {
        if (hasUnsavedChanges) showUnsavedChangesDialog = true else onBack()
    }
    BackHandler { requestDismiss() }

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

    // Keep the title, but let the header scroll away with the content.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    fun performSave() {
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
                        sensoryData = withDerivedAromas(
                            sensoryData.copy(overallScore = rating.roundToInt().coerceIn(50, 100))
                        ),
                        importKey = existingNote?.importKey
                    )
                    if (entryMode == TastingNoteEntryMode.CELLAR) {
                        val cellarDefinition = definition ?: return
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
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = {
            aromaUndoLabel?.let { label ->
                AromaRemovalUndoBar(
                    removedLabel = label,
                    onUndo = {
                        aromaUndoAction?.invoke()
                        aromaUndoLabel = null
                        aromaUndoAction = null
                    }
                )
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (noteId == null) "Add Tasting Note" else "Edit Tasting Note") },
                navigationIcon = {
                    IconButton(onClick = ::requestDismiss) {
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
                windowInsets = WindowInsets(0.dp),
                scrollBehavior = scrollBehavior
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
                // The wine of an existing note can't be changed, so the
                // search bar only appears when creating a note.
                if (noteId == null) {
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
                        placeholder = { Text("Search", maxLines = 1) },
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

            SectionedSensoryFields(
                data = sensoryData,
                definitionColor = selectedDefinition?.defaultColorHsv,
                legacyAromasUnresolved = legacyAromasUnresolved,
                onDataChange = { sensoryData = it },
                onOpenWheel = { showAromaWheel = true },
                onAssignLegacy = { showLegacyPhaseSheet = true },
                onAromaRemoved = { label, undo ->
                    aromaUndoLabel = label
                    aromaUndoAction = undo
                }
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
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
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
                if (entryMode == TastingNoteEntryMode.CELLAR) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Price",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            displayPriceText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            FilterChip(
                selected = rebuy,
                onClick = { rebuy = !rebuy },
                label = { Text(if (rebuy) "Would buy again" else "Recommend / rebuy") }
            )

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
                    // This read-only field mirrors the selected reviewer. Keep
                    // its label raised even before the first dropdown interaction.
                    val reviewerFieldState = remember(recommendedBy) {
                        TextFieldState(initialText = recommendedBy)
                    }
                    OutlinedTextField(
                        state = reviewerFieldState,
                        readOnly = true,
                        labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
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
                        lineLimits = TextFieldLineLimits.SingleLine
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
                onClick = { performSave() },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (noteId == null) "Save Tasting Note" else "Save Changes")
            }
        }
    }

    if (showUnsavedChangesDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedChangesDialog = false },
            title = { Text("Unsaved changes") },
            text = { Text("You have unsaved changes to this tasting note. Save them before leaving?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedChangesDialog = false
                        performSave()
                    },
                    enabled = canSave
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedChangesDialog = false
                        onBack()
                    }) { Text("Don't save") }
                    TextButton(onClick = { showUnsavedChangesDialog = false }) { Text("Cancel") }
                }
            }
        )
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

    if (showAromaWheel) {
        PhaseAromaWheelDialog(
            initialObservations = sensoryData.aromaObservations,
            initialPhase = activePhase,
            onConfirm = { phase, observations ->
                activePhase = phase
                sensoryData = withDerivedAromas(sensoryData.copy(aromaObservations = observations))
                showAromaWheel = false
            },
            onDismiss = { showAromaWheel = false }
        )
    }

    if (showLegacyPhaseSheet) {
        LegacyAromaPhaseSheet(
            aromaIds = sensoryData.aromas,
            onApply = { assignments, removedIds ->
                val remainingIds = sensoryData.aromas.filterNot { it in removedIds }
                val observations = remainingIds.flatMap { id ->
                    TastingPhase.values()
                        .filter { it in assignments.getValue(id) }
                        .map { phase -> id to phase }
                }.mapIndexed { index, (id, phase) ->
                    AromaObservation(
                        descriptorId = id,
                        phase = phase,
                        intensity = null,
                        displayOrder = index
                    )
                }
                sensoryData = withDerivedAromas(
                    sensoryData.copy(aromas = remainingIds, aromaObservations = observations)
                )
                showLegacyPhaseSheet = false
            },
            onDismiss = { showLegacyPhaseSheet = false }
        )
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

/** Snapshot of every editable field, used to detect unsaved changes. */
private data class TastingNoteFormSnapshot(
    val entryMode: TastingNoteEntryMode,
    val selectedDefinitionId: Long?,
    val vintage: String,
    val dateTasted: Long,
    val rating: Float,
    val ratingSelected: Boolean,
    val notes: String,
    val rebuy: Boolean,
    val recommendedBy: String,
    val reviewerRating: Int,
    val valueScore: Int,
    val valueScoreSelected: Boolean,
    val manualMaker: String,
    val manualWineName: String,
    val manualVintage: String,
    val manualPrice: String,
    val manualCountry: String,
    val manualRegion: String,
    val manualSubregion: String,
    val sensoryData: SensoryData
)

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
